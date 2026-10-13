package dev.mcwebclient.web.mc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

import dev.mcwebclient.web.WebRuntime;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.VanillaPackResourcesBuilder;

/**
 * Vanilla resources come from the game bundle's resource zip (tools/bundle/ResourceZip:
 * the game jar's assets/ and data/), mounted with zipfs, rather than from the image's
 * embedded resources: Web Image's resource: file system can't resolve paths relative
 * to the probe file the builder starts from.
 */
public final class ResourceSubstitutions {
    private ResourceSubstitutions() {
    }

    public static final Path VANILLA_ZIP = WebRuntime.ROOT.resolve("resources/vanilla.zip");
    private static Map<PackType, Path> roots;

    static synchronized Map<PackType, Path> vanillaRoots() {
        if (roots == null) {
            Map<PackType, Path> m = new EnumMap<>(PackType.class);
            if (Files.exists(VANILLA_ZIP)) {
                try {
                    FileSystem fs = FileSystems.newFileSystem(VANILLA_ZIP);
                    Path root = fs.getPath("/");
                    for (PackType type : PackType.values()) {
                        m.put(type, root.resolve(type.getDirectory()));
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException("Couldn't open " + VANILLA_ZIP, e);
                }
            }
            roots = m;
        }
        return roots;
    }
}

@TargetClass(VanillaPackResourcesBuilder.class)
final class Target_net_minecraft_server_packs_VanillaPackResourcesBuilder {
    @Alias
    private native void pushRootPath(Path path);

    @Alias
    private native void pushPathForType(PackType packType, Path path);

    @Alias private static Map<PackType, Path> ROOT_DIR_BY_TYPE;

    /** As the original, with the bundle zip's roots (falls back to the classpath probe). */
    @Substitute
    public VanillaPackResourcesBuilder pushJarResources() {
        Map<PackType, Path> roots = ResourceSubstitutions.vanillaRoots();
        if (roots.isEmpty()) {
            roots = ROOT_DIR_BY_TYPE;
        }
        roots.forEach((packType, path) -> {
            pushRootPath(path.getParent());
            pushPathForType(packType, path);
        });
        return (VanillaPackResourcesBuilder) (Object) this;
    }
}
