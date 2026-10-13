package dev.mcwebclient.web.gpu;

import java.nio.ByteBuffer;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import com.mojang.renderpearl.backend.util.TransientBlockAllocator;

import dev.mcwebclient.web.mem.NativeHeap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntComparator;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.minecraft.util.Mth;
import org.lwjgl.system.MemoryUtil;

/**
 * Per-submit scratch memory, structured like the Vulkan backend's: blocks from
 * renderpearl's TransientBlockAllocator, rotated every submit. A GPU block is one
 * GPUBuffer; blocks the CPU writes ("staging" and "GPU mapped") also have an nmem
 * shadow whose written ranges are uploaded with queue.writeBuffer right before submit.
 * WebGPU executes queue operations in order, so blocks can be reused by the next submit
 * without waiting for the GPU.
 */
final class WebGpuTransientMemory implements TransientMemory {
    private static final long BLOCK_SIZE = 524288L;
    private static final long MAX_GPU_ALIGNMENT = Long.highestOneBit(Long.MAX_VALUE);
    private static final int ALL_USAGE = WebGpu.BUF_COPY_SRC | WebGpu.BUF_COPY_DST | WebGpu.BUF_VERTEX | WebGpu.BUF_INDEX
            | WebGpu.BUF_UNIFORM | WebGpu.BUF_STORAGE | WebGpu.BUF_INDIRECT;

    /** A GPU block: one GPUBuffer, plus a CPU shadow when the CPU writes into it. */
    static final class Block implements TransientBlockAllocator.Allocator.Block {
        final int id;
        final long size;
        final long shadow;
        long dirtyStart = Long.MAX_VALUE;
        long dirtyEnd;

        Block(long size, boolean mapped) {
            this.size = size;
            this.id = WebGpu.newId();
            WebGpu.begin(4);
            WebGpu.put(WebGpu.CREATE_BUFFER);
            WebGpu.put(id);
            WebGpu.put((int) ((size + 3) & ~3L));
            WebGpu.put(ALL_USAGE);
            this.shadow = mapped ? NativeHeap.malloc((size + 3) & ~3L) : 0;
        }

        void free() {
            WebGpuDevice.current().releaseBindings(id);
            WebGpu.destroy(id);
            if (shadow != 0) {
                NativeHeap.free(shadow);
            }
        }

        void markDirty(long offset, long length) {
            dirtyStart = Math.min(dirtyStart, offset);
            dirtyEnd = Math.max(dirtyEnd, offset + length);
        }

        @Override
        public boolean suboptimal() {
            return false;
        }
    }

    private final TransientBlockAllocator<TransientBlockAllocator.Allocator.CpuBlock> cpu =
            new TransientBlockAllocator<>(BLOCK_SIZE, 16L, TransientBlockAllocator.Allocator.CpuBlock.memalloc());
    private final TransientBlockAllocator<Block> mapped = new TransientBlockAllocator<>(BLOCK_SIZE, MAX_GPU_ALIGNMENT,
            TransientBlockAllocator.Allocator.create(size -> new Block(size, true), Block::free));
    private final TransientBlockAllocator<Block> gpu = new TransientBlockAllocator<>(BLOCK_SIZE, MAX_GPU_ALIGNMENT,
            TransientBlockAllocator.Allocator.create(size -> new Block(size, false), Block::free));
    private final Map<Block, Boolean> dirty = new IdentityHashMap<>();
    private long submitIndex;

    /** Uploads everything the CPU wrote this submit. Called right before the submit. */
    void flushWrites() {
        for (Block b : dirty.keySet()) {
            if (b.dirtyEnd > b.dirtyStart) {
                long start = b.dirtyStart & ~3L;
                long end = Math.min((b.dirtyEnd + 3) & ~3L, (b.size + 3) & ~3L);
                WebGpu.writeBuffer(b.id, start, b.shadow + start, end - start);
            }
            b.dirtyStart = Long.MAX_VALUE;
            b.dirtyEnd = 0;
        }
        dirty.clear();
    }

    /** After the submit: everything may be reused (queue order protects in-flight data). */
    void rotate() {
        cpu.rotate().run();
        mapped.rotate().run();
        gpu.rotate().run();
        submitIndex++;
    }

    @Override
    public ByteBuffer allocateCpu(long size, long alignment, long minimumAllocation, long elementSize) {
        TransientBlockAllocator.Allocation<TransientBlockAllocator.Allocator.CpuBlock> a = cpu.allocate(size, alignment, minimumAllocation, elementSize);
        return MemoryUtil.memByteBuffer(a.block().address() + a.offset(), (int) a.size());
    }

    private GpuBufferSlice.MappedView mappedAllocation(long size, long alignment, @GpuBuffer.Usage int usage, long minimumAllocation, long elementSize) {
        TransientBlockAllocator.Allocation<Block> a = mapped.allocate(size, alignment, minimumAllocation, elementSize);
        Block block = a.block();
        block.markDirty(a.offset(), a.size());
        dirty.put(block, Boolean.TRUE);
        TransientBuffer buffer = new TransientBuffer(block, usage);
        ByteBuffer cpuView = MemoryUtil.memByteBuffer(block.shadow + a.offset(), (int) a.size());
        return new GpuBufferSlice.MappedView(new GpuBufferSlice(buffer, a.offset(), a.size()), cpuView, () -> {});
    }

    @Override
    public GpuBufferSlice.MappedView allocateStaging(long size, long alignment, @GpuBuffer.Usage int usage, long minimumAllocation, long elementSize) {
        return mappedAllocation(size, alignment, usage, minimumAllocation, elementSize);
    }

    @Override
    public GpuBufferSlice.MappedView allocateGpuMapped(long size, long alignment, @GpuBuffer.Usage int usage, long minimumAllocation, long elementSize) {
        return mappedAllocation(size, alignment, usage, minimumAllocation, elementSize);
    }

    @Override
    public GpuBufferSlice allocateGpu(long size, long alignment, @GpuBuffer.Usage int usage, long minimumAllocation, long elementSize) {
        TransientBlockAllocator.Allocation<Block> a = gpu.allocate(size, alignment, minimumAllocation, elementSize);
        return new GpuBufferSlice(new TransientBuffer(a.block(), usage), a.offset(), a.size());
    }

    @Override
    public GpuBufferSlice uploadStaging(List<ByteBuffer> data, long alignment, @GpuBuffer.Usage int usage, long minimumAllocation, long elementSize) {
        return upload(data, alignment, usage, minimumAllocation, elementSize);
    }

    @Override
    public GpuBufferSlice uploadGpu(List<ByteBuffer> data, long alignment, @GpuBuffer.Usage int usage, long minimumAllocation, long elementSize) {
        return upload(data, alignment, usage, minimumAllocation, elementSize);
    }

    private GpuBufferSlice upload(List<ByteBuffer> data, long alignment, @GpuBuffer.Usage int usage, long minimumAllocation, long elementSize) {
        long total = 0;
        for (ByteBuffer b : data) {
            total += b.remaining();
            total = Mth.roundToward(total, alignment);
        }
        try (GpuBufferSlice.MappedView view = mappedAllocation(total, alignment, usage, minimumAllocation, elementSize)) {
            long dst = MemoryUtil.memAddress(view.data());
            long offset = 0;
            for (ByteBuffer b : data) {
                MemoryUtil.memCopy(MemoryUtil.memAddress(b), dst + offset, Math.min(view.slice().length() - offset, b.remaining()));
                offset += b.remaining();
                offset = Mth.roundToward(offset, alignment);
                if (offset >= view.slice().length()) {
                    break;
                }
            }
            return view.slice();
        }
    }

    @Override
    public List<GpuBufferSlice> multiUploadStaging(List<ByteBuffer> data, long alignment, @GpuBuffer.Usage int usage) {
        return multiUpload(data, alignment, usage);
    }

    @Override
    public List<GpuBufferSlice> multiUploadGpu(List<ByteBuffer> data, long alignment, @GpuBuffer.Usage int usage) {
        return multiUpload(data, alignment, usage);
    }

    /** Packs uploads into the current block largest-first, as the Vulkan backend does. */
    private List<GpuBufferSlice> multiUpload(List<ByteBuffer> data, long alignment, @GpuBuffer.Usage int usage) {
        ReferenceArrayList<GpuBufferSlice> result = new ReferenceArrayList<>();
        result.size(data.size());
        IntArrayList order = IntArrayList.toList(IntStream.range(0, data.size()));
        order.sort(IntComparator.comparing(i -> data.get(i).remaining()));
        while (!order.isEmpty()) {
            boolean placed = false;
            for (int i = order.size() - 1; i >= 0; i--) {
                int index = order.getInt(i);
                ByteBuffer b = data.get(index);
                if (mapped.canAllocateInCurrentBlock(b.remaining(), alignment)) {
                    order.removeInt(i);
                    try (GpuBufferSlice.MappedView view = mappedAllocation(b.remaining(), alignment, usage, b.remaining(), 1L)) {
                        MemoryUtil.memCopy(b, view.data());
                        result.set(index, view.slice());
                    }
                    placed = true;
                    break;
                }
            }
            if (!placed) {
                int index = order.popInt();
                ByteBuffer b = data.get(index);
                try (GpuBufferSlice.MappedView view = mappedAllocation(b.remaining(), alignment, usage, b.remaining(), 1L)) {
                    MemoryUtil.memCopy(b, view.data());
                    result.set(index, view.slice());
                }
            }
        }
        return result;
    }

    /** A slice owner for transient memory; valid until the end of the current submit. */
    final class TransientBuffer extends WebGpuBuffer {
        private final long bufferSubmitIndex;
        private boolean closed;

        TransientBuffer(Block block, @GpuBuffer.Usage int usage) {
            super(block.id, usage, block.size);
            this.bufferSubmitIndex = submitIndex;
        }

        @Override
        public GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) {
            throw new IllegalStateException("Cannot map transient buffer");
        }

        @Override
        public boolean isClosed() {
            return closed || bufferSubmitIndex < submitIndex;
        }

        @Override
        public void close() {
            closed = true;
        }

        @Override
        public GpuBufferSlice slice(long offset, long length) {
            throw new IllegalStateException("Cannot slice transient buffer");
        }

        @Override
        public GpuBufferSlice slice() {
            throw new IllegalStateException("Cannot slice transient buffer");
        }
    }
}
