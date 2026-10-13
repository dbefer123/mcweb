package dev.mcwebclient.web.fabric;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import net.fabricmc.loader.impl.game.GameProvider;
import net.fabricmc.loader.impl.game.patch.GameTransformer;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.metadata.BuiltinModMetadata;
import net.fabricmc.loader.impl.util.Arguments;

/**
 * Fabric game provider for the browser image. There are no jars or class path to
 * inspect: the game classes are compiled in (already patched by the baker), so this
 * only reports the game identity and starts Minecraft's main method directly.
 */
public final class WebGameProvider implements GameProvider {
    public static final String GAME_VERSION = "26.3";

    private final Path gameDir;
    private final Path gameRoot;
    private Arguments arguments;

    /**
     * @param gameDir  the game directory (options, config, mods, saves)
     * @param gameRoot path standing in for the game jar's root (vanilla resources)
     */
    public WebGameProvider(Path gameDir, Path gameRoot) {
        this.gameDir = gameDir;
        this.gameRoot = gameRoot;
    }

    @Override
    public String getGameId() {
        return "minecraft";
    }

    @Override
    public String getGameName() {
        return "Minecraft";
    }

    @Override
    public String getRawGameVersion() {
        return GAME_VERSION;
    }

    @Override
    public String getNormalizedGameVersion() {
        return GAME_VERSION;
    }

    @Override
    public Collection<BuiltinMod> getBuiltinMods() {
        return List.of(new BuiltinMod(List.of(gameRoot),
                new BuiltinModMetadata.Builder(getGameId(), getNormalizedGameVersion()).setName(getGameName()).build()));
    }

    @Override
    public String getEntrypoint() {
        return "net.minecraft.client.main.Main";
    }

    @Override
    public Path getLaunchDirectory() {
        return gameDir;
    }

    @Override
    public boolean requiresUrlClassLoader() {
        return false;
    }

    @Override
    public Set<BuiltinTransform> getBuiltinTransforms(String className) {
        return Set.of();
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public boolean locateGame(FabricLauncher launcher, String[] args) {
        arguments = new Arguments();
        arguments.parse(args);
        return true;
    }

    @Override
    public void initialize(FabricLauncher launcher) {
    }

    @Override
    public GameTransformer getEntrypointTransformer() {
        return new GameTransformer();
    }

    @Override
    public void unlockClassPath(FabricLauncher launcher) {
    }

    @Override
    public void launch(ClassLoader loader) {
        net.minecraft.client.main.Main.main(arguments.toArray());
    }

    @Override
    public Arguments getArguments() {
        return arguments;
    }

    @Override
    public String[] getLaunchArguments(boolean sanitize) {
        return arguments.toArray();
    }

    @Override
    public boolean canOpenErrorGui() {
        return false;
    }

    @Override
    public boolean hasAwtSupport() {
        return false;
    }
}
