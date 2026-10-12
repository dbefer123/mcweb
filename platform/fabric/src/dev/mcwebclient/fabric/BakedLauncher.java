package dev.mcwebclient.fabric;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.FormattedException;
import net.fabricmc.loader.impl.game.GameProvider;
import net.fabricmc.loader.impl.game.minecraft.MinecraftGameProvider;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.fabricmc.loader.impl.util.log.Log;

/**
 * Fabric launcher for pre-baked classes. It mirrors Knot's init (mod discovery,
 * metadata, entrypoints, class tweaker loading) but has no transforming class
 * loader and no Mixin: every class on the class path was already transformed by
 * tools/fabric-baker. Classes load from the application class loader.
 */
public final class BakedLauncher extends FabricLauncherBase {
    private final List<Path> classPath = new ArrayList<>();
    private GameProvider provider;

    public static void main(String[] args) {
        BakedLauncher launcher = new BakedLauncher();
        try {
            launcher.init(args);
            launcher.provider.launch(launcher.getTargetClassLoader());
        } catch (FormattedException e) {
            handleFormattedException(e);
        }
    }

    void init(String[] args) {
        setProperties(new HashMap<>());
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            Path p = Paths.get(entry);
            if (Files.exists(p)) {
                classPath.add(p.toAbsolutePath().normalize());
            }
        }

        MinecraftGameProvider mc = new MinecraftGameProvider();
        if (!mc.isEnabled() || !mc.locateGame(this, args)) {
            throw new IllegalStateException("could not locate the baked Minecraft jar on the class path");
        }
        provider = mc;
        Log.finishBuiltinConfig();
        Log.info(net.fabricmc.loader.impl.util.log.LogCategory.GAME_PROVIDER,
                "Loading %s %s with Fabric Loader %s (baked classes, no runtime transformer)",
                provider.getGameName(), provider.getRawGameVersion(), FabricLoaderImpl.VERSION);

        FabricLoaderImpl loader = FabricLoaderImpl.INSTANCE;
        loader.setGameProvider(provider);
        provider.initialize(this);
        loader.load();
        loader.freeze();
        loader.loadClassTweakers();
        provider.unlockClassPath(this);

        try {
            loader.invokeEntrypoints("preLaunch", PreLaunchEntrypoint.class, PreLaunchEntrypoint::onPreLaunch);
        } catch (RuntimeException e) {
            throw FormattedException.ofLocalized("exception.initializerFailure", e);
        }

        if (Boolean.getBoolean("mcweb.selftest")) {
            try {
                Class.forName("dev.mcwebclient.selftest.SelfTest").getMethod("install").invoke(null);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("self-test requested but not on the class path", e);
            }
        }
    }

    @Override
    public void addToClassPath(Path path, String... allowedPrefixes) {
        // Classes are already baked onto the real class path; nothing to add.
    }

    @Override
    public void setAllowedPrefixes(Path path, String... prefixes) {
    }

    @Override
    public void setValidParentClassPath(Collection<Path> paths) {
    }

    @Override
    public EnvType getEnvironmentType() {
        return EnvType.CLIENT;
    }

    @Override
    public boolean isClassLoaded(String name) {
        return false;
    }

    @Override
    public Class<?> loadIntoTarget(String name) throws ClassNotFoundException {
        return Class.forName(name, true, getTargetClassLoader());
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        return getTargetClassLoader().getResourceAsStream(name);
    }

    @Override
    public ClassLoader getTargetClassLoader() {
        return BakedLauncher.class.getClassLoader();
    }

    @Override
    public byte[] getClassByteArray(String name, boolean runTransformers) throws IOException {
        try (InputStream in = getTargetClassLoader().getResourceAsStream(name.replace('.', '/') + ".class")) {
            return in == null ? null : in.readAllBytes();
        }
    }

    @Override
    public Manifest getManifest(Path originPath) {
        if (!Files.isRegularFile(originPath)) {
            return null;
        }
        try (JarFile jar = new JarFile(originPath.toFile())) {
            return jar.getManifest();
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public String getEntrypoint() {
        return provider.getEntrypoint();
    }

    @Override
    public List<Path> getClassPath() {
        return classPath;
    }
}
