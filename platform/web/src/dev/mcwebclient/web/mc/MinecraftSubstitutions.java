package dev.mcwebclient.web.mc;

import com.mojang.blaze3d.platform.SDLEventHandler;
import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

import dev.mcwebclient.web.exec.WebExecutorService;
import dev.mcwebclient.web.gpu.WebGpuBackend;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.TracingExecutor;
import net.minecraft.client.Minecraft;

/**
 * Minecraft classes whose behaviour depends on real threads, a blocking main loop or
 * native graphics backends.
 *
 * <p>Substituting a method replaces its baked body, including any mixin code injected
 * into it. Each substitution here notes what the bake injected and how it is kept.</p>
 */
final class MinecraftSubstitutions {
    private MinecraftSubstitutions() {
    }
}

@TargetClass(net.minecraft.util.Util.class)
final class Target_net_minecraft_util_Util {
    /** Background worker pools become cooperative executors (no mixins injected). */
    @Substitute
    private static TracingExecutor makeExecutor(String name) {
        return new TracingExecutor(new WebExecutorService("Worker-" + name));
    }

    @Substitute
    private static TracingExecutor makeIoExecutor(String prefix, boolean daemon) {
        return new TracingExecutor(new WebExecutorService(prefix));
    }
}

@TargetClass(Minecraft.class)
final class Target_net_minecraft_client_Minecraft {
    @Alias Thread gameThread;
    @Alias volatile boolean running;
    @Alias SDLEventHandler sdlEventHandler;

    @Alias
    native void runTick(boolean advanceGameTime);

    /**
     * The desktop loop never returns; the browser needs control back every frame.
     * This starts the frame pump and unwinds Main.main with {@link MainLoopDetached}.
     *
     * <p>Baked mixins in the original: fabric-lifecycle-events-v1 fires
     * ClientLifecycleEvents.CLIENT_STARTED at the head of run(); fired here instead.</p>
     */
    @Substitute
    public void run() {
        gameThread = Thread.currentThread();
        Minecraft self = (Minecraft) (Object) this;
        ClientLifecycleEvents.CLIENT_STARTED.invoker().onClientStarted(self);
        FramePump.start(self);
        throw new MainLoopDetached();
    }
}

@TargetClass(net.minecraft.client.PreferredGraphicsApi.class)
final class Target_net_minecraft_client_PreferredGraphicsApi {
    /** Only the browser backend exists; OpenGL/Vulkan drop out of the image entirely. */
    @Substitute
    public GpuBackend[] getBackendsToTry() {
        return new GpuBackend[] {new WebGpuBackend()};
    }
}

@TargetClass(com.mojang.renderpearl.backend.vulkan.VulkanBackend.class)
final class Target_com_mojang_renderpearl_backend_vulkan_VulkanBackend {
    @Substitute
    public static BackendCreationException checkBackendAvailable() {
        return null;
    }
}
