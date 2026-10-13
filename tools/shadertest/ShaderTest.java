import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.mcwebclient.web.gpu.spirv.SpirvModule;
import dev.mcwebclient.web.gpu.spirv.SpirvReflection;
import dev.mcwebclient.web.gpu.spirv.WgslTranslator;

/**
 * Offline check of the browser shader path against build/shader-corpus (recorded by the
 * desktop self-test with --shader-corpus): every recorded backend pipeline's SPIR-V is
 * reflected and translated to WGSL. Writes <stage>.wgsl next to each .spv, plus
 * corpus/index.json for web/dev/wgsl-check.html, which compiles them with WebGPU.
 *
 * <p>Usage: java ShaderTest <corpus dir></p>
 */
public final class ShaderTest {
    public static void main(String[] args) throws IOException {
        Path corpus = Path.of(args[0]);
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        List<Path> dirs;
        try (Stream<Path> s = Files.list(corpus.resolve("pipelines"))) {
            dirs = s.filter(Files::isDirectory).sorted().toList();
        }
        Map<String, Integer> errors = new TreeMap<>();
        JsonArray index = new JsonArray();
        int ok = 0, failed = 0;
        for (Path dir : dirs) {
            JsonObject p = gson.fromJson(Files.readString(dir.resolve("pipeline.json")), JsonObject.class);
            JsonArray uniforms = p.getAsJsonArray("uniforms");
            WgslTranslator.Options options = new WgslTranslator.Options();
            for (int i = 0; i < uniforms.size(); i++) {
                JsonObject u = uniforms.get(i).getAsJsonObject();
                JsonElement f = u.get("format");
                if ("TEXEL_BUFFER".equals(u.get("type").getAsString()) && f != null && !f.isJsonNull()) {
                    options.texelBufferFormats.put(i, f.getAsString());
                }
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("dir", corpus.relativize(dir).toString().replace('\\', '/'));
            entry.add("pipeline", p);
            boolean allOk = true;
            for (JsonElement se : p.getAsJsonArray("shaders")) {
                JsonObject shader = se.getAsJsonObject();
                String stage = shader.get("type").getAsString().toLowerCase();
                byte[] bytes = Files.readAllBytes(dir.resolve(shader.get("spv").getAsString()));
                int[] words = new int[bytes.length / 4];
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(words);
                try {
                    SpirvModule module = new SpirvModule(words);
                    SpirvReflection reflection = new SpirvReflection(module);
                    // The recorded SPIR-V already has renderpearl's bindings; reflection must agree with them.
                    for (var d : reflection.descriptors()) {
                        String expected = uniforms.get(d.binding()).getAsJsonObject().get("name").getAsString();
                        if (!expected.equals(d.name())) {
                            throw new IllegalStateException("binding " + d.binding() + " is " + d.name() + ", pipeline says " + expected);
                        }
                    }
                    String wgsl = WgslTranslator.translate(module, options);
                    Files.writeString(dir.resolve(stage + ".wgsl"), wgsl, StandardCharsets.UTF_8);
                    entry.addProperty(stage, stage + ".wgsl");
                } catch (RuntimeException | com.mojang.renderpearl.util.ShaderCompileException e) {
                    allOk = false;
                    String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                    errors.merge(msg, 1, Integer::sum);
                    Files.writeString(dir.resolve(stage + ".error.txt"), msg + "\n", StandardCharsets.UTF_8);
                    if (errors.get(msg) == 1) {
                        System.out.println(dir.getFileName() + " " + stage + ": " + msg);
                        if (!(e instanceof WgslTranslator.TranslationException)) {
                            e.printStackTrace(System.out);
                        }
                    }
                }
            }
            if (allOk) {
                ok++;
                index.add(entry);
            } else {
                failed++;
            }
        }
        Files.writeString(corpus.resolve("index.json"), gson.toJson(index), StandardCharsets.UTF_8);
        System.out.println("translated " + ok + " pipelines, " + failed + " failed");
        for (Map.Entry<String, Integer> e : errors.entrySet()) {
            System.out.println("  " + e.getValue() + "x " + e.getKey());
        }
    }
}
