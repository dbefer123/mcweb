package dev.mcwebclient.web.log;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/** com.mojang.logging.LogUtils reaches into log4j-core, which the image doesn't include. */
@TargetClass(className = "com.mojang.logging.LogUtils")
final class Target_com_mojang_logging_LogUtils {
    @Substitute
    public static boolean isLoggerActive() {
        return true;
    }

    @Substitute
    public static void configureRootLoggingLevel(org.slf4j.event.Level level) {
        WebLogger.setThreshold(level);
    }
}
