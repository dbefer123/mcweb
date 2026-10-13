package dev.mcwebclient.web.gpu;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.util.ShaderCompileException;
import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

import dev.mcwebclient.web.mem.NativeHeap;
import dev.mcwebclient.web.mem.Nmem;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;
import org.graalvm.webimage.api.JS;
import org.lwjgl.util.shaderc.ShadercIncludeResult;

/**
 * GLSL → SPIR-V with glslang compiled to WebAssembly (tools/glslang-wasm) instead of
 * shaderc's native library, with the same settings renderpearl gives shaderc.
 */
final class ShaderSubstitutions {
    private ShaderSubstitutions() {
    }

    private static final Pattern INCLUDE = Pattern.compile("^[ \\t]*#[ \\t]*include[ \\t]*[<\"]([^>\"]+)[>\"].*$", Pattern.MULTILINE);

    /** Inlines {@code #include}s the way shaderc's include callback resolves them (ids via ShaderSource). */
    static String expandIncludes(String source, ShaderSource shaderSource, Set<String> stack, int depth) {
        if (depth > 32) {
            return "#error include depth limit\n";
        }
        Matcher m = INCLUDE.matcher(source);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String requested = m.group(1);
            String replacement;
            Identifier id = Identifier.tryParse(requested);
            ShaderSource.CachedIncludeSource inc = id == null ? null : shaderSource.getInclude(id);
            String content = null;
            if (inc != null) {
                ShadercIncludeResult r = ShadercIncludeResult.create(inc.includeResultPtr());
                if (!r.source_nameString().isEmpty()) {
                    content = r.contentString();
                }
            }
            if (content == null) {
                replacement = "#error include not found: " + requested;
            } else if (stack.contains(requested)) {
                replacement = ""; // recursive include; the guards would make it empty anyway
            } else {
                stack.add(requested);
                replacement = expandIncludes(content, shaderSource, stack, depth + 1);
                stack.remove(requested);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    static int[] compile(String name, String source, ShaderType type, String preamble) throws ShaderCompileException {
        int stage = type == ShaderType.FRAGMENT ? 4 : 0;
        int count = glslCompile(stage, source, preamble);
        if (count < 0) {
            throw new ShaderCompileException("Couldn't parse GLSL (" + name + "): " + glslLog());
        }
        long tmp = NativeHeap.malloc(Math.max(4, count * 4L));
        try {
            glslTake((int) tmp);
            int[] words = new int[count];
            Nmem.copyToInts(tmp, words, 0, count);
            return words;
        } finally {
            NativeHeap.free(tmp);
        }
    }

    @JS.Coerce
    @JS(value = "return globalThis.mcweb.glsl.compile(stage, source, preamble);", args = {"stage", "source", "preamble"})
    private static native int glslCompile(int stage, String source, String preamble);

    @JS.Coerce
    @JS(value = "globalThis.mcweb.glsl.take(getExport('nmem'), addr >>> 0);", args = {"addr"})
    private static native void glslTake(int addr);

    @JS.Coerce
    @JS(value = "return globalThis.mcweb.glsl.lastLog();", args = {})
    private static native String glslLog();
}

@TargetClass(com.mojang.renderpearl.frontend.shaders.GlslCompiler.class)
final class Target_com_mojang_renderpearl_frontend_shaders_GlslCompiler {
    @Alias private boolean isZeroToOne;
    @Alias private boolean shaderDrawParameters;

    @Substitute
    public SpvModule compileToSpv(String name, String source, ShaderType type, ShaderDefines shaderDefines, ShaderSource shaderSource)
            throws ShaderCompileException {
        StringBuilder preamble = new StringBuilder();
        if (isZeroToOne) {
            preamble.append("#define RENDERPEARL_DEPTH_IS_ZERO_TO_ONE\n");
        }
        if (shaderDrawParameters) {
            preamble.append("#define RENDERPEARL_INSTANCE_INDEX_INCLUDES_BASE_INSTANCE\n");
        }
        for (Map.Entry<String, String> e : shaderDefines.values().entrySet()) {
            preamble.append("#define ").append(e.getKey()).append(' ').append(e.getValue()).append('\n');
        }
        for (String flag : shaderDefines.flags()) {
            preamble.append("#define ").append(flag).append('\n');
        }
        String expanded = ShaderSubstitutions.expandIncludes(source, shaderSource, new HashSet<>(), 0);
        int[] words = ShaderSubstitutions.compile(name, expanded, type, preamble.toString());
        return new WebSpvModule(words, type);
    }
}
