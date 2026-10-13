package dev.mcwebclient.web.gpu;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.common.BaseGpuTexture;

/** A GPUTexture plus single-mip 2D views used as render pass attachments. */
final class WebGpuTexture extends BaseGpuTexture {
    final int id;
    private final int[] attachmentViews;
    private boolean closed;

    WebGpuTexture(@GpuTexture.Usage int usage, String label, GpuFormat format, int width, int height, int depthOrLayers, int mipLevels) {
        super(usage, label, format, width, height, depthOrLayers, mipLevels);
        if (!WebGpuEnums.textureFormatSupported(format)) {
            throw new IllegalArgumentException("WebGPU has no texture format for " + format);
        }
        this.id = WebGpu.newId();
        this.attachmentViews = new int[mipLevels];
        if (label != null && !label.isEmpty()) {
            WebGpu.setLabel(id, label);
        }
        WebGpu.begin(9);
        WebGpu.put(WebGpu.CREATE_TEXTURE);
        WebGpu.put(id);
        WebGpu.put(WebGpuEnums.format(format));
        WebGpu.put(0); // 2d
        WebGpu.put(Math.max(1, width));
        WebGpu.put(Math.max(1, height));
        WebGpu.put(Math.max(1, depthOrLayers));
        WebGpu.put(Math.max(1, mipLevels));
        WebGpu.put(gpuUsage(usage, format));
    }

    private static int gpuUsage(@GpuTexture.Usage int usage, GpuFormat format) {
        // Copies are always allowed (uploads, readbacks); render-attachment is added where the
        // format allows it so clears can use render passes.
        int u = WebGpu.TEX_COPY_SRC | WebGpu.TEX_COPY_DST;
        if ((usage & USAGE_TEXTURE_BINDING) != 0) {
            u |= WebGpu.TEX_BINDING;
        }
        if ((usage & USAGE_RENDER_ATTACHMENT) != 0 || renderable(format)) {
            u |= WebGpu.TEX_RENDER;
        }
        return u;
    }

    static boolean renderable(GpuFormat f) {
        return switch (f) {
            case R8_SNORM, RG8_SNORM, RGBA8_SNORM, R16_UNORM, R16_SNORM, RG16_UNORM, RG16_SNORM, RGBA16_UNORM,
                    RGBA16_SNORM, RG11B10_FLOAT -> false;
            default -> WebGpuEnums.textureFormatSupported(f);
        };
    }

    boolean isCube() {
        return (usage() & USAGE_CUBEMAP_COMPATIBLE) != 0;
    }

    /** A view of one mip level (layer 0), usable as a color/depth attachment. */
    int attachmentView(int mip) {
        int v = attachmentViews[mip];
        if (v == 0) {
            v = WebGpu.newId();
            attachmentViews[mip] = v;
            WebGpu.begin(9);
            WebGpu.put(WebGpu.CREATE_VIEW);
            WebGpu.put(v);
            WebGpu.put(id);
            WebGpu.put(WebGpuEnums.VIEW_2D);
            WebGpu.put(mip);
            WebGpu.put(1);
            WebGpu.put(0);
            WebGpu.put(1);
            WebGpu.put(WebGpuEnums.ASPECT_ALL);
        }
        return v;
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
        closed = true;
        for (int v : attachmentViews) {
            if (v != 0) {
                WebGpuDevice.current().releaseBindings(v);
                WebGpu.destroy(v);
            }
        }
        WebGpuDevice.current().releaseBindings(id);
        WebGpu.destroy(id);
    }
}
