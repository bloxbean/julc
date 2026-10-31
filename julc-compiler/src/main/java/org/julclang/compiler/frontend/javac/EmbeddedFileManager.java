package org.julclang.compiler.frontend.javac;

import javax.tools.FileObject;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardLocation;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The file manager of every javac task JuLC builds (ADR-068 D3, I10). It never touches the file system, {@code jrt:/}
 * or {@code ct.sym}:
 * <ul>
 *   <li>{@code SYSTEM_MODULES}: one module, {@code java.base}, from the signature-only platform blob;</li>
 *   <li>{@code CLASS_PATH}: the signature-only stubs of JuLC's on-chain API;</li>
 *   <li>{@code SOURCE_PATH}: the host's pool, in memory.</li>
 * </ul>
 * Every other location is empty. Nothing is written: {@code analyze()} produces no output. One instance serves one
 * task; the platform's bytes are shared.
 */
final class EmbeddedFileManager implements JavaFileManager {

    /** The {@code java.base} location inside {@code SYSTEM_MODULES}. */
    private static final class ModuleLocation implements Location {
        @Override
        public String getName() {
            return "SYSTEM_MODULES[" + JavacPlatform.JAVA_BASE + "]";
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

    /** A class file of the platform or the stubs. */
    private final class EmbeddedClass extends SimpleJavaFileObject {
        final String binaryName;
        final boolean system;
        final byte[] bytes;

        EmbeddedClass(boolean system, String binaryName, byte[] bytes) {
            super(URI.create("embedded:///" + (system ? JavacPlatform.JAVA_BASE : "classpath") + "/"
                    + binaryName.replace('.', '/') + ".class"), Kind.CLASS);
            this.system = system;
            this.binaryName = binaryName;
            this.bytes = bytes;
        }

        @Override
        public InputStream openInputStream() {
            if (opened != null) opened.accept((system ? JavacPlatform.JAVA_BASE + "/" : "")
                    + binaryName.replace('.', '/') + ".class");
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public String getName() {
            return toUri().getPath();
        }
    }

    private final ModuleLocation javaBase = new ModuleLocation();
    private final JavacPlatform platform;
    /** package -> simple name -> pool source. */
    private final Map<String, Map<String, JavacSourceFile>> sourcePath = new TreeMap<>();
    /** Observes every class file javac opens (the platform derivation), or null. */
    private final Consumer<String> opened;

    EmbeddedFileManager(JavacPlatform platform, List<JavacSourceFile> pool, Consumer<String> opened) {
        this.platform = platform;
        this.opened = opened;
        for (var source : pool) {
            sourcePath.computeIfAbsent(source.packageName(), p -> new TreeMap<>()).put(source.simpleName(), source);
        }
    }

    @Override
    public ClassLoader getClassLoader(Location location) {
        return null; // -proc:none: no processors, no plugins
    }

    @Override
    public Iterable<JavaFileObject> list(Location location, String packageName, Set<JavaFileObject.Kind> kinds,
                                         boolean recurse) {
        List<JavaFileObject> result = new ArrayList<>();
        if (location == StandardLocation.SOURCE_PATH) {
            if (kinds.contains(JavaFileObject.Kind.SOURCE)) {
                select(sourcePath, packageName, recurse, (pkg, files) -> result.addAll(files.values()));
            }
            return result;
        }
        Map<String, Map<String, byte[]>> classes = classesOf(location);
        if (classes == null || !kinds.contains(JavaFileObject.Kind.CLASS)) return result;
        boolean system = location == javaBase;
        select(classes, packageName, recurse, (pkg, files) -> files.forEach((simple, bytes) ->
                result.add(new EmbeddedClass(system, binaryName(pkg, simple), bytes))));
        return result;
    }

    private static <V> void select(Map<String, V> packages, String packageName, boolean recurse,
                                   BiConsumer<String, V> action) {
        if (!recurse) {
            V files = packages.get(packageName);
            if (files != null) action.accept(packageName, files);
            return;
        }
        packages.forEach((pkg, files) -> {
            if (packageName.isEmpty() || pkg.equals(packageName) || pkg.startsWith(packageName + ".")) {
                action.accept(pkg, files);
            }
        });
    }

    private Map<String, Map<String, byte[]>> classesOf(Location location) {
        if (location == javaBase) return platform.system();
        if (location == StandardLocation.CLASS_PATH) return platform.classPath();
        return null;
    }

    private static String binaryName(String pkg, String simple) {
        return pkg.isEmpty() ? simple : pkg + "." + simple;
    }

    @Override
    public String inferBinaryName(Location location, JavaFileObject file) {
        if (file instanceof EmbeddedClass c) return c.binaryName;
        if (file instanceof JavacSourceFile s) return s.binaryName();
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
        return location == StandardLocation.SYSTEM_MODULES || location == javaBase
                || location == StandardLocation.CLASS_PATH || location == StandardLocation.SOURCE_PATH;
    }

    @Override
    public JavaFileObject getJavaFileForInput(Location location, String className, JavaFileObject.Kind kind) {
        int last = className.lastIndexOf('.');
        String pkg = last < 0 ? "" : className.substring(0, last);
        String simple = className.substring(last + 1);
        if (location == StandardLocation.SOURCE_PATH) {
            if (kind != JavaFileObject.Kind.SOURCE) return null;
            Map<String, JavacSourceFile> files = sourcePath.get(pkg);
            return files == null ? null : files.get(simple);
        }
        Map<String, Map<String, byte[]>> classes = classesOf(location);
        if (classes == null || kind != JavaFileObject.Kind.CLASS) return null;
        Map<String, byte[]> files = classes.get(pkg);
        byte[] bytes = files == null ? null : files.get(simple);
        return bytes == null ? null : new EmbeddedClass(location == javaBase, className, bytes);
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
        return location == StandardLocation.SYSTEM_MODULES && JavacPlatform.JAVA_BASE.equals(moduleName)
                ? javaBase : null;
    }

    @Override
    public Location getLocationForModule(Location location, JavaFileObject fo) {
        return location == StandardLocation.SYSTEM_MODULES && fo instanceof EmbeddedClass c && c.system
                ? javaBase : null;
    }

    @Override
    public String inferModuleName(Location location) {
        return location == javaBase ? JavacPlatform.JAVA_BASE : null;
    }

    @Override
    public Iterable<Set<Location>> listLocationsForModules(Location location) {
        return location == StandardLocation.SYSTEM_MODULES ? List.of(Set.of(javaBase)) : List.of();
    }

    @Override
    public boolean contains(Location location, FileObject fo) {
        if (fo instanceof EmbeddedClass c) {
            return c.system ? location == javaBase : location == StandardLocation.CLASS_PATH;
        }
        return location == StandardLocation.SOURCE_PATH && fo instanceof JavacSourceFile;
    }
}
