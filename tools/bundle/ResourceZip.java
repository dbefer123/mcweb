import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Copies the game's resources (assets/**, data/**, pack.png) out of the baked game jar
 * into a zip the browser mounts as the vanilla resource pack root. Part of the locally
 * built game bundle (never deployed).
 *
 * <p>Usage: java ResourceZip.java &lt;minecraft-baked.jar&gt; &lt;out.zip&gt;</p>
 */
public final class ResourceZip {
    public static void main(String[] args) throws IOException {
        Path in = Path.of(args[0]);
        Path out = Path.of(args[1]);
        Files.createDirectories(out.getParent());
        Path tmp = out.resolveSibling(out.getFileName() + ".part");
        int count = 0;
        try (ZipFile zip = new ZipFile(in.toFile()); OutputStream os = Files.newOutputStream(tmp); ZipOutputStream zos = new ZipOutputStream(os)) {
            zos.setLevel(6);
            for (ZipEntry e : Collections.list(zip.entries())) {
                String name = e.getName();
                if (e.isDirectory() || !(name.startsWith("assets/") || name.startsWith("data/") || name.equals("pack.png"))) {
                    continue;
                }
                ZipEntry copy = new ZipEntry(name);
                copy.setTime(315532800000L);
                zos.putNextEntry(copy);
                try (InputStream is = zip.getInputStream(e)) {
                    is.transferTo(zos);
                }
                zos.closeEntry();
                count++;
            }
        }
        Files.move(tmp, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        System.out.println("wrote " + count + " resources to " + out);
    }
}
