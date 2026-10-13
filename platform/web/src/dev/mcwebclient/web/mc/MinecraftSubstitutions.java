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

/**
 * Minecraft pre-loads LWJGL's native libraries (FreeType, OpenGL, STB, SDL, OpenAL, Vulkan)
 * at startup to fail early. In the browser there are none: each binding that is used is
 * substituted instead (see sdl/, mem/), so this is a no-op.
 */
@TargetClass(com.mojang.blaze3d.platform.NativeLibrariesBootstrap.class)
final class Target_com_mojang_blaze3d_platform_NativeLibrariesBootstrap {
    @Substitute
    public static void loadLibraries() {
    }

    @Substitute
    public static boolean isVulkanLoaderAvailable() {
        return false;
    }
}

/** System reports query OSHI (native hardware probes), which can't work in a browser. */
@TargetClass(net.minecraft.SystemReport.class)
final class Target_net_minecraft_SystemReport {
    @Alias
    public native void setDetail(String key, String value);

    @Substitute
    private void putHardware(oshi.SystemInfo systemInfo) {
        setDetail("Platform", "Web browser (WebAssembly)");
    }

    @Substitute
    private void putSoftware(oshi.SystemInfo systemInfo) {
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

/**
 * The original ends by initializing VulkanFeatureSets, whose static fields describe
 * Vulkan feature structs by probing LWJGL classes reflectively. Only the Vulkan backend
 * reads them, so that step is left out (no mixins are baked into this method).
 */
@TargetClass(net.minecraft.client.ClientBootstrap.class)
final class Target_net_minecraft_client_ClientBootstrap {
    @Alias private static volatile boolean isBootstrapped;

    @Substitute
    public static void bootstrap() {
        if (!isBootstrapped) {
            isBootstrapped = true;
            net.minecraft.client.renderer.item.ItemModels.bootstrap();
            net.minecraft.client.renderer.special.SpecialModelRenderers.bootstrap();
            net.minecraft.client.color.item.ItemTintSources.bootstrap();
            net.minecraft.client.renderer.item.properties.select.SelectItemModelProperties.bootstrap();
            net.minecraft.client.renderer.item.properties.conditional.ConditionalItemModelProperties.bootstrap();
            net.minecraft.client.renderer.item.properties.numeric.RangeSelectItemModelProperties.bootstrap();
            net.minecraft.client.renderer.texture.atlas.SpriteSources.bootstrap();
            net.minecraft.client.gui.screens.dialog.DialogScreens.bootstrap();
            net.minecraft.client.gui.screens.dialog.input.InputControlHandlers.bootstrap();
            net.minecraft.client.gui.screens.dialog.body.DialogBodyHandlers.bootstrap();
        }
    }
}

@TargetClass(com.mojang.renderpearl.backend.vulkan.VulkanBackend.class)
final class Target_com_mojang_renderpearl_backend_vulkan_VulkanBackend {
    @Substitute
    public static BackendCreationException checkBackendAvailable() {
        return null;
    }
}
