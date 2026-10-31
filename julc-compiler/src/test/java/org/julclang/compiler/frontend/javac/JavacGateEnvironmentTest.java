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
class JavacGateEnvironmentTest {

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
        var stdlib = classPath().stream().filter(JavacGateEnvironmentTest::isStdlib).toList();
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
                        ? JavacGateEnvironmentTest.class.getResource("JavacGateEnvironmentTest.class") : null;
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

    /** Boundary: a loader that denies the compiler API gives JULC0089, in report mode without an exception. */
    @Test
    void aLoaderWithoutTheCompilerApiIsJulc0089() throws Exception {
        try (var loader = withoutCompilerApi()) {
            Class<?> modes = loader.loadClass(JavacGateMode.class.getName());
            Class<?> optionsClass = loader.loadClass("org.julclang.compiler.CompilerOptions");
            Class<?> unit = loader.loadClass(JavacFrontend.Unit.class.getName());
            Class<?> gate = loader.loadClass(JavacGate.class.getName());
            Object root = unit.getMethod("of", String.class).invoke(null, "class A {}");
            var check = gate.getMethod("check", String.class, List.class, List.class, optionsClass);
            for (String mode : List.of("REPORT", "ENFORCE")) {
                Object options = optionsClass.getConstructor().newInstance();
                optionsClass.getMethod("setJavacGate", modes).invoke(options, Enum.valueOf(cast(modes), mode));
                String code;
                try {
                    Object result = check.invoke(null, "probe", List.of(root), List.of(), options);
                    assertEquals("REPORT", mode, "enforce must reject");
                    code = codeOf(result.getClass().getMethod("diagnostics").invoke(result));
                } catch (InvocationTargetException e) {
                    assertEquals("ENFORCE", mode, String.valueOf(e.getCause()));
                    assertEquals("org.julclang.compiler.CompilerException", e.getCause().getClass().getName(),
                            String.valueOf(e.getCause()));
                    code = codeOf(e.getCause().getClass().getMethod("diagnostics").invoke(e.getCause()));
                }
                assertEquals("JULC0089", code, mode);
            }
        }
    }

    /** I6: the javac frontend on a runtime without the compiler API is JULC0089, at every door. */
    @Test
    void aLoaderWithoutTheCompilerApiIsJulc0089OnTheJavacFrontend() throws Exception {
        try (var loader = withoutCompilerApi()) {
            Class<?> frontends = loader.loadClass("org.julclang.compiler.CompilerFrontend");
            Class<?> optionsClass = loader.loadClass("org.julclang.compiler.CompilerOptions");
            Class<?> lookup = loader.loadClass("org.julclang.compiler.pir.StdlibLookup");
            Object options = optionsClass.getConstructor().newInstance();
            optionsClass.getMethod("setFrontend", frontends).invoke(options, Enum.valueOf(cast(frontends), "JAVAC"));
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

    /** F530: without a javac, readJars fails with JULC0089 when enforced, and keeps B0's path check otherwise. */
    @Test
    void readJarsWithoutJavacFailsWhenEnforcedAndChecksPathsOtherwise(@TempDir Path dir) throws Exception {
        Path foo = JavacGateReviewTest.jar(dir.resolve("foo.jar"), "app/Foo.java", JavacGateReviewTest.FOO);
        Path bar = JavacGateReviewTest.jar(dir.resolve("bar.jar"), "app/Bar.java", JavacGateReviewTest.BAR);
        String saved = System.getProperty(JavacGate.MODE_PROPERTY);
        try (var loader = withoutCompilerApi()) {
            var readJars = loader.loadClass("org.julclang.compiler.LibrarySources").getMethod("readJars", List.class);
            System.setProperty(JavacGate.MODE_PROPERTY, "enforce");
            var e = assertThrows(InvocationTargetException.class, () -> readJars.invoke(null, List.of(foo, bar)));
            assertEquals("org.julclang.compiler.CompilerException", e.getCause().getClass().getName());
            assertEquals("JULC0089", codeOf(e.getCause().getClass().getMethod("diagnostics").invoke(e.getCause())));
            for (String mode : List.of("report", "off")) {
                System.setProperty(JavacGate.MODE_PROPERTY, mode);
                assertEquals(2, ((java.util.Map<?, ?>) readJars.invoke(null, List.of(foo, bar))).size(), mode);
            }
            // A clash of entry paths is still B0's error.
            Path again = JavacGateReviewTest.jar(dir.resolve("again.jar"), "app/Foo.java", JavacGateReviewTest.FOO);
            var path = assertThrows(InvocationTargetException.class, () -> readJars.invoke(null, List.of(foo, again)));
            assertTrue(path.getCause().getMessage().startsWith("Duplicate library source ownership"),
                    path.getCause().getMessage());
        } finally {
            if (saved == null) System.clearProperty(JavacGate.MODE_PROPERTY);
            else System.setProperty(JavacGate.MODE_PROPERTY, saved);
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
