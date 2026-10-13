package dev.mcwebclient.selftest;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import com.mojang.renderpearl.frontend.shaders.GlslCompiler;
import com.mojang.renderpearl.frontend.shaders.PipelineBuilder;
import com.mojang.renderpearl.util.ShaderCompileException;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;
import org.lwjgl.util.shaderc.ShadercIncludeResult;

/**
 * Desktop-only test corpus for the browser shader path (-Dmcweb.shaderCorpus=dir).
 *
 * <p>Wraps the running renderpearl frontend so that every pipeline compile records
 * (1) the GLSL inputs given to shaderc (source, defines, includes) and (2) exactly what
 * the backend receives: binding-patched SPIR-V and the pipeline description. The web
 * backend's GLSL and SPIR-V-to-WGSL steps are tested against this offline.</p>
 */
final class ShaderCorpus {
    private static final Pattern INCLUDE = Pattern.compile("^[ \\t]*#include\\s*[<\"]([^>\"]+)[>\"]", Pattern.MULTILINE);

    private static Path root;
    private static int pipelines;
    private static int sources;

    private ShaderCorpus() {
    }

    static boolean enabled() {
        return System.getProperty("mcweb.shaderCorpus") != null;
    }

    /** Installs the recorders; pipelines compiled from now on are captured. */
    static void install() throws ReflectiveOperationException, IOException {
        root = Path.of(System.getProperty("mcweb.shaderCorpus"));
        Files.createDirectories(root.resolve("pipelines"));
        Files.createDirectories(root.resolve("glsl"));
        FrontendGpuDevice device = (FrontendGpuDevice) RenderSystem.getDevice();
        PipelineBuilder builder = (PipelineBuilder) field(FrontendGpuDevice.class, "pipelineBuilder").get(device);

        Field backendField = field(PipelineBuilder.class, "backendDevice");
        GpuDeviceBackend backend = (GpuDeviceBackend) backendField.get(builder);
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getName().equals("compilePipeline") && args != null && args.length == 1
                    && args[0] instanceof BackendRenderPipeline.CreateInfo info) {
                record(info);
            }
            try {
                return method.invoke(backend, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        };
        backendField.set(builder, Proxy.newProxyInstance(GpuDeviceBackend.class.getClassLoader(),
                new Class<?>[] {GpuDeviceBackend.class}, handler));

        Field compilerField = field(PipelineBuilder.class, "compiler");
        GlslCompiler original = (GlslCompiler) compilerField.get(builder);
        boolean zeroToOne = field(GlslCompiler.class, "isZeroToOne").getBoolean(original);
        boolean drawParams = field(GlslCompiler.class, "shaderDrawParameters").getBoolean(original);
        compilerField.set(builder, new GlslCompiler(zeroToOne, drawParams) {
            @Override
            public SpvModule compileToSpv(String name, String source, ShaderType type, ShaderDefines defines,
                    ShaderSource shaderSource) throws ShaderCompileException {
                recordGlsl(name, source, type, defines, shaderSource, zeroToOne, drawParams);
                return super.compileToSpv(name, source, type, defines, shaderSource);
            }
        });
    }

    static String summary() {
        return pipelines + " pipelines, " + sources + " GLSL compiles -> " + root;
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static String safe(String s) {
        return s.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static synchronized void record(BackendRenderPipeline.CreateInfo info) {
        try {
            Path dir = root.resolve("pipelines").resolve(String.format("%04d-%s", pipelines++, safe(info.name())));
            Files.createDirectories(dir);
            StringBuilder sb = new StringBuilder("{\n");
            kv(sb, "name", info.name()).append(",\n");
            sb.append("  \"shaders\": [");
            int i = 0;
            for (BackendRenderPipeline.CreateInfo.Shader s : info.shaders()) {
                String file = s.module().type().name().toLowerCase() + ".spv";
                ByteBuffer spv = s.module().spv().duplicate();
                byte[] bytes = new byte[spv.remaining()];
                spv.get(bytes);
                Files.write(dir.resolve(file), bytes);
                sb.append(i++ == 0 ? "\n" : ",\n").append("    {");
                kv(sb, "name", s.name()).append(", ");
                kv(sb, "entryPoint", s.entryPoint()).append(", ");
                kv(sb, "type", s.module().type().name()).append(", ");
                kv(sb, "spv", file).append("}");
            }
            sb.append("\n  ],\n  \"vertexBuffers\": [");
            i = 0;
            for (BackendRenderPipeline.CreateInfo.VertexBuffer vb : info.vertexBuffers()) {
                sb.append(i++ == 0 ? "" : ", ").append("{\"slot\": ").append(vb.bufferSlot()).append(", \"stride\": ")
                        .append(vb.stride()).append(", \"stepRate\": ").append(vb.stepRate()).append("}");
            }
            sb.append("],\n  \"attribs\": [");
            i = 0;
            for (BackendRenderPipeline.CreateInfo.AttribBinding a : info.attribBindings()) {
                sb.append(i++ == 0 ? "" : ", ").append("{\"slot\": ").append(a.bufferSlot()).append(", \"location\": ")
                        .append(a.location()).append(", \"offset\": ").append(a.offset()).append(", ");
                kv(sb, "format", a.format().name()).append("}");
            }
            sb.append("],\n  \"uniforms\": [");
            i = 0;
            for (BindGroupLayout.UniformDescription u : info.uniforms()) {
                sb.append(i++ == 0 ? "" : ", ").append("{");
                kv(sb, "name", u.name()).append(", ");
                kv(sb, "type", u.type().name()).append(", ");
                kv(sb, "format", u.gpuFormat() == null ? null : u.gpuFormat().name()).append("}");
            }
            sb.append("],\n  \"pushConstantsSize\": ").append(info.pushConstantsSize()).append(",\n");
            var ds = info.depthStencilState();
            sb.append("  \"depthStencil\": ").append(ds == null ? "null"
                    : "{\"depthTest\": \"" + ds.depthTest() + "\", \"writeDepth\": " + ds.writeDepth()
                            + ", \"biasScale\": " + ds.depthBiasScaleFactor() + ", \"biasConstant\": " + ds.depthBiasConstant() + "}")
                    .append(",\n");
            kv(sb, "polygonMode", info.polygonMode().name()).append(",\n");
            sb.append("  \"cull\": ").append(info.cull()).append(",\n");
            sb.append("  \"colorTargets\": [");
            i = 0;
            for (ColorTargetState c : info.colorTargetStates()) {
                sb.append(i++ == 0 ? "" : ", ");
                if (c == null) {
                    sb.append("null");
                } else {
                    sb.append("{");
                    kv(sb, "blend", c.blendFunction().map(Object::toString).orElse(null)).append(", ");
                    kv(sb, "format", c.format().name()).append(", \"writeMask\": ").append(c.writeMask()).append("}");
                }
            }
            sb.append("],\n");
            kv(sb, "topology", info.primitiveTopology().name()).append("\n}\n");
            Files.writeString(dir.resolve("pipeline.json"), sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static synchronized void recordGlsl(String name, String source, ShaderType type, ShaderDefines defines,
            ShaderSource shaderSource, boolean zeroToOne, boolean drawParams) {
        try {
            Path dir = root.resolve("glsl").resolve(String.format("%04d-%s-%s", sources++, safe(name), type.name().toLowerCase()));
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("source.glsl"), source, StandardCharsets.UTF_8);
            Map<String, String> includes = new LinkedHashMap<>();
            collectIncludes(source, shaderSource, includes, new LinkedHashSet<>());
            StringBuilder sb = new StringBuilder("{\n");
            kv(sb, "name", name).append(",\n");
            kv(sb, "type", type.name()).append(",\n");
            sb.append("  \"zeroToOne\": ").append(zeroToOne).append(", \"shaderDrawParameters\": ").append(drawParams).append(",\n");
            sb.append("  \"defines\": {");
            int i = 0;
            for (Map.Entry<String, String> e : defines.values().entrySet()) {
                sb.append(i++ == 0 ? "" : ", ");
                kv(sb, e.getKey(), e.getValue());
            }
            sb.append("},\n  \"flags\": [");
            i = 0;
            for (String f : defines.flags()) {
                sb.append(i++ == 0 ? "" : ", ").append(quote(f));
            }
            sb.append("],\n  \"includes\": {");
            i = 0;
            for (Map.Entry<String, String> e : includes.entrySet()) {
                sb.append(i++ == 0 ? "\n    " : ",\n    ");
                kv(sb, e.getKey(), e.getValue());
            }
            sb.append("\n  }\n}\n");
            Files.writeString(dir.resolve("inputs.json"), sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void collectIncludes(String source, ShaderSource shaderSource, Map<String, String> out, Set<String> seen) {
        Matcher m = INCLUDE.matcher(source);
        while (m.find()) {
            String requested = m.group(1);
            if (!seen.add(requested)) {
                continue;
            }
            Identifier id = Identifier.tryParse(requested);
            ShaderSource.CachedIncludeSource inc = id == null ? null : shaderSource.getInclude(id);
            if (inc == null) {
                out.put(requested, null);
                continue;
            }
            String content = ShadercIncludeResult.create(inc.includeResultPtr()).contentString();
            out.put(requested, content);
            collectIncludes(content, shaderSource, out, seen);
        }
    }

    private static StringBuilder kv(StringBuilder sb, String k, String v) {
        return sb.append(quote(k)).append(": ").append(v == null ? "null" : quote(v));
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c < 0x20 ? String.format("\\u%04x", (int) c) : String.valueOf(c));
            }
        }
        return sb.append('"').toString();
    }

}
