package dev.mcwebclient.web.gpu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;

import dev.mcwebclient.web.gpu.spirv.Spirv;
import dev.mcwebclient.web.gpu.spirv.SpirvModule;

/**
 * A translated pipeline: the WGSL modules plus what bind-group layouts need to know
 * about each uniform. GPU pipelines are created per variant (attachment formats, and
 * which sampled textures are unfilterable) when a draw first needs one.
 */
final class WebGpuRenderPipeline implements BackendRenderPipeline {
    /** What the shaders declare for a combined image sampler uniform. */
    record SamplerInfo(int viewDimension, int sampleKind, boolean depthCompare) {
    }

    final String name;
    final BackendRenderPipeline.CreateInfo info;
    final int vertexModule;
    final int fragmentModule;
    final List<BindGroupLayout.UniformDescription> uniforms;
    final SamplerInfo[] samplers;
    /** Minimum binding size of each uniform buffer (WGSL struct size), 0 for other uniforms. */
    final long[] minBindingSize;
    private final Map<String, Variant> variants = new HashMap<>();
    private boolean closed;

    /** One GPU pipeline + its bind group layout. */
    record Variant(int pipeline, int layout0, long unfilterableMask) {
    }

    WebGpuRenderPipeline(BackendRenderPipeline.CreateInfo info, int vertexModule, int fragmentModule, List<SpirvModule> modules) {
        this.name = info.name();
        this.info = info;
        this.vertexModule = vertexModule;
        this.fragmentModule = fragmentModule;
        this.uniforms = info.uniforms();
        this.samplers = new SamplerInfo[uniforms.size()];
        this.minBindingSize = new long[uniforms.size()];
        for (SpirvModule m : modules) {
            for (SpirvModule.Variable v : m.globals) {
                SpirvModule.Decoration bd = m.decoration(v.id(), Spirv.DecorationBinding);
                if (bd == null) {
                    continue;
                }
                int binding = m.words[bd.at()];
                if (binding < 0 || binding >= uniforms.size()) {
                    continue;
                }
                SpirvModule.Type pointee = m.pointee(v.type());
                if (v.storage() == Spirv.UniformConstant && pointee.kind == SpirvModule.Kind.SAMPLED_IMAGE) {
                    SpirvModule.Type img = m.type(pointee.component);
                    SpirvModule.Type st = m.type(img.component);
                    int dim = img.dim == Spirv.DimCube ? (img.arrayed != 0 ? WebGpuEnums.VIEW_CUBE_ARRAY : WebGpuEnums.VIEW_CUBE)
                            : img.dim == Spirv.Dim3D ? WebGpuEnums.VIEW_3D
                            : img.arrayed != 0 ? WebGpuEnums.VIEW_2D_ARRAY : WebGpuEnums.VIEW_2D;
                    int kind = img.depth == 1 ? WebGpuEnums.SAMPLE_DEPTH
                            : st.kind == SpirvModule.Kind.FLOAT ? WebGpuEnums.SAMPLE_FLOAT
                            : st.signed ? WebGpuEnums.SAMPLE_SINT : WebGpuEnums.SAMPLE_UINT;
                    samplers[binding] = new SamplerInfo(dim, kind, img.depth == 1);
                } else if (v.storage() == Spirv.Uniform) {
                    long size = (m.declaredSize(pointee.id) + 15) & ~15L;
                    minBindingSize[binding] = Math.max(minBindingSize[binding], size);
                }
            }
        }
    }

    boolean hasPushConstants() {
        return info.pushConstantsSize() > 0;
    }

    /** The GPU pipeline for this pass setup, creating it (and its layout) on first use. */
    Variant variant(GpuFormat[] colorFormats, GpuFormat depthFormat, long unfilterableMask) {
        StringBuilder key = new StringBuilder();
        for (GpuFormat f : colorFormats) {
            key.append(f == null ? "-" : f.name()).append(',');
        }
        key.append(depthFormat == null ? "-" : depthFormat.name()).append('|').append(unfilterableMask);
        String k = key.toString();
        Variant v = variants.get(k);
        if (v == null) {
            v = create(colorFormats, depthFormat, unfilterableMask);
            variants.put(k, v);
        }
        return v;
    }

    private Variant create(GpuFormat[] colorFormats, GpuFormat depthFormat, long unfilterableMask) {
        WebGpuDevice device = WebGpuDevice.current();
        int layout0 = device.bindGroupLayout(this, unfilterableMask);
        int pipelineLayout = device.pipelineLayout(layout0, hasPushConstants());
        int id = WebGpu.newId();
        WebGpu.setLabel(id, name);

        List<CreateInfo.VertexBuffer> vbs = info.vertexBuffers();
        int slots = 0;
        for (CreateInfo.VertexBuffer vb : vbs) {
            slots = Math.max(slots, vb.bufferSlot() + 1);
        }
        int words = 32 + slots * 3 + info.attribBindings().size() * 3 + colorFormats.length * 9;
        WebGpu.begin(words);
        WebGpu.put(WebGpu.CREATE_RENDER_PIPELINE);
        WebGpu.put(id);
        WebGpu.put(pipelineLayout);
        WebGpu.put(vertexModule);
        WebGpu.put(fragmentModule);
        WebGpu.put(WebGpuEnums.topology(info.primitiveTopology()));
        WebGpu.put(info.cull() ? 1 : 0);
        WebGpu.put(1); // front face cw: framebuffer layout matches Vulkan's, whose backend uses clockwise
        DepthStencilState ds = info.depthStencilState();
        if (depthFormat != null) {
            WebGpu.put(WebGpuEnums.format(depthFormat));
            WebGpu.put(ds != null && ds.writeDepth() ? 1 : 0);
            WebGpu.put(ds != null ? WebGpuEnums.compare(ds.depthTest()) : 7);
            WebGpu.put(ds != null ? Math.round(ds.depthBiasConstant()) : 0);
            WebGpu.putF(ds != null ? ds.depthBiasScaleFactor() : 0f);
        } else {
            WebGpu.put(-1);
            WebGpu.put(0);
            WebGpu.put(7);
            WebGpu.put(0);
            WebGpu.putF(0f);
        }
        WebGpu.put(slots);
        for (int s = 0; s < slots; s++) {
            CreateInfo.VertexBuffer vb = null;
            for (CreateInfo.VertexBuffer c : vbs) {
                if (c.bufferSlot() == s) {
                    vb = c;
                }
            }
            List<CreateInfo.AttribBinding> attrs = new ArrayList<>();
            for (CreateInfo.AttribBinding a : info.attribBindings()) {
                if (a.bufferSlot() == s) {
                    attrs.add(a);
                }
            }
            if (vb == null) {
                WebGpu.put(-1);
                WebGpu.put(0);
                WebGpu.put(0);
                continue;
            }
            WebGpu.put(vb.stride());
            WebGpu.put(vb.stepRate() > 0 ? 1 : 0);
            WebGpu.put(attrs.size());
            for (CreateInfo.AttribBinding a : attrs) {
                WebGpu.put(WebGpuEnums.format(a.format()));
                WebGpu.put(a.offset());
                WebGpu.put(a.location());
            }
        }
        List<ColorTargetState> targets = info.colorTargetStates();
        WebGpu.put(colorFormats.length);
        for (int i = 0; i < colorFormats.length; i++) {
            ColorTargetState t = i < targets.size() ? targets.get(i) : null;
            if (colorFormats[i] == null) {
                WebGpu.put(-1);
                for (int k = 0; k < 8; k++) {
                    WebGpu.put(0);
                }
                continue;
            }
            WebGpu.put(WebGpuEnums.format(colorFormats[i]));
            WebGpu.put(t == null ? 0 : t.writeMask() & 15);
            var blend = t == null ? null : t.blendFunction().orElse(null);
            boolean blendable = !WebGpuEnums.isInteger(colorFormats[i])
                    && (colorFormats[i].componentType() != GpuFormat.ComponentType.FLOAT_32 || WebGpuDevice.features().contains("float32-blendable"));
            if (blend != null && blendable) {
                WebGpu.put(1);
                WebGpu.put(WebGpuEnums.blendFactor(blend.color().sourceFactor()));
                WebGpu.put(WebGpuEnums.blendFactor(blend.color().destFactor()));
                WebGpu.put(WebGpuEnums.blendOp(blend.color().op()));
                WebGpu.put(WebGpuEnums.blendFactor(blend.alpha().sourceFactor()));
                WebGpu.put(WebGpuEnums.blendFactor(blend.alpha().destFactor()));
                WebGpu.put(WebGpuEnums.blendOp(blend.alpha().op()));
            } else {
                for (int k = 0; k < 7; k++) {
                    WebGpu.put(0);
                }
            }
        }
        return new Variant(id, layout0, unfilterableMask);
    }

    boolean isFan() {
        return info.primitiveTopology() == PrimitiveTopology.TRIANGLE_FAN;
    }

    UniformType uniformType(int index) {
        return uniforms.get(index).type();
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (Variant v : variants.values()) {
            WebGpu.destroy(v.pipeline());
        }
        variants.clear();
        WebGpu.destroy(vertexModule);
        WebGpu.destroy(fragmentModule);
    }
}
