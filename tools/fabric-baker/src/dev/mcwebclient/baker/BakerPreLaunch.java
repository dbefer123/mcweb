package dev.mcwebclient.baker;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/**
 * Runs the bake inside a real Knot launch, after Fabric has discovered mods,
 * applied class tweakers and bootstrapped Mixin, but before Minecraft starts.
 * The JVM is halted afterwards so the game never actually launches.
 */
public final class BakerPreLaunch implements PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
        int status = 0;
        try {
            new Baker().run();
        } catch (Throwable t) {
            System.err.println("[baker] FAILED");
            t.printStackTrace();
            status = 2;
        }
        System.out.flush();
        System.err.flush();
        Runtime.getRuntime().halt(status);
    }
}
