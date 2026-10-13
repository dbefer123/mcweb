package dev.mcwebclient.web.mem;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * malloc/free over {@link Nmem}. All bookkeeping lives on the Java heap, so a stray
 * native write can corrupt data but never the allocator itself.
 *
 * <ul>
 *   <li>Small blocks (≤ 2 KiB): power-of-two size classes carved from 64 KiB slabs,
 *       one free stack per class.</li>
 *   <li>Large blocks: best-fit over free ranges indexed by size and by address, with
 *       coalescing on free. 16-byte aligned (or more on request).</li>
 *   <li>Growth: memory.grow by at least a quarter of the current size.</li>
 * </ul>
 * Address 0 is never returned; the first 64 KiB are reserved.
 */
public final class NativeHeap {
    private static final long PAGE = 65536;
    private static final int MIN_CLASS_SHIFT = 4;   // 16 B
    private static final int MAX_CLASS_SHIFT = 11;  // 2 KiB
    private static final long SLAB = 65536;
    private static final long LARGE_ALIGN = 16;

    private static final long[][] freeStacks = new long[MAX_CLASS_SHIFT - MIN_CLASS_SHIFT + 1][];
    private static final int[] freeCounts = new int[MAX_CLASS_SHIFT - MIN_CLASS_SHIFT + 1];

    /** A slab of one size class, with a bitmap of allocated slots. */
    private static final class Slab {
        final int cls;
        final long[] used;

        Slab(int cls) {
            this.cls = cls;
            this.used = new long[(int) ((SLAB >> (cls + MIN_CLASS_SHIFT)) + 63) / 64];
        }
    }

    /** slab base -> slab */
    private static final Map<Long, Slab> slabs = new HashMap<>();
    private static int badFrees;
    /** free large ranges: address -> size, and size -> addresses */
    private static final TreeMap<Long, Long> freeByAddr = new TreeMap<>();
    private static final TreeMap<Long, TreeSet<Long>> freeBySize = new TreeMap<>();
    /** live large blocks: address -> size */
    private static final Map<Long, Long> large = new HashMap<>();

    private static long heapEnd;
    private static long liveBytes;
    private static long peakBytes;
    private static boolean initialized;

    private NativeHeap() {
    }

    private static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        for (int i = 0; i < freeStacks.length; i++) {
            freeStacks[i] = new long[64];
        }
        heapEnd = (long) Nmem.pages() * PAGE;
        if (heapEnd > PAGE) {
            addFree(PAGE, heapEnd - PAGE);
        }
    }

    public static synchronized long malloc(long size) {
        init();
        if (size <= 0) {
            size = 1;
        }
        long addr;
        if (size <= (1L << MAX_CLASS_SHIFT)) {
            addr = mallocSmall(classIndex(size));
            liveBytes += 1L << (classIndex(size) + MIN_CLASS_SHIFT);
        } else {
            long rounded = roundUp(size, LARGE_ALIGN);
            addr = allocLarge(rounded, LARGE_ALIGN);
            large.put(addr, rounded);
            liveBytes += rounded;
        }
        if (liveBytes > peakBytes) {
            peakBytes = liveBytes;
        }
        return addr;
    }

    public static synchronized long calloc(long num, long size) {
        long bytes = Math.multiplyExact(num, size);
        long addr = malloc(bytes);
        Nmem.fill(addr, 0, Math.max(bytes, 1));
        return addr;
    }

    public static synchronized long alignedAlloc(long alignment, long size) {
        init();
        if (alignment <= LARGE_ALIGN) {
            return malloc(size);
        }
        long rounded = roundUp(Math.max(size, 1), LARGE_ALIGN);
        long addr = allocLarge(rounded, alignment);
        large.put(addr, rounded);
        liveBytes += rounded;
        if (liveBytes > peakBytes) {
            peakBytes = liveBytes;
        }
        return addr;
    }

    public static synchronized void free(long addr) {
        if (addr == 0) {
            return;
        }
        Long size = large.remove(addr);
        if (size != null) {
            liveBytes -= size;
            addFree(addr, size);
            return;
        }
        long base = addr & ~(SLAB - 1);
        Slab slab = slabs.get(base);
        if (slab == null) {
            badFree("free of unknown address", addr);
            return;
        }
        int cls = slab.cls;
        long offset = addr - base;
        int slot = (int) (offset >> (cls + MIN_CLASS_SHIFT));
        if ((offset & ((1L << (cls + MIN_CLASS_SHIFT)) - 1)) != 0 || (slab.used[slot >> 6] & (1L << slot)) == 0) {
            // A double free would hand the block to two owners later: report and ignore.
            badFree("double free or interior pointer", addr);
            return;
        }
        slab.used[slot >> 6] &= ~(1L << slot);
        liveBytes -= 1L << (cls + MIN_CLASS_SHIFT);
        push(cls, addr);
    }

    private static void badFree(String what, long addr) {
        if (badFrees++ < 20) {
            new IllegalStateException("NativeHeap: " + what + " 0x" + Long.toHexString(addr)).printStackTrace();
        }
    }

    public static synchronized long realloc(long addr, long size) {
        if (addr == 0) {
            return malloc(size);
        }
        if (size == 0) {
            free(addr);
            return 0;
        }
        long old = blockSize(addr);
        if (size <= old) {
            return addr;
        }
        long fresh = malloc(size);
        Nmem.copy(addr, fresh, old);
        free(addr);
        return fresh;
    }

    /** Usable size of a live block. */
    public static synchronized long blockSize(long addr) {
        Long size = large.get(addr);
        if (size != null) {
            return size;
        }
        Slab slab = slabs.get(addr & ~(SLAB - 1));
        if (slab == null) {
            throw new IllegalArgumentException("unknown address " + addr);
        }
        return 1L << (slab.cls + MIN_CLASS_SHIFT);
    }

    public static synchronized long liveBytes() {
        return liveBytes;
    }

    public static synchronized long peakBytes() {
        return peakBytes;
    }

    public static synchronized long capacityBytes() {
        return heapEnd;
    }

    // ---- small blocks ----

    private static int classIndex(long size) {
        int shift = 64 - Long.numberOfLeadingZeros(Math.max(size, 1) - 1);
        return Math.max(shift, MIN_CLASS_SHIFT) - MIN_CLASS_SHIFT;
    }

    private static long mallocSmall(int cls) {
        if (freeCounts[cls] == 0) {
            long slab = allocLarge(SLAB, SLAB);
            slabs.put(slab, new Slab(cls));
            long block = 1L << (cls + MIN_CLASS_SHIFT);
            for (long a = slab + SLAB - block; a >= slab; a -= block) {
                push(cls, a);
            }
        }
        long addr = freeStacks[cls][--freeCounts[cls]];
        long base = addr & ~(SLAB - 1);
        Slab slab = slabs.get(base);
        int slot = (int) ((addr - base) >> (cls + MIN_CLASS_SHIFT));
        slab.used[slot >> 6] |= 1L << slot;
        return addr;
    }

    private static void push(int cls, long addr) {
        long[] stack = freeStacks[cls];
        if (freeCounts[cls] == stack.length) {
            stack = java.util.Arrays.copyOf(stack, stack.length * 2);
            freeStacks[cls] = stack;
        }
        stack[freeCounts[cls]++] = addr;
    }

    // ---- large blocks ----

    private static long allocLarge(long size, long alignment) {
        for (int attempt = 0; attempt < 2; attempt++) {
            // Best fit: smallest free range that can hold size plus alignment padding.
            for (Map.Entry<Long, TreeSet<Long>> bucket : freeBySize.tailMap(size, true).entrySet()) {
                for (long start : bucket.getValue()) {
                    long rangeSize = bucket.getKey();
                    long aligned = roundUp(start, alignment);
                    long pad = aligned - start;
                    if (pad + size <= rangeSize) {
                        removeFree(start, rangeSize);
                        if (pad > 0) {
                            addFree(start, pad);
                        }
                        long tail = rangeSize - pad - size;
                        if (tail > 0) {
                            addFree(aligned + size, tail);
                        }
                        return aligned;
                    }
                }
            }
            grow(size + alignment);
        }
        throw new OutOfMemoryError("native heap exhausted: " + size + " bytes requested, "
                + liveBytes + " live of " + heapEnd);
    }

    private static void grow(long atLeast) {
        long current = (long) Nmem.pages();
        long wantPages = Math.max((atLeast + PAGE - 1) / PAGE, Math.max(16, current / 4));
        int previous = Nmem.grow((int) wantPages);
        if (previous < 0) {
            wantPages = (atLeast + PAGE - 1) / PAGE;
            previous = Nmem.grow((int) wantPages);
            if (previous < 0) {
                throw new OutOfMemoryError("wasm memory.grow failed (" + current + " pages)");
            }
        }
        long start = (long) previous * PAGE;
        heapEnd = start + wantPages * PAGE;
        addFree(start, wantPages * PAGE);
    }

    private static void addFree(long start, long size) {
        // Coalesce with neighbours.
        Map.Entry<Long, Long> before = freeByAddr.floorEntry(start - 1);
        if (before != null && before.getKey() + before.getValue() == start) {
            removeFree(before.getKey(), before.getValue());
            start = before.getKey();
            size += before.getValue();
        }
        Long afterSize = freeByAddr.get(start + size);
        if (afterSize != null) {
            removeFree(start + size, afterSize);
            size += afterSize;
        }
        freeByAddr.put(start, size);
        freeBySize.computeIfAbsent(size, k -> new TreeSet<>()).add(start);
    }

    private static void removeFree(long start, long size) {
        freeByAddr.remove(start);
        TreeSet<Long> set = freeBySize.get(size);
        set.remove(start);
        if (set.isEmpty()) {
            freeBySize.remove(size);
        }
    }

    private static long roundUp(long v, long align) {
        return (v + align - 1) & -align;
    }
}
