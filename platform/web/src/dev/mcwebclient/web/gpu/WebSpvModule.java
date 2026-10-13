package dev.mcwebclient.web.gpu;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.util.ShaderCompileException;

import dev.mcwebclient.web.gpu.spirv.SpirvModule;
import dev.mcwebclient.web.gpu.spirv.SpirvReflection;
import org.jspecify.annotations.Nullable;

/**
 * A compiled shader on the web: SPIR-V words plus reflection computed in Java (instead
 * of SPIRV-Cross). The frontend's binding/location patches write straight into
 * {@link #words()}, which the WGSL translator then reads.
 */
public final class WebSpvModule implements SpvModule {
    private final int[] words;
    private final ShaderType type;
    private @Nullable SpirvReflection reflection;

    public WebSpvModule(int[] words, ShaderType type) {
        this.words = words;
        this.type = type;
    }

    public int[] words() {
        return words;
    }

    @Override
    public void close() {
    }

    @Override
    public ByteBuffer spv() {
        ByteBuffer b = ByteBuffer.allocate(words.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        b.asIntBuffer().put(words);
        return b;
    }

    @Override
    public ShaderType type() {
        return type;
    }

    @Override
    public SpvModule.Reflection reflect() throws ShaderCompileException {
        if (reflection == null) {
            reflection = new SpirvReflection(new SpirvModule(words));
        }
        return reflection;
    }

    @Override
    public SpvModule.@Nullable Reflection getReflectionInfoIfAvailable() {
        return reflection;
    }
}
