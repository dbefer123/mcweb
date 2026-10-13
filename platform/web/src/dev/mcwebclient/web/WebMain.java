package dev.mcwebclient.web;

import java.nio.file.Files;
import java.nio.file.Path;

import dev.mcwebclient.web.fabric.WebLauncher;
import dev.mcwebclient.web.mc.MainLoopDetached;

/**
 * Entry point of the WebAssembly image: prepares the runtime, then runs Fabric init
 * and Minecraft's main method. Minecraft.run() hands the loop to the browser's frame
 * callback and unwinds back here with {@link MainLoopDetached}.
 */
public final class WebMain {
    private WebMain() {
    }

    public static void main(String[] args) throws Exception {
        WebJs.stage("boot");
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
        try {
            WebLauncher.launch(WebRuntime.GAME_DIR, gameRoot, gameArgs);
            WebJs.log("[web] Minecraft main returned without starting the frame loop");
            WebJs.exit(2);
        } catch (MainLoopDetached detached) {
            WebJs.log("[web] main loop handed to the browser");
        }
    }
}
