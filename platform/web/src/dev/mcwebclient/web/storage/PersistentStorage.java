package dev.mcwebclient.web.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import dev.mcwebclient.web.WebJs;
import dev.mcwebclient.web.WebRuntime;
import dev.mcwebclient.web.exec.CoThreads;
import dev.mcwebclient.web.mem.NativeHeap;
import dev.mcwebclient.web.mem.Nmem;
import org.graalvm.webimage.api.JS;

/**
 * Keeps the game directory (saves, options, configs, server list) across page loads.
 *
 * <p>The game works on the in-memory file system (/root/game). The page restores the
 * directory from the origin-private file system (OPFS) before the game starts (host.js,
 * mcweb.persist.loadAll), and this class mirrors changes back: a green thread compares
 * each file's modification time and size with what was last stored and hands changed
 * files and deletions to the page, which writes them to OPFS in the background.
 */
public final class PersistentStorage {
    private static final long INTERVAL_NANOS = 5_000_000_000L;
    /** Not worth keeping (rewritten every run; .fabric/ holds the loader's extracted nested mod jars). */
    private static final List<String> EXCLUDED = List.of("logs/", "debug/", ".fabric/");

    private static final Path ROOT = WebRuntime.GAME_DIR;
    private static Map<String, Long> stored = new HashMap<>();
    private static boolean available;

    private PersistentStorage() {
    }

    /** Records what the page restored (already stored), then starts the sync thread. */
    public static void start() {
        available = hostAvailable();
        if (!available) {
            WebJs.log("[storage] no persistent storage in this browser: worlds and settings last until the page closes");
            return;
        }
        stored = scan();
        WebJs.log("[storage] keeping " + stored.size() + " game files in browser storage");
        Thread t = new Thread(PersistentStorage::loop, "Storage sync");
        t.setDaemon(true);
        t.start();
    }

    private static void loop() {
        while (true) {
            CoThreads.sleep(INTERVAL_NANOS); // outside the try: a failure never makes this spin
            try {
                sync();
            } catch (Throwable t) {
                WebJs.log("[storage] sync failed: " + t);
            }
        }
    }

    /** Stores changed files and forgets deleted ones. */
    public static synchronized void sync() {
        if (!available) {
            return;
        }
        Map<String, Long> now = scan();
        int written = 0;
        List<String> failed = new java.util.ArrayList<>();
        for (Map.Entry<String, Long> e : now.entrySet()) {
            if (!e.getValue().equals(stored.get(e.getKey()))) {
                try {
                    write(e.getKey(), Files.readAllBytes(ROOT.resolve(e.getKey())));
                    written++;
                } catch (IOException ex) {
                    failed.add(e.getKey());
                }
            }
        }
        failed.forEach(now::remove); // not recorded as stored: tried again next time
        int removed = 0;
        for (String path : stored.keySet()) {
            if (!now.containsKey(path)) {
                hostRemove(path);
                removed++;
            }
        }
        stored = now;
        if (written + removed > 0) {
            WebJs.log("[storage] stored " + written + " changed file(s), removed " + removed);
        }
    }

    /** Relative path → modification stamp (time and size) of every file worth keeping. */
    private static Map<String, Long> scan() {
        Map<String, Long> files = new HashMap<>();
        if (!Files.isDirectory(ROOT)) {
            return files;
        }
        try (Stream<Path> walk = Files.walk(ROOT)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
                if (!a.isRegularFile()) {
                    continue;
                }
                String rel = ROOT.relativize(p).toString().replace('\\', '/');
                if (EXCLUDED.stream().anyMatch(rel::startsWith)) {
                    continue;
                }
                files.put(rel, a.lastModifiedTime().toMillis() * 31 + a.size());
            }
        } catch (IOException e) {
            WebJs.log("[storage] scan failed: " + e);
        }
        return files;
    }

    private static void write(String path, byte[] data) {
        long tmp = NativeHeap.malloc(Math.max(1, data.length));
        try {
            Nmem.copyFromBytes(data, 0, tmp, data.length);
            hostWrite(path, (int) tmp, data.length);
        } finally {
            NativeHeap.free(tmp);
        }
    }

    @JS.Coerce
    @JS(value = "return !!globalThis.mcweb?.persist?.available;", args = {})
    private static native boolean hostAvailable();

    /** Copies the bytes out of nmem at once; the OPFS write happens later. */
    @JS.Coerce
    @JS(value = "globalThis.mcweb.persist.write(path, new Uint8Array(getExport('nmem').buffer, addr >>> 0, len).slice());",
            args = {"path", "addr", "len"})
    private static native void hostWrite(String path, int addr, int len);

    @JS.Coerce
    @JS(value = "globalThis.mcweb.persist.remove(path);", args = {"path"})
    private static native void hostRemove(String path);
}
