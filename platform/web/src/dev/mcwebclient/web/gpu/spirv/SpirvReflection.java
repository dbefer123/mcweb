package dev.mcwebclient.web.gpu.spirv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.util.ShaderCompileException;

import dev.mcwebclient.web.gpu.spirv.SpirvModule.Decoration;
import dev.mcwebclient.web.gpu.spirv.SpirvModule.Kind;

import dev.mcwebclient.web.gpu.spirv.SpirvModule.Variable;

import static dev.mcwebclient.web.gpu.spirv.Spirv.*;

/**
 * renderpearl's shader reflection, computed from the SPIR-V itself instead of
 * SPIRV-Cross (spvc). It mirrors what spvc reports for the same module: resource kinds
 * and order, block-type names for buffers, spvc base-type codes, and setters that patch
 * Location/Binding/DescriptorSet literals in {@link SpirvModule#words}.
 */
public final class SpirvReflection implements SpvModule.Reflection {
    /** spvc_resource_type values used by renderpearl. */
    public static final int UNIFORM_BUFFER = 1, STORAGE_BUFFER = 2, STAGE_INPUT = 3, STAGE_OUTPUT = 4,
            STORAGE_IMAGE = 6, SAMPLED_IMAGE = 7, PUSH_CONSTANT = 9, SEPARATE_IMAGE = 10, SEPARATE_SAMPLERS = 11;
    private static final int[] DESCRIPTOR_TYPES = {UNIFORM_BUFFER, STORAGE_BUFFER, STORAGE_IMAGE, SAMPLED_IMAGE,
        SEPARATE_IMAGE, SEPARATE_SAMPLERS};

    private final SpirvModule module;
    private final List<InterfaceVariable> inputs = new ArrayList<>();
    private final List<InterfaceVariable> outputs = new ArrayList<>();
    private final Map<Integer, List<Descriptor>> descriptors = new LinkedHashMap<>();
    private final List<Descriptor> allDescriptors = new ArrayList<>();
    private final List<PushConstant> pushConstants = new ArrayList<>();

    public SpirvReflection(SpirvModule module) throws ShaderCompileException {
        this.module = module;
        for (int t : DESCRIPTOR_TYPES) {
            descriptors.put(t, new ArrayList<>());
        }
        for (Variable v : module.globals) {
            SpirvModule.Type pointee = module.pointee(v.type());
            switch (v.storage()) {
                case Input, Output -> {
                    if (module.hasDecoration(v.id(), DecorationBuiltIn) || module.isBuiltinBlock(pointee.id)) {
                        continue;
                    }
                    (v.storage() == Input ? inputs : outputs).add(new InterfaceVar(v, pointee));
                }
                case Uniform, StorageBuffer, UniformConstant -> {
                    int kind = resourceKind(v, pointee);
                    if (kind != 0) {
                        Desc d = new Desc(v, pointee, kind);
                        descriptors.get(kind).add(d);
                    }
                }
                case PushConstant -> {
                    int size = module.declaredSize(pointee.id);
                    pushConstants.add(() -> size);
                }
                default -> {
                }
            }
        }
        for (List<Descriptor> list : descriptors.values()) {
            allDescriptors.addAll(list);
        }
    }

    public SpirvModule module() {
        return module;
    }

    private int resourceKind(Variable v, SpirvModule.Type pointee) {
        SpirvModule.Type base = pointee;
        while (base.kind == Kind.ARRAY || base.kind == Kind.RUNTIME_ARRAY) {
            base = module.type(base.component);
        }
        if (v.storage() == StorageBuffer) {
            return STORAGE_BUFFER;
        }
        if (v.storage() == Uniform) {
            if (module.hasDecoration(base.id, DecorationBufferBlock)) {
                return STORAGE_BUFFER;
            }
            return module.hasDecoration(base.id, DecorationBlock) ? UNIFORM_BUFFER : 0;
        }
        return switch (base.kind) {
            case SAMPLED_IMAGE -> SAMPLED_IMAGE;
            case IMAGE -> base.sampled == 2 ? STORAGE_IMAGE : SEPARATE_IMAGE;
            case SAMPLER -> SEPARATE_SAMPLERS;
            default -> 0;
        };
    }

    @Override
    public List<InterfaceVariable> inputs() {
        return Collections.unmodifiableList(inputs);
    }

    @Override
    public List<InterfaceVariable> outputs() {
        return Collections.unmodifiableList(outputs);
    }

    @Override
    public List<Descriptor> descriptors(int resourceType) {
        return Collections.unmodifiableList(descriptors.getOrDefault(resourceType, List.of()));
    }

    @Override
    public List<Descriptor> descriptors() {
        return Collections.unmodifiableList(allDescriptors);
    }

    @Override
    public List<PushConstant> pushConstants() {
        return Collections.unmodifiableList(pushConstants);
    }

    private Decoration require(int id, int kind, String what, String name) throws ShaderCompileException {
        Decoration d = module.decoration(id, kind);
        if (d == null || d.at() < 0) {
            throw new ShaderCompileException("Couldn't find byte offset for " + what + " decoration of " + name);
        }
        return d;
    }

    /** spvc type info for a (pointee) type; arrays report their element type's base and vector size. */
    final class TypeInfo implements Type0 {
        private final SpirvModule.Type type;
        private final List<Integer> arrayLengths = new ArrayList<>();
        private final SpirvModule.Type base;

        TypeInfo(SpirvModule.Type type) {
            this.type = type;
            SpirvModule.Type t = type;
            while (t.kind == Kind.ARRAY || t.kind == Kind.RUNTIME_ARRAY) {
                arrayLengths.add(0, t.kind == Kind.ARRAY ? t.count : 0);
                t = module.type(t.component);
            }
            this.base = t;
        }

        @Override
        public int baseType() {
            SpirvModule.Type t = base;
            if (t.kind == Kind.VECTOR || t.kind == Kind.MATRIX) {
                t = module.type(t.component);
                if (t.kind == Kind.VECTOR) {
                    t = module.type(t.component);
                }
            }
            return switch (t.kind) {
                case VOID -> 1;
                case BOOL -> 2;
                case INT -> switch (t.width) {
                    case 8 -> t.signed ? 3 : 4;
                    case 16 -> t.signed ? 5 : 6;
                    case 64 -> t.signed ? 9 : 10;
                    default -> t.signed ? 7 : 8;
                };
                case FLOAT -> t.width == 16 ? 12 : t.width == 64 ? 14 : 13;
                case STRUCT -> 15;
                case IMAGE -> 16;
                case SAMPLED_IMAGE -> 17;
                case SAMPLER -> 18;
                default -> 0;
            };
        }

        @Override
        public int dimensions() {
            return switch (base.kind) {
                case IMAGE -> base.dim;
                case SAMPLED_IMAGE -> module.type(base.component).dim;
                default -> Integer.MAX_VALUE;
            };
        }

        @Override
        public int vectorSize() {
            if (base.kind == Kind.VECTOR) {
                return base.count;
            }
            if (base.kind == Kind.MATRIX) {
                return module.type(base.component).count;
            }
            return 1;
        }

        @Override
        public int arrayDimensions() {
            return arrayLengths.size();
        }

        @Override
        public int arrayLength(int dimensionIndex) {
            // spvc orders dimensions innermost first.
            return arrayLengths.get(arrayLengths.size() - 1 - dimensionIndex);
        }

        SpirvModule.Type type() {
            return type;
        }
    }

    /** Alias so the inner class can implement the nested renderpearl interface by a short name. */
    private interface Type0 extends SpvModule.Reflection.Type {
    }

    private final class InterfaceVar implements InterfaceVariable {
        private final Variable variable;
        private final TypeInfo type;
        private final String name;
        private final int locationAt;

        InterfaceVar(Variable variable, SpirvModule.Type pointee) throws ShaderCompileException {
            this.variable = variable;
            this.type = new TypeInfo(pointee);
            String n = module.name(variable.id());
            this.name = n == null ? "" : n;
            this.locationAt = require(variable.id(), DecorationLocation, "location", name).at();
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public SpvModule.Reflection.Type type() {
            return type;
        }

        @Override
        public int location() {
            return module.words[locationAt];
        }

        @Override
        public void location(int location) {
            module.words[locationAt] = location;
        }

        @Override
        public int decoration(int decoration) {
            Decoration d = module.decoration(variable.id(), decoration);
            if (d == null) {
                return 0;
            }
            return d.literals().length > 0 ? d.value() : 1;
        }
    }

    private final class Desc implements Descriptor {
        private final Variable variable;
        private final TypeInfo type;
        private final int kind;
        private final String name;
        private final int setAt;
        private final int bindingAt;

        Desc(Variable variable, SpirvModule.Type pointee, int kind) throws ShaderCompileException {
            this.variable = variable;
            this.type = new TypeInfo(pointee);
            this.kind = kind;
            this.name = descriptorName(variable, pointee, kind);
            this.setAt = require(variable.id(), DecorationDescriptorSet, "set", name).at();
            this.bindingAt = require(variable.id(), DecorationBinding, "binding", name).at();
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public SpvModule.Reflection.Type type() {
            return type;
        }

        @Override
        public int resourceType() {
            return kind;
        }

        @Override
        public int descriptorSetIndex() {
            return module.words[setAt];
        }

        @Override
        public void descriptorSetIndex(int index) {
            module.words[setAt] = index;
        }

        @Override
        public int binding() {
            return module.words[bindingAt];
        }

        @Override
        public void binding(int binding) {
            module.words[bindingAt] = binding;
        }

        int variableId() {
            return variable.id();
        }
    }

    /** spvc names buffer blocks by their block type; everything else by the variable. */
    private String descriptorName(Variable v, SpirvModule.Type pointee, int kind) {
        if (kind == UNIFORM_BUFFER || kind == STORAGE_BUFFER) {
            SpirvModule.Type base = pointee;
            while (base.kind == Kind.ARRAY || base.kind == Kind.RUNTIME_ARRAY) {
                base = module.type(base.component);
            }
            String blockName = module.name(base.id);
            if (blockName != null && !blockName.isEmpty()) {
                return blockName;
            }
            return "_" + v.id();
        }
        String n = module.name(v.id());
        return n == null || n.isEmpty() ? "_" + v.id() : n;
    }

    /** The SPIR-V variable id behind a descriptor returned by {@link #descriptors()}. */
    public static int variableId(Descriptor d) {
        return ((Desc) d).variableId();
    }
}
