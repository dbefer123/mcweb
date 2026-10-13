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
