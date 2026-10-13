package dev.mcwebclient.web.fabric;

import java.nio.file.Path;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

import dev.mcwebclient.web.WebRuntime;

/** Fabric Loader internals that assume a JVM class path. */
final class FabricSubstitutions {
    private FabricSubstitutions() {
    }
}

@TargetClass(net.fabricmc.loader.impl.util.UrlUtil.class)
final class Target_net_fabricmc_loader_impl_util_UrlUtil {
    /**
     * Code sources don't exist in the image. Fabric only asks for its own (to register
     * the "fabricloader" built-in mod and its nested MixinExtras jar), so answer with
     * the copy of the loader jar in the virtual file system.
     */
    @Substitute
    public static Path getCodeSource(Class<?> cls) {
        return WebRuntime.fabricLoaderJar();
    }
}
