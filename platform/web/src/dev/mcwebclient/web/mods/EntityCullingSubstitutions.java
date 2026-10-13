package dev.mcwebclient.web.mods;

import com.logisticscraft.occlusionculling.OcclusionCullingInstance;
import com.logisticscraft.occlusionculling.util.Vec3d;
import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

import dev.tr7zw.entityculling.EntityCullingModBase;
import dev.tr7zw.entityculling.versionless.EntityCullingVersionlessBase;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * EntityCulling traces visibility on its own "CullThread", which loops with
 * {@code Thread.sleep(sleepDelay)} for the life of the game. On the single browser
 * thread that loop would never yield, so {@link CullTask#run()} does one pass and
 * {@link dev.mcwebclient.web.exec.WebThreads} runs it again every sleepDelay ms.
 */
public final class EntityCullingSubstitutions {
    private EntityCullingSubstitutions() {
    }

    /** Milliseconds between culling passes, as configured for the original thread. */
    public static int sleepDelayMillis() {
        EntityCullingModBase mod = EntityCullingModBase.instance;
        return mod == null ? 10 : Math.max(1, mod.config.sleepDelay);
    }
}

@TargetClass(EntityCullingModBase.class)
final class Target_dev_tr7zw_entityculling_EntityCullingModBase {
    @Alias
    native void extractEntityData(Minecraft client);
}

@TargetClass(dev.tr7zw.entityculling.CullTask.class)
final class Target_dev_tr7zw_entityculling_CullTask {
    @Alias public boolean requestCull;
    @Alias public double lastTime;
    @Alias private OcclusionCullingInstance culling;
    @Alias private Vec3d lastPos;
    @Alias private boolean ingame;
    @Alias private Vec3 cameraMC;

    @Alias
    private native void cullEntities(Vec3 cameraMC, Vec3d camera);

    @Alias
    private native void cullBlockEntities(Vec3 cameraMC, Vec3d camera);

    /** One iteration of the original loop body (without its sleep). */
    @Substitute
    public void run() {
        if (!Minecraft.getInstance().isRunning()) {
            return;
        }
        try {
            if (EntityCullingVersionlessBase.enabled && ingame) {
                if (requestCull || cameraMC.x != lastPos.x || cameraMC.y != lastPos.y || cameraMC.z != lastPos.z) {
                    long start = System.nanoTime();
                    requestCull = false;
                    lastPos.set(cameraMC.x, cameraMC.y, cameraMC.z);
                    Vec3d camera = lastPos;
                    culling.resetCache();
                    if (!EntityCullingModBase.instance.config.safeMode) {
                        Target_dev_tr7zw_entityculling_EntityCullingModBase mod =
                                (Target_dev_tr7zw_entityculling_EntityCullingModBase) (Object) EntityCullingModBase.instance;
                        mod.extractEntityData(Minecraft.getInstance());
                    }
                    cullBlockEntities(cameraMC, camera);
                    cullEntities(cameraMC, camera);
                    lastTime = (System.nanoTime() - start) / 1000000.0;
                }
            } else {
                lastTime = 0.0;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
