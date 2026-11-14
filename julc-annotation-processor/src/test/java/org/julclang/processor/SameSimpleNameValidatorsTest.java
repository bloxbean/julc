package org.julclang.processor;

import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
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
import java.util.IntSummaryStatistics;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Issue #326 (ADR-073): validators with one simple name in different packages, such as {@code com.a.VaultValidator}
 * and {@code com.b.VaultValidator}, each load their own script and source map, never the other's.
 */
class SameSimpleNameValidatorsTest {

    @TempDir
    Path tempDir;

    /** {@code com.a}'s validator accepts everything; its code sits in lines 1-13. */
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

    static final int B_FIRST_LINE = 40;

    /** {@code com.b}'s validator differs, and its code starts below line {@value #B_FIRST_LINE}. */
    static final String VAULT_B = shifted(B_FIRST_LINE, VAULT_A.replace("package com.a;", "package com.b;")
            .replace("return true;", "return ctx.txInfo().fee().signum() > 0;"));

    static String shifted(int lines, String source) {
        return "\n".repeat(lines) + source;
    }

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

    /** One validator, with a source map, compiled without errors. */
    Path module(String name, String path, String source) throws IOException {
        var module = compile(name, Map.of(path, source), List.of("-Ajulc.sourceMap=true"));
        assertEquals(List.of(), module.errors());
        return module.classes();
    }

    Path moduleA() throws IOException {
        return module("a", "com/a/VaultValidator.java", VAULT_A);
    }

    Path moduleB() throws IOException {
        return module("b", "com/b/VaultValidator.java", VAULT_B);
    }

    /** The directory packaged as a jar beside it. */
    static Path jar(Path root) throws IOException {
        Path jar = root.resolveSibling(root.getFileName() + ".jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar)); var files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                out.putNextEntry(new JarEntry(root.relativize(file).toString().replace('\\', '/')));
                out.write(Files.readAllBytes(file));
                out.closeEntry();
            }
        }
        return jar;
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

    static String sourceMapOf(String script) {
        return script.replace(".plutus.json", ".sourcemap.json");
    }

    /** Every entry point that reads the script, with and without parameters. */
    static List<Executable> entryPoints(Class<?> validator) {
        var param = BigIntPlutusData.of(1);
        return List.of(
                () -> JulcScriptLoader.load(validator),
                () -> JulcScriptLoader.load(validator, param),
                () -> JulcScriptLoader.loadOutput(validator),
                () -> JulcScriptLoader.scriptHash(validator),
                () -> JulcScriptLoader.scriptHash(validator, param),
                () -> JulcScriptLoader.loadWithSourceMap(validator),
                () -> JulcScriptLoader.loadWithSourceMap(validator, param),
                () -> JulcScriptLoader.loadSourceMap(validator));
    }

    static void assertEveryEntryPointRefuses(Class<?> validator, String expected) {
        for (var entryPoint : entryPoints(validator)) {
            var error = assertThrows(IllegalArgumentException.class, entryPoint);
            assertTrue(error.getMessage().contains(expected), error.getMessage());
        }
    }

    @Test
    void eachScriptIsWrittenUnderItsQualifiedNameAndItsSimpleName() throws Exception {
        Path b = moduleB();
        assertEquals(json(b, B_SCRIPT), json(b, SIMPLE_SCRIPT));
        assertEquals(json(b, sourceMapOf(B_SCRIPT)), json(b, SIMPLE_SOURCE_MAP));
        var output = ValidatorOutput.fromJson(json(b, B_SCRIPT));
        assertEquals("com.b.VaultValidator", output.validator());
        assertEquals("VaultValidator", output.description(), "the description keeps the simple name");
    }

    /** The issue's repro: both modules on one class path, in either order, as directories and as jars. */
    @Test
    void eachClassLoadsItsOwnScriptAndSourceMapWhicheverModuleComesFirst() throws Exception {
        Path a = moduleA();
        Path b = moduleB();
        assertNotEquals(cbor(a, A_SCRIPT), cbor(b, B_SCRIPT));
        Path aJar = jar(a);
        Path bJar = jar(b);
        for (var order : List.of(List.of(a, b), List.of(b, a), List.of(aJar, bJar), List.of(bJar, aJar))) {
            try (var loader = classPath(order.toArray(Path[]::new))) {
                var classA = loader.loadClass("com.a.VaultValidator");
                var classB = loader.loadClass("com.b.VaultValidator");
                assertEquals(cbor(a, A_SCRIPT), JulcScriptLoader.load(classA).getCborHex(), "order " + order);
                assertEquals(cbor(b, B_SCRIPT), JulcScriptLoader.load(classB).getCborHex(), "order " + order);
                assertEquals("com.b.VaultValidator", JulcScriptLoader.loadOutput(classB).validator());
                assertLinesWithin(JulcScriptLoader.loadWithSourceMap(classA), 0, B_FIRST_LINE);
                assertLinesWithin(JulcScriptLoader.loadWithSourceMap(classB), B_FIRST_LINE, 2 * B_FIRST_LINE);
            }
        }
    }

    /** Every location the source map gives for the program's terms lies in {@code (from, to)}. */
    static void assertLinesWithin(JulcScriptLoader.LoadResult result, int from, int to) {
        assertNotNull(result.sourceMap(), "no source map");
        var lines = new ArrayList<Integer>();
        collectLines(result.program().term(), result.sourceMap(), lines);
        assertFalse(lines.isEmpty(), "the source map locates no term");
        IntSummaryStatistics stats = lines.stream().mapToInt(Integer::intValue).summaryStatistics();
        assertTrue(stats.getMin() > from && stats.getMax() < to,
                "lines " + lines + " outside (" + from + ", " + to + ")");
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

    /** Makes a module look built before #326: only the simple-name copies, which record no class. */
    static void makeLegacy(Path root, String qualifiedScript) throws IOException {
        Files.delete(root.resolve(qualifiedScript));
        Files.delete(root.resolve(sourceMapOf(qualifiedScript)));
        var output = ValidatorOutput.fromJson(json(root, SIMPLE_SCRIPT));
        Files.writeString(root.resolve(SIMPLE_SCRIPT), new ValidatorOutput(output.type(), output.purpose(),
                output.description(), output.cborHex(), output.hash(), output.params(), output.sizeBytes()).toJson());
        assertFalse(json(root, SIMPLE_SCRIPT).contains("\"validator\""));
    }

    /** Removes a module's scripts, as when the processor did not run for it. */
    static void removeScripts(Path root, String qualifiedScript) throws IOException {
        for (String path : List.of(qualifiedScript, SIMPLE_SCRIPT)) {
            Files.delete(root.resolve(path));
            Files.delete(root.resolve(sourceMapOf(path)));
        }
    }

    @Test
    void aLegacyModuleAloneStillLoadsItsScriptAndSourceMap() throws Exception {
        Path b = moduleB();
        makeLegacy(b, B_SCRIPT);
        for (Path root : List.of(b, jar(b))) {
            try (var loader = classPath(root)) {
                var classB = loader.loadClass("com.b.VaultValidator");
                assertEquals(cbor(b, SIMPLE_SCRIPT), JulcScriptLoader.load(classB).getCborHex());
                assertLinesWithin(JulcScriptLoader.loadWithSourceMap(classB), B_FIRST_LINE, 2 * B_FIRST_LINE);
            }
        }
    }

    /** Old modules each read the simple-name copy beside their own class, in either order. */
    @Test
    void legacyModulesEachLoadTheirOwnScript() throws Exception {
        Path a = moduleA();
        Path b = moduleB();
        makeLegacy(a, A_SCRIPT);
        makeLegacy(b, B_SCRIPT);
        for (var order : List.of(List.of(a, b), List.of(b, a), List.of(jar(a), jar(b)))) {
            try (var loader = classPath(order.toArray(Path[]::new))) {
                assertEquals(cbor(a, SIMPLE_SCRIPT),
                        JulcScriptLoader.load(loader.loadClass("com.a.VaultValidator")).getCborHex(), "order " + order);
                assertEquals(cbor(b, SIMPLE_SCRIPT),
                        JulcScriptLoader.load(loader.loadClass("com.b.VaultValidator")).getCborHex(), "order " + order);
            }
        }
    }

    /** An old module and a new one: the new one by its qualified copy, the old one by the copy beside it. */
    @Test
    void aLegacyAndANewModuleEachLoadTheirOwnScript() throws Exception {
        Path a = moduleA();
        Path b = moduleB();
        makeLegacy(b, B_SCRIPT);
        for (var order : List.of(List.of(a, b), List.of(b, a))) {
            try (var loader = classPath(order.toArray(Path[]::new))) {
                assertEquals(cbor(a, A_SCRIPT),
                        JulcScriptLoader.load(loader.loadClass("com.a.VaultValidator")).getCborHex(), "order " + order);
                var classB = loader.loadClass("com.b.VaultValidator");
                assertEquals(cbor(b, SIMPLE_SCRIPT), JulcScriptLoader.load(classB).getCborHex(), "order " + order);
                assertLinesWithin(JulcScriptLoader.loadWithSourceMap(classB), B_FIRST_LINE, 2 * B_FIRST_LINE);
            }
        }
    }

    /**
     * Two old modules whose scripts are byte for byte the same, but whose source maps differ: each class reads its own
     * source map, not the first module's.
     */
    @Test
    void legacyModulesWithOneScriptEachLoadTheirOwnSourceMap() throws Exception {
        Path a = moduleA();
        Path b = module("b", "com/b/VaultValidator.java",
                shifted(B_FIRST_LINE, VAULT_A.replace("package com.a;", "package com.b;")));
        makeLegacy(a, A_SCRIPT);
        makeLegacy(b, B_SCRIPT);
        assertEquals(json(a, SIMPLE_SCRIPT), json(b, SIMPLE_SCRIPT), "precondition: the same script JSON");
        assertNotEquals(json(a, SIMPLE_SOURCE_MAP), json(b, SIMPLE_SOURCE_MAP));
        for (var order : List.of(List.of(a, b), List.of(b, a))) {
            try (var loader = classPath(order.toArray(Path[]::new))) {
                assertLinesWithin(JulcScriptLoader.loadWithSourceMap(loader.loadClass("com.a.VaultValidator")),
                        0, B_FIRST_LINE);
                assertLinesWithin(JulcScriptLoader.loadWithSourceMap(loader.loadClass("com.b.VaultValidator")),
                        B_FIRST_LINE, 2 * B_FIRST_LINE);
            }
        }
    }

    /** A class with no script of its own never takes another class's simple-name copy, recorded or not. */
    @Test
    void aClassWithoutAScriptRefusesAnotherClassesSimpleNameCopy() throws Exception {
        Path a = moduleA();
        Path b = moduleB();
        removeScripts(b, B_SCRIPT);
        try (var loader = classPath(a, b)) {
            assertEveryEntryPointRefuses(loader.loadClass("com.b.VaultValidator"),
                    "No compiled Plutus script for com.b.VaultValidator is on the class path");
        }
        makeLegacy(a, A_SCRIPT);
        for (var order : List.of(List.of(a, b), List.of(b, a))) {
            try (var loader = classPath(order.toArray(Path[]::new))) {
                var classB = loader.loadClass("com.b.VaultValidator");
                assertEveryEntryPointRefuses(classB, "(names no class)");
                assertEveryEntryPointRefuses(classB, "No compiled Plutus script for com.b.VaultValidator");
            }
        }
    }

    /** Two different qualified copies of one class, such as a stale jar beside fresh classes, are refused. */
    @Test
    void twoDifferentQualifiedCopiesAreRefused() throws Exception {
        Path b = moduleB();
        Path stale = compile("stale", Map.of("com/b/VaultValidator.java",
                VAULT_B.replace("signum() > 0", "signum() < 0")), List.of()).classes();
        assertNotEquals(cbor(b, B_SCRIPT), cbor(stale, B_SCRIPT));
        for (var order : List.of(List.of(b, stale), List.of(stale, b))) {
            try (var loader = classPath(order.toArray(Path[]::new))) {
                assertEveryEntryPointRefuses(loader.loadClass("com.b.VaultValidator"),
                        "2 different compiled Plutus scripts for com.b.VaultValidator at " + B_SCRIPT);
            }
        }
    }

    /**
     * Identical copies of one script are one script, and its source map is the one beside the class that was loaded:
     * here the two builds differ only in where the code sits.
     */
    @Test
    void identicalCopiesAreOneScriptWithTheLoadedClassesSourceMap() throws Exception {
        Path b = moduleB();
        Path moved = module("moved", "com/b/VaultValidator.java", shifted(2 * B_FIRST_LINE, VAULT_B));
        assertEquals(json(b, B_SCRIPT), json(moved, B_SCRIPT), "precondition: the same script JSON");
        try (var loader = classPath(b, moved)) {
            var classB = loader.loadClass("com.b.VaultValidator");
            assertEquals(cbor(b, B_SCRIPT), JulcScriptLoader.load(classB).getCborHex());
            assertLinesWithin(JulcScriptLoader.loadWithSourceMap(classB), B_FIRST_LINE, 2 * B_FIRST_LINE);
        }
        try (var loader = classPath(moved, b)) {
            assertLinesWithin(JulcScriptLoader.loadWithSourceMap(loader.loadClass("com.b.VaultValidator")),
                    3 * B_FIRST_LINE, 4 * B_FIRST_LINE);
        }
        // A root ahead of the class's own that holds only a copy of the script, without its source map.
        Path copyOnly = Files.createDirectories(tempDir.resolve("copy-only/META-INF/plutus/com/b"))
                .getParent().getParent().getParent().getParent();
        Files.copy(b.resolve(B_SCRIPT), copyOnly.resolve(B_SCRIPT));
        try (var loader = classPath(copyOnly, b)) {
            assertLinesWithin(JulcScriptLoader.loadWithSourceMap(loader.loadClass("com.b.VaultValidator")),
                    B_FIRST_LINE, 2 * B_FIRST_LINE);
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
        Path root = module("unnamed", "VaultValidator.java", VAULT_A.replace("package com.a;\n", ""));
        try (var files = Files.list(root.resolve("META-INF/plutus"))) {
            assertEquals(List.of("VaultValidator.plutus.json", "VaultValidator.sourcemap.json", "plutus.json"),
                    files.map(p -> p.getFileName().toString()).sorted().toList());
        }
        assertEquals("VaultValidator", ValidatorOutput.fromJson(json(root, SIMPLE_SCRIPT)).validator());
        try (var loader = classPath(root)) {
            assertEquals(cbor(root, SIMPLE_SCRIPT),
                    JulcScriptLoader.load(loader.loadClass("VaultValidator")).getCborHex());
        }
    }

    /** The unnamed package's one path is shared with every packaged validator of that name; the record tells them apart. */
    @Test
    void anUnnamedPackageValidatorAndAPackagedOneEachLoadTheirOwnScript() throws Exception {
        Path unnamed = module("unnamed", "VaultValidator.java", VAULT_A.replace("package com.a;\n", ""));
        Path b = moduleB();
        for (var order : List.of(List.of(unnamed, b), List.of(b, unnamed))) {
            try (var loader = classPath(order.toArray(Path[]::new))) {
                assertEquals(cbor(unnamed, SIMPLE_SCRIPT),
                        JulcScriptLoader.load(loader.loadClass("VaultValidator")).getCborHex(), "order " + order);
                assertEquals(cbor(b, B_SCRIPT),
                        JulcScriptLoader.load(loader.loadClass("com.b.VaultValidator")).getCborHex(), "order " + order);
            }
        }
    }

    /** A nested validator is written and read under its binary name, {@code Outer$VaultValidator}. */
    @Test
    void aNestedValidatorLoadsByItsBinaryName() throws Exception {
        String source = """
                package com.n;

                import org.julclang.core.PlutusData;
                import org.julclang.ledger.ScriptContext;
                import org.julclang.stdlib.annotation.Entrypoint;
                import org.julclang.stdlib.annotation.SpendingValidator;

                public class Outer {
                    @SpendingValidator
                    public static class VaultValidator {
                        @Entrypoint
                        public static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            return false;
                        }
                    }
                }
                """;
        Path n = module("n", "com/n/Outer.java", source);
        String nested = "META-INF/plutus/com/n/Outer$VaultValidator.plutus.json";
        assertEquals("com.n.Outer$VaultValidator", ValidatorOutput.fromJson(json(n, nested)).validator());
        Path b = moduleB();
        try (var loader = classPath(b, n)) {
            assertEquals(cbor(n, nested),
                    JulcScriptLoader.load(loader.loadClass("com.n.Outer$VaultValidator")).getCborHex());
            assertEquals(cbor(b, B_SCRIPT), JulcScriptLoader.load(loader.loadClass("com.b.VaultValidator")).getCborHex());
        }
    }

    /** A relocated class whose script was moved with it still records its old name, and is refused with that cause. */
    @Test
    void aRelocatedValidatorIsRefusedWithTheRelocationFix() throws Exception {
        Path b = moduleB();
        Path shaded = compile("shaded", Map.of("shaded/com/b/VaultValidator.java",
                "package shaded.com.b; public class VaultValidator {}"), List.of()).classes();
        Path target = shaded.resolve("META-INF/plutus/shaded/com/b/VaultValidator.plutus.json");
        Files.createDirectories(target.getParent());
        Files.copy(b.resolve(B_SCRIPT), target);
        try (var loader = classPath(shaded)) {
            assertEveryEntryPointRefuses(loader.loadClass("shaded.com.b.VaultValidator"),
                    "was written for com.b.VaultValidator, not for shaded.com.b.VaultValidator. If"
                            + " shaded.com.b.VaultValidator was relocated");
        }
    }

    /** A relocated class whose resources stayed under the original package is refused as relocated. */
    @Test
    void aRelocatedValidatorWithUnmovedResourcesIsRefusedAsRelocated() throws Exception {
        Path b = moduleB();
        Path shaded = compile("shaded", Map.of("shaded/com/b/VaultValidator.java",
                "package shaded.com.b; public class VaultValidator {}"), List.of()).classes();
        copyTree(b.resolve("META-INF"), shaded.resolve("META-INF"));
        try (var loader = classPath(shaded)) {
            assertEveryEntryPointRefuses(loader.loadClass("shaded.com.b.VaultValidator"),
                    "but one for com.b.VaultValidator is: shaded.com.b.VaultValidator looks relocated");
        }
    }

    /**
     * A fat jar that holds an old validator's simple-name copy and a relocated new one of the same name: the relocated
     * class does not take the old validator's script.
     */
    @Test
    void aRelocatedValidatorDoesNotTakeAnOldValidatorsCopyInItsJar() throws Exception {
        Path a = moduleA();
        makeLegacy(a, A_SCRIPT);
        Path b = moduleB();
        Path fat = compile("fat", Map.of("shaded/com/b/VaultValidator.java",
                "package shaded.com.b; public class VaultValidator {}"), List.of()).classes();
        copyTree(b.resolve("META-INF/plutus/com"), fat.resolve("META-INF/plutus/com"));
        copyTree(a, fat);
        assertEquals(json(a, SIMPLE_SCRIPT), json(fat, SIMPLE_SCRIPT), "the merge kept the old validator's copy");
        try (var loader = classPath(jar(fat))) {
            assertEquals(cbor(a, SIMPLE_SCRIPT),
                    JulcScriptLoader.load(loader.loadClass("com.a.VaultValidator")).getCborHex());
            assertEveryEntryPointRefuses(loader.loadClass("shaded.com.b.VaultValidator"), "looks relocated");
        }
    }

    static void copyTree(Path from, Path to) throws IOException {
        try (var files = Files.walk(from)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path target = to.resolve(from.relativize(file).toString());
                Files.createDirectories(target.getParent());
                Files.copy(file, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** Two copies of a class's script that both record it, with no qualified copy, are refused when they differ. */
    @Test
    void twoDifferentRecordedSimpleNameCopiesAreRefused() throws Exception {
        Path b = moduleB();
        Path stale = compile("stale", Map.of("com/b/VaultValidator.java",
                VAULT_B.replace("signum() > 0", "signum() < 0")), List.of()).classes();
        Files.delete(b.resolve(B_SCRIPT));
        Files.delete(stale.resolve(B_SCRIPT));
        try (var loader = classPath(b, stale)) {
            assertEveryEntryPointRefuses(loader.loadClass("com.b.VaultValidator"),
                    "2 different compiled Plutus scripts for com.b.VaultValidator at " + SIMPLE_SCRIPT);
        }
    }

    /** An old module on the class path twice, as a directory and as its jar, still loads its script. */
    @Test
    void aLegacyModuleTwiceStillLoadsItsScript() throws Exception {
        Path b = moduleB();
        makeLegacy(b, B_SCRIPT);
        Path bJar = jar(b);
        Path a = moduleA();
        makeLegacy(a, A_SCRIPT);
        try (var loader = classPath(a, b, bJar)) {
            var classB = loader.loadClass("com.b.VaultValidator");
            assertEquals(cbor(b, SIMPLE_SCRIPT), JulcScriptLoader.load(classB).getCborHex());
            assertLinesWithin(JulcScriptLoader.loadWithSourceMap(classB), B_FIRST_LINE, 2 * B_FIRST_LINE);
        }
    }

    /**
     * A loader that defines classes from its own roots first, while it looks resources up in its parent first. The
     * parent holds another build of {@code com.b.VaultValidator} without a script, beside {@code com.a}'s old copy: the
     * loader may have defined the class from either root, so the old copy is refused.
     */
    @Test
    void aChildFirstLoaderDoesNotTakeAnOldCopyBesideAnotherBuildOfTheClass() throws Exception {
        Path parentRoot = moduleA();
        makeLegacy(parentRoot, A_SCRIPT);
        compile("parent-b", Map.of("com/b/VaultValidator.java", "package com.b; public class VaultValidator {}"),
                List.of());
        copyTree(tempDir.resolve("parent-b/com"), parentRoot.resolve("com"));
        Path child = moduleB();
        removeScripts(child, B_SCRIPT);
        try (var parent = classPath(parentRoot); var loader = new ChildFirstClassLoader(child, parent)) {
            var classB = loader.loadClass("com.b.VaultValidator");
            assertSame(loader, classB.getClassLoader(), "precondition: defined by the child");
            assertEveryEntryPointRefuses(classB, "No compiled Plutus script for com.b.VaultValidator");
        }
    }

    /** Defines classes from its own root before asking its parent; resources keep the default parent-first order. */
    static final class ChildFirstClassLoader extends URLClassLoader {
        ChildFirstClassLoader(Path root, ClassLoader parent) throws IOException {
            super(new URL[] {root.toUri().toURL()}, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null && name.startsWith("com.")) {
                    try {
                        loaded = findClass(name);
                    } catch (ClassNotFoundException e) {
                        // not ours
                    }
                }
                return loaded != null ? loaded : super.loadClass(name, resolve);
            }
        }
    }

    /** An old module packaged as a multi-release jar, its class in a version directory, still loads its script. */
    @Test
    void aLegacyModuleInAMultiReleaseJarStillLoadsItsScript() throws Exception {
        Path b = moduleB();
        makeLegacy(b, B_SCRIPT);
        Path jar = tempDir.resolve("multi-release.jar");
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MULTI_RELEASE, "true");
        String classFile = "com/b/VaultValidator.class";
        try (var out = new JarOutputStream(Files.newOutputStream(jar), manifest); var files = Files.walk(b)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                String name = b.relativize(file).toString().replace('\\', '/');
                for (String entry : name.equals(classFile)
                        ? List.of(name, "META-INF/versions/" + Runtime.version().feature() + "/" + name)
                        : List.of(name)) {
                    out.putNextEntry(new JarEntry(entry));
                    out.write(Files.readAllBytes(file));
                    out.closeEntry();
                }
            }
        }
        try (var loader = classPath(jar)) {
            var classB = loader.loadClass("com.b.VaultValidator");
            assertEquals(cbor(b, SIMPLE_SCRIPT), JulcScriptLoader.load(classB).getCborHex());
            assertLinesWithin(JulcScriptLoader.loadWithSourceMap(classB), B_FIRST_LINE, 2 * B_FIRST_LINE);
        }
    }

    /** Names outside ASCII are percent-encoded in resource URLs; old and new modules still load their own scripts. */
    @Test
    void validatorsWithNonAsciiNamesLoadTheirOwnScripts() throws Exception {
        String source = VAULT_B.replace("package com.b;", "package com.b\u00e4.\u65e5;")
                .replace("class VaultValidator", "class V\u00e4ultValidator");
        Path root = module("unicode", "com/b\u00e4/\u65e5/V\u00e4ultValidator.java", source);
        String qualified = "META-INF/plutus/com/b\u00e4/\u65e5/V\u00e4ultValidator.plutus.json";
        String simple = "META-INF/plutus/V\u00e4ultValidator.plutus.json";
        Path moved = module("unicode-moved", "com/b\u00e4/\u65e5/V\u00e4ultValidator.java",
                shifted(2 * B_FIRST_LINE, source));
        String name = "com.b\u00e4.\u65e5.V\u00e4ultValidator";
        // New modules: the source map of the class's own root, after a root that holds only a copy of the script.
        Path copyOnly = tempDir.resolve("unicode-copy-only");
        Files.createDirectories(copyOnly.resolve(qualified).getParent());
        Files.copy(moved.resolve(qualified), copyOnly.resolve(qualified));
        try (var loader = classPath(copyOnly, root)) {
            assertLinesWithin(JulcScriptLoader.loadWithSourceMap(loader.loadClass(name)), B_FIRST_LINE, 2 * B_FIRST_LINE);
        }
        // An old module alone, as a directory and as a jar.
        for (var path : List.of(qualified, sourceMapOf(qualified))) Files.delete(root.resolve(path));
        var output = ValidatorOutput.fromJson(json(root, simple));
        Files.writeString(root.resolve(simple), new ValidatorOutput(output.type(), output.purpose(),
                output.description(), output.cborHex(), output.hash(), output.params(), output.sizeBytes()).toJson());
        for (Path entry : List.of(root, jar(root))) {
            try (var loader = classPath(entry)) {
                var validator = loader.loadClass(name);
                assertEquals(cbor(root, simple), JulcScriptLoader.load(validator).getCborHex(), entry.toString());
                assertLinesWithin(JulcScriptLoader.loadWithSourceMap(validator), B_FIRST_LINE, 2 * B_FIRST_LINE);
            }
        }
    }

    /**
     * An old {@code org.example.com.b.VaultValidator} beside an unrelated, new {@code com.b.VaultValidator} in another
     * module: the longer name is not mistaken for a relocation of the shorter one.
     */
    @Test
    void aLegacyModuleIsNotTakenForARelocationOfAnUnrelatedValidator() throws Exception {
        Path b = moduleB();
        Path longer = module("longer", "org/example/com/b/VaultValidator.java",
                VAULT_A.replace("package com.a;", "package org.example.com.b;"));
        Files.delete(longer.resolve("META-INF/plutus/org/example/com/b/VaultValidator.plutus.json"));
        Files.delete(longer.resolve("META-INF/plutus/org/example/com/b/VaultValidator.sourcemap.json"));
        var output = ValidatorOutput.fromJson(json(longer, SIMPLE_SCRIPT));
        Files.writeString(longer.resolve(SIMPLE_SCRIPT), new ValidatorOutput(output.type(), output.purpose(),
                output.description(), output.cborHex(), output.hash(), output.params(), output.sizeBytes()).toJson());
        for (var order : List.of(List.of(b, longer), List.of(longer, b))) {
            try (var loader = classPath(order.toArray(Path[]::new))) {
                assertEquals(cbor(longer, SIMPLE_SCRIPT),
                        JulcScriptLoader.load(loader.loadClass("org.example.com.b.VaultValidator")).getCborHex(),
                        "order " + order);
                assertEquals(cbor(b, B_SCRIPT),
                        JulcScriptLoader.load(loader.loadClass("com.b.VaultValidator")).getCborHex(), "order " + order);
            }
        }
    }

    /** An old class on the class path twice, with a different unrecorded copy beside each, is refused. */
    @Test
    void twoDifferentLegacyCopiesBesideOneClassAreRefused() throws Exception {
        Path b = moduleB();
        makeLegacy(b, B_SCRIPT);
        Path stale = compile("stale", Map.of("com/b/VaultValidator.java",
                VAULT_B.replace("signum() > 0", "signum() < 0")), List.of()).classes();
        makeLegacyWithoutSourceMap(stale);
        assertNotEquals(cbor(b, SIMPLE_SCRIPT), cbor(stale, SIMPLE_SCRIPT));
        for (var order : List.of(List.of(b, stale), List.of(stale, b))) {
            try (var loader = classPath(order.toArray(Path[]::new))) {
                assertEveryEntryPointRefuses(loader.loadClass("com.b.VaultValidator"),
                        "No compiled Plutus script for com.b.VaultValidator");
            }
        }
    }

    static void makeLegacyWithoutSourceMap(Path root) throws IOException {
        Files.delete(root.resolve(B_SCRIPT));
        var output = ValidatorOutput.fromJson(json(root, SIMPLE_SCRIPT));
        Files.writeString(root.resolve(SIMPLE_SCRIPT), new ValidatorOutput(output.type(), output.purpose(),
                output.description(), output.cborHex(), output.hash(), output.params(), output.sizeBytes()).toJson());
    }
}
