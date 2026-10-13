package dev.mcwebclient.web.gpu.spirv;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.mcwebclient.web.gpu.spirv.SpirvModule.Constant;
import dev.mcwebclient.web.gpu.spirv.SpirvModule.Decoration;
import dev.mcwebclient.web.gpu.spirv.SpirvModule.Function;
import dev.mcwebclient.web.gpu.spirv.SpirvModule.Kind;
import dev.mcwebclient.web.gpu.spirv.SpirvModule.Type;
import dev.mcwebclient.web.gpu.spirv.SpirvModule.Variable;

import static dev.mcwebclient.web.gpu.spirv.Spirv.*;
import static dev.mcwebclient.web.gpu.spirv.SpirvModule.length;
import static dev.mcwebclient.web.gpu.spirv.SpirvModule.opcode;

/**
 * Translates one shader stage from SPIR-V (as glslang produces it for Minecraft's GLSL)
 * into WGSL for WebGPU.
 *
 * <p>Resource mapping, for descriptor set 0 binding {@code b} (renderpearl puts every
 * uniform there): uniform buffer at {@code @binding(2b)}; a combined image sampler is
 * split into a texture at {@code 2b} and a sampler at {@code 2b+1}; a texel buffer
 * becomes a read-only storage array at {@code 2b}. Push constants are a read-only
 * storage buffer at {@code @group(1) @binding(0)}.</p>
 *
 * <p>Stage inputs and outputs become private globals that a generated entry point fills
 * and returns, as in SPIR-V. The vertex output's Y is negated so that framebuffer
 * contents are laid out as on Vulkan (renderpearl's other explicit-API backend);
 * the surface flips them back when presenting.</p>
 */
public final class WgslTranslator {
    /** Translation settings for one stage. */
    public static final class Options {
        /** Negate gl_Position.y (WebGPU NDC is Y-up, Vulkan's is Y-down). */
        public boolean flipY = true;
        /** Binding number (set 0) -> GpuFormat name, for texel buffer uniforms. */
        public final Map<Integer, String> texelBufferFormats = new HashMap<>();
        public int pushConstantGroup = 1;
        public int pushConstantBinding = 0;
    }

    /** Raised for SPIR-V this translator doesn't handle. */
    public static final class TranslationException extends RuntimeException {
        public TranslationException(String message) {
            super(message);
        }
    }

    private WgslTranslator() {
    }

    public static String translate(int[] words, Options options) {
        return new Translator(new SpirvModule(words), options).run();
    }

    public static String translate(SpirvModule module, Options options) {
        return new Translator(module, options).run();
    }

    // ------------------------------------------------------------------------------------

    private enum VK { EXPR, PTR, TEX, SAMP, COMBINED, TEXEL_BUFFER, FUNC_PARAM_PTR }

    /** What an SSA id stands for while translating. */
    private static final class Val {
        VK k;
        String expr;      // EXPR: value; PTR: lvalue; TEX: texture; SAMP: sampler; COMBINED: texture; TEXEL_BUFFER: load function name
        String samp;      // COMBINED: sampler expression
        int type;         // EXPR: value type; PTR: pointee type; TEX/COMBINED: image type
        int storage = -1; // PTR: storage class
        boolean addressable; // PTR: lvalue is a whole variable or (*param), so &lvalue is a valid pointer argument

        static Val expr(String e, int type) {
            Val v = new Val();
            v.k = VK.EXPR;
            v.expr = e;
            v.type = type;
            return v;
        }

        static Val ptr(String lvalue, int pointee, int storage, boolean addressable) {
            Val v = new Val();
            v.k = VK.PTR;
            v.expr = lvalue;
            v.type = pointee;
            v.storage = storage;
            v.addressable = addressable;
            return v;
        }
    }

    private static final class Block {
        final int label;
        final int start;   // first instruction after OpLabel
        int end;           // word index of the terminator
        int mergeAt = -1;  // OpSelectionMerge / OpLoopMerge word index
        final List<Integer> phis = new ArrayList<>();
        final List<Integer> body = new ArrayList<>();

        Block(int label, int start) {
            this.label = label;
            this.start = start;
        }
    }

    /** Structured-emission context: where branches lead. */
    private record Ctx(int stop, int loopMerge, int loopContinue, int switchMerge, boolean inSwitch) {
    }

    private static final class Translator {
        private final SpirvModule m;
        private final int[] w;
        private final Options opt;
        private final StringBuilder globals = new StringBuilder();
        private final Map<Integer, String> structNames = new LinkedHashMap<>();
        private final StringBuilder structs = new StringBuilder();
        private final Set<String> helpers = new LinkedHashSet<>();
        private final Val[] vals;
        private final boolean vertex;
        private String glslExt = "";

        // stage IO
        private final List<int[]> ioInputs = new ArrayList<>();  // {varId}
        private final List<int[]> ioOutputs = new ArrayList<>();

        // per function
        private StringBuilder out;
        private int indent;
        private Map<Integer, Block> blocks;
        private Map<Integer, Integer> defBlock;
        private Set<Integer> hoisted;
        private int currentBlock;
        private int currentReturnType;
        private final Map<Integer, String> functionNames = new HashMap<>();

        Translator(SpirvModule module, Options options) {
            this.m = module;
            this.w = module.words;
            this.opt = options;
            this.vals = new Val[module.bound];
            this.vertex = module.executionModel == ExecutionModelVertex;
            if (module.executionModel != ExecutionModelVertex && module.executionModel != ExecutionModelFragment) {
                throw new TranslationException("unsupported execution model " + module.executionModel);
            }
            for (Map.Entry<Integer, String> e : module.extInstSets.entrySet()) {
                if (e.getValue().equals("GLSL.std.450")) {
                    glslExt = String.valueOf(e.getKey());
                }
            }
        }

        String run() {
            for (Function f : m.functions) {
                functionNames.put(f.id(), f.id() == m.entryPoint ? "main_1" : "f_" + f.id() + suffix(m.name(f.id())));
            }
            declareGlobals();
            StringBuilder functions = new StringBuilder();
            for (Function f : m.functions) {
                functions.append(function(f)).append('\n');
            }
            String entry = entryPoint();
            StringBuilder sb = new StringBuilder();
            sb.append("// Translated from SPIR-V by mcweb WgslTranslator\n");
            sb.append("diagnostic(off, derivative_uniformity);\n\n");
            sb.append(structs);
            sb.append(globals).append('\n');
            for (String h : helpers) {
                sb.append(h).append('\n');
            }
            sb.append(functions);
            sb.append(entry);
            return sb.toString();
        }

        private static String suffix(String name) {
            if (name == null || name.isEmpty()) {
                return "";
            }
            String base = name.contains("(") ? name.substring(0, name.indexOf('(')) : name;
            StringBuilder sb = new StringBuilder("_");
            for (char c : base.toCharArray()) {
                if (Character.isLetterOrDigit(c) && c < 128) {
                    sb.append(c);
                } else if (sb.charAt(sb.length() - 1) != '_') {
                    sb.append('_');
                }
                if (sb.length() > 40) {
                    break;
                }
            }
            String s = sb.toString();
            while (s.endsWith("_") && s.length() > 1) {
                s = s.substring(0, s.length() - 1);
            }
            return s.equals("_") ? "" : s.replace("__", "_");
        }

        // ---------------------------------------------------------------- types

        private String type(int id) {
            Type t = m.type(id);
            return switch (t.kind) {
                case VOID -> "void";
                case BOOL -> "bool";
                case INT -> {
                    if (t.width != 32) {
                        throw new TranslationException(t.width + "-bit integers are not supported");
                    }
                    yield t.signed ? "i32" : "u32";
                }
                case FLOAT -> {
                    if (t.width != 32) {
                        throw new TranslationException(t.width + "-bit floats are not supported");
                    }
                    yield "f32";
                }
                case VECTOR -> "vec" + t.count + "<" + type(t.component) + ">";
                case MATRIX -> {
                    Type col = m.type(t.component);
                    yield "mat" + t.count + "x" + col.count + "<" + type(col.component) + ">";
                }
                case ARRAY -> {
                    Decoration stride = m.decoration(id, DecorationArrayStride);
                    int natural = roundUp(naturalSize(t.component), naturalAlign(t.component));
                    if (stride != null && stride.value() != natural) {
                        throw new TranslationException("array stride " + stride.value() + " of " + type(t.component)
                                + " differs from WGSL's " + natural);
                    }
                    yield "array<" + type(t.component) + ", " + t.count + ">";
                }
                case RUNTIME_ARRAY -> "array<" + type(t.component) + ">";
                case STRUCT -> structName(id);
                case IMAGE -> textureType(t);
                case SAMPLER -> "sampler";
                case POINTER -> "ptr<" + addressSpace(t.storage) + ", " + type(t.component) + ">";
                default -> throw new TranslationException("unsupported type kind " + t.kind);
            };
        }

        private String addressSpace(int storage) {
            return switch (storage) {
                case Function -> "function";
                case Private, Input, Output -> "private";
                case Uniform -> "uniform";
                case StorageBuffer, PushConstant -> "storage";
                case Workgroup -> "workgroup";
                default -> throw new TranslationException("no WGSL address space for storage class " + storage);
            };
        }

        private String textureType(Type img) {
            Type sampled = m.type(img.component);
            String st = sampled.kind == Kind.FLOAT ? "f32" : sampled.signed ? "i32" : "u32";
            boolean arrayed = img.arrayed != 0;
            if (img.depth == 1) {
                return switch (img.dim) {
                    case DimCube -> arrayed ? "texture_depth_cube_array" : "texture_depth_cube";
                    default -> arrayed ? "texture_depth_2d_array" : "texture_depth_2d";
                };
            }
            if (img.ms != 0) {
                return "texture_multisampled_2d<" + st + ">";
            }
            return switch (img.dim) {
                case Dim1D -> "texture_1d<" + st + ">";
                case Dim2D, DimRect -> (arrayed ? "texture_2d_array<" : "texture_2d<") + st + ">";
                case Dim3D -> "texture_3d<" + st + ">";
                case DimCube -> (arrayed ? "texture_cube_array<" : "texture_cube<") + st + ">";
                default -> throw new TranslationException("unsupported image dimension " + img.dim);
            };
        }

        private boolean isInt(int typeId) {
            Type t = scalarOf(typeId);
            return t.kind == Kind.INT;
        }

        private boolean isSigned(int typeId) {
            Type t = scalarOf(typeId);
            return t.kind == Kind.INT && t.signed;
        }

        private Type scalarOf(int typeId) {
            Type t = m.type(typeId);
            while (t.kind == Kind.VECTOR || t.kind == Kind.MATRIX) {
                t = m.type(t.component);
            }
            return t;
        }

        private int vectorSize(int typeId) {
            Type t = m.type(typeId);
            return t.kind == Kind.VECTOR ? t.count : 1;
        }

        /** WGSL type with the same shape as {@code typeId} but the given scalar ("i32", "u32", "f32", "bool"). */
        private String withScalar(int typeId, String scalar) {
            int n = vectorSize(typeId);
            return n == 1 ? scalar : "vec" + n + "<" + scalar + ">";
        }

        private String structName(int id) {
            String name = structNames.get(id);
            if (name != null) {
                return name;
            }
            name = "S_" + id + suffix(m.name(id));
            structNames.put(id, name);
            Type t = m.type(id);
            boolean explicit = t.members.length > 0 && m.memberDecoration(id, 0, DecorationOffset) != null;
            StringBuilder sb = new StringBuilder("struct ").append(name).append(" {\n");
            for (int i = 0; i < t.members.length; i++) {
                sb.append("    ");
                if (explicit && i + 1 < t.members.length) {
                    int off = m.memberDecoration(id, i, DecorationOffset).value();
                    Decoration next = m.memberDecoration(id, i + 1, DecorationOffset);
                    int size = next.value() - off;
                    if (size != naturalSize(t.members[i])) {
                        sb.append("@size(").append(size).append(") ");
                    }
                }
                sb.append("m").append(i).append(": ").append(memberType(id, i)).append(",\n");
            }
            sb.append("}\n\n");
            structs.append(sb);
            return name;
        }

        private String memberType(int structId, int member) {
            return type(m.type(structId).members[member]);
        }

        /** WGSL size of a host-shareable type (natural layout). */
        private int naturalSize(int typeId) {
            Type t = m.type(typeId);
            return switch (t.kind) {
                case BOOL, INT, FLOAT -> 4;
                case VECTOR -> t.count == 3 ? 12 : 4 * t.count;
                case MATRIX -> {
                    Type col = m.type(t.component);
                    int colAlign = col.count == 2 ? 8 : 16;
                    yield t.count * colAlign;
                }
                case ARRAY -> {
                    Decoration stride = m.decoration(typeId, DecorationArrayStride);
                    int elem = stride != null ? stride.value() : roundUp(naturalSize(t.component), naturalAlign(t.component));
                    yield t.count * elem;
                }
                case STRUCT -> {
                    int size = 0;
                    int align = 1;
                    for (int i = 0; i < t.members.length; i++) {
                        int a = naturalAlign(t.members[i]);
                        align = Math.max(align, a);
                        Decoration off = m.memberDecoration(typeId, i, DecorationOffset);
                        int o = off != null ? off.value() : roundUp(size, a);
                        size = o + naturalSize(t.members[i]);
                    }
                    yield roundUp(size, align);
                }
                default -> 0;
            };
        }

        private int naturalAlign(int typeId) {
            Type t = m.type(typeId);
            return switch (t.kind) {
                case BOOL, INT, FLOAT -> 4;
                case VECTOR -> t.count == 2 ? 8 : 16;
                case MATRIX -> m.type(t.component).count == 2 ? 8 : 16;
                case ARRAY -> naturalAlign(t.component);
                case STRUCT -> {
                    int a = 1;
                    for (int mt : t.members) {
                        a = Math.max(a, naturalAlign(mt));
                    }
                    yield a;
                }
                default -> 4;
            };
        }

        private static int roundUp(int v, int a) {
            return (v + a - 1) / a * a;
        }

        // ---------------------------------------------------------------- constants

        private String constant(int id) {
            Constant c = m.constants[id];
            int t = c.type;
            return switch (c.opcode) {
                case OpConstantTrue, OpSpecConstantTrue -> "true";
                case OpConstantFalse, OpSpecConstantFalse -> "false";
                case OpConstant, OpSpecConstant -> scalarLiteral(t, c.operands[0]);
                case OpConstantComposite, OpSpecConstantComposite -> {
                    StringBuilder sb = new StringBuilder(type(t)).append('(');
                    for (int i = 0; i < c.operands.length; i++) {
                        sb.append(i == 0 ? "" : ", ").append(value(c.operands[i]));
                    }
                    yield sb.append(')').toString();
                }
                case OpConstantNull, OpUndef -> type(t) + "()";
                default -> throw new TranslationException("unsupported constant opcode " + c.opcode);
            };
        }

        private String scalarLiteral(int typeId, int bits) {
            Type t = m.type(typeId);
            if (t.kind == Kind.FLOAT) {
                float f = Float.intBitsToFloat(bits);
                if (Float.isNaN(f) || Float.isInfinite(f)) {
                    return "bitcast<f32>(" + Integer.toUnsignedString(bits) + "u)";
                }
                String s = Float.toString(f).replace('E', 'e');
                return f < 0 || (f == 0 && (bits & 0x80000000) != 0) ? "(" + s + "f)" : s + "f";
            }
            if (t.kind == Kind.INT) {
                if (!t.signed) {
                    return Integer.toUnsignedString(bits) + "u";
                }
                if (bits == Integer.MIN_VALUE) {
                    return "i32(-2147483647 - 1)";
                }
                return bits < 0 ? "(" + bits + "i)" : bits + "i";
            }
            if (t.kind == Kind.BOOL) {
                return bits != 0 ? "true" : "false";
            }
            throw new TranslationException("unsupported scalar constant type " + t.kind);
        }

        // ---------------------------------------------------------------- globals

        private int binding(int varId) {
            Decoration d = m.decoration(varId, DecorationBinding);
            return d == null ? 0 : w[d.at()];
        }

        private int set(int varId) {
            Decoration d = m.decoration(varId, DecorationDescriptorSet);
            return d == null ? 0 : w[d.at()];
        }

        private void declareGlobals() {
            for (Variable v : m.globals) {
                Type pointee = m.pointee(v.type());
                int id = v.id();
                switch (v.storage()) {
                    case Input, Output -> {
                        String name = "io_" + id + suffix(m.name(id));
                        globals.append("var<private> ").append(name).append(": ").append(type(pointee.id)).append(";\n");
                        vals[id] = Val.ptr(name, pointee.id, Private, true);
                        (v.storage() == Input ? ioInputs : ioOutputs).add(new int[] {id});
                    }
                    case Private -> {
                        String name = "g_" + id + suffix(m.name(id));
                        globals.append("var<private> ").append(name).append(": ").append(type(pointee.id));
                        if (v.initializer() != 0) {
                            globals.append(" = ").append(value(v.initializer()));
                        }
                        globals.append(";\n");
                        vals[id] = Val.ptr(name, pointee.id, Private, true);
                    }
                    case Uniform, StorageBuffer -> {
                        boolean storage = v.storage() == StorageBuffer || m.hasDecoration(pointee.id, DecorationBufferBlock);
                        String name = "u_" + id + suffix(m.name(pointee.id));
                        globals.append("@group(").append(set(id)).append(") @binding(").append(2 * binding(id)).append(") var<")
                                .append(storage ? "storage, read" : "uniform").append("> ").append(name).append(": ")
                                .append(type(pointee.id)).append(";\n");
                        vals[id] = Val.ptr(name, pointee.id, storage ? StorageBuffer : Uniform, true);
                    }
                    case PushConstant -> {
                        String name = "pc_" + id;
                        globals.append("@group(").append(opt.pushConstantGroup).append(") @binding(").append(opt.pushConstantBinding)
                                .append(") var<storage, read> ").append(name).append(": ").append(type(pointee.id)).append(";\n");
                        vals[id] = Val.ptr(name, pointee.id, StorageBuffer, true);
                    }
                    case UniformConstant -> declareHandle(v, pointee);
                    default -> throw new TranslationException("unsupported global storage class " + v.storage());
                }
            }
        }

        private void declareHandle(Variable v, Type pointee) {
            int id = v.id();
            String base = suffix(m.name(id));
            int group = set(id);
            int b = binding(id);
            switch (pointee.kind) {
                case SAMPLED_IMAGE -> {
                    Type img = m.type(pointee.component);
                    if (img.dim == DimBuffer) {
                        declareTexelBuffer(id, img, group, b, base);
                        return;
                    }
                    String tex = "t_" + id + base;
                    String samp = "s_" + id + base;
                    globals.append("@group(").append(group).append(") @binding(").append(2 * b).append(") var ").append(tex)
                            .append(": ").append(textureType(img)).append(";\n");
                    globals.append("@group(").append(group).append(") @binding(").append(2 * b + 1).append(") var ").append(samp)
                            .append(": ").append(img.depth == 1 ? "sampler_comparison" : "sampler").append(";\n");
                    Val val = new Val();
                    val.k = VK.COMBINED;
                    val.expr = tex;
                    val.samp = samp;
                    val.type = img.id;
                    vals[id] = val;
                }
                case IMAGE -> {
                    if (pointee.dim == DimBuffer) {
                        declareTexelBuffer(id, pointee, group, b, base);
                        return;
                    }
                    String tex = "t_" + id + base;
                    globals.append("@group(").append(group).append(") @binding(").append(2 * b).append(") var ").append(tex)
                            .append(": ").append(textureType(pointee)).append(";\n");
                    Val val = new Val();
                    val.k = VK.TEX;
                    val.expr = tex;
                    val.type = pointee.id;
                    vals[id] = val;
                }
                case SAMPLER -> {
                    String samp = "s_" + id + base;
                    globals.append("@group(").append(group).append(") @binding(").append(2 * b + 1).append(") var ").append(samp)
                            .append(": sampler;\n");
                    Val val = new Val();
                    val.k = VK.SAMP;
                    val.expr = samp;
                    vals[id] = val;
                }
                default -> throw new TranslationException("unsupported UniformConstant variable of kind " + pointee.kind);
            }
        }

        /** A texel buffer: a storage array plus a load function returning the 4-component texel. */
        private void declareTexelBuffer(int id, Type img, int group, int b, String base) {
            String format = opt.texelBufferFormats.get(b);
            if (format == null) {
                throw new TranslationException("no format given for texel buffer at binding " + b);
            }
            Type sampled = m.type(img.component);
            String st = sampled.kind == Kind.FLOAT ? "f32" : sampled.signed ? "i32" : "u32";
            String buf = "tb_" + id + base;
            String fn = "tb_load_" + id;
            TexelFormat tf = TexelFormat.of(format);
            globals.append("@group(").append(group).append(") @binding(").append(2 * b).append(") var<storage, read> ").append(buf)
                    .append(": array<").append(tf.storageType).append(">;\n");
            String body = tf.load(buf, st);
            helpers.add("fn " + fn + "(i: i32) -> vec4<" + st + "> {\n" + body + "}\n");
            helpers.add("fn " + fn + "_size() -> i32 {\n    return i32(arrayLength(&" + buf + ")) * " + tf.texelsPerElement + ";\n}\n");
            Val val = new Val();
            val.k = VK.TEXEL_BUFFER;
            val.expr = fn;
            val.type = img.id;
            vals[id] = val;
        }

        // ---------------------------------------------------------------- values

        private String value(int id) {
            if (m.isConstant(id) && (vals[id] == null)) {
                return constant(id);
            }
            Val v = vals[id];
            if (v == null) {
                throw new TranslationException("value %" + id + " used before definition");
            }
            if (v.k != VK.EXPR) {
                throw new TranslationException("%" + id + " is not a plain value (" + v.k + ")");
            }
            return v.expr;
        }

        private Val val(int id) {
            Val v = vals[id];
            if (v == null) {
                if (m.isConstant(id)) {
                    return Val.expr(constant(id), m.constants[id].type);
                }
                throw new TranslationException("%" + id + " used before definition");
            }
            return v;
        }

        private int typeOf(int id) {
            if (m.isConstant(id)) {
                return m.constants[id].type;
            }
            Val v = vals[id];
            if (v != null && v.k == VK.EXPR) {
                return v.type;
            }
            return m.resultTypes[id];
        }

        /** {@code id}'s value converted (bit-preserving) to integer signedness {@code signed}. */
        private String asInt(int id, boolean signed) {
            int t = typeOf(id);
            String e = value(id);
            if (isInt(t) && isSigned(t) != signed) {
                return "bitcast<" + withScalar(t, signed ? "i32" : "u32") + ">(" + e + ")";
            }
            return e;
        }

        /** Converts an expression of integer type {@code from} to the WGSL type of {@code to}, if they differ. */
        private String castTo(String e, int from, int to) {
            String a = type(from);
            String b = type(to);
            if (a.equals(b)) {
                return e;
            }
            return "bitcast<" + b + ">(" + e + ")";
        }

        private String valueAs(int id, int targetType) {
            return castTo(value(id), typeOf(id), targetType);
        }

        // ---------------------------------------------------------------- functions

        private String function(Function f) {
            out = new StringBuilder();
            indent = 1;
            blocks = new LinkedHashMap<>();
            defBlock = new HashMap<>();
            hoisted = new HashSet<>();
            currentReturnType = f.resultType();
            Type ft = m.type(f.functionType());

            List<String> params = new ArrayList<>();
            List<Integer> paramIds = new ArrayList<>();
            List<String> locals = new ArrayList<>();
            int i = f.start() + length(w[f.start()]);
            int paramIndex = 0;
            Block cur = null;
            while (i < f.end()) {
                int op = opcode(w[i]);
                int len = length(w[i]);
                if (op == OpFunctionParameter) {
                    int pt = w[i + 1];
                    int pid = w[i + 2];
                    paramIds.add(pid);
                    params.add(param(pid, pt));
                    paramIndex++;
                } else if (op == OpLabel) {
                    cur = new Block(w[i + 1], i + len);
                    blocks.put(cur.label, cur);
                } else if (op == OpVariable) {
                    int vt = w[i + 1];
                    int vid = w[i + 2];
                    Type pointee = m.pointee(vt);
                    String name = "l_" + vid + suffix(m.name(vid));
                    StringBuilder decl = new StringBuilder("var ").append(name).append(": ").append(type(pointee.id));
                    if (len > 4) {
                        decl.append(" = ").append(value(w[i + 4]));
                    }
                    locals.add(decl.append(';').toString());
                    vals[vid] = Val.ptr(name, pointee.id, Function, true);
                    defBlock.put(vid, cur == null ? -1 : cur.label);
                } else if (cur != null) {
                    if (op == OpPhi) {
                        cur.phis.add(i);
                    } else if (op == OpSelectionMerge || op == OpLoopMerge) {
                        cur.mergeAt = i;
                    } else if (isTerminator(op)) {
                        cur.end = i;
                    } else if (op != OpLine && op != OpNoLine) {
                        cur.body.add(i);
                    }
                    if (Ops.hasResult(op) && Ops.hasResultType(op)) {
                        defBlock.put(w[i + 2], cur.label);
                    }
                }
                i += len;
            }
            analyzeUses();

            // Phi variables and hoisted values live at function scope.
            for (Block b : blocks.values()) {
                for (int at : b.phis) {
                    int t = w[at + 1];
                    int id = w[at + 2];
                    locals.add("var phi_" + id + ": " + type(t) + ";");
                    vals[id] = Val.expr("phi_" + id, t);
                }
            }
            for (int id : hoisted) {
                int t = m.resultTypes[id];
                if (t != 0 && storable(t)) {
                    locals.add("var h_" + id + ": " + type(t) + ";");
                }
            }

            StringBuilder body = out;
            for (String l : locals) {
                line(l);
            }
            Block entry = blocks.values().iterator().next();
            emitSequence(entry.label, -1, new Ctx(-1, -1, -1, -1, false));

            StringBuilder sb = new StringBuilder("fn ").append(functionNames.get(f.id())).append('(').append(String.join(", ", params)).append(')');
            if (m.type(f.resultType()).kind != Kind.VOID) {
                sb.append(" -> ").append(type(f.resultType()));
            }
            sb.append(" {\n").append(body).append("}\n");
            return sb.toString();
        }

        private boolean storable(int typeId) {
            Kind k = m.type(typeId).kind;
            return k != Kind.POINTER && k != Kind.IMAGE && k != Kind.SAMPLER && k != Kind.SAMPLED_IMAGE && k != Kind.VOID;
        }

        private String param(int id, int typeId) {
            Type t = m.type(typeId);
            String base = "p_" + id + suffix(m.name(id));
            if (t.kind == Kind.POINTER) {
                Type pointee = m.type(t.component);
                if (t.storage == UniformConstant) {
                    return handleParam(id, pointee, base);
                }
                String as = t.storage == Function ? "function" : addressSpace(t.storage);
                vals[id] = Val.ptr("(*" + base + ")", pointee.id, t.storage, true);
                vals[id].k = VK.FUNC_PARAM_PTR;
                return base + ": ptr<" + as + ", " + type(pointee.id) + ">";
            }
            if (t.kind == Kind.SAMPLED_IMAGE || t.kind == Kind.IMAGE || t.kind == Kind.SAMPLER) {
                return handleParam(id, t, base);
            }
            vals[id] = Val.expr(base, typeId);
            return base + ": " + type(typeId);
        }

        private String handleParam(int id, Type t, String base) {
            Val v = new Val();
            switch (t.kind) {
                case SAMPLED_IMAGE -> {
                    Type img = m.type(t.component);
                    v.k = VK.COMBINED;
                    v.expr = base + "_t";
                    v.samp = base + "_s";
                    v.type = img.id;
                    vals[id] = v;
                    return v.expr + ": " + textureType(img) + ", " + v.samp + ": " + (img.depth == 1 ? "sampler_comparison" : "sampler");
                }
                case IMAGE -> {
                    v.k = VK.TEX;
                    v.expr = base + "_t";
                    v.type = t.id;
                    vals[id] = v;
                    return v.expr + ": " + textureType(t);
                }
                case SAMPLER -> {
                    v.k = VK.SAMP;
                    v.expr = base + "_s";
                    vals[id] = v;
                    return v.expr + ": sampler";
                }
                default -> throw new TranslationException("unsupported handle parameter " + t.kind);
            }
        }

        private static boolean isTerminator(int op) {
            return op == OpBranch || op == OpBranchConditional || op == OpSwitch || op == OpReturn || op == OpReturnValue
                    || op == OpKill || op == OpUnreachable || op == OpTerminateInvocation;
        }

        /** Values used outside their defining block get a function-scope variable. */
        private void analyzeUses() {
            for (Block b : blocks.values()) {
                List<Integer> all = new ArrayList<>(b.body);
                if (b.mergeAt >= 0) {
                    all.add(b.mergeAt);
                }
                all.add(b.end);
                for (int at : all) {
                    int op = opcode(w[at]);
                    int len = length(w[at]);
                    int first = Ops.hasResult(op) ? (Ops.hasResultType(op) ? at + 3 : at + 2) : at + 1;
                    for (int k = first; k < at + len; k++) {
                        noteUse(w[k], b.label);
                    }
                }
                for (int at : b.phis) {
                    int len = length(w[at]);
                    for (int k = at + 3; k + 1 < at + len; k += 2) {
                        noteUse(w[k], w[k + 1]); // used at the end of the predecessor block
                    }
                }
            }
        }

        private void noteUse(int id, int block) {
            if (id <= 0 || id >= m.bound) {
                return;
            }
            Integer def = defBlock.get(id);
            if (def != null && def != block && !m.isConstant(id)) {
                int t = m.resultTypes[id];
                if (t != 0 && m.types[t] != null && storable(t)) {
                    hoisted.add(id);
                }
            }
        }

        // ---------------------------------------------------------------- structured control flow

        private void line(String s) {
            for (int i = 0; i < indent; i++) {
                out.append("    ");
            }
            out.append(s).append('\n');
        }

        private void emitSequence(int label, int stop, Ctx ctx) {
            while (label != stop) {
                // A construct's merge may itself be an exit of an enclosing loop or switch.
                if (label == ctx.loopMerge() || (ctx.inSwitch() && label == ctx.switchMerge())) {
                    line("break;");
                    return;
                }
                if (label == ctx.loopContinue()) {
                    line("continue;");
                    return;
                }
                Block b = blocks.get(label);
                if (b == null) {
                    throw new TranslationException("branch to unknown block %" + label);
                }
                currentBlock = label;
                if (b.mergeAt >= 0 && opcode(w[b.mergeAt]) == OpLoopMerge) {
                    int merge = w[b.mergeAt + 1];
                    int cont = w[b.mergeAt + 2];
                    line("loop {");
                    indent++;
                    Ctx inner = new Ctx(cont, merge, cont, ctx.switchMerge(), false);
                    emitBody(b);
                    int first = emitTerminator(b, inner);
                    if (first >= 0) {
                        emitSequence(first, cont, inner);
                    }
                    if (cont != label) {
                        line("continuing {");
                        indent++;
                        emitContinuing(cont, label, merge, inner);
                        indent--;
                        line("}");
                    }
                    indent--;
                    line("}");
                    label = merge;
                    continue;
                }
                emitBody(b);
                int next = emitTerminator(b, ctx);
                if (next < 0) {
                    return;
                }
                label = next;
            }
        }

        /** Emits a block's terminator. Returns the block to continue the sequence with, or -1. */
        private int emitTerminator(Block b, Ctx ctx) {
            int at = b.end;
            int op = opcode(w[at]);
            switch (op) {
                case OpBranch -> {
                    int target = w[at + 1];
                    return branch(b.label, target, ctx);
                }
                case OpBranchConditional -> {
                    String cond = value(w[at + 1]);
                    int t = w[at + 2];
                    int f = w[at + 3];
                    if (b.mergeAt >= 0 && opcode(w[b.mergeAt]) == OpSelectionMerge) {
                        int merge = w[b.mergeAt + 1];
                        Ctx arm = new Ctx(merge, ctx.loopMerge(), ctx.loopContinue(), ctx.switchMerge(), ctx.inSwitch());
                        line("if (" + cond + ") {");
                        indent++;
                        emitArm(b.label, t, merge, arm);
                        indent--;
                        if (f != merge || !phiAssignments(b.label, merge).isEmpty()) {
                            line("} else {");
                            indent++;
                            emitArm(b.label, f, merge, arm);
                            indent--;
                        }
                        line("}");
                        return merge == ctx.stop() ? -1 : merge;
                    }
                    if (t == f) {
                        return branch(b.label, t, ctx);
                    }
                    boolean ts = special(t, ctx);
                    boolean fs = special(f, ctx);
                    if (ts && !fs) {
                        line("if (" + cond + ") {");
                        indent++;
                        exitBranch(b.label, t, ctx);
                        indent--;
                        line("}");
                        return branch(b.label, f, ctx);
                    }
                    if (fs && !ts) {
                        line("if (!(" + cond + ")) {");
                        indent++;
                        exitBranch(b.label, f, ctx);
                        indent--;
                        line("}");
                        return branch(b.label, t, ctx);
                    }
                    if (ts) {
                        line("if (" + cond + ") {");
                        indent++;
                        exitBranch(b.label, t, ctx);
                        indent--;
                        line("} else {");
                        indent++;
                        exitBranch(b.label, f, ctx);
                        indent--;
                        line("}");
                        return -1;
                    }
                    throw new TranslationException("unstructured conditional branch in block %" + b.label);
                }
                case OpSwitch -> {
                    if (b.mergeAt < 0) {
                        throw new TranslationException("OpSwitch without merge in %" + b.label);
                    }
                    int merge = w[b.mergeAt + 1];
                    int selector = w[at + 1];
                    int def = w[at + 2];
                    int len = length(w[at]);
                    Map<Integer, List<String>> cases = new LinkedHashMap<>();
                    String suffix = isSigned(typeOf(selector)) ? "i" : "u";
                    for (int k = at + 3; k + 1 < at + len; k += 2) {
                        cases.computeIfAbsent(w[k + 1], x -> new ArrayList<>()).add(
                                (isSigned(typeOf(selector)) ? String.valueOf(w[k]) : Integer.toUnsignedString(w[k])) + suffix);
                    }
                    Ctx arm = new Ctx(merge, ctx.loopMerge(), ctx.loopContinue(), merge, true);
                    line("switch (" + value(selector) + ") {");
                    indent++;
                    for (Map.Entry<Integer, List<String>> e : cases.entrySet()) {
                        boolean isDefault = e.getKey() == def;
                        line("case " + String.join(", ", e.getValue()) + (isDefault ? ", default" : "") + ": {");
                        indent++;
                        emitArm(b.label, e.getKey(), merge, arm);
                        indent--;
                        line("}");
                    }
                    if (!cases.containsKey(def)) {
                        line("default: {");
                        indent++;
                        emitArm(b.label, def, merge, arm);
                        indent--;
                        line("}");
                    }
                    indent--;
                    line("}");
                    return merge == ctx.stop() ? -1 : merge;
                }
                case OpReturn -> {
                    line("return;");
                    return -1;
                }
                case OpReturnValue -> {
                    line("return " + valueAs(w[at + 1], currentReturnType) + ";");
                    return -1;
                }
                case OpKill, OpTerminateInvocation -> {
                    line("discard;");
                    if (m.type(currentReturnType).kind != Kind.VOID) {
                        line("return " + type(currentReturnType) + "();");
                    }
                    return -1;
                }
                case OpUnreachable -> {
                    if (m.type(currentReturnType).kind != Kind.VOID) {
                        line("return " + type(currentReturnType) + "();");
                    }
                    return -1;
                }
                default -> throw new TranslationException("unexpected terminator " + op);
            }
        }

        private boolean special(int target, Ctx ctx) {
            return target == ctx.loopMerge() || target == ctx.loopContinue() || target == ctx.stop()
                    || (ctx.inSwitch() && target == ctx.switchMerge());
        }

        /**
         * Branch from {@code from} to {@code target}: phi copies, then break/continue/fall
         * out if the target ends a construct. Returns the target to continue with, or -1.
         */
        private int branch(int from, int target, Ctx ctx) {
            for (String a : phiAssignments(from, target)) {
                line(a);
            }
            if (target == ctx.stop()) {
                return -1;
            }
            if (ctx.inSwitch() && target == ctx.switchMerge()) {
                line("break;");
                return -1;
            }
            if (target == ctx.loopMerge()) {
                if (ctx.inSwitch()) {
                    throw new TranslationException("loop break from inside a switch");
                }
                line("break;");
                return -1;
            }
            if (target == ctx.loopContinue()) {
                line("continue;");
                return -1;
            }
            return target;
        }

        /**
         * A conditional exit (no selection merge): unlike falling through, leaving the
         * construct here needs an explicit statement.
         */
        private void exitBranch(int from, int target, Ctx ctx) {
            if (target == ctx.stop()) {
                for (String a : phiAssignments(from, target)) {
                    line(a);
                }
                if (target == ctx.loopContinue()) {
                    line("continue;");
                } else if (ctx.inSwitch() && target == ctx.switchMerge()) {
                    line("break;");
                } else {
                    throw new TranslationException("conditional exit to %" + target + " without a selection merge");
                }
                return;
            }
            if (branch(from, target, ctx) >= 0) {
                throw new TranslationException("conditional branch to %" + target + " is not a construct exit");
            }
        }

        private void emitArm(int from, int target, int merge, Ctx arm) {
            int next = branch(from, target, arm);
            if (next >= 0) {
                emitSequence(next, merge, arm);
            }
        }

        private void emitContinuing(int cont, int header, int merge, Ctx loop) {
            int label = cont;
            while (true) {
                Block b = blocks.get(label);
                currentBlock = label;
                emitBody(b);
                int op = opcode(w[b.end]);
                if (op == OpBranch) {
                    int target = w[b.end + 1];
                    for (String a : phiAssignments(label, target)) {
                        line(a);
                    }
                    if (target == header) {
                        return;
                    }
                    label = target;
                    continue;
                }
                if (op == OpBranchConditional) {
                    String cond = value(w[b.end + 1]);
                    int t = w[b.end + 2];
                    int f = w[b.end + 3];
                    // Phi copies for the loop header must happen before "break if".
                    for (String a : phiAssignments(label, header)) {
                        line(a);
                    }
                    for (String a : phiAssignments(label, merge)) {
                        line(a);
                    }
                    if (t == header && f == merge) {
                        line("break if !(" + cond + ");");
                    } else if (t == merge && f == header) {
                        line("break if " + cond + ";");
                    } else {
                        throw new TranslationException("unsupported continue construct in %" + label);
                    }
                    return;
                }
                throw new TranslationException("unsupported terminator in continue construct %" + label);
            }
        }

        private List<String> phiAssignments(int from, int target) {
            Block tb = blocks.get(target);
            if (tb == null || tb.phis.isEmpty()) {
                return List.of();
            }
            List<String> out0 = new ArrayList<>();
            for (int at : tb.phis) {
                int t = w[at + 1];
                int id = w[at + 2];
                int len = length(w[at]);
                for (int k = at + 3; k + 1 < at + len; k += 2) {
                    if (w[k + 1] == from) {
                        out0.add("phi_" + id + " = " + valueAs(w[k], t) + ";");
                    }
                }
            }
            return out0;
        }

        // ---------------------------------------------------------------- instructions

        private void emitBody(Block b) {
            for (int at : b.body) {
                instruction(at);
            }
        }

        private void define(int id, int type, String expr) {
            if (hoisted.contains(id) && storable(type)) {
                line("h_" + id + " = " + expr + ";");
                vals[id] = Val.expr("h_" + id, type);
            } else {
                line("let v_" + id + " = " + expr + ";");
                vals[id] = Val.expr("v_" + id, type);
            }
        }

        private void instruction(int at) {
            int op = opcode(w[at]);
            int len = length(w[at]);
            int rt = len > 1 ? w[at + 1] : 0;
            int id = len > 2 ? w[at + 2] : 0;
            switch (op) {
                case OpNop, OpLine, OpNoLine, OpSource, OpModuleProcessed -> {
                }
                case OpUndef -> define(id, rt, type(rt) + "()");
                case OpLoad -> load(id, rt, w[at + 3]);
                case OpStore -> store(w[at + 1], w[at + 2]);
                case OpCopyMemory -> line(lvalue(w[at + 1]) + " = " + lvalue(w[at + 2]) + ";");
                case OpAccessChain, OpInBoundsAccessChain -> accessChain(id, rt, w[at + 3], at + 4, at + len);
                case OpFunctionCall -> call(id, rt, w[at + 3], at + 4, at + len);
                case OpExtInst -> extInst(id, rt, w[at + 3], w[at + 4], at + 5, at + len);
                case OpCopyObject, OpCopyLogical -> {
                    Val src = val(w[at + 3]);
                    if (src.k == VK.EXPR) {
                        define(id, rt, src.expr);
                    } else {
                        vals[id] = src;
                    }
                }
                case OpCompositeConstruct -> {
                    StringBuilder sb = new StringBuilder(type(rt)).append('(');
                    Type t = m.type(rt);
                    for (int k = at + 3; k < at + len; k++) {
                        int c = w[k];
                        String e = value(c);
                        if (t.kind == Kind.STRUCT) {
                            e = valueAs(c, t.members[k - at - 3]);
                        } else if (t.kind == Kind.ARRAY) {
                            e = valueAs(c, t.component);
                        }
                        sb.append(k == at + 3 ? "" : ", ").append(e);
                    }
                    define(id, rt, sb.append(')').toString());
                }
                case OpCompositeExtract -> {
                    int base = w[at + 3];
                    int t = typeOf(base);
                    StringBuilder sb = new StringBuilder(value(base));
                    for (int k = at + 4; k < at + len; k++) {
                        t = component(sb, t, w[k]);
                    }
                    define(id, rt, castTo(sb.toString(), t, rt));
                }
                case OpCompositeInsert -> {
                    int object = w[at + 3];
                    int composite = w[at + 4];
                    String var = forceVar(id, rt);
                    line(var + " = " + value(composite) + ";");
                    StringBuilder sb = new StringBuilder(var);
                    int t = rt;
                    for (int k = at + 5; k < at + len; k++) {
                        t = component(sb, t, w[k]);
                    }
                    line(sb + " = " + valueAs(object, t) + ";");
                }
                case OpVectorExtractDynamic -> define(id, rt, value(w[at + 3]) + "[" + value(w[at + 4]) + "]");
                case OpVectorInsertDynamic -> {
                    String var = forceVar(id, rt);
                    line(var + " = " + value(w[at + 3]) + ";");
                    line(var + "[" + value(w[at + 5]) + "] = " + value(w[at + 4]) + ";");
                }
                case OpVectorShuffle -> shuffle(id, rt, w[at + 3], w[at + 4], at + 5, at + len);
                case OpTranspose -> define(id, rt, "transpose(" + value(w[at + 3]) + ")");
                case OpSampledImage -> {
                    Val img = val(w[at + 3]);
                    Val samp = val(w[at + 4]);
                    Val v = new Val();
                    v.k = VK.COMBINED;
                    v.expr = img.expr;
                    v.samp = samp.expr;
                    v.type = img.type;
                    vals[id] = v;
                }
                case OpImage -> {
                    Val si = val(w[at + 3]);
                    if (si.k == VK.TEXEL_BUFFER) {
                        vals[id] = si;
                    } else {
                        Val v = new Val();
                        v.k = VK.TEX;
                        v.expr = si.expr;
                        v.type = si.type;
                        vals[id] = v;
                    }
                }
                case OpImageSampleImplicitLod, OpImageSampleExplicitLod, OpImageSampleDrefImplicitLod,
                        OpImageSampleDrefExplicitLod, OpImageSampleProjImplicitLod, OpImageSampleProjExplicitLod ->
                    sample(op, id, rt, at, len);
                case OpImageFetch -> fetch(id, rt, at, len);
                case OpImageGather -> {
                    Val si = val(w[at + 3]);
                    String coord = value(w[at + 4]);
                    define(id, rt, "textureGather(" + value(w[at + 5]) + ", " + si.expr + ", " + si.samp + ", " + coord + ")");
                }
                case OpImageQuerySizeLod -> {
                    Val img = val(w[at + 3]);
                    define(id, rt, castTo("textureDimensions(" + img.expr + ", u32(" + value(w[at + 4]) + "))",
                            0, rt, "u32"));
                }
                case OpImageQuerySize -> {
                    Val img = val(w[at + 3]);
                    if (img.k == VK.TEXEL_BUFFER) {
                        define(id, rt, img.expr + "_size()");
                    } else {
                        define(id, rt, castTo("textureDimensions(" + img.expr + ")", 0, rt, "u32"));
                    }
                }
                case OpImageQueryLevels -> define(id, rt, castTo("textureNumLevels(" + val(w[at + 3]).expr + ")", 0, rt, "u32"));
                case OpImageQuerySamples -> define(id, rt, castTo("textureNumSamples(" + val(w[at + 3]).expr + ")", 0, rt, "u32"));
                case OpConvertFToU, OpConvertFToS, OpConvertSToF, OpConvertUToF -> {
                    String src = op == OpConvertSToF ? asInt(w[at + 3], true) : op == OpConvertUToF ? asInt(w[at + 3], false) : value(w[at + 3]);
                    define(id, rt, type(rt) + "(" + src + ")");
                }
                case OpUConvert, OpSConvert, OpFConvert, OpQuantizeToF16 -> {
                    if (op == OpQuantizeToF16) {
                        define(id, rt, "quantizeToF16(" + value(w[at + 3]) + ")");
                    } else {
                        define(id, rt, valueAs(w[at + 3], rt));
                    }
                }
                case OpBitcast -> {
                    String t = type(rt);
                    String from = type(typeOf(w[at + 3]));
                    define(id, rt, t.equals(from) ? value(w[at + 3]) : "bitcast<" + t + ">(" + value(w[at + 3]) + ")");
                }
                case OpSNegate -> define(id, rt, castTo("(-" + asInt(w[at + 3], true) + ")", 0, rt, "i32"));
                case OpFNegate -> define(id, rt, "(-" + value(w[at + 3]) + ")");
                case OpNot -> define(id, rt, "(~" + valueAs(w[at + 3], rt) + ")");
                case OpLogicalNot -> define(id, rt, "(!" + value(w[at + 3]) + ")");
                case OpIAdd -> define(id, rt, bin(rt, w[at + 3], "+", w[at + 4]));
                case OpISub -> define(id, rt, bin(rt, w[at + 3], "-", w[at + 4]));
                case OpIMul -> define(id, rt, bin(rt, w[at + 3], "*", w[at + 4]));
                case OpBitwiseOr -> define(id, rt, bin(rt, w[at + 3], "|", w[at + 4]));
                case OpBitwiseXor -> define(id, rt, bin(rt, w[at + 3], "^", w[at + 4]));
                case OpBitwiseAnd -> define(id, rt, bin(rt, w[at + 3], "&", w[at + 4]));
                case OpFAdd -> define(id, rt, "(" + value(w[at + 3]) + " + " + value(w[at + 4]) + ")");
                case OpFSub -> define(id, rt, "(" + value(w[at + 3]) + " - " + value(w[at + 4]) + ")");
                case OpFMul, OpVectorTimesScalar, OpMatrixTimesScalar, OpVectorTimesMatrix, OpMatrixTimesVector,
                        OpMatrixTimesMatrix -> define(id, rt, "(" + value(w[at + 3]) + " * " + value(w[at + 4]) + ")");
                case OpFDiv -> define(id, rt, "(" + value(w[at + 3]) + " / " + value(w[at + 4]) + ")");
                case OpFRem -> define(id, rt, "(" + value(w[at + 3]) + " % " + value(w[at + 4]) + ")");
                case OpFMod -> {
                    String a = value(w[at + 3]);
                    String b = value(w[at + 4]);
                    define(id, rt, "(" + a + " - " + b + " * floor(" + a + " / " + b + "))");
                }
                case OpSDiv -> define(id, rt, signedBin(rt, w[at + 3], "/", w[at + 4], true));
                case OpUDiv -> define(id, rt, signedBin(rt, w[at + 3], "/", w[at + 4], false));
                case OpSRem -> define(id, rt, signedBin(rt, w[at + 3], "%", w[at + 4], true));
                case OpUMod -> define(id, rt, signedBin(rt, w[at + 3], "%", w[at + 4], false));
                case OpSMod -> {
                    String a = asInt(w[at + 3], true);
                    String b = asInt(w[at + 4], true);
                    String r = "((" + a + " % " + b + " + " + b + ") % " + b + ")";
                    define(id, rt, castTo(r, 0, rt, "i32"));
                }
                case OpShiftLeftLogical -> define(id, rt, "(" + valueAs(w[at + 3], rt) + " << " + shiftAmount(w[at + 4]) + ")");
                case OpShiftRightLogical -> define(id, rt, castTo("(" + asInt(w[at + 3], false) + " >> " + shiftAmount(w[at + 4]) + ")", 0, rt, "u32"));
                case OpShiftRightArithmetic -> define(id, rt, castTo("(" + asInt(w[at + 3], true) + " >> " + shiftAmount(w[at + 4]) + ")", 0, rt, "i32"));
                case OpDot -> define(id, rt, "dot(" + value(w[at + 3]) + ", " + value(w[at + 4]) + ")");
                case OpOuterProduct -> {
                    Type mt = m.type(rt);
                    String a = value(w[at + 3]);
                    String b = value(w[at + 4]);
                    StringBuilder sb = new StringBuilder(type(rt)).append('(');
                    for (int c = 0; c < mt.count; c++) {
                        sb.append(c == 0 ? "" : ", ").append(a).append(" * ").append(b).append('[').append(c).append(']');
                    }
                    define(id, rt, sb.append(')').toString());
                }
                case OpAny -> define(id, rt, "any(" + value(w[at + 3]) + ")");
                case OpAll -> define(id, rt, "all(" + value(w[at + 3]) + ")");
                case OpIsNan -> define(id, rt, "(" + value(w[at + 3]) + " != " + value(w[at + 3]) + ")");
                case OpIsInf -> {
                    String x = value(w[at + 3]);
                    String inf = withScalar(typeOf(w[at + 3]), "f32") + "(bitcast<f32>(0x7f800000u))";
                    define(id, rt, "(abs(" + x + ") == " + inf + ")");
                }
                case OpLogicalEqual -> define(id, rt, "(" + value(w[at + 3]) + " == " + value(w[at + 4]) + ")");
                case OpLogicalNotEqual -> define(id, rt, "(" + value(w[at + 3]) + " != " + value(w[at + 4]) + ")");
                case OpLogicalOr -> define(id, rt, "(" + value(w[at + 3]) + " | " + value(w[at + 4]) + ")");
                case OpLogicalAnd -> define(id, rt, "(" + value(w[at + 3]) + " & " + value(w[at + 4]) + ")");
                case OpSelect -> {
                    int t = rt;
                    define(id, rt, "select(" + valueAs(w[at + 5], t) + ", " + valueAs(w[at + 4], t) + ", " + value(w[at + 3]) + ")");
                }
                case OpIEqual -> define(id, rt, cmp(w[at + 3], "==", w[at + 4], null));
                case OpINotEqual -> define(id, rt, cmp(w[at + 3], "!=", w[at + 4], null));
                case OpUGreaterThan -> define(id, rt, cmp(w[at + 3], ">", w[at + 4], false));
                case OpSGreaterThan -> define(id, rt, cmp(w[at + 3], ">", w[at + 4], true));
                case OpUGreaterThanEqual -> define(id, rt, cmp(w[at + 3], ">=", w[at + 4], false));
                case OpSGreaterThanEqual -> define(id, rt, cmp(w[at + 3], ">=", w[at + 4], true));
                case OpULessThan -> define(id, rt, cmp(w[at + 3], "<", w[at + 4], false));
                case OpSLessThan -> define(id, rt, cmp(w[at + 3], "<", w[at + 4], true));
                case OpULessThanEqual -> define(id, rt, cmp(w[at + 3], "<=", w[at + 4], false));
                case OpSLessThanEqual -> define(id, rt, cmp(w[at + 3], "<=", w[at + 4], true));
                case OpFOrdEqual, OpFUnordEqual -> define(id, rt, "(" + value(w[at + 3]) + " == " + value(w[at + 4]) + ")");
                case OpFOrdNotEqual, OpFUnordNotEqual -> define(id, rt, "(" + value(w[at + 3]) + " != " + value(w[at + 4]) + ")");
                case OpFOrdLessThan, OpFUnordLessThan -> define(id, rt, "(" + value(w[at + 3]) + " < " + value(w[at + 4]) + ")");
                case OpFOrdGreaterThan, OpFUnordGreaterThan -> define(id, rt, "(" + value(w[at + 3]) + " > " + value(w[at + 4]) + ")");
                case OpFOrdLessThanEqual, OpFUnordLessThanEqual -> define(id, rt, "(" + value(w[at + 3]) + " <= " + value(w[at + 4]) + ")");
                case OpFOrdGreaterThanEqual, OpFUnordGreaterThanEqual -> define(id, rt, "(" + value(w[at + 3]) + " >= " + value(w[at + 4]) + ")");
                case OpBitFieldInsert -> define(id, rt, "insertBits(" + valueAs(w[at + 3], rt) + ", " + valueAs(w[at + 4], rt) + ", u32("
                        + value(w[at + 5]) + "), u32(" + value(w[at + 6]) + "))");
                case OpBitFieldSExtract -> define(id, rt, castTo("extractBits(" + asInt(w[at + 3], true) + ", u32(" + value(w[at + 4])
                        + "), u32(" + value(w[at + 5]) + "))", 0, rt, "i32"));
                case OpBitFieldUExtract -> define(id, rt, castTo("extractBits(" + asInt(w[at + 3], false) + ", u32(" + value(w[at + 4])
                        + "), u32(" + value(w[at + 5]) + "))", 0, rt, "u32"));
                case OpBitReverse -> define(id, rt, "reverseBits(" + valueAs(w[at + 3], rt) + ")");
                case OpBitCount -> define(id, rt, castTo("countOneBits(" + value(w[at + 3]) + ")", typeOf(w[at + 3]), rt));
                case OpDPdx -> define(id, rt, "dpdx(" + value(w[at + 3]) + ")");
                case OpDPdy -> define(id, rt, "dpdy(" + value(w[at + 3]) + ")");
                case OpFwidth -> define(id, rt, "fwidth(" + value(w[at + 3]) + ")");
                case OpDPdxFine -> define(id, rt, "dpdxFine(" + value(w[at + 3]) + ")");
                case OpDPdyFine -> define(id, rt, "dpdyFine(" + value(w[at + 3]) + ")");
                case OpFwidthFine -> define(id, rt, "fwidthFine(" + value(w[at + 3]) + ")");
                case OpDPdxCoarse -> define(id, rt, "dpdxCoarse(" + value(w[at + 3]) + ")");
                case OpDPdyCoarse -> define(id, rt, "dpdyCoarse(" + value(w[at + 3]) + ")");
                case OpFwidthCoarse -> define(id, rt, "fwidthCoarse(" + value(w[at + 3]) + ")");
                case OpDemoteToHelperInvocation -> line("discard;");
                case OpArrayLength -> {
                    Val p = val(w[at + 3]);
                    define(id, rt, castTo("arrayLength(&" + p.expr + ".m" + w[at + 4] + ")", 0, rt, "u32"));
                }
                default -> throw new TranslationException("unsupported instruction opcode " + op);
            }
        }

        /** {@link #castTo(String, int, int)} where the source type is given as a WGSL scalar of rt's shape. */
        private String castTo(String e, int ignored, int rt, String scalar) {
            String from = withScalar(rt, scalar);
            String to = type(rt);
            return from.equals(to) ? e : (isIntName(to) && isIntName(from) ? "bitcast<" + to + ">(" + e + ")" : to + "(" + e + ")");
        }

        private static boolean isIntName(String t) {
            return t.contains("i32") || t.contains("u32");
        }

        private String forceVar(int id, int type) {
            String name = "h_" + id;
            if (!hoisted.contains(id)) {
                line("var " + name + ": " + type(type) + ";");
            }
            vals[id] = Val.expr(name, type);
            return name;
        }

        private String bin(int rt, int a, String op, int b) {
            return "(" + valueAs(a, rt) + " " + op + " " + valueAs(b, rt) + ")";
        }

        private String signedBin(int rt, int a, String op, int b, boolean signed) {
            String e = "(" + asInt(a, signed) + " " + op + " " + asInt(b, signed) + ")";
            return castTo(e, 0, rt, signed ? "i32" : "u32");
        }

        private String cmp(int a, String op, int b, Boolean signed) {
            if (signed == null) {
                int t = typeOf(a);
                return "(" + value(a) + " " + op + " " + valueAs(b, t) + ")";
            }
            return "(" + asInt(a, signed) + " " + op + " " + asInt(b, signed) + ")";
        }

        private String shiftAmount(int id) {
            int t = typeOf(id);
            String e = value(id);
            if (isInt(t) && isSigned(t)) {
                return "bitcast<" + withScalar(t, "u32") + ">(" + e + ")";
            }
            return e;
        }

        /** Appends the access for composite index {@code index} of type {@code t}; returns the component type. */
        private int component(StringBuilder sb, int t, int index) {
            Type ty = m.type(t);
            switch (ty.kind) {
                case STRUCT -> {
                    sb.append(".m").append(index);
                    return ty.members[index];
                }
                case VECTOR -> {
                    sb.append('.').append("xyzw".charAt(index));
                    return ty.component;
                }
                case MATRIX, ARRAY, RUNTIME_ARRAY -> {
                    sb.append('[').append(index).append(']');
                    return ty.component;
                }
                default -> throw new TranslationException("cannot index into " + ty.kind);
            }
        }

        private void shuffle(int id, int rt, int a, int b, int from, int to) {
            int na = vectorSize(typeOf(a));
            String ea = value(a);
            String eb = value(b);
            boolean allA = true;
            boolean allB = true;
            for (int k = from; k < to; k++) {
                int c = w[k];
                if (c == -1 || c < na) {
                    allB = false;
                } else {
                    allA = false;
                }
            }
            StringBuilder sw = new StringBuilder();
            if (allA || allB) {
                for (int k = from; k < to; k++) {
                    int c = w[k] == -1 ? 0 : w[k] - (allB ? na : 0);
                    sw.append("xyzw".charAt(c));
                }
                define(id, rt, (allA ? ea : eb) + "." + sw);
                return;
            }
            StringBuilder sb = new StringBuilder(type(rt)).append('(');
            for (int k = from; k < to; k++) {
                int c = w[k];
                sb.append(k == from ? "" : ", ");
                if (c == -1 || c < na) {
                    sb.append(ea).append('.').append("xyzw".charAt(c == -1 ? 0 : c));
                } else {
                    sb.append(eb).append('.').append("xyzw".charAt(c - na));
                }
            }
            define(id, rt, sb.append(')').toString());
        }

        private void load(int id, int rt, int pointer) {
            Val p = val(pointer);
            switch (p.k) {
                case PTR, FUNC_PARAM_PTR -> define(id, rt, castTo(p.expr, p.type, rt));
                case COMBINED, TEX, SAMP, TEXEL_BUFFER -> vals[id] = p;
                default -> throw new TranslationException("load from " + p.k);
            }
        }

        private void store(int pointer, int value) {
            Val p = val(pointer);
            if (p.k != VK.PTR && p.k != VK.FUNC_PARAM_PTR) {
                throw new TranslationException("store to " + p.k);
            }
            line(p.expr + " = " + valueAs(value, p.type) + ";");
        }

        private String lvalue(int pointer) {
            Val p = val(pointer);
            return p.expr;
        }

        private void accessChain(int id, int rt, int base, int from, int to) {
            Val b = val(base);
            if (b.k != VK.PTR && b.k != VK.FUNC_PARAM_PTR) {
                throw new TranslationException("access chain on " + b.k);
            }
            StringBuilder sb = new StringBuilder(b.expr);
            int t = b.type;
            for (int k = from; k < to; k++) {
                int idx = w[k];
                Type ty = m.type(t);
                boolean constIdx = m.isConstant(idx);
                switch (ty.kind) {
                    case STRUCT -> {
                        int mi = m.intConstant(idx);
                        sb.append(".m").append(mi);
                        t = ty.members[mi];
                    }
                    case VECTOR -> {
                        if (constIdx) {
                            sb.append('.').append("xyzw".charAt(m.intConstant(idx)));
                        } else {
                            sb.append('[').append(value(idx)).append(']');
                        }
                        t = ty.component;
                    }
                    case MATRIX, ARRAY, RUNTIME_ARRAY -> {
                        sb.append('[').append(constIdx ? String.valueOf(m.intConstant(idx)) : value(idx)).append(']');
                        t = ty.component;
                    }
                    default -> throw new TranslationException("access chain into " + ty.kind);
                }
            }
            Val v = Val.ptr(sb.toString(), t, b.storage, false);
            vals[id] = v;
        }

        private void call(int id, int rt, int fn, int from, int to) {
            StringBuilder sb = new StringBuilder(functionNames.get(fn)).append('(');
            for (int k = from; k < to; k++) {
                Val a = val(w[k]);
                sb.append(k == from ? "" : ", ");
                switch (a.k) {
                    case EXPR -> sb.append(a.expr);
                    case PTR -> {
                        if (!a.addressable) {
                            throw new TranslationException("pointer argument is not a whole variable: " + a.expr);
                        }
                        sb.append('&').append(a.expr);
                    }
                    case FUNC_PARAM_PTR -> sb.append(a.expr, 2, a.expr.length() - 1); // "(*p)" -> "p"
                    case COMBINED -> sb.append(a.expr).append(", ").append(a.samp);
                    case TEX, SAMP -> sb.append(a.expr);
                    default -> throw new TranslationException("unsupported call argument " + a.k);
                }
            }
            sb.append(')');
            if (m.type(rt).kind == Kind.VOID) {
                line(sb + ";");
            } else {
                define(id, rt, sb.toString());
            }
        }

        // ---------------------------------------------------------------- images

        private void sample(int op, int id, int rt, int at, int len) {
            Val si = val(w[at + 3]);
            if (si.k != VK.COMBINED) {
                throw new TranslationException("sampling a " + si.k);
            }
            Type img = m.type(si.type);
            String coord = value(w[at + 4]);
            int coordType = typeOf(w[at + 4]);
            int k = at + 5;
            String dref = null;
            if (op == OpImageSampleDrefImplicitLod || op == OpImageSampleDrefExplicitLod) {
                dref = value(w[k++]);
            }
            boolean proj = op == OpImageSampleProjImplicitLod || op == OpImageSampleProjExplicitLod;
            int mask = k < at + len ? w[k++] : 0;
            String bias = null, lod = null, gradX = null, gradY = null, offset = null;
            if ((mask & ImageOperandsBias) != 0) {
                bias = value(w[k++]);
            }
            if ((mask & ImageOperandsLod) != 0) {
                lod = value(w[k++]);
            }
            if ((mask & ImageOperandsGrad) != 0) {
                gradX = value(w[k++]);
                gradY = value(w[k++]);
            }
            if ((mask & ImageOperandsConstOffset) != 0) {
                offset = value(w[k++]);
            }
            if ((mask & ~(ImageOperandsBias | ImageOperandsLod | ImageOperandsGrad | ImageOperandsConstOffset)) != 0) {
                throw new TranslationException("unsupported image operands 0x" + Integer.toHexString(mask));
            }
            int coordSize = vectorSize(coordType);
            int spatial = img.dim == DimCube || img.dim == Dim3D ? 3 : img.dim == Dim1D ? 1 : 2;
            if (proj) {
                String q = coord + "." + "xyzw".charAt(coordSize - 1);
                coord = "(" + coord + "." + "xyzw".substring(0, spatial) + " / " + q + ")";
            }
            StringBuilder args = new StringBuilder(si.expr).append(", ").append(si.samp).append(", ");
            if (img.arrayed != 0) {
                args.append(coord).append('.').append("xyzw", 0, spatial).append(", i32(round(").append(coord).append('.')
                        .append("xyzw".charAt(spatial)).append("))");
            } else if (!proj && coordSize > spatial) {
                args.append(coord).append('.').append("xyzw", 0, spatial);
            } else {
                args.append(coord);
            }
            String fn;
            if (dref != null) {
                fn = lod != null || op == OpImageSampleDrefExplicitLod ? "textureSampleCompareLevel" : "textureSampleCompare";
                args.append(", ").append(dref);
            } else if (gradX != null) {
                fn = "textureSampleGrad";
                args.append(", ").append(gradX).append(", ").append(gradY);
            } else if (lod != null) {
                fn = "textureSampleLevel";
                args.append(", ").append(img.depth == 1 ? "i32(" + lod + ")" : lod);
            } else if (bias != null) {
                fn = "textureSampleBias";
                args.append(", ").append(bias);
            } else {
                fn = "textureSample";
            }
            if (offset != null) {
                args.append(", ").append(offset);
            }
            String e = fn + "(" + args + ")";
            if (dref != null || img.depth == 1) {
                // Depth sampling returns a scalar; SPIR-V Dref returns a scalar, plain sampling a vec4.
                Type r = m.type(rt);
                if (r.kind == Kind.VECTOR) {
                    e = type(rt) + "(" + e + ", 0.0, 0.0, 1.0)";
                }
            }
            define(id, rt, e);
        }

        private void fetch(int id, int rt, int at, int len) {
            Val img = val(w[at + 3]);
            String coord = value(w[at + 4]);
            int coordType = typeOf(w[at + 4]);
            if (img.k == VK.TEXEL_BUFFER) {
                define(id, rt, img.expr + "(" + asInt(w[at + 4], true) + ")");
                return;
            }
            int mask = at + 5 < at + len ? w[at + 5] : 0;
            String lod = "0";
            int k = at + 6;
            if ((mask & ImageOperandsLod) != 0) {
                lod = value(w[k++]);
            }
            String sample = null;
            if ((mask & ImageOperandsSample) != 0) {
                sample = value(w[k++]);
            }
            Type it = m.type(img.type);
            StringBuilder args = new StringBuilder(img.expr).append(", ");
            int spatial = it.dim == Dim3D ? 3 : it.dim == Dim1D ? 1 : 2;
            if (it.arrayed != 0) {
                args.append(coord).append('.').append("xyzw", 0, spatial).append(", ").append(coord).append('.').append("xyzw".charAt(spatial));
            } else {
                args.append(coord);
            }
            args.append(", ").append(it.ms != 0 ? (sample == null ? "0" : sample) : lod);
            String e = "textureLoad(" + args + ")";
            if (it.depth == 1 && m.type(rt).kind == Kind.VECTOR) {
                e = type(rt) + "(" + e + ", 0.0, 0.0, 1.0)";
            }
            define(id, rt, e);
        }

        // ---------------------------------------------------------------- GLSL.std.450

        private void extInst(int id, int rt, int set, int inst, int from, int to) {
            if (!String.valueOf(set).equals(glslExt)) {
                String name = m.extInstSets.get(set);
                if (name != null && name.startsWith("NonSemantic.")) {
                    return;
                }
                throw new TranslationException("unsupported extended instruction set " + name);
            }
            List<String> a = new ArrayList<>();
            for (int k = from; k < to; k++) {
                a.add(value(w[k]));
            }
            String e = switch (inst) {
                case 1, 2 -> call1("round", a);
                case 3 -> call1("trunc", a);
                case 4 -> call1("abs", a);
                case 5 -> castTo("abs(" + asInt(w[from], true) + ")", 0, rt, "i32");
                case 6 -> call1("sign", a);
                case 7 -> castTo("sign(" + asInt(w[from], true) + ")", 0, rt, "i32");
                case 8 -> call1("floor", a);
                case 9 -> call1("ceil", a);
                case 10 -> call1("fract", a);
                case 11 -> call1("radians", a);
                case 12 -> call1("degrees", a);
                case 13 -> call1("sin", a);
                case 14 -> call1("cos", a);
                case 15 -> call1("tan", a);
                case 16 -> call1("asin", a);
                case 17 -> call1("acos", a);
                case 18 -> call1("atan", a);
                case 19 -> call1("sinh", a);
                case 20 -> call1("cosh", a);
                case 21 -> call1("tanh", a);
                case 22 -> call1("asinh", a);
                case 23 -> call1("acosh", a);
                case 24 -> call1("atanh", a);
                case 25 -> call1("atan2", a);
                case 26 -> call1("pow", a);
                case 27 -> call1("exp", a);
                case 28 -> call1("log", a);
                case 29 -> call1("exp2", a);
                case 30 -> call1("log2", a);
                case 31 -> call1("sqrt", a);
                case 32 -> call1("inverseSqrt", a);
                case 33 -> call1("determinant", a);
                case 34 -> inverse(rt, a.get(0));
                case 37, 79 -> call1("min", a);
                case 38 -> castTo("min(" + asInt(w[from], false) + ", " + asInt(w[from + 1], false) + ")", 0, rt, "u32");
                case 39 -> castTo("min(" + asInt(w[from], true) + ", " + asInt(w[from + 1], true) + ")", 0, rt, "i32");
                case 40, 80 -> call1("max", a);
                case 41 -> castTo("max(" + asInt(w[from], false) + ", " + asInt(w[from + 1], false) + ")", 0, rt, "u32");
                case 42 -> castTo("max(" + asInt(w[from], true) + ", " + asInt(w[from + 1], true) + ")", 0, rt, "i32");
                case 43, 81 -> call1("clamp", a);
                case 44 -> castTo("clamp(" + asInt(w[from], false) + ", " + asInt(w[from + 1], false) + ", " + asInt(w[from + 2], false) + ")", 0, rt, "u32");
                case 45 -> castTo("clamp(" + asInt(w[from], true) + ", " + asInt(w[from + 1], true) + ", " + asInt(w[from + 2], true) + ")", 0, rt, "i32");
                case 46 -> call1("mix", a);
                case 48 -> call1("step", a);
                case 49 -> call1("smoothstep", a);
                case 50 -> call1("fma", a);
                case 53 -> "ldexp(" + a.get(0) + ", " + asInt(w[from + 1], true) + ")";
                case 54 -> "pack4x8snorm(" + a.get(0) + ")";
                case 55 -> "pack4x8unorm(" + a.get(0) + ")";
                case 56 -> "pack2x16snorm(" + a.get(0) + ")";
                case 57 -> "pack2x16unorm(" + a.get(0) + ")";
                case 58 -> "pack2x16float(" + a.get(0) + ")";
                case 60 -> "unpack2x16snorm(" + asInt(w[from], false) + ")";
                case 61 -> "unpack2x16unorm(" + asInt(w[from], false) + ")";
                case 62 -> "unpack2x16float(" + asInt(w[from], false) + ")";
                case 63 -> "unpack4x8snorm(" + asInt(w[from], false) + ")";
                case 64 -> "unpack4x8unorm(" + asInt(w[from], false) + ")";
                case 66 -> call1("length", a);
                case 67 -> call1("distance", a);
                case 68 -> call1("cross", a);
                case 69 -> call1("normalize", a);
                case 70 -> call1("faceForward", a);
                case 71 -> call1("reflect", a);
                case 72 -> call1("refract", a);
                case 73 -> castTo("firstTrailingBit(" + a.get(0) + ")", typeOf(w[from]), rt);
                case 74 -> castTo("firstLeadingBit(" + asInt(w[from], true) + ")", 0, rt, "i32");
                case 75 -> castTo("firstLeadingBit(" + asInt(w[from], false) + ")", 0, rt, "u32");
                case 76, 77, 78 -> a.get(0); // InterpolateAt*: no WGSL equivalent; use the plain value
                default -> throw new TranslationException("unsupported GLSL.std.450 instruction " + inst);
            };
            define(id, rt, e);
        }

        private static String call1(String fn, List<String> args) {
            return fn + "(" + String.join(", ", args) + ")";
        }

        private String inverse(int rt, String mat) {
            Type t = m.type(rt);
            int n = t.count;
            String name = "mcweb_inverse" + n;
            String mt = type(rt);
            if (n == 2) {
                helpers.add("fn " + name + "(m: " + mt + ") -> " + mt + " {\n"
                        + "    let d = determinant(m);\n"
                        + "    return " + mt + "(m[1][1], -m[0][1], -m[1][0], m[0][0]) * (1.0 / d);\n}\n");
            } else if (n == 3) {
                helpers.add("fn " + name + "(m: " + mt + ") -> " + mt + " {\n"
                        + "    let a = m[0]; let b = m[1]; let c = m[2];\n"
                        + "    let r0 = cross(b, c); let r1 = cross(c, a); let r2 = cross(a, b);\n"
                        + "    let d = dot(a, r0);\n"
                        + "    return transpose(" + mt + "(r0, r1, r2)) * (1.0 / d);\n}\n");
            } else if (n == 4) {
                helpers.add("fn " + name + "(m: " + mt + ") -> " + mt + " {\n"
                        + "    let a00 = m[0][0]; let a01 = m[0][1]; let a02 = m[0][2]; let a03 = m[0][3];\n"
                        + "    let a10 = m[1][0]; let a11 = m[1][1]; let a12 = m[1][2]; let a13 = m[1][3];\n"
                        + "    let a20 = m[2][0]; let a21 = m[2][1]; let a22 = m[2][2]; let a23 = m[2][3];\n"
                        + "    let a30 = m[3][0]; let a31 = m[3][1]; let a32 = m[3][2]; let a33 = m[3][3];\n"
                        + "    let b00 = a00 * a11 - a01 * a10; let b01 = a00 * a12 - a02 * a10;\n"
                        + "    let b02 = a00 * a13 - a03 * a10; let b03 = a01 * a12 - a02 * a11;\n"
                        + "    let b04 = a01 * a13 - a03 * a11; let b05 = a02 * a13 - a03 * a12;\n"
                        + "    let b06 = a20 * a31 - a21 * a30; let b07 = a20 * a32 - a22 * a30;\n"
                        + "    let b08 = a20 * a33 - a23 * a30; let b09 = a21 * a32 - a22 * a31;\n"
                        + "    let b10 = a21 * a33 - a23 * a31; let b11 = a22 * a33 - a23 * a32;\n"
                        + "    let det = b00 * b11 - b01 * b10 + b02 * b09 + b03 * b08 - b04 * b07 + b05 * b06;\n"
                        + "    return " + mt + "(\n"
                        + "        a11 * b11 - a12 * b10 + a13 * b09, a02 * b10 - a01 * b11 - a03 * b09,\n"
                        + "        a31 * b05 - a32 * b04 + a33 * b03, a22 * b04 - a21 * b05 - a23 * b03,\n"
                        + "        a12 * b08 - a10 * b11 - a13 * b07, a00 * b11 - a02 * b08 + a03 * b07,\n"
                        + "        a32 * b02 - a30 * b05 - a33 * b01, a20 * b05 - a22 * b02 + a23 * b01,\n"
                        + "        a10 * b10 - a11 * b08 + a13 * b06, a01 * b08 - a00 * b10 - a03 * b06,\n"
                        + "        a30 * b04 - a31 * b02 + a33 * b00, a21 * b02 - a20 * b04 - a23 * b00,\n"
                        + "        a11 * b07 - a10 * b09 - a12 * b06, a00 * b09 - a01 * b07 + a02 * b06,\n"
                        + "        a31 * b01 - a30 * b03 - a32 * b00, a20 * b03 - a21 * b01 + a22 * b00) * (1.0 / det);\n}\n");
            } else {
                throw new TranslationException("inverse of " + n + "x" + n + " matrix");
            }
            return name + "(" + mat + ")";
        }

        // ---------------------------------------------------------------- entry point

        private String entryPoint() {
            StringBuilder sb = new StringBuilder();
            List<String> params = new ArrayList<>();
            List<String> copyIn = new ArrayList<>();
            List<String> outFields = new ArrayList<>();
            List<String> copyOut = new ArrayList<>();
            int inputIndex = 0;
            for (int[] io : ioInputs) {
                int id = io[0];
                Val v = vals[id];
                Decoration builtin = m.decoration(id, DecorationBuiltIn);
                String pname = "in_" + (inputIndex++);
                if (builtin != null) {
                    switch (builtin.value()) {
                        case BuiltInVertexIndex, BuiltInVertexId -> {
                            params.add("@builtin(vertex_index) " + pname + ": u32");
                            copyIn.add(v.expr + " = " + castTo(pname, 0, v.type, "u32") + ";");
                        }
                        case BuiltInInstanceIndex, BuiltInInstanceId -> {
                            params.add("@builtin(instance_index) " + pname + ": u32");
                            copyIn.add(v.expr + " = " + castTo(pname, 0, v.type, "u32") + ";");
                        }
                        case BuiltInFragCoord -> {
                            params.add("@builtin(position) " + pname + ": vec4<f32>");
                            copyIn.add(v.expr + " = " + pname + ";");
                        }
                        case BuiltInFrontFacing -> {
                            params.add("@builtin(front_facing) " + pname + ": bool");
                            copyIn.add(v.expr + " = " + pname + ";");
                        }
                        case BuiltInSampleId -> {
                            params.add("@builtin(sample_index) " + pname + ": u32");
                            copyIn.add(v.expr + " = " + castTo(pname, 0, v.type, "u32") + ";");
                        }
                        case BuiltInPointCoord -> copyIn.add(v.expr + " = vec2<f32>(0.5, 0.5);");
                        case BuiltInHelperInvocation -> copyIn.add(v.expr + " = false;");
                        case BuiltInBaseVertex, BuiltInBaseInstance, BuiltInDrawIndex -> copyIn.add(v.expr + " = 0;");
                        default -> throw new TranslationException("unsupported input builtin " + builtin.value());
                    }
                    continue;
                }
                if (m.isBuiltinBlock(m.pointee(m.globals.stream().filter(g -> g.id() == id).findFirst().orElseThrow().type()).id)) {
                    continue; // gl_PerVertex inputs only exist in tessellation/geometry stages
                }
                Decoration loc = m.decoration(id, DecorationLocation);
                if (loc == null) {
                    throw new TranslationException("input %" + id + " has no location");
                }
                Type it = m.type(v.type);
                if (it.kind == Kind.ARRAY) {
                    checkInterfaceType(it.component, id);
                    for (int e = 0; e < it.count; e++) {
                        params.add("@location(" + (w[loc.at()] + e) + ") " + interpolation(id, it.component, !vertex) + pname + "_" + e
                                + ": " + type(it.component));
                        copyIn.add(v.expr + "[" + e + "] = " + pname + "_" + e + ";");
                    }
                    continue;
                }
                checkInterfaceType(v.type, id);
                params.add("@location(" + w[loc.at()] + ") " + interpolation(id, v.type, !vertex) + pname + ": " + type(v.type));
                copyIn.add(v.expr + " = " + pname + ";");
            }
            String positionExpr = null;
            boolean invariant = false;
            int outIndex = 0;
            for (int[] io : ioOutputs) {
                int id = io[0];
                Val v = vals[id];
                Decoration builtin = m.decoration(id, DecorationBuiltIn);
                Type t = m.type(v.type);
                if (builtin != null) {
                    switch (builtin.value()) {
                        case BuiltInPosition -> {
                            positionExpr = v.expr;
                            invariant = m.hasDecoration(id, DecorationInvariant);
                        }
                        case BuiltInFragDepth -> {
                            outFields.add("@builtin(frag_depth) depth: f32");
                            copyOut.add("o.depth = " + v.expr + ";");
                        }
                        case BuiltInPointSize, BuiltInClipDistance, BuiltInCullDistance -> {
                        }
                        case BuiltInSampleMask -> {
                            outFields.add("@builtin(sample_mask) sample_mask: u32");
                            copyOut.add("o.sample_mask = bitcast<u32>(" + v.expr + "[0]);");
                        }
                        default -> throw new TranslationException("unsupported output builtin " + builtin.value());
                    }
                    continue;
                }
                if (t.kind == Kind.STRUCT && m.isBuiltinBlock(t.id)) {
                    for (int mi = 0; mi < t.members.length; mi++) {
                        Decoration mb = m.memberDecoration(t.id, mi, DecorationBuiltIn);
                        if (mb != null && mb.value() == BuiltInPosition) {
                            positionExpr = v.expr + ".m" + mi;
                            invariant = m.memberDecoration(t.id, mi, DecorationInvariant) != null;
                        }
                    }
                    continue;
                }
                Decoration loc = m.decoration(id, DecorationLocation);
                if (loc == null) {
                    throw new TranslationException("output %" + id + " has no location");
                }
                if (!vertex) {
                    Decoration index = m.decoration(id, DecorationIndex);
                    if (index != null && index.value() != 0) {
                        throw new TranslationException("dual-source blending output");
                    }
                }
                // Arrays of scalars/vectors take consecutive locations, one element each.
                int elemType = v.type;
                int count = 1;
                if (t.kind == Kind.ARRAY) {
                    elemType = t.component;
                    count = t.count;
                }
                checkInterfaceType(elemType, id);
                for (int e = 0; e < count; e++) {
                    String field = "o" + (outIndex++);
                    String attrs = "@location(" + (w[loc.at()] + e) + ") " + (vertex ? interpolation(id, elemType, true) : "");
                    outFields.add(attrs + field + ": " + type(elemType));
                    copyOut.add("o." + field + " = " + v.expr + (t.kind == Kind.ARRAY ? "[" + e + "]" : "") + ";");
                }
            }
            if (vertex) {
                if (positionExpr == null) {
                    throw new TranslationException("vertex shader without gl_Position");
                }
                outFields.add(0, (invariant ? "@invariant " : "") + "@builtin(position) position: vec4<f32>");
                String p = positionExpr;
                copyOut.add(0, opt.flipY ? "o.position = vec4<f32>(" + p + ".x, -" + p + ".y, " + p + ".z, " + p + ".w);"
                        : "o.position = " + p + ";");
            }
            String outType = vertex ? "VertexOutput" : "FragmentOutput";
            if (!outFields.isEmpty()) {
                sb.append("struct ").append(outType).append(" {\n");
                for (String f : outFields) {
                    sb.append("    ").append(f).append(",\n");
                }
                sb.append("}\n\n");
            }
            sb.append(vertex ? "@vertex" : "@fragment").append("\nfn main(").append(String.join(", ", params)).append(')');
            if (!outFields.isEmpty()) {
                sb.append(" -> ").append(outType);
            }
            sb.append(" {\n");
            for (String c : copyIn) {
                sb.append("    ").append(c).append('\n');
            }
            sb.append("    main_1();\n");
            if (!outFields.isEmpty()) {
                sb.append("    var o: ").append(outType).append(";\n");
                for (String c : copyOut) {
                    sb.append("    ").append(c).append('\n');
                }
                sb.append("    return o;\n");
            }
            sb.append("}\n");
            return sb.toString();
        }

        private void checkInterfaceType(int typeId, int id) {
            Type t = m.type(typeId);
            if (t.kind != Kind.INT && t.kind != Kind.FLOAT && t.kind != Kind.VECTOR) {
                throw new TranslationException("unsupported interface variable type " + t.kind + " for %" + id);
            }
        }

        private String interpolation(int id, int typeId, boolean interStage) {
            if (!interStage) {
                return "";
            }
            if (isInt(typeId) || m.hasDecoration(id, DecorationFlat)) {
                return "@interpolate(flat) ";
            }
            if (m.hasDecoration(id, DecorationNoPerspective)) {
                return "@interpolate(linear) ";
            }
            if (m.hasDecoration(id, DecorationCentroid)) {
                return "@interpolate(perspective, centroid) ";
            }
            if (m.hasDecoration(id, DecorationSample)) {
                return "@interpolate(perspective, sample) ";
            }
            return "";
        }
    }

    // ------------------------------------------------------------------------------------

    /** Storage layout of a texel buffer format and how to read one texel as a vec4. */
    private static final class TexelFormat {
        final String storageType;   // array element type
        final int texelsPerElement; // >1 for packed 8/16-bit formats
        private final String format;

        private TexelFormat(String format, String storageType, int texelsPerElement) {
            this.format = format;
            this.storageType = storageType;
            this.texelsPerElement = texelsPerElement;
        }

        static TexelFormat of(String format) {
            return switch (format) {
                case "R32_SINT", "RG32_SINT", "RGB32_SINT", "RGBA32_SINT" -> new TexelFormat(format, "i32", 1);
                case "R32_UINT", "RG32_UINT", "RGB32_UINT", "RGBA32_UINT" -> new TexelFormat(format, "u32", 1);
                case "R32_FLOAT", "RG32_FLOAT", "RGB32_FLOAT", "RGBA32_FLOAT" -> new TexelFormat(format, "f32", 1);
                case "RGBA8_UNORM", "RGBA8_UINT", "RGBA8_SINT", "RGBA8_SNORM" -> new TexelFormat(format, "u32", 1);
                case "R8_UINT", "R8_SINT", "R8_UNORM" -> new TexelFormat(format, "u32", 4);
                case "R16_UINT", "R16_SINT", "R16_UNORM", "RG16_UINT", "RG16_SINT" -> new TexelFormat(format, "u32", format.startsWith("RG") ? 1 : 2);
                default -> throw new TranslationException("unsupported texel buffer format " + format);
            };
        }

        private int components() {
            if (format.startsWith("RGBA")) {
                return 4;
            }
            if (format.startsWith("RGB")) {
                return 3;
            }
            if (format.startsWith("RG")) {
                return 2;
            }
            return 1;
        }

        /** Body of {@code fn(i: i32) -> vec4<st>}. */
        String load(String buf, String st) {
            String one = st.equals("f32") ? "1.0" : st.equals("i32") ? "1i" : "1u";
            String zero = st.equals("f32") ? "0.0" : st.equals("i32") ? "0i" : "0u";
            int n = components();
            if (format.contains("32")) {
                StringBuilder sb = new StringBuilder("    let b = i * ").append(n).append(";\n    return vec4<").append(st).append(">(");
                for (int c = 0; c < 4; c++) {
                    sb.append(c == 0 ? "" : ", ");
                    if (c < n) {
                        sb.append(st).append('(').append(buf).append("[b + ").append(c).append("])");
                    } else {
                        sb.append(c == 3 ? one : zero);
                    }
                }
                return sb.append(");\n").toString();
            }
            return switch (format) {
                case "RGBA8_UNORM" -> "    return vec4<" + st + ">(unpack4x8unorm(" + buf + "[i]));\n";
                case "RGBA8_SNORM" -> "    return vec4<" + st + ">(unpack4x8snorm(" + buf + "[i]));\n";
                case "RGBA8_UINT" -> "    let v = " + buf + "[i];\n    return vec4<" + st + ">(" + st + "(v & 0xffu), " + st
                        + "((v >> 8u) & 0xffu), " + st + "((v >> 16u) & 0xffu), " + st + "(v >> 24u));\n";
                case "RGBA8_SINT" -> "    let v = bitcast<i32>(" + buf + "[i]);\n    return vec4<" + st + ">(" + st
                        + "(extractBits(v, 0u, 8u)), " + st + "(extractBits(v, 8u, 8u)), " + st + "(extractBits(v, 16u, 8u)), " + st
                        + "(extractBits(v, 24u, 8u)));\n";
                case "R8_UINT", "R8_UNORM", "R8_SINT" -> {
                    String raw = "extractBits(" + (format.equals("R8_SINT") ? "bitcast<i32>(" + buf + "[i / 4])" : buf + "[i / 4]")
                            + ", u32(i % 4) * 8u, 8u)";
                    String v = format.equals("R8_UNORM") ? "f32(" + raw + ") / 255.0" : st + "(" + raw + ")";
                    yield "    return vec4<" + st + ">(" + v + ", " + zero + ", " + zero + ", " + one + ");\n";
                }
                case "R16_UINT", "R16_UNORM", "R16_SINT" -> {
                    String raw = "extractBits(" + (format.equals("R16_SINT") ? "bitcast<i32>(" + buf + "[i / 2])" : buf + "[i / 2]")
                            + ", u32(i % 2) * 16u, 16u)";
                    String v = format.equals("R16_UNORM") ? "f32(" + raw + ") / 65535.0" : st + "(" + raw + ")";
                    yield "    return vec4<" + st + ">(" + v + ", " + zero + ", " + zero + ", " + one + ");\n";
                }
                case "RG16_UINT", "RG16_SINT" -> {
                    String src = format.equals("RG16_SINT") ? "bitcast<i32>(" + buf + "[i])" : buf + "[i]";
                    yield "    let v = " + src + ";\n    return vec4<" + st + ">(" + st + "(extractBits(v, 0u, 16u)), " + st
                            + "(extractBits(v, 16u, 16u)), " + zero + ", " + one + ");\n";
                }
                default -> throw new TranslationException("unsupported texel buffer format " + format);
            };
        }
    }
}
