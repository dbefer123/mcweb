package dev.mcwebclient.web.fabric;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.jar.Manifest;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.FormattedException;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

/**
 * Fabric launcher inside the WasmGC image. Same steps as Knot.init minus everything
 * that needs a JVM class loader (no class path, no transformers, no Mixin): classes
 * are compiled into the image already baked. Mod jars still live in the virtual file
 * system so Fabric can read their metadata and resources normally.
 */
public final class WebLauncher extends FabricLauncherBase {
    private final WebGameProvider provider;

    private WebLauncher(WebGameProvider provider) {
        this.provider = provider;
    }

    /** Runs Fabric init and then Minecraft's main method. */
    public static void launch(Path gameDir, Path gameRoot, String[] args) {
        WebLauncher launcher = new WebLauncher(new WebGameProvider(gameDir, gameRoot));
        try {
            launcher.init(args);
        } catch (FormattedException e) {
            handleFormattedException(e);
            return;
        }
        launcher.provider.launch(launcher.getTargetClassLoader());
    }

    private void init(String[] args) {
        setProperties(new HashMap<>());
        provider.locateGame(this, args);
        Log.finishBuiltinConfig();
        Log.info(LogCategory.GAME_PROVIDER, "Loading %s %s with Fabric Loader %s (WebAssembly, baked classes)",
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
    }

    @Override
    public void addToClassPath(Path path, String... allowedPrefixes) {
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
        return WebLauncher.class.getClassLoader();
    }

    @Override
    public byte[] getClassByteArray(String name, boolean runTransformers) {
        return null;
    }

    @Override
    public Manifest getManifest(Path originPath) {
        return null;
    }

    @Override
    public String getEntrypoint() {
        return provider.getEntrypoint();
    }

    @Override
    public List<Path> getClassPath() {
        return List.of();
    }
}
