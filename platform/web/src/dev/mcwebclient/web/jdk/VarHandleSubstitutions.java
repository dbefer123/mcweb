package dev.mcwebclient.web.jdk;

import java.lang.invoke.VarHandle;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * The WasmGC backend can't lower floating-point compare-and-swap ("unhandled compare
 * ... EQ"). These VarHandle access modes are compiled only because every access mode of
 * a reachable VarHandle kind becomes an entry point; nothing in the game is known to
 * CAS a float/double field. If one is ever used, this fails with a clear message
 * rather than miscompiling.
 */
final class VarHandleSubstitutions {
    private VarHandleSubstitutions() {
    }

    static UnsupportedOperationException unsupported(String what) {
        return new UnsupportedOperationException(what + " is not supported in the WebAssembly build");
    }
}

@TargetClass(className = "java.lang.invoke.VarHandleFloats$FieldInstanceReadWrite")
final class Target_VarHandleFloats_FieldInstanceReadWrite {
    @Substitute
    static boolean compareAndSet(VarHandle ob, Object holder, float expected, float value) {
        throw VarHandleSubstitutions.unsupported("float field compareAndSet");
    }

    @Substitute
    static float compareAndExchange(VarHandle ob, Object holder, float expected, float value) {
        throw VarHandleSubstitutions.unsupported("float field compareAndExchange");
    }
}

@TargetClass(className = "java.lang.invoke.VarHandleFloats$FieldStaticReadWrite")
final class Target_VarHandleFloats_FieldStaticReadWrite {
    @Substitute
    static boolean compareAndSet(VarHandle ob, float expected, float value) {
        throw VarHandleSubstitutions.unsupported("static float compareAndSet");
    }

    @Substitute
    static float compareAndExchange(VarHandle ob, float expected, float value) {
        throw VarHandleSubstitutions.unsupported("static float compareAndExchange");
    }
}

@TargetClass(className = "java.lang.invoke.VarHandleDoubles$FieldInstanceReadWrite")
final class Target_VarHandleDoubles_FieldInstanceReadWrite {
    @Substitute
    static boolean compareAndSet(VarHandle ob, Object holder, double expected, double value) {
        throw VarHandleSubstitutions.unsupported("double field compareAndSet");
    }

    @Substitute
    static double compareAndExchange(VarHandle ob, Object holder, double expected, double value) {
        throw VarHandleSubstitutions.unsupported("double field compareAndExchange");
    }
}

@TargetClass(className = "java.lang.invoke.VarHandleDoubles$FieldStaticReadWrite")
final class Target_VarHandleDoubles_FieldStaticReadWrite {
    @Substitute
    static boolean compareAndSet(VarHandle ob, double expected, double value) {
        throw VarHandleSubstitutions.unsupported("static double compareAndSet");
    }

    @Substitute
    static double compareAndExchange(VarHandle ob, double expected, double value) {
        throw VarHandleSubstitutions.unsupported("static double compareAndExchange");
    }
}
