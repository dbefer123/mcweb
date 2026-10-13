package dev.mcwebclient.web.gpu;

import java.util.Collection;
import java.util.List;

import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;

/**
 * The page's canvas. The browser presents whatever was drawn into the canvas texture
 * when the frame callback returns, so acquire/present are bookkeeping; the blit
 * flips rows back (framebuffers use the Vulkan layout, see WgslTranslator).
 */
final class WebGpuSurface implements GpuSurfaceBackend {
    @Override
    public void configure(GpuSurface.Configuration config) {
        WebGpu.flush();
        WebGpu.configureCanvas(Math.max(1, config.width()), Math.max(1, config.height()));
    }

    @Override
    public boolean isSuboptimal() {
        return false;
    }

    @Override
    public void acquireNextTexture() {
    }

    @Override
    public void blitFromTexture(CommandEncoderBackend commandEncoder, GpuTextureView textureView) {
        WebGpu.begin(2);
        WebGpu.put(WebGpu.BLIT_CANVAS);
        WebGpu.put(((WebGpuTextureView) textureView).id);
    }

    @Override
    public void present() {
    }

    @Override
    public Collection<GpuSurface.PresentMode> supportedPresentModes() {
        return List.of(GpuSurface.PresentMode.FIFO, GpuSurface.PresentMode.IMMEDIATE, GpuSurface.PresentMode.MAILBOX);
    }

    @Override
    public void close() {
    }
}
