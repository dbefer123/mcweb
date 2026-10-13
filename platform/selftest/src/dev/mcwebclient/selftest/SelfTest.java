package dev.mcwebclient.selftest;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * Automated end-to-end check, enabled with -Dmcweb.selftest=true. Driven by client
 * ticks: title screen -> screenshot -> create a fixed-seed creative world -> wait for
 * terrain -> screenshot + stats -> quit (which saves the world). Every step logs a
 * "[selftest]" line so a harness can follow along; any failure ends with
 * "[selftest] FAIL" and a non-zero exit.
 */
public final class SelfTest {
    private enum Stage { WAIT_TITLE, CREATING, IN_WORLD, DONE }

    private static final long SEED = 20261012L;
    private static final int TIMEOUT_TICKS = 20 * 240;

    private static Stage stage = Stage.WAIT_TITLE;
    private static int ticks;
    private static int stageTicks;
    private static int titleTicks;

    private SelfTest() {
    }

    public static void install() {
        log("installed");
        ClientTickEvents.END_CLIENT_TICK.register(SelfTest::tick);
    }

    private static void tick(Minecraft mc) {
        ticks++;
        stageTicks++;
        try {
            if (ticks == 1 && ShaderCorpus.enabled()) {
                // Recompile everything through the recorders (startup compiled before ticks began).
                ShaderCorpus.install();
                log("shader corpus recording; reloading resources");
                mc.reloadResourcePacks();
            }
            switch (stage) {
                case WAIT_TITLE -> waitTitle(mc);
                case CREATING -> creating(mc);
                case IN_WORLD -> inWorld(mc);
                case DONE -> {
                }
            }
            if (stage != Stage.DONE && stageTicks > TIMEOUT_TICKS) {
                fail("timeout in stage " + stage + " (screen=" + screenName(mc) + ")");
            }
        } catch (Throwable t) {
            log("exception: " + t);
            t.printStackTrace();
            fail("exception in stage " + stage);
        }
    }

    private static void waitTitle(Minecraft mc) {
        if (mc.gui.screen() instanceof AccessibilityOnboardingScreen && mc.gui.overlay() == null) {
            log("dismissing first-launch accessibility screen");
            mc.gui.setScreen(new TitleScreen(true));
            return;
        }
        if (mc.gui.screen() instanceof TitleScreen && mc.gui.overlay() == null) {
            if (++titleTicks == 40) {
                log("title screen up after " + ticks + " ticks, fps=" + mc.getFps());
                screenshot(mc, "selftest-title.png");
            } else if (titleTicks == 60) {
                String name = "selftest-" + SEED;
                log("creating world '" + name + "' seed=" + SEED);
                LevelSettings settings = new LevelSettings(name, GameType.CREATIVE,
                        new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false),
                        true, WorldDataConfiguration.DEFAULT);
                WorldOptions options = new WorldOptions(SEED, true, false);
                mc.createWorldOpenFlows().createFreshLevel(name, settings, options,
                        WorldPresets::createNormalWorldDimensions, new TitleScreen());
                next(Stage.CREATING);
            }
        }
    }

    private static void creating(Minecraft mc) {
        if (mc.player != null && mc.level != null && mc.gui.screen() == null) {
            log("joined world after " + stageTicks + " ticks at " + mc.player.blockPosition());
            next(Stage.IN_WORLD);
        }
    }

    private static int renderedTicks;

    private static void inWorld(Minecraft mc) {
        boolean allRendered = mc.levelRenderer.hasRenderedAllSections();
        if (allRendered) {
            renderedTicks++;
        }
        if (stageTicks % 100 == 0) {
            log("in world: tick " + stageTicks + " fps=" + mc.getFps() + " allSectionsRendered=" + allRendered
                    + " visibleSections=" + mc.levelRenderer.visibleSections().size());
        }
        if (renderedTicks == 60 || stageTicks == 20 * 90) {
            log("terrain ready=" + allRendered + " after " + stageTicks + " ticks, fps=" + mc.getFps()
                    + " visibleSections=" + mc.levelRenderer.visibleSections().size()
                    );
            screenshot(mc, "selftest-world.png");
        } else if (renderedTicks == 100 || stageTicks == 20 * 95) {
            if (ShaderCorpus.enabled()) {
                log("shader corpus: " + ShaderCorpus.summary());
            }
            log("PASS");
            next(Stage.DONE);
            mc.stop();
        }
    }

    private static void screenshot(Minecraft mc, String file) {
        Screenshot.grab(mc.gameDirectory, file, mc.gameRenderer.mainRenderTarget(), 1,
                msg -> log("screenshot " + file + ": " + msg.getString()));
    }

    private static void next(Stage s) {
        stage = s;
        stageTicks = 0;
    }

    private static String screenName(Minecraft mc) {
        return mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getName();
    }

    private static void fail(String why) {
        log("FAIL " + why);
        stage = Stage.DONE;
        System.out.flush();
        Runtime.getRuntime().halt(3);
    }

    private static void log(String msg) {
        System.out.println("[selftest] " + msg);
    }
}
