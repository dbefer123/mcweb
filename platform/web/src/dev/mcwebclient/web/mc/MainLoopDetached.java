package dev.mcwebclient.web.mc;

/**
 * Thrown out of Minecraft.run() once the frame pump owns the loop, so Main.main
 * unwinds without running its shutdown path. Caught by WebMain.
 */
public final class MainLoopDetached extends RuntimeException {
    public MainLoopDetached() {
        super("main loop handed to the browser frame pump", null, false, false);
    }
}
