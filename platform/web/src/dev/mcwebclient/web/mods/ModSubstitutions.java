package dev.mcwebclient.web.mods;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * Mod-specific adjustments for the browser build.
 *
 * <p>EntityCulling's crash reporter (Sentry) is replaced by a no-op class that shadows it
 * on the image class path: platform/web/src/dev/tr7zw/transition/sentry/SentryInstance.java.</p>
 */
final class ModSubstitutions {
    private ModSubstitutions() {
    }
}

/** log4j's no-argument getLogger() looks up the caller class, which Web Image can't. */
@TargetClass(className = "org.apache.logging.log4j.LogManager")
final class Target_org_apache_logging_log4j_LogManager {
    @Substitute
    public static org.apache.logging.log4j.Logger getLogger() {
        return org.apache.logging.log4j.LogManager.getLogger("mod");
    }
}
