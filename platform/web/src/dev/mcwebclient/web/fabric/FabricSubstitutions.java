package dev.mcwebclient.web.fabric;

import java.nio.file.Path;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

import dev.mcwebclient.web.WebRuntime;
import dev.mcwebclient.web.fabric.gen.BakedEntrypoints;
import net.fabricmc.loader.api.LanguageAdapterException;
import net.fabricmc.loader.api.ModContainer;

/** Fabric Loader internals that assume a JVM class path. */
final class FabricSubstitutions {
    private FabricSubstitutions() {
    }
}

/**
 * Entrypoints come from the build-time table (tools/fabric-baker EntrypointTable):
 * Fabric's reflective path turns {@code Class::method} entrypoints into
 * MethodHandleProxies classes, which can't be defined in the image.
 */
@TargetClass(net.fabricmc.loader.impl.util.DefaultLanguageAdapter.class)
final class Target_net_fabricmc_loader_impl_util_DefaultLanguageAdapter {
    @Substitute
    public <T> T create(ModContainer mod, String value, Class<T> type) throws LanguageAdapterException {
        Object created;
        try {
            created = BakedEntrypoints.create(value, type);
        } catch (Exception e) {
            throw new LanguageAdapterException(e);
        }
        if (created == null) {
            throw new LanguageAdapterException("Entrypoint " + value + " (" + type.getName() + ") of "
                    + mod.getMetadata().getId() + " is not in the baked entrypoint table; rebake the mods");
        }
        if (!type.isInstance(created)) {
            throw new LanguageAdapterException(created.getClass().getName() + " cannot be cast to " + type.getName() + "!");
        }
        return type.cast(created);
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
