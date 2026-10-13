package dev.mcwebclient.web.gpu;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.RenderPassBackend;

import dev.mcwebclient.web.mem.Nmem;
import org.joml.Vector4fc;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

/**
 * One GPUCommandEncoder per submit, like the Vulkan backend's command buffer. Uploads
 * go through transient staging memory and encoder copies, so they stay ordered with
 * the passes around them.
 */
final class WebGpuCommandEncoder implements CommandEncoderBackend {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final WebGpuDevice device;
    private final WebGpuTransientMemory transientMemory = new WebGpuTransientMemory();
    private WebGpuRenderPass currentPass;
    private boolean warnedUnaligned;

    WebGpuCommandEncoder(WebGpuDevice device) {
        this.device = device;
    }

    @Override
    public void submit() {
        if (currentPass != null) {
            throw new IllegalStateException("Cannot submit with an open render pass");
        }
        transientMemory.flushWrites();
        device.beforeSubmit();
        WebGpu.begin(1);
        WebGpu.put(WebGpu.SUBMIT);
        WebGpu.flush();
        transientMemory.rotate();
        device.afterSubmit();
    }

    @Override
    public TransientMemory transientMemory() {
        return transientMemory;
    }

    // ---------------------------------------------------------------- passes

    @Override
    public RenderPassBackend createRenderPass(RenderPassDescriptor descriptor) {
        List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colors = descriptor.colorAttachments();
        GpuFormat[] colorFormats = new GpuFormat[colors.size()];
        int width = 0;
        int height = 0;
        // Attachment views may be created on first use: do that before writing this command.
        int[] colorViews = new int[colors.size()];
        for (int i = 0; i < colors.size(); i++) {
            RenderPassDescriptor.Attachment<Optional<Vector4fc>> a = colors.get(i);
            if (a != null) {
                WebGpuTextureView view = (WebGpuTextureView) a.textureView();
                colorViews[i] = view.webTexture().attachmentView(view.baseMipLevel());
            }
        }
        RenderPassDescriptor.Attachment<OptionalDouble> depth = descriptor.depthAttachment();
        int depthView = 0;
        if (depth != null) {
            WebGpuTextureView view = (WebGpuTextureView) depth.textureView();
            depthView = view.webTexture().attachmentView(view.baseMipLevel());
        }
        WebGpu.begin(14 + colors.size() * 6);
        WebGpu.put(WebGpu.BEGIN_PASS);
        WebGpu.put(colors.size());
        for (int i = 0; i < colors.size(); i++) {
            RenderPassDescriptor.Attachment<Optional<Vector4fc>> a = colors.get(i);
            if (a == null) {
                WebGpu.put(-1);
                WebGpu.put(0);
                for (int k = 0; k < 4; k++) {
                    WebGpu.putF(0f);
                }
                continue;
            }
            WebGpuTextureView view = (WebGpuTextureView) a.textureView();
            colorFormats[i] = view.texture().getFormat();
            width = view.getWidth(0);
            height = view.getHeight(0);
            WebGpu.put(colorViews[i]);
            Optional<Vector4fc> clear = a.clearValue();
            WebGpu.put(clear.isPresent() ? 1 : 0);
            Vector4fc c = clear.orElse(null);
            WebGpu.putF(c == null ? 0f : c.x());
            WebGpu.putF(c == null ? 0f : c.y());
            WebGpu.putF(c == null ? 0f : c.z());
            WebGpu.putF(c == null ? 0f : c.w());
        }
        GpuFormat depthFormat = null;
        if (depth != null) {
            WebGpuTextureView view = (WebGpuTextureView) depth.textureView();
            depthFormat = view.texture().getFormat();
            if (width == 0) {
                width = view.getWidth(0);
                height = view.getHeight(0);
            }
            WebGpu.put(depthView);
            WebGpu.put(depth.clearValue().isPresent() ? 1 : 0);
            WebGpu.putF((float) depth.clearValue().orElse(0.0));
            WebGpu.put(depthFormat.hasStencilAspect() ? 1 : 0);
        } else {
            WebGpu.put(-1);
            WebGpu.put(0);
            WebGpu.putF(0f);
            WebGpu.put(0);
        }
        var area = descriptor.renderArea();
        WebGpu.put(width);
        WebGpu.put(height);
        WebGpu.put(area.x());
        WebGpu.put(area.y());
        WebGpu.put(area.width());
        WebGpu.put(area.height());
        currentPass = new WebGpuRenderPass(device, colorFormats, depthFormat, width, height, area);
        return currentPass;
    }

    @Override
    public void submitRenderPass() {
        if (currentPass == null) {
            throw new IllegalStateException("Cannot submit a renderpass if one hasn't been started!");
        }
        currentPass.end();
        currentPass = null;
        WebGpu.begin(1);
        WebGpu.put(WebGpu.END_PASS);
    }

    /** A pass that only clears (loadOp clear) the given mip of a color and/or depth texture. */
    private void clearPass(WebGpuTexture color, Vector4fc clearColor, WebGpuTexture depth, double clearDepth, int mip) {
        int colorView = color != null ? color.attachmentView(mip) : -1;
        int depthView = depth != null ? depth.attachmentView(mip) : -1;
        WebGpu.begin(24);
        WebGpu.put(WebGpu.BEGIN_PASS);
        int w;
        int h;
        if (color != null) {
            WebGpu.put(1);
            WebGpu.put(colorView);
            WebGpu.put(1);
            WebGpu.putF(clearColor.x());
            WebGpu.putF(clearColor.y());
            WebGpu.putF(clearColor.z());
            WebGpu.putF(clearColor.w());
            w = color.getWidth(mip);
            h = color.getHeight(mip);
        } else {
            WebGpu.put(0);
            w = depth.getWidth(mip);
            h = depth.getHeight(mip);
        }
        if (depth != null) {
            WebGpu.put(depthView);
            WebGpu.put(1);
            WebGpu.putF((float) clearDepth);
            WebGpu.put(depth.getFormat().hasStencilAspect() ? 1 : 0);
        } else {
            WebGpu.put(-1);
            WebGpu.put(0);
            WebGpu.putF(0f);
            WebGpu.put(0);
        }
        WebGpu.put(Math.max(1, w));
        WebGpu.put(Math.max(1, h));
        WebGpu.put(0);
        WebGpu.put(0);
        WebGpu.put(Math.max(1, w));
        WebGpu.put(Math.max(1, h));
        WebGpu.put(WebGpu.END_PASS);
    }

    @Override
    public void clearColorTexture(GpuTexture colorTexture, Vector4fc clearColor) {
        WebGpuTexture t = (WebGpuTexture) colorTexture;
        for (int mip = 0; mip < t.getMipLevels(); mip++) {
            clearPass(t, clearColor, null, 0, mip);
        }
    }

    @Override
    public void clearColorAndDepthTextures(GpuTexture colorTexture, Vector4fc clearColor, GpuTexture depthTexture, double clearDepth) {
        WebGpuTexture c = (WebGpuTexture) colorTexture;
        for (int mip = 0; mip < c.getMipLevels(); mip++) {
            clearPass(c, clearColor, (WebGpuTexture) depthTexture, clearDepth, mip);
        }
    }

    @Override
    public void clearColorAndDepthTextures(GpuTexture colorTexture, Vector4fc clearColor, GpuTexture depthTexture, double clearDepth,
            int regionX, int regionY, int regionWidth, int regionHeight, int mipLevel) {
        WebGpuTexture c = (WebGpuTexture) colorTexture;
        WebGpuTexture d = (WebGpuTexture) depthTexture;
        int colorView = c.attachmentView(mipLevel);
        int depthView = d.attachmentView(mipLevel);
        WebGpu.begin(16);
        WebGpu.put(WebGpu.CLEAR_REGION);
        WebGpu.put(colorView);
        WebGpu.put(WebGpuEnums.format(c.getFormat()));
        WebGpu.put(depthView);
        WebGpu.put(WebGpuEnums.format(d.getFormat()));
        WebGpu.put(regionX);
        WebGpu.put(regionY);
        WebGpu.put(regionWidth);
        WebGpu.put(regionHeight);
        WebGpu.put(c.getWidth(mipLevel));
        WebGpu.put(c.getHeight(mipLevel));
        WebGpu.putF(clearColor.x());
        WebGpu.putF(clearColor.y());
        WebGpu.putF(clearColor.z());
        WebGpu.putF(clearColor.w());
        WebGpu.putF((float) clearDepth);
    }

    @Override
    public void clearDepthTexture(GpuTexture depthTexture, double clearDepth) {
        WebGpuTexture d = (WebGpuTexture) depthTexture;
        for (int mip = 0; mip < d.getMipLevels(); mip++) {
            clearPass(null, null, d, clearDepth, mip);
        }
    }

    // ---------------------------------------------------------------- copies

    @Override
    public void writeToBuffer(GpuBufferSlice destination, ByteBuffer data) {
        int size = data.remaining();
        if (size == 0) {
            return;
        }
        WebGpuBuffer dst = (WebGpuBuffer) destination.buffer();
        long dstOffset = destination.offset();
        if ((dstOffset & 3) != 0 || (size & 3) != 0) {
            if (!warnedUnaligned) {
                warnedUnaligned = true;
                LOGGER.warn("Unaligned buffer write ({} bytes at {}): uploaded out of command order", size, dstOffset);
            }
            dst.upload(dstOffset, MemoryUtil.memAddress(data), size);
            WebGpu.flush();
            return;
        }
        GpuBufferSlice staging = transientMemory.uploadStaging(data, 4L, GpuBuffer.USAGE_COPY_SRC);
        copy((WebGpuBuffer) staging.buffer(), staging.offset(), dst, dstOffset, size);
        if (dst.shadow() != 0) {
            Nmem.copy(MemoryUtil.memAddress(data), dst.shadow() + dstOffset, size);
        }
    }

    private void copy(WebGpuBuffer src, long srcOffset, WebGpuBuffer dst, long dstOffset, long size) {
        WebGpu.begin(6);
        WebGpu.put(WebGpu.COPY_B2B);
        WebGpu.put(src.id);
        WebGpu.put((int) srcOffset);
        WebGpu.put(dst.id);
        WebGpu.put((int) dstOffset);
        WebGpu.put((int) ((size + 3) & ~3L));
    }

    @Override
    public void copyToBuffer(GpuBufferSlice source, GpuBufferSlice target) {
        WebGpuBuffer src = (WebGpuBuffer) source.buffer();
        WebGpuBuffer dst = (WebGpuBuffer) target.buffer();
        copy(src, source.offset(), dst, target.offset(), source.length());
        if (dst.shadow() != 0 && (dst.usage() & GpuBuffer.USAGE_MAP_READ) != 0) {
            readbackBuffer(dst, target.offset(), target.length(), null);
        }
    }

    private void readbackBuffer(WebGpuBuffer buffer, long offset, long length, Runnable callback) {
        int request = WebGpu.registerReadback(callback == null ? () -> {} : callback);
        WebGpu.begin(6);
        WebGpu.put(WebGpu.READBACK_BUFFER);
        WebGpu.put(request);
        WebGpu.put(buffer.id);
        WebGpu.put((int) offset);
        WebGpu.put((int) length);
        WebGpu.putAddr(buffer.shadow() + offset);
    }

    @Override
    public void writeToTexture(GpuTexture destination, ByteBuffer source, int mipLevel, int depthOrLayer, int destX, int destY, int width, int height) {
        WebGpuTexture t = (WebGpuTexture) destination;
        int texel = t.getFormat().blockSize();
        long rowBytes = (long) width * texel;
        long padded = (rowBytes + 255) & ~255L;
        GpuBufferSlice.MappedView staging = transientMemory.allocateStaging(padded * height, 256L, GpuBuffer.USAGE_COPY_SRC);
        long src = MemoryUtil.memAddress(source);
        long dst = MemoryUtil.memAddress(staging.data());
        if (padded == rowBytes) {
            Nmem.copy(src, dst, rowBytes * height);
        } else {
            for (int row = 0; row < height; row++) {
                Nmem.copy(src + row * rowBytes, dst + row * padded, rowBytes);
            }
        }
        staging.close();
        copyBufferToTexture((WebGpuBuffer) staging.slice().buffer(), staging.slice().offset(), (int) padded, height,
                t, mipLevel, destX, destY, depthOrLayer, width, height);
    }

    private void copyBufferToTexture(WebGpuBuffer buffer, long offset, int bytesPerRow, int rowsPerImage, WebGpuTexture texture,
            int mip, int x, int y, int layer, int width, int height) {
        WebGpu.begin(13);
        WebGpu.put(WebGpu.COPY_B2T);
        WebGpu.put(buffer.id);
        WebGpu.put((int) offset);
        WebGpu.put(bytesPerRow);
        WebGpu.put(rowsPerImage);
        WebGpu.put(texture.id);
        WebGpu.put(mip);
        WebGpu.put(x);
        WebGpu.put(y);
        WebGpu.put(layer);
        WebGpu.put(width);
        WebGpu.put(height);
        WebGpu.put(1);
    }

    @Override
    public void copyBufferToTexture(GpuBufferSlice source, int sourceX, int sourceY, int sourceWidth, int sourceHeight, GpuTexture destination,
            int destinationX, int destinationY, int copyWidth, int copyHeight, int mipLevel, int arrayLayer) {
        WebGpuTexture t = (WebGpuTexture) destination;
        WebGpuBuffer b = (WebGpuBuffer) source.buffer();
        int texel = t.getFormat().blockSize();
        int bytesPerRow = sourceWidth * texel;
        long base = source.offset() + ((long) sourceX + (long) sourceY * sourceWidth) * texel;
        if ((bytesPerRow & 255) == 0) {
            copyBufferToTexture(b, base, bytesPerRow, sourceHeight, t, mipLevel, destinationX, destinationY, arrayLayer, copyWidth, copyHeight);
            return;
        }
        // WebGPU needs a 256-byte row pitch for multi-row copies: copy row by row (a single
        // row only needs bytesPerRow >= its length, rounded to 256).
        int padded = (bytesPerRow + 255) & ~255;
        for (int row = 0; row < copyHeight; row++) {
            copyBufferToTexture(b, base + (long) row * bytesPerRow, padded, 1, t, mipLevel, destinationX, destinationY + row, arrayLayer, copyWidth, 1);
        }
    }

    @Override
    public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback, int mipLevel) {
        copyTextureToBuffer(source, destination, offset, callback, mipLevel, 0, 0, source.getWidth(mipLevel), source.getHeight(mipLevel));
    }

    @Override
    public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback, int mipLevel,
            int x, int y, int width, int height) {
        WebGpuTexture t = (WebGpuTexture) source;
        WebGpuBuffer b = (WebGpuBuffer) destination;
        if (b.shadow() == 0) {
            LOGGER.warn("copyTextureToBuffer into a buffer without MAP_READ; data is not observable");
            device.afterNextSubmit(callback);
            return;
        }
        int request = WebGpu.registerReadback(callback);
        WebGpu.begin(11);
        WebGpu.put(WebGpu.READBACK_TEXTURE);
        WebGpu.put(request);
        WebGpu.put(t.id);
        WebGpu.put(mipLevel);
        WebGpu.put(x);
        WebGpu.put(y);
        WebGpu.put(width);
        WebGpu.put(height);
        WebGpu.put(t.getFormat().blockSize());
        WebGpu.putAddr(b.shadow() + offset);
        WebGpu.put(t.getFormat().hasDepthAspect() ? WebGpuEnums.ASPECT_DEPTH : WebGpuEnums.ASPECT_ALL);
    }

    @Override
    public void copyTextureToTexture(GpuTexture source, GpuTexture destination, int mipLevel, int destX, int destY, int sourceX, int sourceY,
            int width, int height) {
        WebGpu.begin(10);
        WebGpu.put(WebGpu.COPY_T2T);
        WebGpu.put(((WebGpuTexture) source).id);
        WebGpu.put(((WebGpuTexture) destination).id);
        WebGpu.put(mipLevel);
        WebGpu.put(sourceX);
        WebGpu.put(sourceY);
        WebGpu.put(destX);
        WebGpu.put(destY);
        WebGpu.put(width);
        WebGpu.put(height);
    }

    /** WebGPU queue operations run in order, so by the time anything waits, earlier work is done. */
    @Override
    public GpuFence createFence() {
        return new GpuFence() {
            @Override
            public void close() {
            }

            @Override
            public boolean awaitCompletion(long timeoutNS) {
                return true;
            }
        };
    }

    @Override
    public void writeTimestamp(GpuQueryPool pool, int index) {
    }
}
