package dev.mcwebclient.web.gpu;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.device.DeviceFeatures;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.DeviceLimits;
import com.mojang.renderpearl.api.device.DeviceType;
import com.mojang.renderpearl.api.device.HintsAndWorkarounds;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import com.mojang.renderpearl.backend.api.SpvModule;

import dev.mcwebclient.web.gpu.spirv.SpirvModule;
import dev.mcwebclient.web.gpu.spirv.WgslTranslator;
import dev.mcwebclient.web.mem.NativeHeap;
import dev.mcwebclient.web.mem.Nmem;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

/** renderpearl's device backend on WebGPU (see {@link WebGpu} for the command stream). */
public final class WebGpuDevice implements GpuDeviceBackend {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static WebGpuDevice current;
    private static Set<String> features = Set.of();

    private final DeviceInfo info;
    private final WebGpuCommandEncoder encoder;
    private final List<Runnable> afterSubmit = new ArrayList<>();

    // Bind group layouts / pipeline layouts / bind groups, cached by structure.
    private final Map<String, Integer> layouts = new HashMap<>();
    private final Map<String, Integer> bindGroups = new HashMap<>();
    private final Map<String, Long> bindGroupLastUse = new HashMap<>();
    private final Map<Integer, Set<String>> bindGroupsByResource = new HashMap<>();
    private long submits;

    // Push constants: a ring of 256-byte slots, uploaded before each submit.
    private static final int PC_SLOT = 256;
    private static final int PC_SLOTS = 8192;
    private final int pcBuffer;
    private final long pcShadow;
    private int pcUsed;
    private int pcLayout;
    private int pcBindGroup;

    // Index buffer expanding triangle fans into lists: 0,1,2, 0,2,3, ...
    private int fanIndexBuffer;
    private int fanTriangles;

    private WebGpuDevice() {
        current = this;
        WebGpu.init();
        Set<String> f = new HashSet<>();
        for (String name : new String[] {"float32-blendable", "float32-filterable", "indirect-first-instance",
            "depth32float-stencil8", "texture-formats-tier1", "rg11b10ufloat-renderable"}) {
            if (WebGpu.hasFeature(name)) {
                f.add(name);
            }
        }
        features = Set.copyOf(f);
        this.info = buildInfo();
        this.encoder = new WebGpuCommandEncoder(this);

        pcBuffer = WebGpu.newId();
        WebGpu.begin(4);
        WebGpu.put(WebGpu.CREATE_BUFFER);
        WebGpu.put(pcBuffer);
        WebGpu.put(PC_SLOT * PC_SLOTS);
        WebGpu.put(WebGpu.BUF_STORAGE | WebGpu.BUF_COPY_DST);
        pcShadow = NativeHeap.calloc(1, (long) PC_SLOT * PC_SLOTS);
    }

    static WebGpuDevice create() {
        return new WebGpuDevice();
    }

    static WebGpuDevice current() {
        return current;
    }

    static Set<String> features() {
        return features;
    }

    private DeviceInfo buildInfo() {
        String vendor = orEmpty(WebGpu.info("vendor"));
        String arch = orEmpty(WebGpu.info("architecture"));
        String description = orEmpty(WebGpu.info("description"));
        String name = !description.isEmpty() ? description : (vendor + " " + arch).trim();
        if (name.isEmpty()) {
            name = "WebGPU adapter";
        }
        int maxTexture = (int) WebGpu.limit("maxTextureDimension2D");
        long maxBuffer = (long) WebGpu.limit("maxBufferSize");
        int maxColor = (int) WebGpu.limit("maxColorAttachments");
        DeviceLimits limits = new DeviceLimits(16, 256, maxTexture, maxBuffer, 1 << 20, Math.min(8, maxColor), 1 << 20);
        DeviceFeatures deviceFeatures = new DeviceFeatures(
                false, // wireframeFillMode: WebGPU has no polygon modes
                false, // shaderDrawParameters
                true,  // multiDrawDirectInterleaved (expanded into draws by the host)
                true,  // multiDrawDirectSeparate (ditto; Sodium's GL path uses it)
                true,  // multiDrawIndirect (one drawIndexedIndirect per command)
                true,  // drawIndirect
                true,  // nonZeroFirstInstance (direct draws always allow it)
                false  // persistentMapping
        );
        HintsAndWorkarounds hints = new HintsAndWorkarounds(false, false, false, false);
        Set<String> extensions = new HashSet<>(features);
        return new DeviceInfo(name, vendor.isEmpty() ? "WebGPU" : vendor, "WebGPU (" + orEmpty(WebGpu.info("browser")) + ")",
                true, "WebGPU", 1.0F, limits, deviceFeatures, extensions, hints, DeviceType.OTHER);
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    // ---------------------------------------------------------------- GpuDeviceBackend

    @Override
    public GpuSurfaceBackend createSurface(long windowHandle, BooleanSupplier isIconified) {
        return new WebGpuSurface();
    }

    @Override
    public CommandEncoderBackend createCommandEncoder() {
        return encoder;
    }

    WebGpuCommandEncoder encoder() {
        return encoder;
    }

    @Override
    public GpuSampler createSampler(AddressMode u, AddressMode v, FilterMode minFilter, FilterMode magFilter, int maxAnisotropy, OptionalDouble maxLod) {
        return new WebGpuSampler(u, v, minFilter, magFilter, maxAnisotropy, maxLod);
    }

    @Override
    public GpuTexture createTexture(@Nullable String label, @GpuTexture.Usage int usage, GpuFormat format, int width, int height, int depthOrLayers, int mipLevels) {
        return new WebGpuTexture(usage, label == null ? "" : label, format, width, height, depthOrLayers, mipLevels);
    }

    @Override
    public GpuTextureView createTextureView(GpuTexture texture, int baseMipLevel, int mipLevels) {
        return new WebGpuTextureView((WebGpuTexture) texture, baseMipLevel, mipLevels);
    }

    @Override
    public GpuBuffer createBuffer(@Nullable Supplier<String> label, @GpuBuffer.Usage int usage, long size) {
        return new WebGpuBuffer(label, usage, size);
    }

    @Override
    public GpuBuffer createBuffer(@Nullable Supplier<String> label, @GpuBuffer.Usage int usage, ByteBuffer data) {
        WebGpuBuffer buffer = new WebGpuBuffer(label, usage | GpuBuffer.USAGE_COPY_DST, data.remaining());
        long size = data.remaining();
        long src;
        long tmp = 0;
        if (data.isDirect()) {
            src = MemoryUtil.memAddress(data);
        } else {
            tmp = NativeHeap.malloc(Math.max(4, size));
            for (int i = 0; i < size; i++) {
                Nmem.putByte(tmp + i, data.get(data.position() + i));
            }
            src = tmp;
        }
        if (buffer.shadow() != 0) {
            Nmem.copy(src, buffer.shadow(), size);
        }
        buffer.upload(0, src, size);
        WebGpu.flush(); // the source memory belongs to the caller
        if (tmp != 0) {
            NativeHeap.free(tmp);
        }
        return buffer;
    }

    @Override
    public List<String> getLastDebugMessages() {
        return List.of();
    }

    @Override
    public boolean isDebuggingEnabled() {
        return false;
    }

    @Override
    public BackendRenderPipeline.Pending compilePipeline(BackendRenderPipeline.CreateInfo createInfo) {
        try {
            WgslTranslator.Options options = new WgslTranslator.Options();
            for (int i = 0; i < createInfo.uniforms().size(); i++) {
                var u = createInfo.uniforms().get(i);
                if (u.type() == UniformType.TEXEL_BUFFER && u.gpuFormat() != null) {
                    options.texelBufferFormats.put(i, u.gpuFormat().name());
                }
            }
            int vs = 0;
            int fs = 0;
            List<SpirvModule> modules = new ArrayList<>();
            for (BackendRenderPipeline.CreateInfo.Shader shader : createInfo.shaders()) {
                SpirvModule module = new SpirvModule(words(shader.module()));
                modules.add(module);
                String wgsl = WgslTranslator.translate(module, options);
                int id = WebGpu.newId();
                String label = createInfo.name() + " " + shader.module().type().getName();
                WebGpu.createShaderModule(id, wgsl, label);
                if (shader.module().type() == ShaderType.VERTEX) {
                    vs = id;
                } else {
                    fs = id;
                }
            }
            WebGpuRenderPipeline pipeline = new WebGpuRenderPipeline(createInfo, vs, fs, modules);
            return () -> pipeline;
        } catch (RuntimeException e) {
            LOGGER.error("Couldn't translate pipeline {} to WGSL", createInfo.name(), e);
            return BackendRenderPipeline.Pending.NULL;
        }
    }

    private static int[] words(SpvModule module) {
        if (module instanceof WebSpvModule web) {
            return web.words();
        }
        ByteBuffer spv = module.spv().duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int[] w = new int[spv.remaining() / 4];
        spv.asIntBuffer().get(w);
        return w;
    }

    @Override
    public void close() {
    }

    @Override
    public GpuQueryPool createTimestampQueryPool(int size) {
        return new GpuQueryPool() {
            @Override
            public int size() {
                return size;
            }

            @Override
            public OptionalLong getValue(int index) {
                return OptionalLong.empty();
            }

            @Override
            public OptionalLong[] getValues(int index, int count) {
                OptionalLong[] values = new OptionalLong[count];
                java.util.Arrays.fill(values, OptionalLong.empty());
                return values;
            }

            @Override
            public void close() {
            }
        };
    }

    @Override
    public long getTimestampCalibrationOffset() {
        return 0L;
    }

    @Override
    public DeviceInfo getDeviceInfo() {
        return info;
    }

    // ---------------------------------------------------------------- layouts and bind groups

    /** Group 0 layout for a pipeline's uniforms; {@code unfilterableMask} marks textures bound as unfilterable. */
    int bindGroupLayout(WebGpuRenderPipeline p, long unfilterableMask) {
        StringBuilder key = new StringBuilder("bgl:");
        List<int[]> entries = new ArrayList<>();
        for (int i = 0; i < p.uniforms.size(); i++) {
            int vis = WebGpu.STAGE_VERTEX | WebGpu.STAGE_FRAGMENT;
            switch (p.uniformType(i)) {
                case UNIFORM_BUFFER -> entries.add(new int[] {2 * i, vis, 0, 0, 0});
                case TEXEL_BUFFER -> entries.add(new int[] {2 * i, vis, 1, 0, 0});
                case COMBINED_IMAGE_SAMPLER -> {
                    WebGpuRenderPipeline.SamplerInfo s = p.samplers[i];
                    int dim = s == null ? WebGpuEnums.VIEW_2D : s.viewDimension();
                    int kind = s == null ? WebGpuEnums.SAMPLE_FLOAT : s.sampleKind();
                    int samplerType = WebGpuEnums.SAMPLER_FILTERING;
                    if (kind == WebGpuEnums.SAMPLE_FLOAT && (unfilterableMask & (1L << i)) != 0) {
                        kind = WebGpuEnums.SAMPLE_UNFILTERABLE;
                        samplerType = WebGpuEnums.SAMPLER_NON_FILTERING;
                    } else if (kind == WebGpuEnums.SAMPLE_SINT || kind == WebGpuEnums.SAMPLE_UINT) {
                        samplerType = WebGpuEnums.SAMPLER_NON_FILTERING;
                    } else if (kind == WebGpuEnums.SAMPLE_DEPTH) {
                        samplerType = WebGpuEnums.SAMPLER_COMPARISON;
                    }
                    entries.add(new int[] {2 * i, vis, 2, kind, dim});
                    entries.add(new int[] {2 * i + 1, vis, 3, samplerType, 0});
                }
            }
        }
        for (int[] e : entries) {
            key.append(e[0]).append(':').append(e[2]).append(':').append(e[3]).append(':').append(e[4]).append(';');
        }
        return layouts.computeIfAbsent(key.toString(), k -> {
            int id = WebGpu.newId();
            WebGpu.begin(3 + entries.size() * 5);
            WebGpu.put(WebGpu.CREATE_BGL);
            WebGpu.put(id);
            WebGpu.put(entries.size());
            for (int[] e : entries) {
                for (int v : e) {
                    WebGpu.put(v);
                }
            }
            return id;
        });
    }

    int pipelineLayout(int layout0, boolean pushConstants) {
        String key = "pl:" + layout0 + ":" + pushConstants;
        return layouts.computeIfAbsent(key, k -> {
            int id = WebGpu.newId();
            WebGpu.begin(5);
            WebGpu.put(WebGpu.CREATE_PIPELINE_LAYOUT);
            WebGpu.put(id);
            WebGpu.put(pushConstants ? 2 : 1);
            WebGpu.put(layout0);
            if (pushConstants) {
                WebGpu.put(pushConstantLayout());
            }
            return id;
        });
    }

    private int pushConstantLayout() {
        if (pcLayout == 0) {
            pcLayout = WebGpu.newId();
            WebGpu.begin(8);
            WebGpu.put(WebGpu.CREATE_BGL);
            WebGpu.put(pcLayout);
            WebGpu.put(1);
            WebGpu.put(0);
            WebGpu.put(WebGpu.STAGE_VERTEX | WebGpu.STAGE_FRAGMENT);
            WebGpu.put(1);
            WebGpu.put(1); // dynamic offset
            WebGpu.put(0);
        }
        return pcLayout;
    }

    int pushConstantBindGroup() {
        if (pcBindGroup == 0) {
            pcBindGroup = WebGpu.newId();
            WebGpu.begin(9);
            WebGpu.put(WebGpu.CREATE_BIND_GROUP);
            WebGpu.put(pcBindGroup);
            WebGpu.put(pushConstantLayout());
            WebGpu.put(1);
            WebGpu.put(0);
            WebGpu.put(0);
            WebGpu.put(pcBuffer);
            WebGpu.put(0);
            WebGpu.put(PC_SLOT);
        }
        return pcBindGroup;
    }

    /** Copies push constant data into the ring; returns its dynamic offset. */
    int pushConstants(ByteBuffer data) {
        if (pcUsed >= PC_SLOTS) {
            throw new IllegalStateException("more than " + PC_SLOTS + " push constant updates in one submit");
        }
        int offset = pcUsed++ * PC_SLOT;
        int n = Math.min(PC_SLOT, data.remaining());
        if (data.isDirect()) {
            Nmem.copy(MemoryUtil.memAddress(data), pcShadow + offset, n);
        } else {
            for (int i = 0; i < n; i++) {
                Nmem.putByte(pcShadow + offset + i, data.get(data.position() + i));
            }
        }
        return offset;
    }

    /**
     * A bind group for the given entries ({binding, kind(0 buffer, 1 view, 2 sampler),
     * resource, offset, size}), cached and released when any of its resources is.
     */
    int bindGroup(int layout, int[][] entries) {
        StringBuilder key = new StringBuilder().append(layout);
        for (int[] e : entries) {
            key.append('|').append(e[0]).append(',').append(e[2]).append(',').append(e[3]).append(',').append(e[4]);
        }
        String k = key.toString();
        Integer id = bindGroups.get(k);
        if (id == null) {
            id = WebGpu.newId();
            WebGpu.begin(4 + entries.length * 5);
            WebGpu.put(WebGpu.CREATE_BIND_GROUP);
            WebGpu.put(id);
            WebGpu.put(layout);
            WebGpu.put(entries.length);
            for (int[] e : entries) {
                for (int v : e) {
                    WebGpu.put(v);
                }
                bindGroupsByResource.computeIfAbsent(e[2], r -> new HashSet<>()).add(k);
            }
            bindGroups.put(k, id);
        }
        bindGroupLastUse.put(k, submits);
        return id;
    }

    /** Drops cached bind groups that reference a destroyed resource. */
    void releaseBindings(int resource) {
        Set<String> keys = bindGroupsByResource.remove(resource);
        if (keys == null) {
            return;
        }
        for (String k : keys) {
            Integer id = bindGroups.remove(k);
            bindGroupLastUse.remove(k);
            if (id != null) {
                WebGpu.destroy(id);
            }
        }
    }

    // ---------------------------------------------------------------- fans

    /** A uint32 index buffer turning a triangle fan of {@code triangles} into a list. */
    int fanIndexBuffer(int triangles) {
        if (triangles > fanTriangles) {
            if (fanIndexBuffer != 0) {
                WebGpu.destroy(fanIndexBuffer);
            }
            fanTriangles = Math.max(triangles, 1024);
            fanIndexBuffer = WebGpu.newId();
            long bytes = (long) fanTriangles * 12;
            WebGpu.begin(4);
            WebGpu.put(WebGpu.CREATE_BUFFER);
            WebGpu.put(fanIndexBuffer);
            WebGpu.put((int) bytes);
            WebGpu.put(WebGpu.BUF_INDEX | WebGpu.BUF_COPY_DST);
            long tmp = NativeHeap.malloc(bytes);
            for (int t = 0; t < fanTriangles; t++) {
                Nmem.putInt(tmp + t * 12L, 0);
                Nmem.putInt(tmp + t * 12L + 4, t + 1);
                Nmem.putInt(tmp + t * 12L + 8, t + 2);
            }
            WebGpu.writeBuffer(fanIndexBuffer, 0, tmp, bytes);
            WebGpu.flush();
            NativeHeap.free(tmp);
        }
        return fanIndexBuffer;
    }

    // ---------------------------------------------------------------- submit hooks

    void afterNextSubmit(Runnable r) {
        afterSubmit.add(r);
    }

    /** Called by the command encoder around each submit. */
    void beforeSubmit() {
        if (pcUsed > 0) {
            WebGpu.writeBuffer(pcBuffer, 0, pcShadow, (long) pcUsed * PC_SLOT);
        }
    }

    void afterSubmit() {
        pcUsed = 0;
        submits++;
        List<Runnable> tasks = new ArrayList<>(afterSubmit);
        afterSubmit.clear();
        tasks.forEach(Runnable::run);
        if (submits % 120 == 0) {
            // Bind groups for transient data change every frame; drop ones unused for a while.
            List<String> stale = new ArrayList<>();
            for (Map.Entry<String, Long> e : bindGroupLastUse.entrySet()) {
                if (submits - e.getValue() > 240) {
                    stale.add(e.getKey());
                }
            }
            for (String k : stale) {
                Integer id = bindGroups.remove(k);
                bindGroupLastUse.remove(k);
                if (id != null) {
                    WebGpu.destroy(id);
                }
            }
            for (Set<String> keys : bindGroupsByResource.values()) {
                keys.removeAll(stale);
            }
        }
    }
}
