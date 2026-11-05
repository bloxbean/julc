package org.julclang.compiler.frontend.javac;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Regressions for the M1a re-review: F510 and the two boundary gaps (child JVMs and isolated class loaders). */
class JavacEnvironmentTest {

    static List<String> classPath() {
        return List.of(System.getProperty("java.class.path").split(File.pathSeparator));
    }

    static boolean isStdlib(String entry) {
        return entry.replace('\\', '/').contains("/julc-stdlib/");
    }

    /** F510: no stale rejection or acceptance survives the bundled set going from 0 to 16 sources. */
    @Test
    void resultsAreScopedToTheEnvironmentThatProducedThem() throws Exception {
        var withoutStdlib = classPath().stream().filter(e -> !isStdlib(e)).toList();
        var stdlib = classPath().stream().filter(JavacEnvironmentTest::isStdlib).toList();
        assertFalse(stdlib.isEmpty(), "julc-stdlib must be on the test class path");
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", String.join(File.pathSeparator, withoutStdlib), JavacEnvironmentProbe.class.getName()));
        command.addAll(stdlib);
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertEquals(0, process.waitFor(), output);
        var lines = output.lines().filter(l -> l.startsWith("bundled=")).toList();
        assertEquals(List.of(
                "bundled=0 usesStdlib=rejected JULC0085 compiler.err.doesnt.exist inStdlibPackage=accepted",
                "bundled=16 usesStdlib=accepted inStdlibPackage=rejected JULC0081"), lines, output);
    }

    /** Boundary: julc-stdlib with an empty manifest is a broken build. */
    @Test
    void anEmptyManifestWithStdlibPresentIsABrokenBuild() {
        ClassLoader emptyManifest = new ClassLoader(null) {
            @Override
            public URL getResource(String name) {
                return name.equals(JavacFrontend.BundledSources.STDLIB_MARKER)
                        ? JavacEnvironmentTest.class.getResource("JavacEnvironmentTest.class") : null;
            }

            @Override
            public InputStream getResourceAsStream(String name) {
                return name.equals(JavacFrontend.BundledSources.RESOURCE) ? new ByteArrayInputStream(new byte[0])
                        : null;
            }
        };
        var e = assertThrows(BrokenJulcBuildException.class, () -> JavacFrontend.BundledSources.load(emptyManifest));
        assertTrue(e.getMessage().contains("lists no bundled source"), e.getMessage());
    }

    /** A loader over this test's class path that loads JuLC itself and denies the compiler API. */
    static URLClassLoader withoutCompilerApi() throws Exception {
        var urls = new ArrayList<URL>();
        for (String entry : classPath()) urls.add(Path.of(entry).toUri().toURL());
        return new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("javax.tools.") || name.startsWith("com.sun.source.")
                        || name.startsWith("javax.lang.model.")) {
                    throw new ClassNotFoundException(name + " (denied)");
                }
                synchronized (getClassLoadingLock(name)) {
                    Class<?> c = findLoadedClass(name);
                    if (c == null && !name.startsWith("java.") && !name.startsWith("jdk.") && !name.startsWith("sun.")
                            && !name.startsWith("javax.")) {
                        try {
                            c = findClass(name);
                        } catch (ClassNotFoundException ignored) {
                            // fall through to the parent
                        }
                    }
                    return c != null ? c : super.loadClass(name, resolve);
                }
            }
        };
    }

    /** I6: the javac frontend on a runtime without the compiler API is JULC0089, at every door. */
    @Test
    void aLoaderWithoutTheCompilerApiIsJulc0089ForACompilation() throws Exception {
        try (var loader = withoutCompilerApi()) {
            Class<?> optionsClass = loader.loadClass("org.julclang.compiler.CompilerOptions");
            Class<?> lookup = loader.loadClass("org.julclang.compiler.pir.StdlibLookup");
            Object options = optionsClass.getConstructor().newInstance();
            Class<?> compilerClass = loader.loadClass("org.julclang.compiler.JulcCompiler");
            Object compiler = compilerClass.getConstructor(lookup, optionsClass).newInstance(null, options);
            var compile = assertThrows(InvocationTargetException.class, () -> compilerClass
                    .getMethod("compileMethod", String.class, String.class, List.class)
                    .invoke(compiler, "class A { static int m() { return 1; } }", "m", List.of()));
            assertEquals("JULC0089", codeOf(compile.getCause().getClass().getMethod("diagnostics").invoke(compile.getCause())));
            Class<?> providerClass = loader.loadClass("org.julclang.compiler.JavaLibraryProvider");
            var provider = assertThrows(InvocationTargetException.class, () -> providerClass
                    .getConstructor(List.class, lookup, optionsClass).newInstance(List.of(), null, options));
            assertEquals("JULC0089", codeOf(provider.getCause().getClass().getMethod("diagnostics").invoke(provider.getCause())));
        }
    }

    /** F530: without a javac, readJars fails with JULC0089. */
    @Test
    void readJarsWithoutJavacFails(@TempDir Path dir) throws Exception {
        Path foo = JavacFrontendPoolTest.jar(dir.resolve("foo.jar"), "app/Foo.java", JavacFrontendPoolTest.FOO);
        Path bar = JavacFrontendPoolTest.jar(dir.resolve("bar.jar"), "app/Bar.java", JavacFrontendPoolTest.BAR);
        try (var loader = withoutCompilerApi()) {
            var readJars = loader.loadClass("org.julclang.compiler.LibrarySources").getMethod("readJars", List.class);
            var e = assertThrows(InvocationTargetException.class, () -> readJars.invoke(null, List.of(foo, bar)));
            assertEquals("org.julclang.compiler.CompilerException", e.getCause().getClass().getName());
            assertEquals("JULC0089", codeOf(e.getCause().getClass().getMethod("diagnostics").invoke(e.getCause())));
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Class<Enum> cast(Class<?> type) {
        return (Class<Enum>) type;
    }

    private static String codeOf(Object diagnostics) throws Exception {
        Object first = ((List<?>) diagnostics).getFirst();
        return (String) first.getClass().getMethod("code").invoke(first);
    }
}
