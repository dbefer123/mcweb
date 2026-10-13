package dev.mcwebclient.web.jdk;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import javax.management.ObjectName;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * java.lang.management in the browser image. Web Image has no platform MXBeans
 * (getGarbageCollectorMXBeans throws "not a platform management interface"), but
 * Minecraft reads them for the F3 memory lines, system reports and crash reports.
 * These answer from {@link Runtime} and the start time; there are no GC beans.
 */
final class ManagementSubstitutions {
    private ManagementSubstitutions() {
    }

    static final long START_MILLIS = System.currentTimeMillis();

    static final MemoryMXBean MEMORY = new MemoryMXBean() {
        @Override
        public int getObjectPendingFinalizationCount() {
            return 0;
        }

        @Override
        public MemoryUsage getHeapMemoryUsage() {
            Runtime rt = Runtime.getRuntime();
            long committed = rt.totalMemory();
            long max = Math.max(committed, rt.maxMemory());
            return new MemoryUsage(committed, committed - rt.freeMemory(), committed, max);
        }

        @Override
        public MemoryUsage getNonHeapMemoryUsage() {
            return new MemoryUsage(0, 0, 0, -1);
        }

        @Override
        public boolean isVerbose() {
            return false;
        }

        @Override
        public void setVerbose(boolean value) {
        }

        @Override
        public void gc() {
            System.gc();
        }

        @Override
        public ObjectName getObjectName() {
            return null;
        }
    };

    static final RuntimeMXBean RUNTIME = new RuntimeMXBean() {
        @Override
        public long getPid() {
            return 1;
        }

        @Override
        public String getName() {
            return "1@browser";
        }

        @Override
        public String getVmName() {
            return System.getProperty("java.vm.name", "Web Image");
        }

        @Override
        public String getVmVendor() {
            return System.getProperty("java.vm.vendor", "");
        }

        @Override
        public String getVmVersion() {
            return System.getProperty("java.vm.version", "");
        }

        @Override
        public String getSpecName() {
            return "Java Virtual Machine Specification";
        }

        @Override
        public String getSpecVendor() {
            return "Oracle Corporation";
        }

        @Override
        public String getSpecVersion() {
            return System.getProperty("java.specification.version", "");
        }

        @Override
        public String getManagementSpecVersion() {
            return "4.0";
        }

        @Override
        public String getClassPath() {
            return System.getProperty("java.class.path", "");
        }

        @Override
        public String getLibraryPath() {
            return System.getProperty("java.library.path", "");
        }

        @Override
        public boolean isBootClassPathSupported() {
            return false;
        }

        @Override
        public String getBootClassPath() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> getInputArguments() {
            return List.of();
        }

        @Override
        public long getUptime() {
            return System.currentTimeMillis() - START_MILLIS;
        }

        @Override
        public long getStartTime() {
            return START_MILLIS;
        }

        @Override
        public Map<String, String> getSystemProperties() {
            Map<String, String> map = new TreeMap<>();
            Properties props = System.getProperties();
            for (String key : props.stringPropertyNames()) {
                map.put(key, props.getProperty(key));
            }
            return map;
        }

        @Override
        public ObjectName getObjectName() {
            return null;
        }
    };
}

@TargetClass(java.lang.management.ManagementFactory.class)
final class Target_java_lang_management_ManagementFactory {
    @Substitute
    public static List<GarbageCollectorMXBean> getGarbageCollectorMXBeans() {
        return List.of();
    }

    @Substitute
    public static MemoryMXBean getMemoryMXBean() {
        return ManagementSubstitutions.MEMORY;
    }

    @Substitute
    public static RuntimeMXBean getRuntimeMXBean() {
        return ManagementSubstitutions.RUNTIME;
    }
}
