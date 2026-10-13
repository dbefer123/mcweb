package dev.mcwebclient.web.gpu;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.function.Supplier;

import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.util.TextureViewAndSampler;

import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

/**
 * Records one render pass. Bindings are resolved at draw time: the pipeline variant
 * depends on which bound textures can be filtered, and bind groups are cached by the
 * device. Push constants are a dynamic offset into the device's ring.
 */
final class WebGpuRenderPass implements RenderPassBackend {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean warnedTexelOffset;
    private static boolean warnedIndexedFan;

    private final WebGpuDevice device;
    private final GpuFormat[] colorFormats;
    private final GpuFormat depthFormat;
    private final int width;
    private final int height;
    private final RenderPass.RenderArea area;

    private WebGpuRenderPipeline pipeline;
    private Object[] uniforms = new Object[0];
    private boolean bindingsDirty = true;
    private int boundPipeline;
    private int pcOffset = -1;
    private boolean pcDirty;
    private int indexBuffer;
    private int indexFormat;

    WebGpuRenderPass(WebGpuDevice device, GpuFormat[] colorFormats, GpuFormat depthFormat, int width, int height, RenderPass.RenderArea area) {
        this.device = device;
        this.colorFormats = colorFormats;
        this.depthFormat = depthFormat;
        this.width = width;
        this.height = height;
        this.area = area;
    }

    void end() {
    }

    @Override
    public void pushDebugGroup(Supplier<String> label) {
    }

    @Override
    public void popDebugGroup() {
    }

    @Override
    public void setPipeline(BackendRenderPipeline p) {
        pipeline = (WebGpuRenderPipeline) p;
        uniforms = new Object[pipeline.uniforms.size()];
        bindingsDirty = true;
        pcDirty = true;
        boundPipeline = 0;
    }

    @Override
    public void setUniform(int index, @Nullable Object value) {
        uniforms[index] = value;
        bindingsDirty = true;
    }

    @Override
    public void pushConstants(ByteBuffer value) {
        pcOffset = device.pushConstants(value);
        pcDirty = true;
    }

    @Override
    public void enableScissor(int x, int y, int w, int h) {
        int x0 = Math.max(0, Math.min(x, width));
        int y0 = Math.max(0, Math.min(y, height));
        int x1 = Math.max(x0, Math.min(x + w, width));
        int y1 = Math.max(y0, Math.min(y + h, height));
        WebGpu.begin(5);
        WebGpu.put(WebGpu.SET_SCISSOR);
        WebGpu.put(x0);
        WebGpu.put(y0);
        WebGpu.put(x1 - x0);
        WebGpu.put(y1 - y0);
    }

    @Override
    public void disableScissor() {
        enableScissor(area.x(), area.y(), area.width(), area.height());
    }

    @Override
    public void setVertexBuffer(int slot, @Nullable GpuBufferSlice vertexBuffer) {
        if (vertexBuffer == null) {
            return;
        }
        WebGpu.begin(5);
        WebGpu.put(WebGpu.SET_VERTEX_BUFFER);
        WebGpu.put(slot);
        WebGpu.put(((WebGpuBuffer) vertexBuffer.buffer()).id);
        WebGpu.put((int) vertexBuffer.offset());
        WebGpu.put((int) vertexBuffer.length());
    }

    @Override
    public void setIndexBuffer(GpuBuffer buffer, IndexType indexType) {
        indexBuffer = ((WebGpuBuffer) buffer).id;
        indexFormat = indexType == IndexType.INT ? 1 : 0;
        bindIndexBuffer(indexBuffer, indexFormat);
    }

    private static void bindIndexBuffer(int buffer, int format) {
        WebGpu.begin(5);
        WebGpu.put(WebGpu.SET_INDEX_BUFFER);
        WebGpu.put(buffer);
        WebGpu.put(format);
        WebGpu.put(0);
        WebGpu.put(-1);
    }

    /** Binds the pipeline variant, bind group and push constants the next draw needs. */
    private void prepare() {
        if (pipeline == null) {
            throw new IllegalStateException("No pipeline set");
        }
        long mask = 0;
        for (int i = 0; i < uniforms.length; i++) {
            WebGpuRenderPipeline.SamplerInfo s = pipeline.samplers[i];
            if (s != null && s.sampleKind() == WebGpuEnums.SAMPLE_FLOAT && uniforms[i] instanceof TextureViewAndSampler tv
                    && !WebGpuEnums.filterable(tv.view().texture().getFormat())) {
                mask |= 1L << i;
            }
        }
        WebGpuRenderPipeline.Variant v = pipeline.variant(colorFormats, depthFormat, mask);
        if (v.pipeline() != boundPipeline) {
            boundPipeline = v.pipeline();
            WebGpu.begin(2);
            WebGpu.put(WebGpu.SET_PIPELINE);
            WebGpu.put(v.pipeline());
            bindingsDirty = true;
            pcDirty = true;
        }
        if (bindingsDirty) {
            int bindGroup = device.bindGroup(v.layout0(), entries(mask));
            WebGpu.begin(4);
            WebGpu.put(WebGpu.SET_BIND_GROUP);
            WebGpu.put(0);
            WebGpu.put(bindGroup);
            WebGpu.put(0);
            bindingsDirty = false;
        }
        if (pipeline.hasPushConstants() && pcDirty && pcOffset >= 0) {
            int pcGroup = device.pushConstantBindGroup();
            WebGpu.begin(5);
            WebGpu.put(WebGpu.SET_BIND_GROUP);
            WebGpu.put(1);
            WebGpu.put(pcGroup);
            WebGpu.put(1);
            WebGpu.put(pcOffset);
            pcDirty = false;
        }
    }

    private int[][] entries(long unfilterableMask) {
        int count = 0;
        for (int i = 0; i < uniforms.length; i++) {
            count += pipeline.uniformType(i) == com.mojang.renderpearl.api.pipeline.UniformType.COMBINED_IMAGE_SAMPLER ? 2 : 1;
        }
        int[][] e = new int[count][];
        int n = 0;
        for (int i = 0; i < uniforms.length; i++) {
            Object value = uniforms[i];
            if (value == null) {
                throw new IllegalStateException("Missing uniform " + pipeline.uniforms.get(i).name() + " for " + pipeline.name);
            }
            switch (pipeline.uniformType(i)) {
                case UNIFORM_BUFFER -> {
                    GpuBufferSlice s = (GpuBufferSlice) value;
                    WebGpuBuffer b = (WebGpuBuffer) s.buffer();
                    long size = Math.max(s.length(), pipeline.minBindingSize[i]);
                    size = Math.min((size + 3) & ~3L, ((b.size() + 3) & ~3L) - s.offset());
                    e[n++] = new int[] {2 * i, 0, b.id, (int) s.offset(), (int) size};
                }
                case TEXEL_BUFFER -> {
                    GpuBufferSlice s = (GpuBufferSlice) value;
                    if ((s.offset() & 255) != 0 && !warnedTexelOffset) {
                        warnedTexelOffset = true;
                        LOGGER.warn("Texel buffer {} bound at offset {} (WebGPU needs 256-byte alignment)",
                                pipeline.uniforms.get(i).name(), s.offset());
                    }
                    e[n++] = new int[] {2 * i, 0, ((WebGpuBuffer) s.buffer()).id, (int) s.offset(), (int) ((s.length() + 3) & ~3L)};
                }
                case COMBINED_IMAGE_SAMPLER -> {
                    TextureViewAndSampler tv = (TextureViewAndSampler) value;
                    WebGpuSampler sampler = (WebGpuSampler) tv.sampler();
                    if ((unfilterableMask & (1L << i)) != 0) {
                        sampler = sampler.nonFiltering();
                    }
                    e[n++] = new int[] {2 * i, 1, ((WebGpuTextureView) tv.view()).id, 0, 0};
                    e[n++] = new int[] {2 * i + 1, 2, sampler.id, 0, 0};
                }
            }
        }
        return e;
    }

    @Override
    public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance) {
        prepare();
        if (pipeline.isFan() && !warnedIndexedFan) {
            warnedIndexedFan = true;
            LOGGER.warn("Indexed triangle fan in {} drawn as a triangle list", pipeline.name);
        }
        WebGpu.begin(6);
        WebGpu.put(WebGpu.DRAW_INDEXED);
        WebGpu.put(indexCount);
        WebGpu.put(instanceCount);
        WebGpu.put(firstIndex);
        WebGpu.put(vertexOffset);
        WebGpu.put(firstInstance);
    }

    @Override
    public void multiDrawIndexed(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
        prepare();
        WebGpu.begin(6);
        WebGpu.put(WebGpu.MULTI_DRAW_INTERLEAVED);
        WebGpu.put(drawCount);
        WebGpu.putAddr(MemoryUtil.memAddress(drawParameters));
        WebGpu.put(instanceCount);
        WebGpu.put(firstInstance);
        WebGpu.put(1);
        WebGpu.flush(); // the parameters belong to the caller
    }

    @Override
    public void multiDrawIndexed(PointerBuffer firstIndexOffsets, IntBuffer indexCounts, IntBuffer vertexOffsets, int drawCount) {
        prepare();
        WebGpu.begin(5);
        WebGpu.put(WebGpu.MULTI_DRAW_SEPARATE);
        WebGpu.put(drawCount);
        WebGpu.putAddr(MemoryUtil.memAddress(firstIndexOffsets));
        WebGpu.putAddr(MemoryUtil.memAddress(indexCounts));
        WebGpu.putAddr(MemoryUtil.memAddress(vertexOffsets));
        WebGpu.flush(); // the arrays belong to the caller
    }

    @Override
    public void drawIndexedIndirect(GpuBufferSlice commands, int drawCount) {
        prepare();
        WebGpu.begin(5);
        WebGpu.put(WebGpu.DRAW_INDEXED_INDIRECT);
        WebGpu.put(((WebGpuBuffer) commands.buffer()).id);
        WebGpu.put((int) commands.offset());
        WebGpu.put(drawCount);
        WebGpu.put(20);
    }

    @Override
    public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
        prepare();
        if (pipeline.isFan()) {
            int triangles = vertexCount - 2;
            if (triangles <= 0) {
                return;
            }
            bindIndexBuffer(device.fanIndexBuffer(triangles), 1);
            WebGpu.begin(6);
            WebGpu.put(WebGpu.DRAW_INDEXED);
            WebGpu.put(triangles * 3);
            WebGpu.put(instanceCount);
            WebGpu.put(0);
            WebGpu.put(firstVertex);
            WebGpu.put(firstInstance);
            if (indexBuffer != 0) {
                bindIndexBuffer(indexBuffer, indexFormat);
            }
            return;
        }
        WebGpu.begin(5);
        WebGpu.put(WebGpu.DRAW);
        WebGpu.put(vertexCount);
        WebGpu.put(instanceCount);
        WebGpu.put(firstVertex);
        WebGpu.put(firstInstance);
    }

    @Override
    public void multiDraw(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
        prepare();
        WebGpu.begin(6);
        WebGpu.put(WebGpu.MULTI_DRAW_INTERLEAVED);
        WebGpu.put(drawCount);
        WebGpu.putAddr(MemoryUtil.memAddress(drawParameters));
        WebGpu.put(instanceCount);
        WebGpu.put(firstInstance);
        WebGpu.put(0);
        WebGpu.flush();
    }

    @Override
    public void multiDraw(IntBuffer firstVertices, IntBuffer vertexCounts, int drawCount) {
        for (int i = 0; i < drawCount; i++) {
            draw(vertexCounts.get(vertexCounts.position() + i), 1, firstVertices.get(firstVertices.position() + i), 0);
        }
    }

    @Override
    public void drawIndirect(GpuBufferSlice commands, int drawCount) {
        prepare();
        WebGpu.begin(5);
        WebGpu.put(WebGpu.DRAW_INDIRECT);
        WebGpu.put(((WebGpuBuffer) commands.buffer()).id);
        WebGpu.put((int) commands.offset());
        WebGpu.put(drawCount);
        WebGpu.put(16);
    }

    @Override
    public void writeTimestamp(GpuQueryPool pool, int index) {
    }
}
