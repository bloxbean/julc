package org.julclang.processor;

import org.julclang.clientlib.JulcScriptLoader;
import org.julclang.clientlib.ValidatorOutput;
import org.julclang.core.Term;
import org.julclang.core.source.SourceMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Issue #326: validators with one simple name in different packages, such as {@code com.a.VaultValidator} and
 * {@code com.b.VaultValidator}, each load their own script and source map, never the other's.
 */
class SameSimpleNameValidatorsTest {

    @TempDir
    Path tempDir;

    /** {@code com.a}'s validator accepts everything. */
    static final String VAULT_A = """
            package com.a;

            import org.julclang.core.PlutusData;
            import org.julclang.ledger.ScriptContext;
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.SpendingValidator;

            @SpendingValidator
            public class VaultValidator {
                @Entrypoint
                public static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                    return true;
                }
            }
            """;

    /** {@code com.b}'s validator differs, and its code starts below line {@value #B_FIRST_LINE}. */
    static final String VAULT_B = "\n".repeat(40) + """
            package com.b;

            import org.julclang.core.PlutusData;
            import org.julclang.ledger.ScriptContext;
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.SpendingValidator;

            @SpendingValidator
            public class VaultValidator {
                @Entrypoint
                public static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                    return ctx.txInfo().fee().signum() > 0;
                }
            }
            """;

    static final int B_FIRST_LINE = 40;

    record Module(Path classes, List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        List<String> errors() {
            return diagnostics.stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    .map(d -> d.getMessage(null)).toList();
        }
    }

    /** Compiles {@code sources} (path -> text) to classes and scripts in a directory of its own. */
    Module compile(String name, Map<String, String> sources, List<String> extraOptions) throws IOException {
        Path classes = Files.createDirectories(tempDir.resolve(name));
        var javac = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var fileManager = javac.getStandardFileManager(diagnostics, null, null)) {
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(classes.toFile()));
            var files = sources.entrySet().stream().map(e -> (JavaFileObject) new SimpleJavaFileObject(
                    URI.create("string:///" + e.getKey()), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return e.getValue();
                }
            }).toList();
            var options = new ArrayList<>(List.of("-processor", JulcAnnotationProcessor.class.getName()));
            options.addAll(extraOptions);
            javac.getTask(null, fileManager, diagnostics, options, null, files).call();
        }
        return new Module(classes, diagnostics.getDiagnostics());
    }

    Module moduleA() throws IOException {
        var module = compile("a", Map.of("com/a/VaultValidator.java", VAULT_A), List.of("-Ajulc.sourceMap=true"));
        assertEquals(List.of(), module.errors());
        return module;
    }

    Module moduleB() throws IOException {
        var module = compile("b", Map.of("com/b/VaultValidator.java", VAULT_B), List.of("-Ajulc.sourceMap=true"));
        assertEquals(List.of(), module.errors());
        return module;
    }

    static URLClassLoader classPath(Path... roots) throws IOException {
        var urls = new URL[roots.length];
        for (int i = 0; i < roots.length; i++) urls[i] = roots[i].toUri().toURL();
        return new URLClassLoader(urls, SameSimpleNameValidatorsTest.class.getClassLoader());
    }

    static String json(Path root, String path) throws IOException {
        return Files.readString(root.resolve(path));
    }

    static String cbor(Path root, String path) throws IOException {
        return ValidatorOutput.fromJson(json(root, path)).cborHex();
    }

    static final String A_SCRIPT = "META-INF/plutus/com/a/VaultValidator.plutus.json";
    static final String B_SCRIPT = "META-INF/plutus/com/b/VaultValidator.plutus.json";
    static final String SIMPLE_SCRIPT = "META-INF/plutus/VaultValidator.plutus.json";
    static final String SIMPLE_SOURCE_MAP = "META-INF/plutus/VaultValidator.sourcemap.json";

    @Test
    void eachScriptIsWrittenUnderItsQualifiedNameAndItsSimpleName() throws Exception {
        Path b = moduleB().classes();
        assertEquals(json(b, B_SCRIPT), json(b, SIMPLE_SCRIPT));
        assertEquals(json(b, "META-INF/plutus/com/b/VaultValidator.sourcemap.json"), json(b, SIMPLE_SOURCE_MAP));
        var output = ValidatorOutput.fromJson(json(b, B_SCRIPT));
        assertEquals("com.b.VaultValidator", output.validator());
        assertEquals("VaultValidator", output.description(), "the description keeps the simple name");
    }

    /** The issue's repro: both modules on one class path, in either order. */
    @Test
    void eachClassLoadsItsOwnScriptWhicheverModuleComesFirst() throws Exception {
        Path a = moduleA().classes();
        Path b = moduleB().classes();
        assertNotEquals(cbor(a, A_SCRIPT), cbor(b, B_SCRIPT));
        for (var order : List.of(List.of(a, b), List.of(b, a))) {
            try (var loader = classPath(order.toArray(Path[]::new))) {
                var classA = loader.loadClass("com.a.VaultValidator");
                var classB = loader.loadClass("com.b.VaultValidator");
                assertEquals(cbor(a, A_SCRIPT), JulcScriptLoader.load(classA).getCborHex(), "order " + order);
                assertEquals(cbor(b, B_SCRIPT), JulcScriptLoader.load(classB).getCborHex(), "order " + order);
                assertEquals("com.b.VaultValidator", JulcScriptLoader.loadOutput(classB).validator());
            }
        }
    }

    /** The source map comes from beside the script that was loaded: {@code com.b}'s locations, not {@code com.a}'s. */
    @Test
    void eachClassLoadsItsOwnSourceMap() throws Exception {
        Path a = moduleA().classes();
        Path b = moduleB().classes();
        try (var loader = classPath(a, b)) {
            var resultB = JulcScriptLoader.loadWithSourceMap(loader.loadClass("com.b.VaultValidator"));
            assertNotNull(resultB.sourceMap());
            assertFalse(resultB.sourceMap().isEmpty());
            assertEquals(cbor(b, B_SCRIPT), resultB.script().getCborHex());
            assertTrue(lines(resultB).stream().allMatch(line -> line > B_FIRST_LINE), "com.b's lines: " + lines(resultB));

            var resultA = JulcScriptLoader.loadWithSourceMap(loader.loadClass("com.a.VaultValidator"));
            assertNotNull(resultA.sourceMap());
            assertTrue(lines(resultA).stream().allMatch(line -> line < B_FIRST_LINE), "com.a's lines: " + lines(resultA));
        }
    }

    static List<Integer> lines(JulcScriptLoader.LoadResult result) {
        var lines = new ArrayList<Integer>();
        collectLines(result.program().term(), result.sourceMap(), lines);
        assertFalse(lines.isEmpty(), "the source map locates no term");
        return lines;
    }

    static void collectLines(Term term, SourceMap map, List<Integer> lines) {
        var location = map.lookup(term);
        if (location != null) lines.add(location.line());
        switch (term) {
            case Term.Lam lam -> collectLines(lam.body(), map, lines);
            case Term.Apply apply -> {
                collectLines(apply.function(), map, lines);
                collectLines(apply.argument(), map, lines);
            }
            case Term.Force force -> collectLines(force.term(), map, lines);
            case Term.Delay delay -> collectLines(delay.term(), map, lines);
            case Term.Constr constr -> constr.fields().forEach(f -> collectLines(f, map, lines));
            case Term.Case kase -> {
                collectLines(kase.scrutinee(), map, lines);
                kase.branches().forEach(branch -> collectLines(branch, map, lines));
            }
            default -> { }
        }
    }

    /** Modules built before #326 have only the shared simple-name copy, without the class it belongs to. */
    static void makeLegacy(Path root, String qualifiedScript) throws IOException {
        Files.delete(root.resolve(qualifiedScript));
        Files.delete(root.resolve(qualifiedScript.replace(".plutus.json", ".sourcemap.json")));
        var output = ValidatorOutput.fromJson(json(root, SIMPLE_SCRIPT));
        Files.writeString(root.resolve(SIMPLE_SCRIPT), new ValidatorOutput(output.type(), output.purpose(),
                output.description(), output.cborHex(), output.hash(), output.params(), output.sizeBytes()).toJson());
    }

    @Test
    void aLegacyModuleAloneStillLoadsItsScript() throws Exception {
        Path b = moduleB().classes();
        makeLegacy(b, B_SCRIPT);
        assertFalse(json(b, SIMPLE_SCRIPT).contains("\"validator\""));
        try (var loader = classPath(b)) {
            var classB = loader.loadClass("com.b.VaultValidator");
            assertEquals(cbor(b, SIMPLE_SCRIPT), JulcScriptLoader.load(classB).getCborHex());
            assertNotNull(JulcScriptLoader.loadWithSourceMap(classB).sourceMap(), "the simple-name source map");
        }
    }

    /** A legacy module's class takes neither copy of the shared name: two are visible, and they differ. */
    @Test
    void twoDifferentSimpleNameCopiesAreRefused() throws Exception {
        Path a = moduleA().classes();
        Path b = moduleB().classes();
        makeLegacy(b, B_SCRIPT);
        try (var loader = classPath(a, b)) {
            var classB = loader.loadClass("com.b.VaultValidator");
            var error = assertThrows(IllegalArgumentException.class, () -> JulcScriptLoader.load(classB));
            assertTrue(error.getMessage().contains("2 different compiled Plutus scripts for com.b.VaultValidator"),
                    error.getMessage());
            assertTrue(error.getMessage().contains("(com.a.VaultValidator)"), error.getMessage());
            assertTrue(error.getMessage().contains(B_SCRIPT), error.getMessage());
            // com.a is unaffected: its qualified copy is its own.
            assertEquals(cbor(a, A_SCRIPT), JulcScriptLoader.load(loader.loadClass("com.a.VaultValidator")).getCborHex());
        }
    }

    /** A class with no script of its own never takes the simple-name copy recorded for another class. */
    @Test
    void aSimpleNameCopyOfAnotherClassIsRefused() throws Exception {
        Path a = moduleA().classes();
        Path b = moduleB().classes();
        for (String suffix : List.of(".plutus.json", ".sourcemap.json")) {
            Files.delete(b.resolve(B_SCRIPT.replace(".plutus.json", suffix)));
            Files.delete(b.resolve(SIMPLE_SCRIPT.replace(".plutus.json", suffix)));
        }
        try (var loader = classPath(a, b)) {
            var classB = loader.loadClass("com.b.VaultValidator");
            for (var load : List.<Executable>of(
                    () -> JulcScriptLoader.load(classB),
                    () -> JulcScriptLoader.scriptHash(classB),
                    () -> JulcScriptLoader.loadWithSourceMap(classB),
                    () -> JulcScriptLoader.loadSourceMap(classB))) {
                var error = assertThrows(IllegalArgumentException.class, load);
                assertTrue(error.getMessage().contains("belongs to com.a.VaultValidator, not to com.b.VaultValidator"),
                        error.getMessage());
            }
        }
    }

    /** Two different qualified copies of one class, such as a stale jar beside fresh classes, are refused too. */
    @Test
    void twoDifferentQualifiedCopiesAreRefused() throws Exception {
        Path b = moduleB().classes();
        Path stale = compile("stale", Map.of("com/b/VaultValidator.java",
                VAULT_B.replace("signum() > 0", "signum() < 0")), List.of()).classes();
        assertNotEquals(cbor(b, B_SCRIPT), cbor(stale, B_SCRIPT));
        try (var loader = classPath(b, stale)) {
            var classB = loader.loadClass("com.b.VaultValidator");
            var error = assertThrows(IllegalArgumentException.class, () -> JulcScriptLoader.load(classB));
            assertTrue(error.getMessage().contains("2 different compiled Plutus scripts for com.b.VaultValidator at "
                    + B_SCRIPT), error.getMessage());
        }
    }

    /** Identical copies of one script, such as the same module twice, are one script. */
    @Test
    void identicalCopiesAreOneScript() throws Exception {
        Path b = moduleB().classes();
        Path again = compile("again", Map.of("com/b/VaultValidator.java", VAULT_B), List.of("-Ajulc.sourceMap=true"))
                .classes();
        assertEquals(json(b, B_SCRIPT), json(again, B_SCRIPT));
        try (var loader = classPath(b, again)) {
            assertEquals(cbor(b, B_SCRIPT), JulcScriptLoader.load(loader.loadClass("com.b.VaultValidator")).getCborHex());
        }
    }

    /** Within one compilation, two validators that share a simple name fail the build with both names. */
    @Test
    void oneCompilationRefusesTwoValidatorsWithOneSimpleName() throws Exception {
        var module = compile("both", Map.of("com/a/VaultValidator.java", VAULT_A,
                "com/b/VaultValidator.java", VAULT_B), List.of());
        var errors = module.errors();
        assertEquals(1, errors.size(), module.diagnostics().toString());
        assertTrue(errors.getFirst().contains("Two validators in this compilation are named VaultValidator"),
                errors.getFirst());
        assertTrue(errors.getFirst().contains("com.a.VaultValidator") && errors.getFirst().contains("com.b.VaultValidator"),
                errors.getFirst());
    }

    /** A class in the unnamed package has one path: its qualified name is its simple name. */
    @Test
    void anUnnamedPackageValidatorIsWrittenOnce() throws Exception {
        String source = VAULT_A.replace("package com.a;\n", "");
        Path root = compile("unnamed", Map.of("VaultValidator.java", source), List.of()).classes();
        try (var files = Files.list(root.resolve("META-INF/plutus"))) {
            assertEquals(List.of("VaultValidator.plutus.json", "plutus.json"),
                    files.map(p -> p.getFileName().toString()).sorted().toList());
        }
        assertEquals("VaultValidator", ValidatorOutput.fromJson(json(root, SIMPLE_SCRIPT)).validator());
        try (var loader = classPath(root)) {
            assertEquals(cbor(root, SIMPLE_SCRIPT),
                    JulcScriptLoader.load(loader.loadClass("VaultValidator")).getCborHex());
        }
    }
}
