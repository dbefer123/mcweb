package dev.mcwebclient.web.media;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

import dev.mcwebclient.web.mem.CallbackSubstitutions;
import dev.mcwebclient.web.mem.NativeHeap;
import dev.mcwebclient.web.mem.Nmem;
import org.lwjgl.stb.STBIWriteCallbackI;

/**
 * stb_image / stb_image_write / stb_image_resize, as used by NativeImage (texture and
 * icon loading, screenshots), implemented in Java over nmem: the binding methods
 * Minecraft calls keep their pointer-based contracts.
 */
public final class StbSubstitutions {
    private StbSubstitutions() {
    }

    private static String failure = "";
    private static long failureString;
    /**
     * stb_image_write's globals as 8-byte nmem slots. STBImageWrite's static initializer
     * takes the address of each (a failure there breaks every PNG write).
     */
    static final int PNG_COMPRESSION_LEVEL = 0;
    static final int FORCE_PNG_FILTER = 1;
    static final int ZLIB_COMPRESS = 2;
    static final int TGA_WITH_RLE = 3;
    private static long globals;

    static long global(int slot) {
        if (globals == 0) {
            globals = NativeHeap.calloc(4, 8);
            Nmem.putInt(globals + 8L * PNG_COMPRESSION_LEVEL, 8);
            Nmem.putInt(globals + 8L * FORCE_PNG_FILTER, -1);
            Nmem.putLong(globals + 8L * ZLIB_COMPRESS, 0); // no custom zlib
            Nmem.putInt(globals + 8L * TGA_WITH_RLE, 1);
        }
        return globals + 8L * slot;
    }

    static long load(long buffer, int len, long x, long y, long channels, int desired) {
        byte[] data = new byte[len];
        Nmem.copyToBytes(buffer, data, 0, len);
        try {
            PngCodec.Image img = PngCodec.decode(data, desired);
            int size = img.width() * img.height() * img.channels();
            long out = NativeHeap.malloc(Math.max(1, size));
            Nmem.copyFromBytes(img.pixels(), 0, out, size);
            Nmem.putInt(x, img.width());
            Nmem.putInt(y, img.height());
            if (channels != 0) {
                Nmem.putInt(channels, img.fileChannels());
            }
            return out;
        } catch (IOException | RuntimeException e) {
            failure = PngCodec.isPng(data) ? "corrupt PNG: " + e.getMessage() : "unknown image type";
            return 0L;
        }
    }

    static long failureReason() {
        if (failureString != 0) {
            NativeHeap.free(failureString);
        }
        byte[] b = failure.getBytes(StandardCharsets.UTF_8);
        failureString = NativeHeap.calloc(1, b.length + 1);
        Nmem.copyFromBytes(b, 0, failureString, b.length);
        return failureString;
    }

    static int writePng(long func, long context, int w, int h, int comp, long data, int stride) {
        int rowStride = stride == 0 ? w * comp : stride;
        byte[] pixels = new byte[rowStride * h];
        Nmem.copyToBytes(data, pixels, 0, pixels.length);
        byte[] png = PngCodec.encode(pixels, w, h, comp, rowStride);
        Object callback = CallbackSubstitutions.lookup(func);
        if (!(callback instanceof STBIWriteCallbackI cb)) {
            return 0;
        }
        long tmp = NativeHeap.malloc(png.length);
        try {
            Nmem.copyFromBytes(png, 0, tmp, png.length);
            cb.invoke(context, tmp, png.length);
        } finally {
            NativeHeap.free(tmp);
        }
        return 1;
    }

    /** stbir pixel layouts → channel count (stb_image_resize2.h enum values). */
    private static int channels(int layout) {
        return switch (layout) {
            case 1 -> 1;
            case 2, 9, 10, 15, 16 -> 2;
            case 0, 3 -> 3;
            default -> 4;
        };
    }

    static long resize(long input, int iw, int ih, int istride, long output, int ow, int oh, int ostride, int layout) {
        int ch = channels(layout);
        int is = istride == 0 ? iw * ch : istride;
        int os = ostride == 0 ? ow * ch : ostride;
        if (output == 0) {
            output = NativeHeap.malloc((long) os * oh);
        }
        for (int y = 0; y < oh; y++) {
            float sy = Math.max(0f, (y + 0.5f) * ih / oh - 0.5f);
            int y0 = Math.min((int) sy, ih - 1);
            int y1 = Math.min(y0 + 1, ih - 1);
            float fy = sy - y0;
            for (int x = 0; x < ow; x++) {
                float sx = Math.max(0f, (x + 0.5f) * iw / ow - 0.5f);
                int x0 = Math.min((int) sx, iw - 1);
                int x1 = Math.min(x0 + 1, iw - 1);
                float fx = sx - x0;
                for (int c = 0; c < ch; c++) {
                    float a = Nmem.getByte(input + (long) y0 * is + x0 * ch + c) & 0xFF;
                    float b = Nmem.getByte(input + (long) y0 * is + x1 * ch + c) & 0xFF;
                    float d = Nmem.getByte(input + (long) y1 * is + x0 * ch + c) & 0xFF;
                    float e = Nmem.getByte(input + (long) y1 * is + x1 * ch + c) & 0xFF;
                    float v = (a * (1 - fx) + b * fx) * (1 - fy) + (d * (1 - fx) + e * fx) * fy;
                    Nmem.putByte(output + (long) y * os + x * ch + c, (byte) Math.round(Math.max(0, Math.min(255, v))));
                }
            }
        }
        return output;
    }
}

/** stb's allocator hooks: nothing native to hook (the codec above allocates from nmem). */
@TargetClass(className = "org.lwjgl.stb.LibSTB")
final class Target_org_lwjgl_stb_LibSTB {
    @Substitute
    private static void setupMalloc(long malloc, long calloc, long realloc, long free, long alignedAlloc, long alignedFree) {
    }
}

@TargetClass(org.lwjgl.stb.STBImage.class)
final class Target_org_lwjgl_stb_STBImage {
    @Substitute
    public static long nstbi_load_from_memory(long buffer, int len, long x, long y, long channels, int desired) {
        return StbSubstitutions.load(buffer, len, x, y, channels, desired);
    }

    @Substitute
    public static long nstbi_failure_reason() {
        return StbSubstitutions.failureReason();
    }

    @Substitute
    public static void nstbi_image_free(long retval) {
        if (retval != 0) {
            NativeHeap.free(retval);
        }
    }
}

@TargetClass(org.lwjgl.stb.STBImageWrite.class)
final class Target_org_lwjgl_stb_STBImageWrite {
    @Substitute
    public static int nstbi_write_png_to_func(long func, long context, int w, int h, int comp, long data, int stride) {
        return StbSubstitutions.writePng(func, context, w, h, comp, data, stride);
    }

    /** Addresses of stb_image_write's globals (taken by STBImageWrite's static initializer). */
    @Substitute
    public static long nstbi_write_png_compression_level() {
        return StbSubstitutions.global(StbSubstitutions.PNG_COMPRESSION_LEVEL);
    }

    @Substitute
    public static long nstbi_write_force_png_filter() {
        return StbSubstitutions.global(StbSubstitutions.FORCE_PNG_FILTER);
    }

    @Substitute
    public static long nstbi_zlib_compress() {
        return StbSubstitutions.global(StbSubstitutions.ZLIB_COMPRESS);
    }

    @Substitute
    public static long nstbi_write_tga_with_rle() {
        return StbSubstitutions.global(StbSubstitutions.TGA_WITH_RLE);
    }
}

@TargetClass(org.lwjgl.stb.STBImageResize.class)
final class Target_org_lwjgl_stb_STBImageResize {
    @Substitute
    public static long nstbir_resize_uint8_linear(long input, int iw, int ih, int istride, long output, int ow, int oh, int ostride, int layout) {
        return StbSubstitutions.resize(input, iw, ih, istride, output, ow, oh, ostride, layout);
    }
}
