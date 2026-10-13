package dev.mcwebclient.web.gpu;

import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;

/**
 * renderpearl backend for the browser: the page's WebGPU device (created by the host
 * before the game starts) behind renderpearl's frontend, like the Vulkan backend.
 */
public final class WebGpuBackend implements GpuBackend {
    /** The canvas is the one and only window. */
    public static final long WINDOW_HANDLE = 1L;

    @Override
    public String getName() {
        return "WebGPU";
    }

    @Override
    public void loadLibrary() throws BackendCreationException {
        if (!WebGpu.available()) {
            throw new BackendCreationException("The page has no WebGPU device", BackendCreationException.Reason.PLATFORM_ERROR);
        }
    }

    @Override
    public void unloadLibrary() {
    }

    @Override
    public long createWindow(String title, int width, int height, long flags) {
        return WINDOW_HANDLE;
    }

    @Override
    public GpuDevice createDevice(GpuDebugOptions debugOptions) throws BackendCreationException {
        return new FrontendGpuDevice(WebGpuDevice.create());
    }
}
