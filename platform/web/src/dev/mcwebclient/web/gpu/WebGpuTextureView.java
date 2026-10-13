package dev.mcwebclient.web.gpu;

import com.mojang.renderpearl.backend.common.BaseGpuTextureView;

/** A GPUTextureView for sampling: cube for cubemap-compatible textures, else 2D (array). */
final class WebGpuTextureView extends BaseGpuTextureView {
    final int id;
    final int dimension;
    private boolean closed;

    WebGpuTextureView(WebGpuTexture texture, int baseMipLevel, int mipLevels) {
        super(texture, baseMipLevel, mipLevels);
        this.id = WebGpu.newId();
        int layers = Math.max(1, texture.getDepthOrLayers());
        if (texture.isCube()) {
            dimension = layers > 6 ? WebGpuEnums.VIEW_CUBE_ARRAY : WebGpuEnums.VIEW_CUBE;
        } else {
            dimension = layers > 1 ? WebGpuEnums.VIEW_2D_ARRAY : WebGpuEnums.VIEW_2D;
        }
        WebGpu.begin(9);
        WebGpu.put(WebGpu.CREATE_VIEW);
        WebGpu.put(id);
        WebGpu.put(texture.id);
        WebGpu.put(dimension);
        WebGpu.put(baseMipLevel);
        WebGpu.put(mipLevels);
        WebGpu.put(0);
        WebGpu.put(texture.isCube() ? (layers / 6) * 6 : layers);
        WebGpu.put(texture.getFormat().hasDepthAspect() ? WebGpuEnums.ASPECT_DEPTH : WebGpuEnums.ASPECT_ALL);
    }

    WebGpuTexture webTexture() {
        return (WebGpuTexture) texture();
    }

    @Override
    public boolean isClosed() {
        return closed || texture().isClosed();
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            WebGpuDevice.current().releaseBindings(id);
            WebGpu.destroy(id);
        }
    }
}
