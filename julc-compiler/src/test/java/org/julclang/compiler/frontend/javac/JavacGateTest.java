package org.julclang.compiler.frontend.javac;

import org.julclang.compiler.CompileResult;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JavaLibraryProvider;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** ADR-068 M1: the javac gate on every JulcCompiler entry point and JavaLibraryProvider. */
class JavacGateTest {

    static final String JAVA_VALIDATOR = """
            package com.example;

            import java.math.BigInteger;
            import org.julclang.core.PlutusData;
            import org.julclang.ledger.ScriptContext;
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.SpendingValidator;
            import org.julclang.stdlib.lib.MathLib;

            @SpendingValidator
            public class FeeGate {
                @Entrypoint
                public static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                    return MathLib.max(ctx.txInfo().fee(), BigInteger.ONE).compareTo(BigInteger.TWO) > 0;
                }
            }
            """;

    /** JuLC's dialect: javac rejects the BigInteger operator; the JavaParser frontend compiles it. */
    static final String DIALECT_VALIDATOR = """
            import java.math.BigInteger;

            @SpendingValidator
            class DialectGate {
                @Entrypoint
                static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                    BigInteger fee = ctx.txInfo().fee();
                    return fee > 2;
                }
            }
            """;

    static final String JAVA_METHOD = """
            import java.math.BigInteger;
            class M {
                static BigInteger twice(BigInteger x) { return x.add(x); }
            }
            """;

    static final String DIALECT_METHOD = """
            import java.math.BigInteger;
            class M {
                static BigInteger twice(BigInteger x) { return x + x; }
            }
            """;

    static JulcCompiler compiler(JavacGateMode mode) {
        return new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions().setJavacGate(mode));
    }

    static byte[] flat(CompileResult result) {
        assertFalse(result.hasErrors(), result.diagnostics().toString());
        return UplcFlatEncoder.encodeProgram(result.program());
    }

    static void assertJavacRejects(Runnable compile, String key) {
        var e = assertThrows(CompilerException.class, compile::run);
        assertEquals("JULC0085", e.diagnostics().getFirst().code(), e.diagnostics().toString());
        assertTrue(e.diagnostics().getFirst().message().endsWith("(" + key + ")"), e.diagnostics().toString());
    }

    @Test
    void modeComesFromOptionsThenThePropertyThenEnforce() {
        String saved = System.getProperty(JavacGate.MODE_PROPERTY);
        try {
            System.clearProperty(JavacGate.MODE_PROPERTY);
            assertEquals(JavacGateMode.ENFORCE, JavacGate.mode(new CompilerOptions()));
            System.setProperty(JavacGate.MODE_PROPERTY, "report");
            assertEquals(JavacGateMode.REPORT, JavacGate.mode(null));
            assertEquals(JavacGateMode.OFF, JavacGate.mode(new CompilerOptions().setJavacGate(JavacGateMode.OFF)));
            System.setProperty(JavacGate.MODE_PROPERTY, "sometimes");
            assertThrows(IllegalArgumentException.class, () -> JavacGate.mode(null));
        } finally {
            if (saved == null) System.clearProperty(JavacGate.MODE_PROPERTY);
            else System.setProperty(JavacGate.MODE_PROPERTY, saved);
        }
    }

    @Test
    void enforceRejectsDialectAtEveryStringEntryPoint() {
        var c = compiler(JavacGateMode.ENFORCE);
        String key = "compiler.err.cant.resolve";
        for (Runnable compile : List.<Runnable>of(
                () -> c.compile(DIALECT_VALIDATOR),
                () -> c.compile(DIALECT_VALIDATOR, List.of()),
                () -> c.compileWithDetails(DIALECT_VALIDATOR),
                () -> c.compileWithDetails(DIALECT_VALIDATOR, List.of()),
                () -> c.compileContract(DIALECT_VALIDATOR),
                () -> c.compileContract(DIALECT_VALIDATOR, List.of()),
                () -> c.compileContractWithDetails(DIALECT_VALIDATOR),
                () -> c.compileContractWithDetails(DIALECT_VALIDATOR, List.of()))) {
            var e = assertThrows(CompilerException.class, compile::run);
            assertEquals("JULC0085", e.diagnostics().getFirst().code());
            assertTrue(e.diagnostics().getFirst().message().contains(key), e.diagnostics().toString());
        }
        assertJavacRejects(() -> c.compileMethod(DIALECT_METHOD, "twice"), "compiler.err.operator.cant.be.applied.1");
        assertJavacRejects(() -> c.compileMethod(DIALECT_METHOD, "twice", List.of()),
                "compiler.err.operator.cant.be.applied.1");
    }

    @Test
    void enforceAcceptsJavaThroughTheClassLoaderScanPool() {
        var c = compiler(JavacGateMode.ENFORCE);
        assertArrayEquals(flat(compiler(JavacGateMode.OFF).compile(JAVA_VALIDATOR)), flat(c.compile(JAVA_VALIDATOR)));
        assertFalse(c.compileContract(JAVA_VALIDATOR).compileResult().hasErrors());
        assertFalse(c.compileMethod(JAVA_METHOD, "twice").hasErrors());
    }

    @Test
    void reportAndOffCompileDialectToTheSameBytesAtEveryLevel() {
        for (var level : OptimizationLevel.values()) {
            Function<JavacGateMode, byte[]> bytes = mode -> flat(new JulcCompiler(StdlibRegistry.defaultRegistry(),
                    new CompilerOptions().setJavacGate(mode).setOptimizationLevel(level)).compile(DIALECT_VALIDATOR));
            assertArrayEquals(bytes.apply(JavacGateMode.OFF), bytes.apply(JavacGateMode.REPORT), level.name());
            Function<JavacGateMode, byte[]> method = mode -> flat(new JulcCompiler(StdlibRegistry.defaultRegistry(),
                    new CompilerOptions().setJavacGate(mode).setOptimizationLevel(level))
                    .compileMethod(DIALECT_METHOD, "twice"));
            assertArrayEquals(method.apply(JavacGateMode.OFF), method.apply(JavacGateMode.REPORT), level.name());
        }
    }

    @Test
    void reportWritesTheWouldBeRejectionAndTheSummaryCountsIt(@TempDir Path dir) throws Exception {
        String saved = System.getProperty(JavacGate.REPORT_PROPERTY);
        try {
            System.setProperty(JavacGate.REPORT_PROPERTY, dir.toString());
            assertFalse(compiler(JavacGateMode.REPORT).compile(DIALECT_VALIDATOR).hasErrors());
            assertFalse(compiler(JavacGateMode.REPORT).compileMethod(DIALECT_METHOD, "twice").hasErrors());
            assertFalse(compiler(JavacGateMode.REPORT).compile(JAVA_VALIDATOR).hasErrors());
        } finally {
            if (saved == null) System.clearProperty(JavacGate.REPORT_PROPERTY);
            else System.setProperty(JavacGate.REPORT_PROPERTY, saved);
        }
        List<Path> cases;
        try (Stream<Path> list = Files.list(dir.resolve("cases"))) {
            cases = list.toList();
        }
        assertEquals(2, cases.size(), "the Java validator is not reported");
        String all = String.join("\n", cases.stream().map(c -> {
            try {
                return Files.readString(c.resolve("case.txt")) + Arrays.toString(c.toFile().list());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).toList());
        assertTrue(all.contains("diagnostic: JULC0085 compiler.err.operator.cant.be.applied.1 M.java:3:"), all);
        assertTrue(all.contains("origin-"), all);
        assertTrue(all.contains("entry-compileMethod"), all);
        JavacGateReport.main(new String[]{dir.toString()});
        String summary = Files.readString(dir.resolve("summary.txt"));
        assertTrue(summary.contains("rejected inputs (distinct roots + pool): 2"), summary);
        assertTrue(summary.contains("compiler.err.operator.cant.be.applied.1"), summary);
        assertTrue(Files.readString(dir.resolve("rejections.tsv"))
                .contains(JavacGateTest.class.getName() + "#reportWritesTheWouldBeRejectionAndTheSummaryCountsIt"));
    }

    @Test
    void offRunsNoJavac() {
        assertNull(JavacGate.check("compile", List.of(JavacFrontend.Unit.of(DIALECT_METHOD)), List.of(),
                new CompilerOptions().setJavacGate(JavacGateMode.OFF)));
        assertFalse(JavacGate.check("compile", List.of(JavacFrontend.Unit.of(DIALECT_METHOD)), List.of(),
                new CompilerOptions().setJavacGate(JavacGateMode.REPORT)).isAccepted());
    }

    @Test
    void pathEntryPointsGateTheValidatorWithItsLibraryFilesAsPool(@TempDir Path dir) throws Exception {
        Path lib = Files.writeString(dir.resolve("Fees.java"), """
                package com.example;

                import java.math.BigInteger;
                import org.julclang.stdlib.annotation.OnchainLibrary;

                @OnchainLibrary
                public class Fees {
                    public static BigInteger minimum() { return BigInteger.TWO; }
                }
                """);
        Path validator = Files.writeString(dir.resolve("FeeGate.java"), JAVA_VALIDATOR
                .replace("import org.julclang.stdlib.lib.MathLib;\n", "")
                .replace("MathLib.max(ctx.txInfo().fee(), BigInteger.ONE)", "ctx.txInfo().fee()")
                .replace("BigInteger.TWO", "Fees.minimum()"));
        var c = compiler(JavacGateMode.ENFORCE);
        assertFalse(c.compile(validator, List.of(lib)).hasErrors());
        assertFalse(c.compileContract(validator, List.of(lib)).compileResult().hasErrors());
        assertFalse(c.compileWithSiblings(validator).hasErrors());
        Files.writeString(lib, Files.readString(lib).replace("return BigInteger.TWO;", "return 2;"));
        var e = assertThrows(CompilerException.class, () -> c.compile(validator, List.of(lib)));
        var d = e.diagnostics().getFirst();
        assertEquals("JULC0085", d.code());
        assertEquals(lib.toString(), d.fileName(), "a referenced pool unit is attributed and reported by its path");
        // Without the library, javac cannot resolve it.
        assertJavacRejects(() -> {
            try {
                c.compile(validator);
            } catch (java.io.IOException io) {
                throw new RuntimeException(io);
            }
        }, "compiler.err.cant.resolve.location");
    }

    @Test
    void javaLibraryProviderGatesEveryUnitAsARoot() {
        String used = """
                package com.example;
                import java.math.BigInteger;
                import org.julclang.stdlib.annotation.OnchainLibrary;
                @OnchainLibrary
                public class Used { public static BigInteger one() { return BigInteger.ONE; } }
                """;
        String unreferenced = """
                package com.example;
                import java.math.BigInteger;
                import org.julclang.stdlib.annotation.OnchainLibrary;
                @OnchainLibrary
                public class Unreferenced { public static BigInteger two() { return 2; } }
                """;
        var enforce = new CompilerOptions().setJavacGate(JavacGateMode.ENFORCE);
        assertDoesNotThrow(() -> new JavaLibraryProvider(List.of(used), StdlibRegistry.defaultRegistry(), enforce));
        var e = assertThrows(CompilerException.class,
                () -> new JavaLibraryProvider(List.of(used, unreferenced), StdlibRegistry.defaultRegistry(), enforce));
        assertEquals("JULC0085", e.diagnostics().getFirst().code());
        assertEquals("Unreferenced.java", e.diagnostics().getFirst().fileName());
        // Report mode: the provider is built as before.
        var report = new JavaLibraryProvider(List.of(used, unreferenced), StdlibRegistry.defaultRegistry(),
                new CompilerOptions().setJavacGate(JavacGateMode.REPORT));
        assertFalse(report.describe("com.example.Unreferenced").isEmpty());
    }

    @Test
    void emptyProviderBuildsTheMetadataTaskAndKeepsItsMetadata() {
        var off = new JavaLibraryProvider(List.of(), null, new CompilerOptions().setJavacGate(JavacGateMode.OFF));
        var enforce = new JavaLibraryProvider(List.of(), null,
                new CompilerOptions().setJavacGate(JavacGateMode.ENFORCE));
        assertEquals(off.types(), enforce.types());
        assertEquals(off.namedDefinitions(), enforce.namedDefinitions());
        assertTrue(enforce.describe("com.example.Used").isEmpty());
        assertNotNull(JavacGate.metadata(new CompilerOptions().setJavacGate(JavacGateMode.ENFORCE)));
        assertNull(JavacGate.metadata(new CompilerOptions().setJavacGate(JavacGateMode.OFF)));
    }
}
