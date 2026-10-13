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
        // JOML writes vectors/matrices into direct buffers with sun.misc.Unsafe at raw
        // addresses, which Web Image traps; its NIO implementation goes through
        // ScopedMemoryAccess, which reaches nmem (mem/ScopedMemoryAccessSubstitutions).
        System.setProperty("joml.nounsafe", "true");
        // Same for Netty's direct buffer fast paths.
        System.setProperty("io.netty.noUnsafe", "true");
        // Mods logging through log4j-api (log4j-core isn't in the image): its simple
        // console provider instead of "could not find a logging provider".
        setIfMissing("log4j.provider", "org.apache.logging.log4j.simple.internal.SimpleProvider");
        setIfMissing("org.apache.logging.log4j.simplelog.level", "INFO");
        setIfMissing("org.apache.logging.log4j.simplelog.logFile", "system.out");
        long scratch = dev.mcwebclient.web.mem.NativeHeap.malloc(64);
        dev.mcwebclient.web.mem.Nmem.selfCheck(scratch);
        dev.mcwebclient.web.mem.NativeHeap.free(scratch);
        Files.createDirectories(GAME_DIR);
        Files.createDirectories(ASSETS_DIR);
        Files.createDirectories(LOADER_DIR);
        Files.createDirectories(Path.of(System.getProperty("java.home")));
        int files = unpackEmbedded();
        int hostFiles = importHostFiles();
        try (var s = Files.list(LOADER_DIR)) {
            fabricLoaderJar = s.filter(p -> p.getFileName().toString().startsWith("fabric-loader")).findFirst().orElse(null);
        }
        WebJs.log("[web] boot: unpacked " + files + " embedded files, " + hostFiles + " files from the page; loader jar " + fabricLoaderJar);
    }

    /**
     * Copies files the page prepared before starting the image (globalThis.mcweb.files:
     * {path relative to /root, data: Uint8Array}) into the virtual file system, e.g.
     * asset objects. The page drops its copies afterwards.
     */
    private static int importHostFiles() throws IOException {
        int n = hostFileCount();
        for (int i = 0; i < n; i++) {
            String rel = hostFileName(i);
            int size = hostFileSize(i);
            byte[] data = new byte[size];
            if (size > 0) {
                long tmp = dev.mcwebclient.web.mem.NativeHeap.malloc(size);
                try {
                    hostFileCopy(i, (int) tmp);
                    dev.mcwebclient.web.mem.Nmem.copyToBytes(tmp, data, 0, size);
                } finally {
                    dev.mcwebclient.web.mem.NativeHeap.free(tmp);
                }
            }
            Path target = ROOT.resolve(rel).normalize();
            if (!target.startsWith(ROOT)) {
                continue;
            }
            Files.createDirectories(target.getParent());
            Files.write(target, data);
        }
        hostFilesDone();
        return n;
    }

    @org.graalvm.webimage.api.JS.Coerce
    @org.graalvm.webimage.api.JS(value = "return globalThis.mcweb?.files?.length ?? 0;", args = {})
    private static native int hostFileCount();

    @org.graalvm.webimage.api.JS.Coerce
    @org.graalvm.webimage.api.JS(value = "return globalThis.mcweb.files[i].path;", args = {"i"})
    private static native String hostFileName(int i);

    @org.graalvm.webimage.api.JS.Coerce
    @org.graalvm.webimage.api.JS(value = "return globalThis.mcweb.files[i].data.length;", args = {"i"})
    private static native int hostFileSize(int i);

    @org.graalvm.webimage.api.JS.Coerce
    @org.graalvm.webimage.api.JS(value = "new Uint8Array(getExport('nmem').buffer, addr >>> 0, globalThis.mcweb.files[i].data.length).set(globalThis.mcweb.files[i].data);",
            args = {"i", "addr"})
    private static native void hostFileCopy(int i, int addr);

    @org.graalvm.webimage.api.JS(value = "if (globalThis.mcweb) globalThis.mcweb.files = null;", args = {})
    private static native void hostFilesDone();

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
