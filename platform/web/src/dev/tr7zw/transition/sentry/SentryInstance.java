package dev.tr7zw.transition.sentry;

/**
 * Browser-build replacement for EntityCulling's (tr7zw "transition") crash reporter.
 * The original uploads crash reports to Sentry and depends on log4j-core, which the
 * image doesn't include; this class shadows it on the image class path (platform
 * classes come first) so nothing is ever sent. Same public surface as the original.
 */
public class SentryInstance {
    public SentryInstance() {
    }

    public static void initialize(SentryDataProvider provider) {
    }

    public static boolean maybeReport(String a, String b, String c, Throwable t) {
        return false;
    }

    public static void forceFlush() {
    }
}
