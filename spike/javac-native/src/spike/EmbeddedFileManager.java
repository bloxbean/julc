package spike;

import javax.tools.FileObject;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardLocation;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;

/**
 * A JavaFileManager with no disk and no jrt:/ access. Its only system module is java.base, made of the class
 * files embedded in one resource blob ("platform.bin", written by tools/MakePlatformBlob). Source comes from
 * in-memory JavaFileObjects passed to getTask; nothing is written (analyze() needs no output).
 */
final class EmbeddedFileManager implements JavaFileManager {

    /** The java.base module location inside SYSTEM_MODULES. */
    static final class ModuleLocation implements Location {
        final String moduleName;

        ModuleLocation(String moduleName) {
            this.moduleName = moduleName;
        }

        @Override
        public String getName() {
            return "SYSTEM_MODULES[" + moduleName + "]";
        }

        @Override
        public boolean isOutputLocation() {
            return false;
        }

        @Override
        public boolean isModuleOrientedLocation() {
            return false;
        }

        @Override
        public String toString() {
            return getName();
        }
    }

    static final class EmbeddedClass extends SimpleJavaFileObject {
        final String binaryName;
        final String module;
        final byte[] bytes;
        final EmbeddedFileManager owner;

        EmbeddedClass(EmbeddedFileManager owner, String module, String binaryName, byte[] bytes) {
            super(URI.create("embedded:///" + module + "/" + binaryName.replace('.', '/') + ".class"), Kind.CLASS);
            this.owner = owner;
            this.module = module;
            this.binaryName = binaryName;
            this.bytes = bytes;
        }

        @Override
        public InputStream openInputStream() {
            owner.opened.add(module + "/" + binaryName.replace('.', '/') + ".class");
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public String getName() {
            return toUri().getPath();
        }
    }

    /** Blob entries under this prefix are served on CLASS_PATH rather than as a system module. */
    static final String CLASS_PATH_MODULE = "classpath";

    // module -> package -> simple file name -> class
    private final Map<String, Map<String, Map<String, EmbeddedClass>>> modules = new TreeMap<>();
    private final Map<String, ModuleLocation> moduleLocations = new TreeMap<>();
    final Set<String> opened = Collections.synchronizedSet(new TreeSet<>());
    private int count;

    static EmbeddedFileManager fromResource(String resource) throws IOException {
        InputStream raw = EmbeddedFileManager.class.getResourceAsStream(resource);
        if (raw == null) {
            throw new IOException("missing resource " + resource);
        }
        EmbeddedFileManager fm = new EmbeddedFileManager();
        // gzip blob (magic 1f 8b) or the same payload uncompressed, for targets without java.util.zip.Inflater.
        BufferedInputStream buffered = new BufferedInputStream(raw, 1 << 16);
        buffered.mark(2);
        boolean gzip = buffered.read() == 0x1f && buffered.read() == 0x8b;
        buffered.reset();
        try (DataInputStream in = new DataInputStream(gzip ? new GZIPInputStream(buffered, 1 << 16) : buffered)) {
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                String path = in.readUTF(); // e.g. java.base/java/lang/Object.class
                byte[] data = new byte[in.readInt()];
                in.readFully(data);
                fm.add(path, data);
            }
        }
        return fm;
    }

    private void add(String path, byte[] data) {
        int slash = path.indexOf('/');
        String module = path.substring(0, slash);
        String rel = path.substring(slash + 1, path.length() - ".class".length());
        int last = rel.lastIndexOf('/');
        String pkg = last < 0 ? "" : rel.substring(0, last).replace('/', '.');
        String simple = rel.substring(last + 1);
        String binary = pkg.isEmpty() ? simple : pkg + "." + simple;
        if (!module.equals(CLASS_PATH_MODULE)) {
            moduleLocations.computeIfAbsent(module, ModuleLocation::new);
        }
        modules.computeIfAbsent(module, m -> new TreeMap<>())
                .computeIfAbsent(pkg, p -> new TreeMap<>())
                .put(simple, new EmbeddedClass(this, module, binary, data));
        count++;
    }

    int classCount() {
        return count;
    }

    void printOpened(String label) {
        System.out.println("OPENED " + label + " " + opened.size());
        for (String s : opened) {
            System.out.println("OPENED " + s);
        }
    }

    @Override
    public ClassLoader getClassLoader(Location location) {
        return null; // no annotation processors or plugins
    }

    @Override
    public Iterable<JavaFileObject> list(Location location, String packageName, Set<JavaFileObject.Kind> kinds,
                                         boolean recurse) {
        String module = moduleOf(location);
        if (module == null || !kinds.contains(JavaFileObject.Kind.CLASS)) {
            return List.of();
        }
        Map<String, Map<String, EmbeddedClass>> pkgs = modules.get(module);
        List<JavaFileObject> result = new ArrayList<>();
        if (recurse) {
            for (Map.Entry<String, Map<String, EmbeddedClass>> e : pkgs.entrySet()) {
                String p = e.getKey();
                if (p.equals(packageName) || packageName.isEmpty() || p.startsWith(packageName + ".")) {
                    result.addAll(e.getValue().values());
                }
            }
        } else {
            Map<String, EmbeddedClass> files = pkgs.get(packageName);
            if (files != null) {
                result.addAll(files.values());
            }
        }
        return result;
    }

    /** The embedded "module" a location reads: a java.base-style system module, or "classpath" for CLASS_PATH. */
    private String moduleOf(Location location) {
        if (location instanceof ModuleLocation ml) return ml.moduleName;
        if (location == StandardLocation.CLASS_PATH && modules.containsKey(CLASS_PATH_MODULE)) return CLASS_PATH_MODULE;
        return null;
    }

    @Override
    public String inferBinaryName(Location location, JavaFileObject file) {
        if (file instanceof EmbeddedClass ec) {
            return ec.binaryName;
        }
        return null;
    }

    @Override
    public boolean isSameFile(FileObject a, FileObject b) {
        return a.toUri().equals(b.toUri());
    }

    @Override
    public boolean handleOption(String current, Iterator<String> remaining) {
        return false;
    }

    @Override
    public boolean hasLocation(Location location) {
        return location == StandardLocation.SYSTEM_MODULES || location instanceof ModuleLocation
                || (location == StandardLocation.CLASS_PATH && modules.containsKey(CLASS_PATH_MODULE));
    }

    @Override
    public JavaFileObject getJavaFileForInput(Location location, String className, JavaFileObject.Kind kind) {
        String module = moduleOf(location);
        if (module != null && kind == JavaFileObject.Kind.CLASS) {
            int last = className.lastIndexOf('.');
            String pkg = last < 0 ? "" : className.substring(0, last);
            String simple = className.substring(last + 1);
            Map<String, EmbeddedClass> files = modules.get(module).get(pkg);
            return files == null ? null : files.get(simple);
        }
        return null;
    }

    @Override
    public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind,
                                               FileObject sibling) {
        return new SimpleJavaFileObject(URI.create("mem:///" + className.replace('.', '/') + kind.extension), kind) {
            @Override
            public OutputStream openOutputStream() {
                return new ByteArrayOutputStream();
            }
        };
    }

    @Override
    public FileObject getFileForInput(Location location, String packageName, String relativeName) {
        return null;
    }

    @Override
    public FileObject getFileForOutput(Location location, String packageName, String relativeName,
                                       FileObject sibling) {
        return getJavaFileForOutput(location, packageName + "." + relativeName, JavaFileObject.Kind.OTHER, sibling);
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }

    @Override
    public int isSupportedOption(String option) {
        return -1;
    }

    @Override
    public Location getLocationForModule(Location location, String moduleName) {
        if (location == StandardLocation.SYSTEM_MODULES) {
            return moduleLocations.get(moduleName);
        }
        return null;
    }

    @Override
    public Location getLocationForModule(Location location, JavaFileObject fo) {
        if (location == StandardLocation.SYSTEM_MODULES && fo instanceof EmbeddedClass ec) {
            return moduleLocations.get(ec.module);
        }
        return null;
    }

    @Override
    public String inferModuleName(Location location) {
        return location instanceof ModuleLocation ml ? ml.moduleName : null;
    }

    @Override
    public Iterable<Set<Location>> listLocationsForModules(Location location) {
        if (location == StandardLocation.SYSTEM_MODULES) {
            Set<Location> all = new TreeSet<>(Comparator.comparing(Location::getName));
            all.addAll(moduleLocations.values());
            return List.of(all);
        }
        return List.of();
    }

    @Override
    public boolean contains(Location location, FileObject fo) {
        String module = moduleOf(location);
        return module != null && fo instanceof EmbeddedClass ec && ec.module.equals(module);
    }
}
