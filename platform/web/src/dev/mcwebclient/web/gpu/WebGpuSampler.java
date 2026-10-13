package dev.mcwebclient.web.gpu;

import java.util.OptionalDouble;

import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;

/**
 * A GPUSampler. Mipmap filtering follows the Vulkan backend (linear unless maxLod
 * pins level 0). {@link #nonFiltering()} is a nearest-only twin, needed when the bound
 * texture can't be filtered (depth, 32-bit float without float32-filterable).
 */
final class WebGpuSampler implements GpuSampler {
    final int id;
    private final AddressMode addressModeU;
    private final AddressMode addressModeV;
    private final FilterMode minFilter;
    private final FilterMode magFilter;
    private final int maxAnisotropy;
    private final OptionalDouble maxLod;
    private final boolean filtering;
    private WebGpuSampler nonFilteringTwin;
    private boolean closed;

    WebGpuSampler(AddressMode u, AddressMode v, FilterMode minFilter, FilterMode magFilter, int maxAnisotropy, OptionalDouble maxLod) {
        this(u, v, minFilter, magFilter, maxAnisotropy, maxLod, false);
    }

    private WebGpuSampler(AddressMode u, AddressMode v, FilterMode minFilter, FilterMode magFilter, int maxAnisotropy,
            OptionalDouble maxLod, boolean forceNearest) {
        this.addressModeU = u;
        this.addressModeV = v;
        this.minFilter = minFilter;
        this.magFilter = magFilter;
        this.maxAnisotropy = maxAnisotropy;
        this.maxLod = maxLod;
        this.id = WebGpu.newId();
        double lod = maxLod.orElse(1000.0);
        boolean mipLinear = lod > 0.25;
        int min = forceNearest ? 0 : minFilter.ordinal();
        int mag = forceNearest ? 0 : magFilter.ordinal();
        int mip = forceNearest ? 0 : (mipLinear ? 1 : 0);
        this.filtering = min == 1 || mag == 1 || mip == 1;
        WebGpu.begin(10);
        WebGpu.put(WebGpu.CREATE_SAMPLER);
        WebGpu.put(id);
        WebGpu.put(address(u));
        WebGpu.put(address(v));
        WebGpu.put(min);
        WebGpu.put(mag);
        WebGpu.put(mip);
        WebGpu.put(forceNearest ? 1 : Math.max(1, Math.min(16, maxAnisotropy)));
        WebGpu.putF((float) Math.max(0.25, Math.min(lod, 32.0)));
        WebGpu.put(-1);
    }

    private static int address(AddressMode m) {
        return m == AddressMode.REPEAT ? 1 : 0;
    }

    boolean filtering() {
        return filtering;
    }

    WebGpuSampler nonFiltering() {
        if (!filtering) {
            return this;
        }
        if (nonFilteringTwin == null) {
            nonFilteringTwin = new WebGpuSampler(addressModeU, addressModeV, minFilter, magFilter, maxAnisotropy, maxLod, true);
        }
        return nonFilteringTwin;
    }

    @Override
    public AddressMode getAddressModeU() {
        return addressModeU;
    }

    @Override
    public AddressMode getAddressModeV() {
        return addressModeV;
    }

    @Override
    public FilterMode getMinFilter() {
        return minFilter;
    }

    @Override
    public FilterMode getMagFilter() {
        return magFilter;
    }

    @Override
    public int getMaxAnisotropy() {
        return maxAnisotropy;
    }

    @Override
    public OptionalDouble getMaxLod() {
        return maxLod;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            WebGpuDevice.current().releaseBindings(id);
            WebGpu.destroy(id);
            if (nonFilteringTwin != null) {
                nonFilteringTwin.close();
            }
        }
    }
}
