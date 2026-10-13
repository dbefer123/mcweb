package dev.mcwebclient.web;

import java.nio.file.Files;
import java.nio.file.Path;

import dev.mcwebclient.web.exec.CoThreads;
import dev.mcwebclient.web.fabric.WebLauncher;

/**
 * Entry point of the WebAssembly image: prepares the runtime, then runs Fabric init
 * and Minecraft's main method. The page calls main through WebAssembly.promising, so this
 * is the main green thread: Minecraft's loop runs here as on the desktop, suspending at
 * each frame boundary (see {@link CoThreads}, mc/GameLoop).
 */
public final class WebMain {
    private WebMain() {
    }

    public static void main(String[] args) throws Exception {
        CoThreads.initMain();
        WebJs.stage("boot");
        // Also keeps the boxing helpers compiled for tools/watpatch (see BoxCache).
        if (!dev.mcwebclient.web.jdk.BoxCache.selfCheck()) {
            throw new IllegalStateException("boxing caches broken: tools/watpatch box patches missing?");
        }
        WebRuntime.boot();
        Path gameRoot = WebRuntime.ROOT.resolve("minecraft-root");
        Files.createDirectories(gameRoot);

        String[] gameArgs = {
            "--username", "Player",
            "--version", "26.3",
            "--gameDir", WebRuntime.GAME_DIR.toString(),
            "--assetsDir", WebRuntime.ASSETS_DIR.toString(),
            "--assetIndex", "34",
            "--uuid", "00000000000000000000000000000000",
            "--accessToken", "0",
            "--versionType", "release",
        };
        WebJs.stage("fabric");
        WebLauncher.launch(WebRuntime.GAME_DIR, gameRoot, gameArgs);
        WebJs.log("[web] Minecraft main returned");
        WebJs.exit(0);
    }

}
