package dev.mcwebclient.web.net;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * Netty on heap buffers. Direct buffers bring nothing in the browser (there is no native
 * I/O to hand them to), and Netty's Java 25 cleaner allocates them through FFM arenas.
 * Its cleaner choice is made at image build time (CleanerJava25 is build-time
 * initialized), so it claims direct buffers can be freed reliably and ioBuffer() would
 * still pick direct memory: both checks are answered here. With the unpooled allocator
 * (io.netty.allocator.type, set in WebRuntime) every buffer is then a heap buffer.
 */
final class NettySubstitutions {
    private NettySubstitutions() {
    }
}

@TargetClass(className = "io.netty.util.internal.PlatformDependent")
final class Target_io_netty_util_internal_PlatformDependent {
    @Substitute
    public static boolean directBufferPreferred() {
        return false;
    }

    @Substitute
    public static boolean canReliabilyFreeDirectBuffers() {
        return false;
    }
}

/**
 * The event loops' clock. Netty's is System.nanoTime() minus a START_TIME captured when
 * SystemTicker initializes, which happens at image build time (netty-codec-http's
 * native-image.properties initializes all of io.netty at build time). The browser's
 * nanoTime counts from page load, so in a page younger than the build machine's reading
 * the clock was negative: deadlines overflowed, every scheduled task (the 30 s read
 * timeout) looked due at once, and the event loop spun without yielding until the
 * deadline passed, freezing the page and stalling the first server ping. The page clock
 * already starts near zero, so it is the ticker's time directly.
 */
@TargetClass(className = "io.netty.util.concurrent.SystemTicker")
final class Target_io_netty_util_concurrent_SystemTicker {
    @Substitute
    public long initialNanoTime() {
        return 0;
    }

    @Substitute
    public long nanoTime() {
        return System.nanoTime();
    }
}
