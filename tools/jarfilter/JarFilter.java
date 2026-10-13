import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Makes library jars loadable by the native-image builder by dropping
 * package-info/module-info classes compiled for class-file versions below 49
 * (Java 5), which current class loaders reject ("Illegal class name").
 *
 * Usage: java tools/jarfilter/JarFilter.java outDir in1.jar in2.jar ...
 * For each input that needs filtering, writes outDir/<name> and prints
 * "in1.jar\toutDir/in1.jar". Inputs that are fine are left alone and not printed.
 */
public final class JarFilter {
    public static void main(String[] args) throws IOException {
        Path outDir = Path.of(args[0]);
        Files.createDirectories(outDir);
        for (int i = 1; i < args.length; i++) {
            Path in = Path.of(args[i]);
            if (!needsFilter(in)) {
                continue;
            }
            Path out = outDir.resolve(in.getFileName());
            copyFiltered(in, out);
            System.out.println(in + "\t" + out);
        }
    }

    private static boolean needsFilter(Path jar) throws IOException {
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            var it = zf.entries();
            while (it.hasMoreElements()) {
                ZipEntry e = it.nextElement();
                if (isInfoClass(e.getName())) {
                    try (InputStream in = zf.getInputStream(e)) {
                        if (isOld(in.readNBytes(8))) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static void copyFiltered(Path in, Path out) throws IOException {
        try (InputStream fin = Files.newInputStream(in);
             ZipInputStream zin = new ZipInputStream(fin);
             OutputStream fout = Files.newOutputStream(out);
             ZipOutputStream zout = new ZipOutputStream(fout)) {
            for (ZipEntry e; (e = zin.getNextEntry()) != null; ) {
                byte[] data = zin.readAllBytes();
                if (isInfoClass(e.getName()) && isOld(data)) {
                    continue;
                }
                ZipEntry copy = new ZipEntry(e.getName());
                copy.setTime(e.getTime());
                zout.putNextEntry(copy);
                zout.write(data);
                zout.closeEntry();
            }
        }
    }

    private static boolean isInfoClass(String name) {
        return name.endsWith("package-info.class") || name.endsWith("module-info.class");
    }

    private static boolean isOld(byte[] head) {
        if (head.length < 8) {
            return true;
        }
        int major = ((head[6] & 0xff) << 8) | (head[7] & 0xff);
        return major < 49;
    }
}
