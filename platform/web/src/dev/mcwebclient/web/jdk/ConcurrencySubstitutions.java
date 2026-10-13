package dev.mcwebclient.web.jdk;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

import dev.mcwebclient.web.exec.WebScheduler;
import dev.mcwebclient.web.exec.WebThreads;

/**
 * Blocking and threads on a single-threaded runtime. Replaces Web Image's versions,
 * which throw (park) or silently drop the thread (start0); see build-image.mjs for
 * the matching -H:DisableSubstitution entries.
 */
final class ConcurrencySubstitutions {
    private ConcurrencySubstitutions() {
    }
}

@TargetClass(className = "jdk.internal.misc.Unsafe")
final class Target_jdk_internal_misc_Unsafe_Park {
    @Substitute
    public void park(boolean isAbsolute, long time) {
        WebScheduler.onPark(isAbsolute, time);
    }

    @Substitute
    public void unpark(Object thread) {
        // Nothing is ever really parked; waiters re-check their condition.
    }
}

@TargetClass(Thread.class)
final class Target_java_lang_Thread_Start {
    @Substitute
    private void start0() {
        WebThreads.start((Thread) (Object) this);
    }
}

@TargetClass(java.io.FileInputStream.class)
final class Target_java_io_FileInputStream_Length {
    /** 0 makes readAllBytes/readNBytes fall back to the generic InputStream loop. */
    @Substitute
    private long length0() {
        return 0;
    }

    @Substitute
    private long position0() {
        return 0;
    }
}

@TargetClass(className = "java.util.zip.ZipUtils")
final class Target_java_util_zip_ZipUtils {
    /** There is no native zip library; zlib is pure Java (see ZipSubstitutions). */
    @Substitute
    static void loadLibrary() {
    }
}
