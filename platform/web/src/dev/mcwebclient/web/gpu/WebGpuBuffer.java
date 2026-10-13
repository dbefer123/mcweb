package dev.mcwebclient.web.gpu;

import java.nio.ByteBuffer;
import java.util.function.Supplier;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.backend.common.BaseGpuBuffer;

import dev.mcwebclient.web.mem.NativeHeap;
import dev.mcwebclient.web.mem.Nmem;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * A GPUBuffer. WebGPU can't map buffers synchronously, so mappable buffers keep a CPU
 * copy ("shadow") in nmem: a write mapping edits the shadow and uploads the mapped range
 * when closed (like host-visible memory, bytes outside the range keep their contents);
 * a read mapping returns the shadow, which readbacks fill (copyTextureToBuffer callback).
 */
class WebGpuBuffer extends BaseGpuBuffer {
    final int id;
    private long shadow;
    private boolean closed;
    private int mappings;

    WebGpuBuffer(@Nullable Supplier<String> label, @GpuBuffer.Usage int usage, long size) {
        super(usage, size);
        this.id = WebGpu.newId();
        if (label != null) {
            WebGpu.setLabel(id, label.get());
        }
        long gpuSize = Math.max(4, (size + 3) & ~3L);
        WebGpu.begin(4);
        WebGpu.put(WebGpu.CREATE_BUFFER);
        WebGpu.put(id);
        WebGpu.put((int) gpuSize);
        WebGpu.put(gpuUsage(usage));
        if ((usage & (USAGE_MAP_READ | USAGE_MAP_WRITE)) != 0) {
            shadow = NativeHeap.calloc(1, gpuSize);
        }
    }

    /** Transient buffers share one GPU buffer per block and are never created by this constructor. */
    WebGpuBuffer(int existingId, @GpuBuffer.Usage int usage, long size) {
        super(usage, size);
        this.id = existingId;
    }

    static int gpuUsage(@GpuBuffer.Usage int usage) {
        int u = WebGpu.BUF_COPY_SRC | WebGpu.BUF_COPY_DST;
        if ((usage & USAGE_VERTEX) != 0) {
            u |= WebGpu.BUF_VERTEX;
        }
        if ((usage & USAGE_INDEX) != 0) {
            u |= WebGpu.BUF_INDEX;
        }
        if ((usage & USAGE_UNIFORM) != 0) {
            u |= WebGpu.BUF_UNIFORM;
        }
        if ((usage & USAGE_UNIFORM_TEXEL_BUFFER) != 0) {
            u |= WebGpu.BUF_STORAGE;
        }
        if ((usage & USAGE_INDIRECT_PARAMETERS) != 0) {
            u |= WebGpu.BUF_INDIRECT;
        }
        return u;
    }

    /** CPU copy of a mappable buffer's contents (0 if the buffer isn't mappable). */
    long shadow() {
        return shadow;
    }

    /** Uploads {@code size} bytes from nmem {@code src} to {@code offset} (queue order, immediate). */
    void upload(long offset, long src, long size) {
        if (size <= 0) {
            return;
        }
        if ((offset & 3) != 0 || (size & 3) != 0) {
            // queue.writeBuffer needs 4-byte granularity: widen the range, keeping the outer bytes.
            long start = offset & ~3L;
            long end = Math.min((offset + size + 3) & ~3L, (size() + 3) & ~3L);
            long tmp = NativeHeap.malloc(end - start);
            if (shadow != 0) {
                Nmem.copy(shadow + start, tmp, end - start);
            } else {
                Nmem.fill(tmp, 0, end - start);
            }
            Nmem.copy(src, tmp + (offset - start), size);
            WebGpu.writeBuffer(id, start, tmp, end - start);
            WebGpu.flush();
            NativeHeap.free(tmp);
            return;
        }
        WebGpu.writeBuffer(id, offset, src, size);
    }

    @Override
    public GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) {
        if (closed) {
            throw new IllegalStateException("Buffer already closed");
        }
        if (!read && !write) {
            throw new IllegalArgumentException("At least read or write must be true");
        }
        if (read && (usage() & USAGE_MAP_READ) == 0) {
            throw new IllegalStateException("Buffer is not readable");
        }
        if (write && (usage() & USAGE_MAP_WRITE) == 0) {
            throw new IllegalStateException("Buffer is not writable");
        }
        if (offset < 0 || length < 0 || offset + length > size()) {
            throw new IllegalArgumentException("Cannot map " + length + " bytes at offset " + offset + " of a " + size() + " byte buffer");
        }
        mappings++;
        ByteBuffer data = MemoryUtil.memByteBuffer(shadow + offset, (int) length);
        return new GpuBufferSlice.MappedView(slice(offset, length), data, new Runnable() {
            private boolean done;

            @Override
            public void run() {
                if (done) {
                    return;
                }
                done = true;
                mappings--;
                if (write && !closed) {
                    upload(offset, shadow + offset, length);
                }
            }
        });
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        if (mappings != 0) {
            throw new IllegalStateException("Attempt to close a mapped buffer");
        }
        closed = true;
        WebGpuDevice.current().releaseBindings(id);
        WebGpu.destroy(id);
        if (shadow != 0) {
            // Readbacks still in flight would write here: free after the next frame.
            long s = shadow;
            shadow = 0;
            WebGpuDevice.current().afterNextSubmit(() -> NativeHeap.free(s));
        }
    }
}
