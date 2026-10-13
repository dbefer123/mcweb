package dev.mcwebclient.web.mem;

import java.util.HashMap;
import java.util.Map;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

import org.lwjgl.system.Callback;
import org.lwjgl.system.CallbackI;

/**
 * LWJGL callbacks (SDL log hooks, shaderc include resolvers, ...) without libffi.
 *
 * <p>Each callback interface builds a libffi call descriptor in its static initializer
 * and every {@link Callback} gets a native closure from {@code Upcalls}. In the browser
 * nothing native ever calls back into Java: a callback is just a Java object, and the
 * emulated libraries (sdl/, shader compiler) call it directly. So libffi's type and ABI
 * getters return placeholders, CIF preparation succeeds trivially, and "function
 * pointers" are opaque handles into a registry.</p>
 */
public final class CallbackSubstitutions {
    private CallbackSubstitutions() {
    }

    /** Handles live in their own range so they can't collide with nmem addresses. */
    private static long nextHandle = 0x7F00_0000_0000L;
    static final Map<Long, Object> CALLBACKS = new HashMap<>();
    /** Zeroed block standing in for every libffi type descriptor (only its address is passed around). */
    private static long typeBlock;

    static synchronized long register(Object instance) {
        long handle = nextHandle;
        nextHandle += 16;
        CALLBACKS.put(handle, instance);
        return handle;
    }

    /** The Java object behind a callback handle, or null. */
    public static synchronized Object lookup(long handle) {
        return CALLBACKS.get(handle);
    }

    static synchronized long typeBlock() {
        if (typeBlock == 0) {
            typeBlock = NativeHeap.calloc(1, 64);
        }
        return typeBlock;
    }
}

/** Replaces the closure machinery (and its native static initializer) entirely. */
@Substitute
@TargetClass(className = "org.lwjgl.system.Upcalls")
final class Target_org_lwjgl_system_Upcalls {
    @Substitute
    static long upcallCreate(Callback.Descriptor descriptor, Object instance) {
        return CallbackSubstitutions.register(instance);
    }

    @Substitute
    @SuppressWarnings("unchecked")
    static <T extends CallbackI> T upcallGet(long functionPointer) {
        Object instance = CallbackSubstitutions.lookup(functionPointer);
        if (instance == null) {
            throw new IllegalArgumentException("unknown callback handle 0x" + Long.toHexString(functionPointer));
        }
        return (T) instance;
    }

    @Substitute
    static void upcallFree(long functionPointer) {
        synchronized (CallbackSubstitutions.class) {
            CallbackSubstitutions.CALLBACKS.remove(functionPointer);
        }
    }
}

/**
 * Callback interfaces build their libffi call descriptor (FFICIF) eagerly; FFICIF's own
 * layout comes from native code. Descriptors are never used here, so there are none.
 */
@TargetClass(org.lwjgl.system.APIUtil.class)
final class Target_org_lwjgl_system_APIUtil {
    @Substitute
    public static org.lwjgl.system.libffi.FFICIF apiCreateCIF(int abi, org.lwjgl.system.libffi.FFIType rtype,
            org.lwjgl.system.libffi.FFIType... atypes) {
        return null;
    }

    @Substitute
    public static org.lwjgl.system.libffi.FFICIF apiCreateCIFVar(int abi, int nfixedargs, org.lwjgl.system.libffi.FFIType rtype,
            org.lwjgl.system.libffi.FFIType... atypes) {
        return null;
    }
}

@TargetClass(org.lwjgl.system.libffi.LibFFI.class)
final class Target_org_lwjgl_system_libffi_LibFFI {
    // Type and ABI constants read by LibFFI's static initializer (values as on x86-64 Linux).
    @Substitute private static short FFI_TYPE_DOUBLE() { return 3; }
    @Substitute private static int FFI_WIN64() { return 1; }
    @Substitute private static int FFI_GNUW64() { return 2; }
    @Substitute private static int FFI_UNIX64() { return 2; }
    @Substitute private static int FFI_EFI64() { return 1; }
    @Substitute private static int FFI_SYSV() { return -1; }
    @Substitute private static int FFI_STDCALL() { return -1; }
    @Substitute private static int FFI_THISCALL() { return -1; }
    @Substitute private static int FFI_FASTCALL() { return -1; }
    @Substitute private static int FFI_MS_CDECL() { return -1; }
    @Substitute private static int FFI_PASCAL() { return -1; }
    @Substitute private static int FFI_REGISTER() { return -1; }
    @Substitute private static int FFI_VFP() { return -1; }
    @Substitute private static int FFI_FIRST_ABI() { return 1; }
    @Substitute private static int FFI_LAST_ABI() { return 4; }
    @Substitute private static int FFI_DEFAULT_ABI() { return 2; }

    @Substitute private static long nffi_type_void() { return CallbackSubstitutions.typeBlock(); }
    @Substitute private static long nffi_type_uint8() { return CallbackSubstitutions.typeBlock(); }
    @Substitute private static long nffi_type_sint8() { return CallbackSubstitutions.typeBlock(); }
    @Substitute private static long nffi_type_uint16() { return CallbackSubstitutions.typeBlock(); }
    @Substitute private static long nffi_type_sint16() { return CallbackSubstitutions.typeBlock(); }
    @Substitute private static long nffi_type_uint32() { return CallbackSubstitutions.typeBlock(); }
    @Substitute private static long nffi_type_sint32() { return CallbackSubstitutions.typeBlock(); }
    @Substitute private static long nffi_type_uint64() { return CallbackSubstitutions.typeBlock(); }
    @Substitute private static long nffi_type_sint64() { return CallbackSubstitutions.typeBlock(); }
    @Substitute private static long nffi_type_uchar() { return CallbackSubstitutions.typeBlock(); }
    @Substitute private static long nffi_type_schar() { return CallbackSubstitutions.typeBlock(); }
    @Substitute private static long nffi_type_ushort() { return CallbackSubstitutions.typeBlock(); }
    @Substitute private static long nffi_type_sshort() { return CallbackSubstitutions.typeBlock(); }
    @Substitute private static long nffi_type_uint() { return CallbackSubstitutions.typeBlock(); }
    @Substitute private static long nffi_type_sint() { return CallbackSubstitutions.typeBlock(); }
    @Substitute private static long nffi_type_ulong() { return CallbackSubstitutions.typeBlock(); }
    @Substitute private static long nffi_type_slong() { return CallbackSubstitutions.typeBlock(); }
    @Substitute private static long nffi_type_float() { return CallbackSubstitutions.typeBlock(); }
    @Substitute private static long nffi_type_double() { return CallbackSubstitutions.typeBlock(); }
    @Substitute private static long nffi_type_longdouble() { return CallbackSubstitutions.typeBlock(); }
    @Substitute private static long nffi_type_pointer() { return CallbackSubstitutions.typeBlock(); }

    /** Call descriptors are never used for calls; preparing one always succeeds. */
    @Substitute
    public static int nffi_prep_cif(long cif, int abi, int nargs, long rtype, long atypes) {
        return 0;
    }

    @Substitute
    public static int nffi_prep_cif_var(long cif, int abi, int nfixedargs, int ntotalargs, long rtype, long atypes) {
        return 0;
    }

    @Substitute
    public static long ffi_get_closure_size() {
        return 64;
    }
}
