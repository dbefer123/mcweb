package dev.mcwebclient.web.mc;

import com.mojang.blaze3d.systems.RenderSystem;

import dev.mcwebclient.web.WebJs;
import dev.mcwebclient.web.gpu.WebGpu;
import dev.mcwebclient.web.exec.WebScheduler;
import net.minecraft.client.Minecraft;

/**
 * Drives the game from requestAnimationFrame: each browser frame runs queued
 * background work for a short budget, then one iteration of Minecraft's loop. When
 * the game asks to stop, runs the normal shutdown path once and tells the page.
 */
public final class FramePump {
    /** Background work budget per frame before the game tick. */
    private static final long BACKGROUND_BUDGET_NANOS = 4_000_000L;

    private static Minecraft minecraft;
    private static Target_net_minecraft_client_Minecraft game;
    private static boolean stopped;
    private static long frames;
    /** Minecraft's current frame rate cap (FramerateLimiter); 260+ means uncapped. */
    private static int framerateLimit = 260;
    private static long lastTickNanos;

    private FramePump() {
    }

    static void start(Minecraft mc) {
        minecraft = mc;
        game = (Target_net_minecraft_client_Minecraft) (Object) mc;
        WebJs.stage("running");
        WebJs.setFrameCallback(FramePump::onFrame);
    }

    private static void onFrame() {
        if (stopped) {
            return;
        }
        try {
            WebScheduler.pumpFrame(BACKGROUND_BUDGET_NANOS);
            WebGpu.pollReadbacks();
            if (!game.running) {
                stop();
                return;
            }
            // Honor the frame rate cap by skipping animation frames (1 ms early is fine).
            long now = System.nanoTime();
            if (framerateLimit < 260 && now - lastTickNanos < 1_000_000_000L / framerateLimit - 1_000_000L) {
                return;
            }
            lastTickNanos = now;
            // One iteration of the original loop body (profiling/metrics omitted).
            RenderSystem.pollEvents(game.sdlEventHandler);
            game.runTick(true);
            frames++;
        } catch (Throwable t) {
            WebJs.log("[frame] uncaught: " + t);
            t.printStackTrace();
            stopped = true;
            WebJs.exit(1);
        }
    }

    static void setFramerateLimit(int limit) {
        framerateLimit = Math.max(1, limit);
    }

    private static void stop() {
        stopped = true;
        try {
            minecraft.exitWorldAndClose();
        } catch (Throwable t) {
            t.printStackTrace();
        }
        WebJs.log("[frame] game stopped after " + frames + " frames");
        WebJs.exit(0);
    }
}
