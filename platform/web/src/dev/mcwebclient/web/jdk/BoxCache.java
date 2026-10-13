package dev.mcwebclient.web.jdk;

import com.oracle.svm.core.NeverInline;
import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * JLS-conformant boxing for the WasmGC image.
 *
 * <p>The WasmGC backend lowers every boxing operation (Integer.valueOf etc. become
 * BoxNodes) with an "AllocatingBox" template that always allocates, skipping the JDK's
 * caches. Java guarantees Integer/Short/Long.valueOf(-128..127), Byte.valueOf(all) and
 * Character.valueOf(0..127) return the same object each time, and Minecraft relies on it
 * (block-state property values are compared with ==; FerriteCore re-boxes them).
 * tools/watpatch routes the generated box functions here. The cached objects are the
 * JDK's own cache arrays, so values boxed at build time (constants in the image heap)
 * and at run time are identical.</p>
 */
public final class BoxCache {
    private BoxCache() {
    }

    @NeverInline("watpatch target")
    public static Integer boxInt(int v) {
        if (v >= -128 && v <= 127) {
            return Target_java_lang_Integer_IntegerCache.cache[v + 128];
        }
        return newInteger(v);
    }

    @NeverInline("watpatch target")
    public static Short boxShort(short v) {
        if (v >= -128 && v <= 127) {
            return Target_java_lang_Short_ShortCache.cache[v + 128];
        }
        return newShort(v);
    }

    @NeverInline("watpatch target")
    public static Long boxLong(long v) {
        if (v >= -128 && v <= 127) {
            return Target_java_lang_Long_LongCache.cache[(int) v + 128];
        }
        return newLong(v);
    }

    @NeverInline("watpatch target")
    public static Byte boxByte(byte v) {
        return Target_java_lang_Byte_ByteCache.cache[v + 128];
    }

    @NeverInline("watpatch target")
    public static Character boxChar(char v) {
        if (v <= 127) {
            return Target_java_lang_Character_CharacterCache.cache[v];
        }
        return newChar(v);
    }

    // Explicit constructor calls are allocations, not BoxNodes, so they don't recurse.
    @SuppressWarnings({"deprecation", "removal"})
    private static Integer newInteger(int v) {
        return new Integer(v);
    }

    @SuppressWarnings({"deprecation", "removal"})
    private static Short newShort(short v) {
        return new Short(v);
    }

    @SuppressWarnings({"deprecation", "removal"})
    private static Long newLong(long v) {
        return new Long(v);
    }

    @SuppressWarnings({"deprecation", "removal"})
    private static Character newChar(char v) {
        return new Character(v);
    }

    /**
     * Keeps the helpers reachable (so they are compiled and the patch can target them)
     * with arguments the analysis can't predict: if every call passed a constant, the
     * analysis would fold the whole helper to "return that one cached object", and the
     * patched box functions would return it for every value.
     */
    public static boolean selfCheck() {
        int x = (int) (System.nanoTime() & 63); // opaque small value
        boolean identity = boxInt(x) == boxInt(x) && boxShort((short) x) == boxShort((short) x)
                && boxLong(x) == boxLong(x) && boxByte((byte) x) == boxByte((byte) x)
                && boxChar((char) ('a' + x % 20)) == boxChar((char) ('a' + x % 20));
        boolean values = boxInt(x + 1000).intValue() == x + 1000 && boxInt(x).intValue() == x
                && boxLong(x - 500L).longValue() == x - 500L && boxShort((short) (x * 3)).shortValue() == (short) (x * 3)
                && boxChar((char) (x + 200)).charValue() == (char) (x + 200) && boxByte((byte) (x - 100)).byteValue() == (byte) (x - 100);
        // After the WAT patch, ordinary boxing goes through the helpers too.
        Integer a = x;
        Integer b = x;
        return identity && values && a == b;
    }
}

@TargetClass(className = "java.lang.Integer$IntegerCache")
final class Target_java_lang_Integer_IntegerCache {
    @Alias static Integer[] cache;
}

@TargetClass(className = "java.lang.Short$ShortCache")
final class Target_java_lang_Short_ShortCache {
    @Alias static Short[] cache;
}

@TargetClass(className = "java.lang.Long$LongCache")
final class Target_java_lang_Long_LongCache {
    @Alias static Long[] cache;
}

@TargetClass(className = "java.lang.Byte$ByteCache")
final class Target_java_lang_Byte_ByteCache {
    @Alias static Byte[] cache;
}

@TargetClass(className = "java.lang.Character$CharacterCache")
final class Target_java_lang_Character_CharacterCache {
    @Alias static Character[] cache;
}
