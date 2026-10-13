package spike;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.Inflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Phase 2 probe: zlib, zip, NIO/jimfs, resources and blocking primitives under Web Image. */
public final class Spike2 {
    private Spike2() {
    }

    public static void main(String[] args) {
        Spike.log("spike2: start");
        Spike.run("zlib.inflater-deflater", () -> {
            byte[] input = "hello hello hello hello minecraft region data".repeat(200).getBytes(StandardCharsets.UTF_8);
            Deflater d = new Deflater(Deflater.DEFAULT_COMPRESSION);
            d.setInput(input);
            d.finish();
            byte[] buf = new byte[input.length];
            int clen = d.deflate(buf);
            d.end();
            Inflater inf = new Inflater();
            inf.setInput(buf, 0, clen);
            byte[] out = new byte[input.length];
            int n = inf.inflate(out);
            inf.end();
            return "in=" + input.length + " compressed=" + clen + " restored=" + n + " equal=" + java.util.Arrays.equals(input, out);
        });
        Spike.run("zlib.gzip-streams", () -> {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
                gz.write("nbt".repeat(1000).getBytes(StandardCharsets.UTF_8));
            }
            try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(bos.toByteArray()))) {
                return "gz=" + bos.size() + " back=" + in.readAllBytes().length;
            }
        });
        Spike.run("zip.streams", () -> {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ZipOutputStream zo = new ZipOutputStream(bos)) {
                zo.putNextEntry(new ZipEntry("assets/test.json"));
                zo.write("{\"a\":1}".getBytes(StandardCharsets.UTF_8));
                zo.closeEntry();
            }
            try (ZipInputStream zi = new ZipInputStream(new ByteArrayInputStream(bos.toByteArray()))) {
                ZipEntry e = zi.getNextEntry();
                return "entry=" + e.getName() + " data=" + new String(zi.readAllBytes(), StandardCharsets.UTF_8);
            }
        });
        Spike.run("fs.default", () -> {
            FileSystem fs = FileSystems.getDefault();
            Path dir = Path.of("game", "config");
            Files.createDirectories(dir);
            Path f = dir.resolve("options.txt");
            Files.writeString(f, "fov:70\n");
            List<String> names;
            try (Stream<Path> s = Files.list(dir)) {
                names = s.map(p -> p.getFileName().toString()).toList();
            }
            return "provider=" + fs.provider().getClass().getName() + " sep=" + fs.getSeparator()
                    + " abs=" + f.toAbsolutePath() + " read=" + Files.readString(f).trim() + " list=" + names
                    + " user.dir=" + System.getProperty("user.dir") + " tmp=" + System.getProperty("java.io.tmpdir");
        });
        Spike.run("fs.java.io.File", () -> {
            File f = new File("game/config/options.txt");
            try (FileInputStream in = new FileInputStream(f)) {
                return "exists=" + f.exists() + " len=" + f.length() + " read=" + new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
        });
        Spike.run("fs.randomAccess+channel", () -> {
            Path p = Path.of("game", "r.0.0.mca");
            try (FileChannel ch = FileChannel.open(p, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                ch.write(ByteBuffer.wrap(new byte[] {1, 2, 3, 4}), 8192);
            }
            try (RandomAccessFile raf = new RandomAccessFile(p.toFile(), "r")) {
                raf.seek(8192);
                return "size=" + raf.length() + " byte=" + raf.read();
            }
        });
        Spike.run("fs.zipfs", () -> {
            Path zip = Path.of("game", "mod.jar");
            try (ZipOutputStream zo = new ZipOutputStream(Files.newOutputStream(zip))) {
                zo.putNextEntry(new ZipEntry("fabric.mod.json"));
                zo.write("{\"id\":\"x\"}".getBytes(StandardCharsets.UTF_8));
                zo.closeEntry();
            }
            try (FileSystem zfs = FileSystems.newFileSystem(zip, Map.of())) {
                return "zipfs read=" + Files.readString(zfs.getPath("fabric.mod.json"));
            }
        });
        Spike.run("resources.stream", () -> {
            try (InputStream in = Spike2.class.getResourceAsStream("/spike/probe.txt")) {
                return in == null ? "null (not embedded)" : new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
        });
        Spike.run("resources.url+fs", () -> {
            java.net.URL url = Spike2.class.getResource("/spike/probe.txt");
            if (url == null) {
                return "url=null";
            }
            URI uri = url.toURI();
            String r = "url=" + url;
            try (FileSystem rfs = FileSystems.newFileSystem(uri, Map.of())) {
                r += " fs=" + rfs.getClass().getName() + " read=" + Files.readString(Path.of(uri)).trim();
            } catch (Throwable t) {
                r += " fs-fail=" + t;
            }
            return r;
        });
        Spike.run("blocking.parkNanos", () -> {
            long t0 = System.nanoTime();
            LockSupport.parkNanos(5_000_000L);
            return "parked " + (System.nanoTime() - t0) / 1000 + " us";
        });
        Spike.run("blocking.semaphore", () -> {
            Semaphore s = new Semaphore(0);
            long t0 = System.nanoTime();
            boolean got = s.tryAcquire(10, TimeUnit.MILLISECONDS);
            return "acquired=" + got + " waited " + (System.nanoTime() - t0) / 1000 + " us";
        });
        Spike.run("thread.misc", () -> {
            Thread.currentThread().setName("Render thread");
            ThreadLocal<String> tl = ThreadLocal.withInitial(() -> "tl-ok");
            Map<String, Integer> chm = new ConcurrentHashMap<>();
            chm.computeIfAbsent("a", k -> 1);
            return Thread.currentThread().getName() + " " + tl.get() + " chm=" + chm
                    + " cpus=" + Runtime.getRuntime().availableProcessors() + " maxMem=" + Runtime.getRuntime().maxMemory() / (1 << 20) + "MiB";
        });
        Spike.log("spike2: end");
    }
}
