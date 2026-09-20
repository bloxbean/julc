package org.julclang.compiler;

import org.julclang.core.Program;
import org.julclang.core.flat.UplcFlatEncoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Properties;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class CompilerVersionTest {
    private static final String RESOURCE = "org/julclang/compiler/version.properties";
    private static final String SOURCE = "class Sample { static long increment(long n) { return n + 1; } }";

    enum Metadata {
        MISSING, UNFILTERED, NO_VERSION, BLANK, MALFORMED, UNREADABLE, VALID
    }

    @Test
    void gradleBuildReportsTheActualFilteredVersion() throws IOException {
        var expected = System.getProperty("julc.test.expectedCompilerVersion");
        assertNotNull(expected, "Gradle must supply its project version independently of the resource");
        var properties = new Properties();
        try (var input = CompilerVersion.class.getResourceAsStream("version.properties")) {
            assertNotNull(input);
            properties.load(input);
        }
        assertEquals(expected, properties.getProperty("version"));
        assertEquals(expected, new JulcCompiler().compileMethod(SOURCE, "increment")
                .optimizationReport().compilerVersion());
    }

    @ParameterizedTest
    @EnumSource(Metadata.class)
    void metadataNeverPoisonsRepeatedCompilation(Metadata metadata) throws Exception {
        var expectedBytes = UplcFlatEncoder.encodeProgram(
                new JulcCompiler().compileMethod(SOURCE, "increment").program());
        var location = JulcCompiler.class.getProtectionDomain().getCodeSource().getLocation();
        // Each scenario initializes CompilerVersion anew; sharing the application's
        // already-initialized class would hide ExceptionInInitializerError regressions.
        try (var loader = new IsolatedCompilerLoader(location, metadata)) {
            var compilerClass = loader.loadClass(JulcCompiler.class.getName());
            var optionsClass = loader.loadClass(CompilerOptions.class.getName());
            var constructor = compilerClass.getConstructor(
                    loader.loadClass("org.julclang.compiler.pir.StdlibLookup"), optionsClass);
            var expectedVersion = metadata == Metadata.VALID ? "test-build-153" : "dev";
            for (boolean verbose : new boolean[] { false, true }) {
                var logs = new ArrayList<String>();
                var options = optionsClass.getConstructor().newInstance();
                optionsClass.getMethod("setVerbose", boolean.class).invoke(options, verbose);
                optionsClass.getMethod("setLogger", Consumer.class).invoke(options, (Consumer<String>) logs::add);
                var compiler = constructor.newInstance(null, options);
                for (int attempt = 0; attempt < 2; attempt++) {
                    var result = compilerClass.getMethod("compileMethod", String.class, String.class)
                            .invoke(compiler, SOURCE, "increment");
                    assertEquals(false, result.getClass().getMethod("hasErrors").invoke(result));
                    var program = (Program) result.getClass().getMethod("program").invoke(result);
                    assertArrayEquals(expectedBytes, UplcFlatEncoder.encodeProgram(program));
                    var report = result.getClass().getMethod("optimizationReport").invoke(result);
                    assertEquals(expectedVersion, report.getClass().getMethod("compilerVersion").invoke(report));
                }
                assertEquals(verbose, logs.stream().anyMatch(line ->
                        line.contains("Compiler version: " + expectedVersion)));
            }
        }
    }

    private static final class IsolatedCompilerLoader extends URLClassLoader {
        private final Metadata metadata;

        IsolatedCompilerLoader(URL location, Metadata metadata) {
            super(new URL[] { location }, CompilerVersionTest.class.getClassLoader());
            this.metadata = metadata;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith("org.julclang.compiler.")) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                var loaded = findLoadedClass(name);
                if (loaded == null) loaded = findClass(name);
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            if (!RESOURCE.equals(name)) return super.getResourceAsStream(name);
            if (metadata == Metadata.MISSING) return null;
            if (metadata == Metadata.UNREADABLE) {
                return new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw new IOException("unreadable version metadata");
                    }
                };
            }
            var text = switch (metadata) {
                case UNFILTERED -> "version=@julcVersion@\n";
                case NO_VERSION -> "other=value\n";
                case BLANK -> "version= \n";
                case MALFORMED -> "version=\\uZZZZ\n";
                case VALID -> "version=test-build-153\n";
                default -> throw new AssertionError(metadata);
            };
            return new ByteArrayInputStream(text.getBytes(StandardCharsets.ISO_8859_1));
        }
    }
}
