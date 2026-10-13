package dev.mcwebclient.web;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Process-level setup for the browser image: system properties and the virtual file
 * system layout. Everything lives under /root (Web Image's in-memory file system):
 *
 * <pre>
 * /root/game     game directory (options.txt, config/, mods/, saves/)
 * /root/assets   asset index + objects
 * /root/loader   Fabric Loader jar (for its metadata and nested MixinExtras jar)
 * </pre>
 */
public final class WebRuntime {
    public static final Path ROOT = Path.of("/root");
    public static final Path GAME_DIR = ROOT.resolve("game");
    public static final Path ASSETS_DIR = ROOT.resolve("assets");
    public static final Path LOADER_DIR = ROOT.resolve("loader");

    private static Path fabricLoaderJar;

    private WebRuntime() {
    }

    public static void boot() throws IOException {
        // Libraries pick code paths from these (LWJGL platform/arch, Minecraft's OS
        // checks). The image behaves like 64-bit Linux: Unix paths, LP64 layouts.
        System.setProperty("os.name", "Linux");
        System.setProperty("os.arch", "amd64");
        setIfMissing("os.version", "6.0-browser");
        // Fabric registers a built-in "java" mod from these; Minecraft reports them.
        setIfMissing("java.home", "/root/jdk");
        setIfMissing("java.version", "25.0.4");
        setIfMissing("java.specification.version", "25");
        setIfMissing("java.vm.name", "GraalVM Web Image");
        setIfMissing("java.vm.vendor", "Oracle Corporation");
        setIfMissing("java.vm.info", "WasmGC");
        setIfMissing("java.vendor", "Oracle Corporation");
        setIfMissing("java.class.path", "");
        setIfMissing("java.library.path", "");
        setIfMissing("file.encoding", "UTF-8");
        setIfMissing("sun.jnu.encoding", "UTF-8");
        setIfMissing("minecraft.launcher.brand", "mcweb");
        System.setProperty("user.home", ROOT.toString());
        System.setProperty("java.awt.headless", "true");
        System.setProperty("log4j2.formatMsgNoLookups", "true");
        long scratch = dev.mcwebclient.web.mem.NativeHeap.malloc(64);
        dev.mcwebclient.web.mem.Nmem.selfCheck(scratch);
        dev.mcwebclient.web.mem.NativeHeap.free(scratch);
        Files.createDirectories(GAME_DIR);
        Files.createDirectories(ASSETS_DIR);
        Files.createDirectories(LOADER_DIR);
        Files.createDirectories(Path.of(System.getProperty("java.home")));
        int files = unpackEmbedded();
        try (var s = Files.list(LOADER_DIR)) {
            fabricLoaderJar = s.filter(p -> p.getFileName().toString().startsWith("fabric-loader")).findFirst().orElse(null);
        }
        WebJs.log("[web] boot: unpacked " + files + " embedded files; loader jar " + fabricLoaderJar);
    }

    private static void setIfMissing(String key, String value) {
        String current = System.getProperty(key);
        if (current == null || current.isEmpty()) {
            System.setProperty(key, value);
        }
    }

    public static Path fabricLoaderJar() {
        return fabricLoaderJar;
    }

    /**
     * Copies files embedded under /webfs/ (listed in /webfs/index.txt) into the virtual
     * file system. Phase 2 stand-in for the streamed game bundle.
     */
    private static int unpackEmbedded() throws IOException {
        InputStream index = WebRuntime.class.getResourceAsStream("/webfs/index.txt");
        if (index == null) {
            return 0;
        }
        int count = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(index, StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                String rel = line.trim();
                if (rel.isEmpty()) {
                    continue;
                }
                try (InputStream in = WebRuntime.class.getResourceAsStream("/webfs/" + rel)) {
                    if (in == null) {
                        WebJs.log("[web] missing embedded file " + rel);
                        continue;
                    }
                    Path target = ROOT.resolve(rel);
                    Files.createDirectories(target.getParent());
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    count++;
                }
            }
        }
        return count;
    }
}
