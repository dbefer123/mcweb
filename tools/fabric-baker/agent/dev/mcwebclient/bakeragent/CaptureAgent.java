package dev.mcwebclient.bakeragent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Build-time Java agent for the bake run. Records the bytes of classes that are
 * generated and defined at runtime (not read from any jar), such as MixinExtras'
 * LocalRef implementations, so the baker can write them into the baked jars.
 * Prefixes come from the agent argument (comma-separated internal-name prefixes).
 */
public final class CaptureAgent {
    /** internal name -> class bytes. Read by the baker through the system class loader. */
    public static final Map<String, byte[]> CAPTURED = new ConcurrentHashMap<>();

    private CaptureAgent() {
    }

    public static void premain(String args, Instrumentation inst) {
        String[] prefixes = (args == null || args.isEmpty() ? "" : args).split(",");
        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> redefined,
                                    ProtectionDomain domain, byte[] bytes) {
                if (className != null && redefined == null) {
                    for (String prefix : prefixes) {
                        if (!prefix.isEmpty() && className.startsWith(prefix)) {
                            CAPTURED.put(className, bytes.clone());
                            break;
                        }
                    }
                }
                return null;
            }
        });
    }
}
