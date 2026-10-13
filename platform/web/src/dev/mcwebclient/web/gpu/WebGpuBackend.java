package dev.mcwebclient.web.gpu;

import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;

/**
 * Renderpearl backend for the browser. Phase 2 stub: device creation is not
 * implemented yet; the WebGPU device backend arrives in Phase 3.
 */
public final class WebGpuBackend implements GpuBackend {
    /** The canvas is the one and only window. */
    public static final long WINDOW_HANDLE = 1L;

    @Override
    public String getName() {
        return "WebGPU";
    }

    @Override
    public void loadLibrary() {
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
        throw new BackendCreationException("WebGPU device backend not implemented yet (Phase 3)",
                BackendCreationException.Reason.PLATFORM_ERROR);
    }
}
