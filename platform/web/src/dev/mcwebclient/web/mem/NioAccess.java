package dev.mcwebclient.web.mem;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * Direct NIO buffers over nmem. A DirectByteBuffer is created at an nmem address with
 * the JDK's own (address, capacity) constructor; its reads and writes reach nmem
 * through the ScopedMemoryAccess substitutions (null base = native memory).
 */
public final class NioAccess {
    private NioAccess() {
    }

    /** Little-endian (native order) direct buffer over [address, address + capacity). */
    public static ByteBuffer wrap(long address, long capacity) {
        ByteBuffer b = (ByteBuffer) (Object) new Target_java_nio_DirectByteBuffer(address, capacity);
        return b.order(ByteOrder.LITTLE_ENDIAN);
    }

    /** Base address of a direct buffer (ignores position). */
    public static long address(Buffer buffer) {
        return ((Target_java_nio_Buffer) (Object) buffer).address;
    }
}

@TargetClass(Buffer.class)
final class Target_java_nio_Buffer {
    @Alias long address;
}

@TargetClass(className = "java.nio.DirectByteBuffer")
final class Target_java_nio_DirectByteBuffer {
    @Alias
    Target_java_nio_DirectByteBuffer(long addr, long cap) {
    }
}
