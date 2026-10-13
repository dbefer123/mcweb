package dev.mcwebclient.web.gpu.spirv;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static dev.mcwebclient.web.gpu.spirv.Spirv.*;

/**
 * A parsed SPIR-V module: names, decorations (with the word index of each literal, so
 * bindings and locations can be patched in place), types, constants, global variables
 * and function boundaries. Instruction bodies stay in {@link #words}.
 */
public final class SpirvModule {
    public enum Kind { VOID, BOOL, INT, FLOAT, VECTOR, MATRIX, IMAGE, SAMPLER, SAMPLED_IMAGE, ARRAY, RUNTIME_ARRAY,
        STRUCT, POINTER, FUNCTION, OTHER }

    /** A type declaration. Which fields are meaningful depends on {@link #kind}. */
    public static final class Type {
        public final int id;
        public final Kind kind;
        public int width;            // INT/FLOAT bits
        public boolean signed;       // INT
        public int component;        // VECTOR/MATRIX/ARRAY/RUNTIME_ARRAY element type; POINTER pointee; IMAGE sampled type; SAMPLED_IMAGE image type
        public int count;            // VECTOR size, MATRIX columns, ARRAY length (resolved constant)
        public int[] members = new int[0]; // STRUCT member types; FUNCTION: [return, params...]
        public int storage;          // POINTER storage class
        public int dim, depth, arrayed, ms, sampled, format; // IMAGE

        Type(int id, Kind kind) {
            this.id = id;
            this.kind = kind;
        }
    }

    /** A constant: scalar bits, composite constituents, or null/undef. */
    public static final class Constant {
        public final int id;
        public final int type;
        public final int opcode;
        public final int[] operands; // OpConstant: literal words; OpConstantComposite: constituent ids

        Constant(int id, int type, int opcode, int[] operands) {
            this.id = id;
            this.type = type;
            this.opcode = opcode;
            this.operands = operands;
        }
    }

    /** One OpDecorate/OpMemberDecorate; {@link #at} is the word index of the first literal (or -1). */
    public record Decoration(int kind, int[] literals, int at) {
        public int value() {
            return literals.length > 0 ? literals[0] : 0;
        }
    }

    /** A global or function-local OpVariable. */
    public record Variable(int id, int type, int storage, int initializer) {
    }

    /** Function boundaries: word index of OpFunction and of OpFunctionEnd. */
    public record Function(int id, int resultType, int functionType, int start, int end) {
    }

    public final int[] words;
    public final int bound;
    public final String[] names;
    public final Map<Integer, Map<Integer, String>> memberNames = new HashMap<>();
    public final Map<Integer, List<Decoration>> decorations = new HashMap<>();
    public final Map<Integer, Map<Integer, List<Decoration>>> memberDecorations = new HashMap<>();
    public final Type[] types;
    public final Constant[] constants;
    public final List<Variable> globals = new ArrayList<>();
    public final List<Function> functions = new ArrayList<>();
    public final Map<Integer, String> extInstSets = new HashMap<>();
    /** Result type of every id that has one (0 otherwise). */
    public final int[] resultTypes;
    public int executionModel = -1;
    public int entryPoint;
    public String entryPointName = "main";
    public int[] entryInterface = new int[0];
    public final List<int[]> executionModes = new ArrayList<>();

    public SpirvModule(int[] words) {
        if (words.length < 5 || words[0] != MAGIC) {
            throw new IllegalArgumentException("not a SPIR-V module");
        }
        this.words = words;
        this.bound = words[3];
        this.names = new String[bound];
        this.types = new Type[bound];
        this.constants = new Constant[bound];
        this.resultTypes = new int[bound];
        parse();
    }

    public static int opcode(int word) {
        return word & 0xFFFF;
    }

    public static int length(int word) {
        return word >>> 16;
    }

    /** Decodes a literal string starting at word index {@code at}. */
    public String string(int at) {
        return string(words, at);
    }

    public static String string(int[] words, int at) {
        byte[] bytes = new byte[(words.length - at) * 4];
        int n = 0;
        outer:
        for (int i = at; i < words.length; i++) {
            int w = words[i];
            for (int b = 0; b < 4; b++) {
                int c = (w >>> (8 * b)) & 0xFF;
                if (c == 0) {
                    break outer;
                }
                bytes[n++] = (byte) c;
            }
        }
        return new String(bytes, 0, n, StandardCharsets.UTF_8);
    }

    /** Number of words a literal string starting at {@code at} occupies. */
    public int stringWords(int at) {
        int i = at;
        while (true) {
            int w = words[i++];
            if ((w & 0xFF000000) == 0 || (w & 0xFF0000) == 0 || (w & 0xFF00) == 0 || (w & 0xFF) == 0) {
                return i - at;
            }
        }
    }

    private void parse() {
        int i = 5;
        int functionStart = -1;
        int functionId = 0, functionResult = 0, functionType = 0;
        while (i < words.length) {
            int op = opcode(words[i]);
            int len = length(words[i]);
            if (len == 0) {
                throw new IllegalArgumentException("zero-length instruction at word " + i);
            }
            switch (op) {
                case OpName -> names[words[i + 1]] = string(i + 2);
                case OpMemberName -> memberNames.computeIfAbsent(words[i + 1], k -> new HashMap<>())
                        .put(words[i + 2], string(i + 3));
                case OpExtInstImport -> extInstSets.put(words[i + 1], string(i + 2));
                case OpEntryPoint -> {
                    if (executionModel < 0) {
                        executionModel = words[i + 1];
                        entryPoint = words[i + 2];
                        entryPointName = string(i + 3);
                        int after = i + 3 + stringWords(i + 3);
                        entryInterface = java.util.Arrays.copyOfRange(words, after, i + len);
                    }
                }
                case OpExecutionMode -> executionModes.add(java.util.Arrays.copyOfRange(words, i + 1, i + len));
                case OpDecorate -> {
                    int target = words[i + 1];
                    int kind = words[i + 2];
                    int[] lits = java.util.Arrays.copyOfRange(words, i + 3, i + len);
                    decorations.computeIfAbsent(target, k -> new ArrayList<>())
                            .add(new Decoration(kind, lits, len > 3 ? i + 3 : -1));
                }
                case OpMemberDecorate -> {
                    int target = words[i + 1];
                    int member = words[i + 2];
                    int kind = words[i + 3];
                    int[] lits = java.util.Arrays.copyOfRange(words, i + 4, i + len);
                    memberDecorations.computeIfAbsent(target, k -> new HashMap<>())
                            .computeIfAbsent(member, k -> new ArrayList<>())
                            .add(new Decoration(kind, lits, len > 4 ? i + 4 : -1));
                }
                case OpTypeVoid -> types[words[i + 1]] = new Type(words[i + 1], Kind.VOID);
                case OpTypeBool -> types[words[i + 1]] = new Type(words[i + 1], Kind.BOOL);
                case OpTypeInt -> {
                    Type t = new Type(words[i + 1], Kind.INT);
                    t.width = words[i + 2];
                    t.signed = words[i + 3] != 0;
                    types[t.id] = t;
                }
                case OpTypeFloat -> {
                    Type t = new Type(words[i + 1], Kind.FLOAT);
                    t.width = words[i + 2];
                    types[t.id] = t;
                }
                case OpTypeVector, OpTypeMatrix -> {
                    Type t = new Type(words[i + 1], op == OpTypeVector ? Kind.VECTOR : Kind.MATRIX);
                    t.component = words[i + 2];
                    t.count = words[i + 3];
                    types[t.id] = t;
                }
                case OpTypeImage -> {
                    Type t = new Type(words[i + 1], Kind.IMAGE);
                    t.component = words[i + 2];
                    t.dim = words[i + 3];
                    t.depth = words[i + 4];
                    t.arrayed = words[i + 5];
                    t.ms = words[i + 6];
                    t.sampled = words[i + 7];
                    t.format = words[i + 8];
                    types[t.id] = t;
                }
                case OpTypeSampler -> types[words[i + 1]] = new Type(words[i + 1], Kind.SAMPLER);
                case OpTypeSampledImage -> {
                    Type t = new Type(words[i + 1], Kind.SAMPLED_IMAGE);
                    t.component = words[i + 2];
                    types[t.id] = t;
                }
                case OpTypeArray -> {
                    Type t = new Type(words[i + 1], Kind.ARRAY);
                    t.component = words[i + 2];
                    Constant len0 = constants[words[i + 3]];
                    t.count = len0 == null ? 0 : len0.operands[0];
                    types[t.id] = t;
                }
                case OpTypeRuntimeArray -> {
                    Type t = new Type(words[i + 1], Kind.RUNTIME_ARRAY);
                    t.component = words[i + 2];
                    types[t.id] = t;
                }
                case OpTypeStruct -> {
                    Type t = new Type(words[i + 1], Kind.STRUCT);
                    t.members = java.util.Arrays.copyOfRange(words, i + 2, i + len);
                    types[t.id] = t;
                }
                case OpTypePointer -> {
                    Type t = new Type(words[i + 1], Kind.POINTER);
                    t.storage = words[i + 2];
                    t.component = words[i + 3];
                    types[t.id] = t;
                }
                case OpTypeFunction -> {
                    Type t = new Type(words[i + 1], Kind.FUNCTION);
                    t.members = java.util.Arrays.copyOfRange(words, i + 2, i + len);
                    types[t.id] = t;
                }
                case OpTypeOpaque -> types[words[i + 1]] = new Type(words[i + 1], Kind.OTHER);
                case OpConstantTrue, OpConstantFalse, OpConstant, OpConstantComposite, OpConstantNull,
                        OpSpecConstantTrue, OpSpecConstantFalse, OpSpecConstant, OpSpecConstantComposite, OpUndef -> {
                    int type = words[i + 1];
                    int id = words[i + 2];
                    resultTypes[id] = type;
                    if (functionStart < 0 || op != OpUndef) {
                        constants[id] = new Constant(id, type, op, java.util.Arrays.copyOfRange(words, i + 3, i + len));
                    }
                }
                case OpVariable -> {
                    int type = words[i + 1];
                    int id = words[i + 2];
                    resultTypes[id] = type;
                    if (functionStart < 0) {
                        globals.add(new Variable(id, type, words[i + 3], len > 4 ? words[i + 4] : 0));
                    }
                }
                case OpFunction -> {
                    functionStart = i;
                    functionResult = words[i + 1];
                    functionId = words[i + 2];
                    functionType = words[i + 4];
                    resultTypes[functionId] = functionResult;
                }
                case OpFunctionEnd -> {
                    functions.add(new Function(functionId, functionResult, functionType, functionStart, i));
                    functionStart = -1;
                }
                default -> {
                    if (functionStart >= 0 && Ops.hasResultType(op)) {
                        resultTypes[words[i + 2]] = words[i + 1];
                    }
                }
            }
            i += len;
        }
    }

    // ---- queries ----

    public Type type(int id) {
        Type t = types[id];
        if (t == null) {
            throw new IllegalArgumentException("id " + id + " is not a type");
        }
        return t;
    }

    public List<Decoration> decorations(int id) {
        return decorations.getOrDefault(id, List.of());
    }

    public Decoration decoration(int id, int kind) {
        for (Decoration d : decorations(id)) {
            if (d.kind() == kind) {
                return d;
            }
        }
        return null;
    }

    public boolean hasDecoration(int id, int kind) {
        return decoration(id, kind) != null;
    }

    public List<Decoration> memberDecorations(int structId, int member) {
        Map<Integer, List<Decoration>> m = memberDecorations.get(structId);
        return m == null ? List.of() : m.getOrDefault(member, List.of());
    }

    public Decoration memberDecoration(int structId, int member, int kind) {
        for (Decoration d : memberDecorations(structId, member)) {
            if (d.kind() == kind) {
                return d;
            }
        }
        return null;
    }

    public String name(int id) {
        return id < names.length ? names[id] : null;
    }

    public String memberName(int structId, int member) {
        Map<Integer, String> m = memberNames.get(structId);
        return m == null ? null : m.get(member);
    }

    /** Pointee of a pointer type id. */
    public Type pointee(int pointerType) {
        return type(type(pointerType).component);
    }

    /** True if a struct type, or any struct nested in it, has BuiltIn member decorations. */
    public boolean isBuiltinBlock(int typeId) {
        Type t = types[typeId];
        while (t != null && (t.kind == Kind.ARRAY || t.kind == Kind.RUNTIME_ARRAY)) {
            t = types[t.component];
        }
        if (t == null || t.kind != Kind.STRUCT) {
            return false;
        }
        for (int m = 0; m < t.members.length; m++) {
            if (memberDecoration(t.id, m, DecorationBuiltIn) != null) {
                return true;
            }
        }
        return false;
    }

    /** Integer value of a scalar integer constant (zero for OpConstantNull). */
    public int intConstant(int id) {
        Constant c = constants[id];
        if (c == null) {
            throw new IllegalArgumentException("id " + id + " is not a constant");
        }
        return c.operands.length > 0 ? c.operands[0] : 0;
    }

    public boolean isConstant(int id) {
        return id < constants.length && constants[id] != null;
    }

    /** Byte size of a type under the explicit layout its decorations describe (std140/std430/push constants). */
    public int declaredSize(int typeId) {
        Type t = type(typeId);
        return switch (t.kind) {
            case BOOL, INT, FLOAT -> Math.max(4, t.width / 8);
            case VECTOR -> t.count * declaredSize(t.component);
            case MATRIX -> {
                // Without the member's MatrixStride here, assume the std140/std430 vec4 column stride for vec3/vec4 columns.
                Type col = type(t.component);
                int colSize = declaredSize(t.component);
                int stride = col.count == 2 ? colSize : 16;
                yield t.count * stride;
            }
            case ARRAY -> {
                Decoration stride = decoration(t.id, DecorationArrayStride);
                yield t.count * (stride != null ? stride.value() : declaredSize(t.component));
            }
            case STRUCT -> {
                int size = 0;
                for (int m = 0; m < t.members.length; m++) {
                    Decoration off = memberDecoration(t.id, m, DecorationOffset);
                    int memberSize = declaredSize(t.members[m]);
                    Type mt = type(t.members[m]);
                    if (mt.kind == Kind.MATRIX) {
                        Decoration ms = memberDecoration(t.id, m, DecorationMatrixStride);
                        if (ms != null) {
                            memberSize = mt.count * ms.value();
                        }
                    }
                    size = Math.max(size, (off != null ? off.value() : size) + memberSize);
                }
                yield size;
            }
            default -> 0;
        };
    }
}
