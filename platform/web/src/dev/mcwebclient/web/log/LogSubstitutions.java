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

    /**
     * The original names the logger after StackWalker.getCallerClass(), which Web Image
     * doesn't support. Use the caller's class name from the JS stack instead (Web Image
     * function names look like "_ClassName_method___sig").
     */
    @Substitute
    public static org.slf4j.Logger getLogger() {
        return org.slf4j.LoggerFactory.getLogger(CallerNames.callerClassName("LogUtils_getLogger"));
    }
}

/** Finds the calling class from the JS stack (frame names come from -H:+DebugNames). */
final class CallerNames {
    private CallerNames() {
    }

    @org.graalvm.webimage.api.JS.Coerce
    @org.graalvm.webimage.api.JS(value = "return String(new Error().stack);", args = {})
    private static native String jsStack();

    static String callerClassName(String selfMarker) {
        String stack = jsStack();
        int self = stack.indexOf(selfMarker);
        if (self >= 0) {
            int lineEnd = stack.indexOf('\n', self);
            if (lineEnd >= 0) {
                int at = stack.indexOf("at ", lineEnd);
                if (at >= 0) {
                    int start = at + 3;
                    if (start < stack.length() && stack.charAt(start) == '_') {
                        start++;
                    }
                    int end = stack.indexOf('_', start);
                    if (end > start) {
                        return stack.substring(start, end);
                    }
                }
            }
        }
        return "Minecraft";
    }
}
