package dev.mcwebclient.baker;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

/**
 * Produces the "baked" class set: every Minecraft and mod class run through the same
 * pipeline Knot uses at class-load time (game entrypoint patch, class tweakers /
 * access wideners, environment stripping, package-access widening, then Mixin).
 * Classes Mixin would generate on demand (synthetic args/inner classes) are found by
 * scanning references and asking the transformer to generate them.
 *
 * <p>Output (in -Dmcweb.bake.out): minecraft-baked.jar (game classes + game resources),
 * mods-baked.jar (mod classes + generated classes), bake-report.json.</p>
 */
final class Baker {
    /** Builtin mods whose classes come from elsewhere on the runtime class path. */
    private static final Set<String> SKIP_MODS = Set.of("minecraft", "java", "fabricloader", "mcweb-baker");
    /** Fixed timestamp so identical inputs produce identical jars. */
    private static final long EPOCH = 315532800000L; // 1980-01-01

    private final Path out = Paths.get(require("mcweb.bake.out"));
    private final FabricLauncher launcher = FabricLauncherBase.getLauncher();
    private final IMixinTransformer mixin =
            (IMixinTransformer) MixinEnvironment.getCurrentEnvironment().getActiveTransformer();

    /** internal name -> owning mod id, in discovery order. */
    private final Map<String, String> classOwner = new LinkedHashMap<>();
    private final Map<String, byte[]> mcClasses = new TreeMap<>();
    private final Map<String, byte[]> mcResources = new TreeMap<>();
    private final Map<String, byte[]> modClasses = new TreeMap<>();
    private final Map<String, byte[]> modResources = new TreeMap<>();
    /** Library classes targeted by mixins; must shadow the original library jars at runtime. */
    private final Map<String, byte[]> libClasses = new TreeMap<>();
    private static final String LIBRARY = "<library>";
    private final Set<String> skippedMixinClasses = new TreeSet<>();
    private final Map<String, String> failures = new TreeMap<>();
    private final Set<String> synthetic = new TreeSet<>();
    private final Set<String> unresolved = new TreeSet<>();
    private final Map<String, Integer> changedPerMod = new TreeMap<>();
    private final List<String> modLines = new ArrayList<>();

    private static String require(String key) {
        String v = System.getProperty(key);
        if (v == null) {
            throw new IllegalStateException("missing -D" + key);
        }
        return v;
    }

    void run() throws Exception {
        long t0 = System.nanoTime();
        Files.createDirectories(out);
        FabricLoader loader = FabricLoader.getInstance();

        ModContainer minecraft = loader.getModContainer("minecraft").orElseThrow();
        for (Path root : minecraft.getRootPaths()) {
            scan(root, "minecraft", true);
        }
        for (ModContainer mod : loader.getAllMods()) {
            String id = mod.getMetadata().getId();
            modLines.add(id + " " + mod.getMetadata().getVersion().getFriendlyString() + " " + mod.getRootPaths());
            if (SKIP_MODS.contains(id)) {
                continue;
            }
            for (Path root : mod.getRootPaths()) {
                scan(root, id, false);
            }
        }
        log("scanned " + classOwner.size() + " classes, " + mcResources.size() + " game resources");

        int done = 0;
        for (Map.Entry<String, String> e : classOwner.entrySet()) {
            transform(e.getKey(), e.getValue());
            if (++done % 2000 == 0) {
                log("transformed " + done + "/" + classOwner.size());
            }
        }
        // Mixins can also target library classes (e.g. DataFixerUpper). Mixin only fills
        // each config's target list once the config is prepared, i.e. after the first
        // transforms above, so collect them now.
        List<String> libTargets = new ArrayList<>();
        for (String mixinClass : skippedMixinClasses) {
            for (String internal : mixinTargets(mixinClass)) {
                if (!classOwner.containsKey(internal) && existsOutsideBake(internal.replace('/', '.'))) {
                    classOwner.put(internal, LIBRARY);
                    libTargets.add(internal);
                }
            }
        }
        for (String internal : libTargets) {
            transform(internal, LIBRARY);
        }
        log("transformed all; " + libTargets.size() + " library mixin targets, " + skippedMixinClasses.size()
                + " mixin classes skipped, " + failures.size() + " failures");

        addAgentCapturedClasses();
        closeOverReferences();
        log("closure: " + synthetic.size() + " generated classes, " + unresolved.size() + " unresolved references");

        writeJar(out.resolve("minecraft-baked.jar"), mcClasses, mcResources);
        writeJar(out.resolve("mods-baked.jar"), modClasses, modResources);
        writeJar(out.resolve("libs-baked.jar"), libClasses, Map.of());
        writeReport(loader, (System.nanoTime() - t0) / 1_000_000L);
        log("done in " + (System.nanoTime() - t0) / 1_000_000L + " ms -> " + out);
    }

    private void scan(Path root, String owner, boolean isGame) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                String rel = root.relativize(p).toString().replace('\\', '/');
                if (rel.endsWith(".class")) {
                    if (rel.equals("module-info.class") || rel.startsWith("META-INF/versions/")) {
                        continue;
                    }
                    String internal = rel.substring(0, rel.length() - ".class".length());
                    classOwner.putIfAbsent(internal, owner);
                } else if (isSignatureFile(rel) || rel.equals("META-INF/MANIFEST.MF")) {
                    continue;
                } else if (isGame) {
                    mcResources.put(rel, Files.readAllBytes(p));
                } else if (!rel.equals("fabric.mod.json") && !rel.startsWith("META-INF/jars/")) {
                    // Mods sometimes read their own files through the class loader.
                    // First mod wins on a clash; fabric.mod.json stays out so the jar never
                    // looks like a mod to Fabric's discovery.
                    modResources.putIfAbsent(rel, Files.readAllBytes(p));
                }
            }
        }
    }

    private static boolean isSignatureFile(String rel) {
        String u = rel.toUpperCase();
        return u.startsWith("META-INF/") && (u.endsWith(".SF") || u.endsWith(".RSA") || u.endsWith(".DSA") || u.endsWith(".EC"));
    }

    private void transform(String internal, String owner) {
        String dotted = internal.replace('/', '.');
        byte[] pre;
        try {
            pre = launcher.getClassByteArray(dotted, true);
        } catch (Throwable t) {
            failures.put(internal, "pre-mixin: " + describe(t));
            return;
        }
        if (pre == null) {
            failures.put(internal, "pre-mixin: no bytes");
            return;
        }
        byte[] post;
        try {
            post = mixin.transformClassBytes(dotted, dotted, pre);
        } catch (Throwable t) {
            if (chainContains(t, "IllegalClassLoadError")) {
                skippedMixinClasses.add(internal);
                return;
            }
            failures.put(internal, "mixin: " + describe(t));
            post = pre;
        }
        if (post != pre) {
            changedPerMod.merge(owner, 1, Integer::sum);
        }
        if (LIBRARY.equals(owner)) {
            libClasses.put(internal, post);
        } else {
            ("minecraft".equals(owner) ? mcClasses : modClasses).put(internal, post);
        }
    }

    /**
     * Adds classes defined at runtime during transformation (MixinExtras defines its
     * LocalRef implementations through Lookup.defineClass), captured by the bake agent.
     */
    @SuppressWarnings("unchecked")
    private void addAgentCapturedClasses() throws ReflectiveOperationException {
        Class<?> agent = Class.forName("dev.mcwebclient.bakeragent.CaptureAgent", true, ClassLoader.getSystemClassLoader());
        Map<String, byte[]> captured = (Map<String, byte[]>) agent.getField("CAPTURED").get(null);
        for (Map.Entry<String, byte[]> e : new TreeMap<>(captured).entrySet()) {
            if (!modClasses.containsKey(e.getKey()) && !existsOutsideBake(e.getKey().replace('/', '.'))) {
                modClasses.put(e.getKey(), e.getValue());
                synthetic.add(e.getKey());
            }
        }
        log("agent captured " + captured.size() + " runtime-defined classes");
    }

    /** Finds classes referenced by baked code that only Mixin can produce, and generates them. */
    private void closeOverReferences() {
        Set<String> known = new HashSet<>(mcClasses.keySet());
        known.addAll(modClasses.keySet());
        known.addAll(libClasses.keySet());
        known.addAll(skippedMixinClasses);
        Deque<byte[]> queue = new ArrayDeque<>();
        queue.addAll(mcClasses.values());
        queue.addAll(modClasses.values());
        queue.addAll(libClasses.values());
        Set<String> checked = new HashSet<>();
        while (!queue.isEmpty()) {
            for (String ref : references(queue.pop())) {
                if (known.contains(ref) || !checked.add(ref)) {
                    continue;
                }
                String dotted = ref.replace('/', '.');
                if (existsOutsideBake(dotted)) {
                    continue;
                }
                byte[] generated = null;
                try {
                    generated = mixin.transformClassBytes(dotted, dotted, null);
                } catch (Throwable t) {
                    failures.put(ref, "generate: " + describe(t));
                }
                if (generated != null) {
                    synthetic.add(ref);
                    known.add(ref);
                    modClasses.put(ref, generated);
                    queue.add(generated);
                } else {
                    unresolved.add(ref);
                }
            }
        }
    }

    private boolean existsOutsideBake(String dotted) {
        try {
            return launcher.getClassByteArray(dotted, false) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Reads @Mixin(value = {...}, targets = {...}) from a mixin class's raw bytes. */
    private Set<String> mixinTargets(String internal) {
        Set<String> targets = new TreeSet<>();
        byte[] raw;
        try {
            raw = launcher.getClassByteArray(internal.replace('/', '.'), false);
        } catch (IOException e) {
            return targets;
        }
        if (raw == null) {
            return targets;
        }
        new ClassReader(raw).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (!"Lorg/spongepowered/asm/mixin/Mixin;".equals(descriptor)) {
                    return null;
                }
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitArray(String name) {
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(String n, Object value) {
                                if ("value".equals(name) && value instanceof Type t) {
                                    targets.add(t.getInternalName());
                                } else if ("targets".equals(name) && value instanceof String s) {
                                    targets.add(s.replace('.', '/'));
                                }
                            }
                        };
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return targets;
    }

    private static Set<String> references(byte[] bytes) {
        Set<String> refs = new HashSet<>();
        Remapper recorder = new Remapper(Opcodes.ASM9) {
            @Override
            public String map(String internalName) {
                if (internalName != null && !internalName.startsWith("[")) {
                    refs.add(internalName);
                }
                return internalName;
            }
        };
        new ClassReader(bytes).accept(new ClassRemapper(new ClassVisitor(Opcodes.ASM9) {
        }, recorder), ClassReader.SKIP_FRAMES);
        return refs;
    }

    private static boolean chainContains(Throwable t, String simpleName) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c.getClass().getSimpleName().equals(simpleName)) {
                return true;
            }
        }
        return false;
    }

    private static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (sb.length() > 0) {
                sb.append(" <- ");
            }
            sb.append(c.getClass().getName()).append(": ").append(c.getMessage());
        }
        return sb.toString();
    }

    private static void writeJar(Path file, Map<String, byte[]> classes, Map<String, byte[]> resources) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Created-By", "mcweb fabric-baker");
        try (OutputStream os = Files.newOutputStream(file); JarOutputStream jar = new JarOutputStream(os)) {
            ZipEntry me = new ZipEntry("META-INF/MANIFEST.MF");
            me.setTime(EPOCH);
            jar.putNextEntry(me);
            manifest.write(jar);
            jar.closeEntry();
            for (Map.Entry<String, byte[]> e : classes.entrySet()) {
                put(jar, e.getKey() + ".class", e.getValue());
            }
            for (Map.Entry<String, byte[]> e : resources.entrySet()) {
                put(jar, e.getKey(), e.getValue());
            }
        }
    }

    private static void put(JarOutputStream jar, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(EPOCH);
        jar.putNextEntry(entry);
        jar.write(data);
        jar.closeEntry();
    }

    private void writeReport(FabricLoader loader, long millis) throws IOException {
        StringBuilder sb = new StringBuilder("{\n");
        field(sb, "millis", millis);
        field(sb, "minecraftClasses", mcClasses.size());
        field(sb, "minecraftResources", mcResources.size());
        field(sb, "modClasses", modClasses.size());
        field(sb, "libraryClasses", libClasses.size());
        list(sb, "libraryMixinTargets", libClasses.keySet());
        sb.append("  \"changedPerMod\": {");
        int i = 0;
        for (Map.Entry<String, Integer> e : changedPerMod.entrySet()) {
            sb.append(i++ == 0 ? "" : ", ").append(q(e.getKey())).append(": ").append(e.getValue());
        }
        sb.append("},\n");
        list(sb, "mods", modLines);
        list(sb, "entrypoints", entrypointLines(loader));
        list(sb, "skippedMixinClasses", skippedMixinClasses);
        list(sb, "generatedClasses", synthetic);
        list(sb, "unresolvedReferences", unresolved);
        sb.append("  \"failures\": {\n");
        i = 0;
        for (Map.Entry<String, String> e : failures.entrySet()) {
            sb.append(i++ == 0 ? "" : ",\n").append("    ").append(q(e.getKey())).append(": ").append(q(e.getValue()));
        }
        sb.append("\n  }\n}\n");
        Files.writeString(out.resolve("bake-report.json"), sb.toString(), StandardCharsets.UTF_8);
    }

    private static List<String> entrypointLines(FabricLoader loader) {
        List<String> lines = new ArrayList<>();
        for (String key : new String[] {"preLaunch", "main", "client", "server"}) {
            for (EntrypointContainer<Object> c : loader.getEntrypointContainers(key, Object.class)) {
                lines.add(key + " " + c.getProvider().getMetadata().getId() + " " + c.getDefinition());
            }
        }
        return lines;
    }

    private static void field(StringBuilder sb, String k, long v) {
        sb.append("  ").append(q(k)).append(": ").append(v).append(",\n");
    }

    private static void list(StringBuilder sb, String k, Iterable<String> values) {
        sb.append("  ").append(q(k)).append(": [");
        int i = 0;
        for (String v : values) {
            sb.append(i++ == 0 ? "\n    " : ",\n    ").append(q(v));
        }
        sb.append(i == 0 ? "],\n" : "\n  ],\n");
    }

    private static String q(String s) {
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

    private static void log(String msg) {
        System.out.println("[baker] " + msg);
    }
}
