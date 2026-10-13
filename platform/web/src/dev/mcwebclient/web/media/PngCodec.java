package dev.mcwebclient.web.media;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * PNG decoding/encoding in plain Java, standing in for stb_image / stb_image_write
 * (native in the desktop game). Decodes every PNG variant (gray, RGB, palette,
 * gray+alpha, RGBA; 1-16 bits; tRNS; Adam7) to 8-bit channels like stbi does.
 */
public final class PngCodec {
    private PngCodec() {
    }

    /** A decoded image: 8-bit pixels, {@code channels} per pixel, rows tightly packed. */
    public record Image(int width, int height, int channels, int fileChannels, byte[] pixels) {
    }

    private static final byte[] SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};

    public static boolean isPng(byte[] data) {
        if (data.length < 8) {
            return false;
        }
        for (int i = 0; i < 8; i++) {
            if (data[i] != SIGNATURE[i]) {
                return false;
            }
        }
        return true;
    }

    private static int u32(byte[] d, int p) {
        return ((d[p] & 0xFF) << 24) | ((d[p + 1] & 0xFF) << 16) | ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF);
    }

    /**
     * Decodes a PNG. {@code desiredChannels} = 0 keeps the file's channel count
     * (1 gray, 2 gray+alpha, 3 RGB, 4 RGBA), else converts like stbi.
     */
    public static Image decode(byte[] data, int desiredChannels) throws IOException {
        if (!isPng(data)) {
            throw new IOException("not a PNG");
        }
        int p = 8;
        int width = 0, height = 0, bitDepth = 0, colorType = 0, interlace = 0;
        byte[] palette = null;
        byte[] trns = null;
        ByteArrayOutputStream idat = new ByteArrayOutputStream(data.length);
        while (p + 8 <= data.length) {
            int len = u32(data, p);
            int type = u32(data, p + 4);
            int body = p + 8;
            if (len < 0 || body + len > data.length) {
                throw new IOException("corrupt PNG chunk");
            }
            switch (type) {
                case 0x49484452 -> { // IHDR
                    width = u32(data, body);
                    height = u32(data, body + 4);
                    bitDepth = data[body + 8] & 0xFF;
                    colorType = data[body + 9] & 0xFF;
                    interlace = data[body + 12] & 0xFF;
                }
                case 0x504C5445 -> { // PLTE
                    palette = new byte[len];
                    System.arraycopy(data, body, palette, 0, len);
                }
                case 0x74524E53 -> { // tRNS
                    trns = new byte[len];
                    System.arraycopy(data, body, trns, 0, len);
                }
                case 0x49444154 -> idat.write(data, body, len); // IDAT
                default -> {
                }
            }
            if (type == 0x49454E44) { // IEND
                break;
            }
            p = body + len + 4;
        }
        if (width <= 0 || height <= 0) {
            throw new IOException("PNG without a valid IHDR");
        }
        int samples = switch (colorType) {
            case 0, 3 -> 1;
            case 2 -> 3;
            case 4 -> 2;
            case 6 -> 4;
            default -> throw new IOException("bad PNG color type " + colorType);
        };
        int fileChannels = switch (colorType) {
            case 0 -> trns != null ? 2 : 1;
            case 2 -> trns != null ? 4 : 3;
            case 3 -> trns != null ? 4 : 3;
            case 4 -> 2;
            default -> 4;
        };
        int outChannels = desiredChannels == 0 ? fileChannels : desiredChannels;
        byte[] raw = inflate(idat.toByteArray());
        // Decode to RGBA8 first, then reduce to the requested channel count.
        byte[] rgba = new byte[width * height * 4];
        int bitsPerPixel = samples * bitDepth;
        int bytesPerPixel = Math.max(1, bitsPerPixel / 8);
        if (interlace == 0) {
            unpack(raw, 0, width, height, bitDepth, colorType, samples, palette, trns, rgba, 0, 0, 1, 1, width, bytesPerPixel);
        } else {
            int[][] passes = {{0, 0, 8, 8}, {4, 0, 8, 8}, {0, 4, 4, 8}, {2, 0, 4, 4}, {0, 2, 2, 4}, {1, 0, 2, 2}, {0, 1, 1, 2}};
            int offset = 0;
            for (int[] pass : passes) {
                int pw = (width - pass[0] + pass[2] - 1) / pass[2];
                int ph = (height - pass[1] + pass[3] - 1) / pass[3];
                if (pw <= 0 || ph <= 0) {
                    continue;
                }
                offset = unpack(raw, offset, pw, ph, bitDepth, colorType, samples, palette, trns, rgba, pass[0], pass[1], pass[2], pass[3],
                        width, bytesPerPixel);
            }
        }
        return new Image(width, height, outChannels, fileChannels, convert(rgba, width * height, outChannels));
    }

    private static byte[] inflate(byte[] z) throws IOException {
        Inflater inflater = new Inflater();
        inflater.setInput(z);
        ByteArrayOutputStream out = new ByteArrayOutputStream(z.length * 4);
        byte[] buf = new byte[65536];
        try {
            while (!inflater.finished()) {
                int n = inflater.inflate(buf);
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        break;
                    }
                }
                out.write(buf, 0, n);
            }
        } catch (DataFormatException e) {
            throw new IOException("corrupt PNG data", e);
        } finally {
            inflater.end();
        }
        return out.toByteArray();
    }

    /** Unfilters one (sub)image and writes its pixels into {@code rgba}; returns the next raw offset. */
    private static int unpack(byte[] raw, int offset, int w, int h, int bitDepth, int colorType, int samples, byte[] palette, byte[] trns,
            byte[] rgba, int x0, int y0, int dx, int dy, int fullWidth, int bpp) throws IOException {
        int rowBytes = (w * samples * bitDepth + 7) / 8;
        byte[] prev = new byte[rowBytes];
        byte[] cur = new byte[rowBytes];
        for (int y = 0; y < h; y++) {
            if (offset + 1 + rowBytes > raw.length) {
                throw new IOException("truncated PNG data");
            }
            int filter = raw[offset] & 0xFF;
            System.arraycopy(raw, offset + 1, cur, 0, rowBytes);
            offset += 1 + rowBytes;
            for (int i = 0; i < rowBytes; i++) {
                int a = i >= bpp ? cur[i - bpp] & 0xFF : 0;
                int b = prev[i] & 0xFF;
                int c = i >= bpp ? prev[i - bpp] & 0xFF : 0;
                int x = cur[i] & 0xFF;
                int v = switch (filter) {
                    case 0 -> x;
                    case 1 -> x + a;
                    case 2 -> x + b;
                    case 3 -> x + ((a + b) >> 1);
                    case 4 -> {
                        int pp = a + b - c;
                        int pa = Math.abs(pp - a);
                        int pb = Math.abs(pp - b);
                        int pc = Math.abs(pp - c);
                        yield x + (pa <= pb && pa <= pc ? a : pb <= pc ? b : c);
                    }
                    default -> throw new IOException("bad PNG filter " + filter);
                };
                cur[i] = (byte) v;
            }
            int outY = y0 + y * dy;
            for (int x = 0; x < w; x++) {
                int outX = x0 + x * dx;
                writePixel(cur, x, bitDepth, colorType, samples, palette, trns, rgba, (outY * fullWidth + outX) * 4);
            }
            byte[] t = prev;
            prev = cur;
            cur = t;
        }
        return offset;
    }

    private static int sample(byte[] row, int index, int bitDepth) {
        return switch (bitDepth) {
            case 8 -> row[index] & 0xFF;
            case 16 -> ((row[index * 2] & 0xFF) << 8) | (row[index * 2 + 1] & 0xFF);
            default -> {
                int perByte = 8 / bitDepth;
                int b = row[index / perByte] & 0xFF;
                int shift = 8 - bitDepth * (index % perByte + 1);
                yield (b >> shift) & ((1 << bitDepth) - 1);
            }
        };
    }

    private static int to8(int v, int bitDepth) {
        return switch (bitDepth) {
            case 1 -> v * 255;
            case 2 -> v * 85;
            case 4 -> v * 17;
            case 16 -> v >> 8;
            default -> v;
        };
    }

    private static void writePixel(byte[] row, int x, int bitDepth, int colorType, int samples, byte[] palette, byte[] trns, byte[] out, int o) {
        int r, g, b, a = 255;
        switch (colorType) {
            case 0 -> {
                int raw = sample(row, x, bitDepth);
                r = g = b = to8(raw, bitDepth);
                if (trns != null && trns.length >= 2 && raw == (((trns[0] & 0xFF) << 8) | (trns[1] & 0xFF))) {
                    a = 0;
                }
            }
            case 2 -> {
                int rr = sample(row, x * 3, bitDepth);
                int gg = sample(row, x * 3 + 1, bitDepth);
                int bb = sample(row, x * 3 + 2, bitDepth);
                r = to8(rr, bitDepth);
                g = to8(gg, bitDepth);
                b = to8(bb, bitDepth);
                if (trns != null && trns.length >= 6 && rr == (((trns[0] & 0xFF) << 8) | (trns[1] & 0xFF))
                        && gg == (((trns[2] & 0xFF) << 8) | (trns[3] & 0xFF)) && bb == (((trns[4] & 0xFF) << 8) | (trns[5] & 0xFF))) {
                    a = 0;
                }
            }
            case 3 -> {
                int idx = sample(row, x, bitDepth);
                if (palette != null && idx * 3 + 2 < palette.length) {
                    r = palette[idx * 3] & 0xFF;
                    g = palette[idx * 3 + 1] & 0xFF;
                    b = palette[idx * 3 + 2] & 0xFF;
                } else {
                    r = g = b = 0;
                }
                if (trns != null && idx < trns.length) {
                    a = trns[idx] & 0xFF;
                }
            }
            case 4 -> {
                r = g = b = to8(sample(row, x * 2, bitDepth), bitDepth);
                a = to8(sample(row, x * 2 + 1, bitDepth), bitDepth);
            }
            default -> {
                r = to8(sample(row, x * 4, bitDepth), bitDepth);
                g = to8(sample(row, x * 4 + 1, bitDepth), bitDepth);
                b = to8(sample(row, x * 4 + 2, bitDepth), bitDepth);
                a = to8(sample(row, x * 4 + 3, bitDepth), bitDepth);
            }
        }
        out[o] = (byte) r;
        out[o + 1] = (byte) g;
        out[o + 2] = (byte) b;
        out[o + 3] = (byte) a;
    }

    /** RGBA8 → 1..4 channels, as stbi converts (gray = luminance). */
    private static byte[] convert(byte[] rgba, int pixels, int channels) {
        if (channels == 4) {
            return rgba;
        }
        byte[] out = new byte[pixels * channels];
        for (int i = 0; i < pixels; i++) {
            int r = rgba[i * 4] & 0xFF, g = rgba[i * 4 + 1] & 0xFF, b = rgba[i * 4 + 2] & 0xFF, a = rgba[i * 4 + 3] & 0xFF;
            int o = i * channels;
            switch (channels) {
                case 1 -> out[o] = (byte) ((r * 77 + g * 150 + b * 29) >> 8);
                case 2 -> {
                    out[o] = (byte) ((r * 77 + g * 150 + b * 29) >> 8);
                    out[o + 1] = (byte) a;
                }
                default -> {
                    out[o] = (byte) r;
                    out[o + 1] = (byte) g;
                    out[o + 2] = (byte) b;
                }
            }
        }
        return out;
    }

    /** Encodes 8-bit pixels (1-4 channels, {@code stride} bytes per row) as a PNG. */
    public static byte[] encode(byte[] pixels, int width, int height, int channels, int stride) {
        int colorType = switch (channels) {
            case 1 -> 0;
            case 2 -> 4;
            case 3 -> 2;
            default -> 6;
        };
        int rowBytes = width * channels;
        byte[] filtered = new byte[(rowBytes + 1) * height];
        for (int y = 0; y < height; y++) {
            filtered[y * (rowBytes + 1)] = 0;
            System.arraycopy(pixels, y * stride, filtered, y * (rowBytes + 1) + 1, rowBytes);
        }
        Deflater deflater = new Deflater(6);
        deflater.setInput(filtered);
        deflater.finish();
        ByteArrayOutputStream z = new ByteArrayOutputStream(filtered.length / 2 + 64);
        byte[] buf = new byte[65536];
        while (!deflater.finished()) {
            int n = deflater.deflate(buf);
            z.write(buf, 0, n);
        }
        deflater.end();
        ByteArrayOutputStream out = new ByteArrayOutputStream(z.size() + 64);
        out.writeBytes(SIGNATURE);
        byte[] ihdr = new byte[13];
        putU32(ihdr, 0, width);
        putU32(ihdr, 4, height);
        ihdr[8] = 8;
        ihdr[9] = (byte) colorType;
        chunk(out, 0x49484452, ihdr);
        chunk(out, 0x49444154, z.toByteArray());
        chunk(out, 0x49454E44, new byte[0]);
        return out.toByteArray();
    }

    private static void putU32(byte[] d, int p, int v) {
        d[p] = (byte) (v >>> 24);
        d[p + 1] = (byte) (v >>> 16);
        d[p + 2] = (byte) (v >>> 8);
        d[p + 3] = (byte) v;
    }

    private static void chunk(ByteArrayOutputStream out, int type, byte[] body) {
        byte[] head = new byte[8];
        putU32(head, 0, body.length);
        putU32(head, 4, type);
        out.writeBytes(head);
        out.writeBytes(body);
        CRC32 crc = new CRC32();
        crc.update(head, 4, 4);
        crc.update(body);
        byte[] c = new byte[4];
        putU32(c, 0, (int) crc.getValue());
        out.writeBytes(c);
    }
}
