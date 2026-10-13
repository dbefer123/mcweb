package dev.mcwebclient.web.mods;

import java.util.List;

import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

import dev.mcwebclient.web.mem.Nmem;
import net.caffeinemc.mods.sodium.api.vertex.serializer.VertexSerializer;
import net.caffeinemc.mods.sodium.client.render.vertex.serializers.VertexSerializerRegistryImpl;
import net.caffeinemc.mods.sodium.client.render.vertex.serializers.generated.VertexSerializerFactory;
import org.joml.FrustumIntersection;
import org.joml.Vector4f;

/** Sodium code the WasmGC image can't compile as written. */
final class SodiumSubstitutions {
    private SodiumSubstitutions() {
    }
}

/**
 * Sodium's raw memory access (vertex writing redirected from BufferBuilder, chunk
 * meshing) uses sun.misc.Unsafe at absolute addresses, which Web Image traps.
 * Native memory is nmem here.
 */
@Substitute
@TargetClass(className = "net.caffeinemc.mods.sodium.api.memory.MemoryIntrinsics")
final class Target_net_caffeinemc_mods_sodium_api_memory_MemoryIntrinsics {
    @Substitute
    public static void copyMemory(long src, long dst, int length) {
        Nmem.copy(src, dst, length);
    }

    @Substitute
    public static void putInt(long ptr, int value) {
        Nmem.putInt(ptr, value);
    }

    @Substitute
    public static void putFloat(long ptr, float value) {
        Nmem.putFloat(ptr, value);
    }

    @Substitute
    public static void putLong(long ptr, long value) {
        Nmem.putLong(ptr, value);
    }

    @Substitute
    public static void putShort(long ptr, short value) {
        Nmem.putShort(ptr, value);
    }

    @Substitute
    public static void putByte(long ptr, byte value) {
        Nmem.putByte(ptr, value);
    }

    @Substitute
    public static int getInt(long ptr) {
        return Nmem.getInt(ptr);
    }

    @Substitute
    public static float getFloat(long ptr) {
        return Nmem.getFloat(ptr);
    }

    @Substitute
    public static long getLong(long ptr) {
        return Nmem.getLong(ptr);
    }

    @Substitute
    public static short getShort(long ptr) {
        return Nmem.getShort(ptr);
    }

    @Substitute
    public static byte getByte(long ptr) {
        return Nmem.getByte(ptr);
    }

    /** Pointers are 64-bit in the LWJGL ABI the image emulates. */
    @Substitute
    public static void putAddress(long ptr, long address) {
        Nmem.putLong(ptr, address);
    }

    @Substitute
    public static long getAddress(long ptr) {
        return Nmem.getLong(ptr);
    }
}

@TargetClass(FrustumIntersection.class)
final class Target_org_joml_FrustumIntersection {
    @Alias Vector4f[] planes;
}

/**
 * Sodium reads JOML's private frustum planes through a MethodHandle (looked up
 * reflectively in a static initializer); Web Image miscompiles the
 * {@code invokeExact} result (missing cast → invalid Wasm). Same class, plain field
 * access.
 */
@Substitute
@TargetClass(className = "net.caffeinemc.mods.sodium.client.render.viewport.frustum.SimpleFrustum")
final class Target_net_caffeinemc_mods_sodium_client_render_viewport_frustum_SimpleFrustum {
    private final float nxX, nxY, nxZ, negNxW;
    private final float pxX, pxY, pxZ, negPxW;
    private final float nyX, nyY, nyZ, negNyW;
    private final float pyX, pyY, pyZ, negPyW;
    private final float nzX, nzY, nzZ, negNzW;
    private final float pzX, pzY, pzZ, negPzW;
    private final FrustumIntersection frustum;

    private static float negW(Vector4f p, float x, float y, float z) {
        return -(p.w + x * (x < 0.0F ? -9.125F : 9.125F) + y * (y < 0.0F ? -9.125F : 9.125F) + z * (z < 0.0F ? -9.125F : 9.125F));
    }

    @Substitute
    Target_net_caffeinemc_mods_sodium_client_render_viewport_frustum_SimpleFrustum(FrustumIntersection frustumIntersection) {
        this.frustum = frustumIntersection;
        Vector4f[] planes = ((Target_org_joml_FrustumIntersection) (Object) frustumIntersection).planes;
        nxX = planes[0].x;
        nxY = planes[0].y;
        nxZ = planes[0].z;
        pxX = planes[1].x;
        pxY = planes[1].y;
        pxZ = planes[1].z;
        nyX = planes[2].x;
        nyY = planes[2].y;
        nyZ = planes[2].z;
        pyX = planes[3].x;
        pyY = planes[3].y;
        pyZ = planes[3].z;
        nzX = planes[4].x;
        nzY = planes[4].y;
        nzZ = planes[4].z;
        pzX = planes[5].x;
        pzY = planes[5].y;
        pzZ = planes[5].z;
        negNxW = negW(planes[0], nxX, nxY, nxZ);
        negPxW = negW(planes[1], pxX, pxY, pxZ);
        negNyW = negW(planes[2], nyX, nyY, nyZ);
        negPyW = negW(planes[3], pyX, pyY, pyZ);
        negNzW = negW(planes[4], nzX, nzY, nzZ);
        negPzW = negW(planes[5], pzX, pzY, pzZ);
    }

    @Substitute
    public boolean testSection(float x, float y, float z) {
        return nxX * x + nxY * y + nxZ * z >= negNxW
                && pxX * x + pxY * y + pxZ * z >= negPxW
                && nyX * x + nyY * y + nyZ * z >= negNyW
                && pyX * x + pyY * y + pyZ * z >= negPyW
                && nzX * x + nzY * y + nzZ * z >= negNzW;
    }

    @Substitute
    public boolean testSectionExpanded(float ox, float oy, float oz, float extend) {
        float minX = ox - extend, maxX = ox + extend;
        float minY = oy - extend, maxY = oy + extend;
        float minZ = oz - extend, maxZ = oz + extend;
        return nxX * (nxX < 0.0F ? minX : maxX) + nxY * (nxY < 0.0F ? minY : maxY) + nxZ * (nxZ < 0.0F ? minZ : maxZ) >= negNxW
                && pxX * (pxX < 0.0F ? minX : maxX) + pxY * (pxY < 0.0F ? minY : maxY) + pxZ * (pxZ < 0.0F ? minZ : maxZ) >= negPxW
                && nyX * (nyX < 0.0F ? minX : maxX) + nyY * (nyY < 0.0F ? minY : maxY) + nyZ * (nyZ < 0.0F ? minZ : maxZ) >= negNyW
                && pyX * (pyX < 0.0F ? minX : maxX) + pyY * (pyY < 0.0F ? minY : maxY) + pyZ * (pyZ < 0.0F ? minZ : maxZ) >= negPyW
                && nzX * (nzX < 0.0F ? minX : maxX) + nzY * (nzY < 0.0F ? minY : maxY) + nzZ * (nzZ < 0.0F ? minZ : maxZ) >= negNzW
                && pzX * (pzX < 0.0F ? minX : maxX) + pzY * (pzY < 0.0F ? minY : maxY) + pzZ * (pzZ < 0.0F ? minZ : maxZ) >= negPzW;
    }

    @Substitute
    public boolean testAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        return frustum.testAab(minX, minY, minZ, maxX, maxY, maxZ);
    }

    @Substitute
    public int intersectAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        return frustum.intersectAab(minX, minY, minZ, maxX, maxY, maxZ);
    }
}

/**
 * Sodium generates a class per (source, destination) vertex format pair that copies the
 * destination's attributes out of each source vertex. Classes can't be defined at run time
 * in the image, so the same copy list drives an interpreted serializer instead.
 */
@TargetClass(VertexSerializerRegistryImpl.class)
final class Target_net_caffeinemc_mods_sodium_client_render_vertex_serializers_VertexSerializerRegistryImpl {
    @Substitute
    private static VertexSerializer createSerializer(VertexFormat srcFormat, VertexFormat dstFormat) {
        List<VertexSerializerFactory.MemoryTransfer> transfers =
                Target_net_caffeinemc_mods_sodium_client_render_vertex_serializers_generated_VertexSerializerFactory
                        .createMemoryTransferList(srcFormat, dstFormat);
        return new InterpretedVertexSerializer(transfers, srcFormat.getVertexSize(), dstFormat.getVertexSize());
    }
}

@TargetClass(VertexSerializerFactory.class)
final class Target_net_caffeinemc_mods_sodium_client_render_vertex_serializers_generated_VertexSerializerFactory {
    @Alias
    static native List<VertexSerializerFactory.MemoryTransfer> createMemoryTransferList(VertexFormat srcFormat, VertexFormat dstFormat);
}

/** The generated serializer's loop, with the copy list as data. */
final class InterpretedVertexSerializer implements VertexSerializer {
    private final int[] src;
    private final int[] dst;
    private final int[] length;
    private final int srcStride;
    private final int dstStride;
    /** One transfer covering whole vertices of the same size: a single bulk copy. */
    private final boolean identity;

    InterpretedVertexSerializer(List<VertexSerializerFactory.MemoryTransfer> transfers, int srcStride, int dstStride) {
        int n = transfers.size();
        this.src = new int[n];
        this.dst = new int[n];
        this.length = new int[n];
        for (int i = 0; i < n; i++) {
            VertexSerializerFactory.MemoryTransfer t = transfers.get(i);
            src[i] = t.src();
            dst[i] = t.dst();
            length[i] = t.length();
        }
        this.srcStride = srcStride;
        this.dstStride = dstStride;
        this.identity = n == 1 && src[0] == 0 && dst[0] == 0 && length[0] == srcStride && srcStride == dstStride;
    }

    @Override
    public void serialize(long srcPtr, long dstPtr, int vertexCount) {
        if (identity) {
            Nmem.copy(srcPtr, dstPtr, (long) vertexCount * srcStride);
            return;
        }
        for (int v = 0; v < vertexCount; v++) {
            for (int i = 0; i < src.length; i++) {
                Nmem.copy(srcPtr + src[i], dstPtr + dst[i], length[i]);
            }
            srcPtr += srcStride;
            dstPtr += dstStride;
        }
    }
}
