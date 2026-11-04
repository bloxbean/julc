package org.julclang.testkit;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerFrontend;
import org.julclang.compiler.error.DiagnosticCodes;
import org.julclang.core.PlutusData;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.vm.TermExtractor;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-068 D4: the testkit's frontend option. A construct {@code javac} alone rejects shows it is honoured. The reference
 * (JavaParser) is selected explicitly: javac is the default from ADR-068 M5.
 */
class FrontendOptionTest {

    private static final String METHOD = """
            import java.math.BigInteger;
            class C { static BigInteger twice(BigInteger a) { return a.add(a); } }
            """;

    private static final String NOT_PORTED = """
            import java.math.BigInteger;
            class C { static BigInteger twice(BigInteger a) { long s = (short) a.longValue(); return a.add(a); } }
            """;

    private static final String VALIDATOR = """
            import org.julclang.core.PlutusData;
            import org.julclang.ledger.ScriptContext;
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.SpendingValidator;
            @SpendingValidator
            class V {
                @Entrypoint
                static boolean validate(PlutusData redeemer, ScriptContext ctx) { return ctx.txInfo().signatories().isEmpty(); }
            }
            """;

    /** A cast to {@code short} has no representation on {@code JAVAC}: {@code JULC0124} (until ADR-068 M5 a stop, {@code JULC0084}). */
    private static boolean isNotPorted(CompilerException e) {
        return e.diagnostics().stream().anyMatch(d -> DiagnosticCodes.PRIMITIVE_TYPE_UNSUPPORTED.code().equals(d.code()));
    }

    @Test
    void julcEvalRunsOnJavac() {
        var result = JulcEval.forSource(METHOD).frontend(CompilerFrontend.JAVAC).call("twice", BigInteger.valueOf(21)).asInteger();
        assertEquals(BigInteger.valueOf(42), result);
    }

    /** D8: the testkit's pool, the context loader's library sources, is javac's pool too; the library is lowered whole. */
    @Test
    void aLibraryOfThePoolRunsOnJavacAsOnTheReference() {
        var source = """
                import java.math.BigInteger;
                import org.julclang.stdlib.lib.MathLib;
                class C { static BigInteger cube(BigInteger a) { return MathLib.pow(a, BigInteger.valueOf(3)).add(MathLib.sign(a)); } }
                """;
        var reference = JulcEval.forSource(source).frontend(CompilerFrontend.JAVAPARSER).call("cube", BigInteger.valueOf(-4)).asInteger();
        assertEquals(BigInteger.valueOf(-65), reference);
        assertEquals(reference, JulcEval.forSource(source).frontend(CompilerFrontend.JAVAC).call("cube", BigInteger.valueOf(-4)).asInteger());
    }

    @Test
    void julcEvalReachesTheFrontend() {
        assertEquals(BigInteger.valueOf(42), JulcEval.forSource(NOT_PORTED).frontend(CompilerFrontend.JAVAPARSER).call("twice", BigInteger.valueOf(21)).asInteger());
        var eval = JulcEval.forSource(NOT_PORTED).frontend(CompilerFrontend.JAVAC);
        var failure = assertThrows(CompilerException.class, () -> eval.call("twice", BigInteger.ONE));
        assertTrue(isNotPorted(failure), failure::getMessage);
    }

    /**
     * The reference reads a hash in a parameter as Data, and it is bytes by then: Java returns them and the script fails.
     * javac reads the bytes, as Java (ADR-068 M5).
     */
    @Test
    void aHashReadFromAParameterFailsOnTheReferenceAndIsJavasOnJavac() {
        String source = """
                import org.julclang.ledger.PubKeyHash;
                class C { static byte[] m(PubKeyHash p) { return p.hash(); } }
                """;
        var bytes = new PlutusData.BytesData(new byte[]{1, 2});
        var reference = assertThrows(TermExtractor.ExtractionException.class, () -> JulcEval.forSource(source).frontend(CompilerFrontend.JAVAPARSER).call("m", bytes).asData());
        assertTrue(reference.getMessage().contains("UnBData: expected data"), reference::getMessage);
        var javac = JulcEval.forSource(source).frontend(CompilerFrontend.JAVAC).call("m", bytes).asData();
        assertEquals(bytes, javac);
    }

    /**
     * {@code fromPlutusData} on a hash is an identity there, so what follows meets Data; javac checks and decodes it, and
     * returns what Java returns.
     */
    @Test
    void aHashFromDataFailsOnTheReferenceAndIsItsBytesOnJavac() {
        String source = """
                import org.julclang.core.PlutusData;
                import org.julclang.ledger.PubKeyHash;
                import org.julclang.stdlib.Builtins;
                class C { static long m(PlutusData d) { return Builtins.lengthOfByteString(PubKeyHash.fromPlutusData(d).hash()); } }
                """;
        var bytes = new PlutusData.BytesData(new byte[]{1, 2});
        var reference = assertThrows(TermExtractor.ExtractionException.class, () -> JulcEval.forSource(source).frontend(CompilerFrontend.JAVAPARSER).call("m", bytes).asInteger());
        assertTrue(reference.getMessage().contains("expected bytestring"), reference::getMessage);
        assertEquals(BigInteger.TWO, JulcEval.forSource(source).frontend(CompilerFrontend.JAVAC).call("m", bytes).asInteger());
    }

    /** ADR-068 M5: with no frontend selected, the testkit compiles on javac, through either evaluation path. */
    @Test
    void theDefaultIsJavac() {
        var plain = assertThrows(CompilerException.class, () -> JulcEval.forSource(NOT_PORTED).call("twice", BigInteger.ONE));
        assertTrue(isNotPorted(plain), plain::getMessage);
        var mapped = assertThrows(RuntimeException.class,
                () -> JulcEval.forSource(NOT_PORTED).sourceMap().call("twice", BigInteger.ONE));
        assertTrue(mapped.getMessage().contains("Unsupported primitive type: short"), mapped::getMessage);
        var validator = assertThrows(CompilerException.class, () -> ValidatorTest.compile(
                VALIDATOR.replace("return ctx", "long fee = (short) ctx.txInfo().fee().longValue(); return ctx")));
        assertTrue(isNotPorted(validator), validator::getMessage);
    }

    /**
     * The review's F1: a class whose sibling {@code @OnchainLibrary} only the source root offers compiles on each frontend,
     * with and without a source map, so the opt-back keeps the project's libraries.
     */
    @Test
    void forClassFindsTheProjectsLibrariesOnEachFrontend() {
        var root = java.nio.file.Path.of("src/test/java");
        var user = org.julclang.testkit.fixtures.SiblingLibraryUser.class;
        for (var frontend : CompilerFrontend.values()) {
            assertEquals(BigInteger.valueOf(42), JulcEval.forClass(user, root).frontend(frontend)
                    .call("m", BigInteger.valueOf(21)).asInteger(), frontend.name());
            assertEquals(BigInteger.valueOf(42), JulcEval.forClass(user, root).frontend(frontend).sourceMap()
                    .call("m", BigInteger.valueOf(21)).asInteger(), frontend + " with a source map");
        }
        assertEquals(BigInteger.valueOf(42), JulcEval.forClass(user, root).call("m", BigInteger.valueOf(21)).asInteger());
    }

    /** The review's F2: the class-based compile entries take a frontend, and find the project's libraries on each. */
    @Test
    void classBasedCompilationTakesAFrontend() {
        var root = java.nio.file.Path.of("src/test/java");
        var sample = org.julclang.testkit.fixtures.SiblingLibraryValidator.class;
        byte[] reference = UplcFlatEncoder.encodeProgram(ValidatorTest.compileValidator(sample, root, CompilerFrontend.JAVAPARSER).program());
        assertArrayEquals(reference, UplcFlatEncoder.encodeProgram(ValidatorTest.compileValidator(sample, root, CompilerFrontend.JAVAC).program()));
        assertArrayEquals(reference, UplcFlatEncoder.encodeProgram(
                ValidatorTest.compileValidatorByName(sample.getName(), root, CompilerFrontend.JAVAPARSER).program()));
        assertArrayEquals(reference, UplcFlatEncoder.encodeProgram(
                ValidatorTest.compileValidatorByName(sample.getName(), root, CompilerFrontend.JAVAC).program()));
        var contract = new ContractTest() {
            byte[] bytes(CompilerFrontend frontend) {
                return UplcFlatEncoder.encodeProgram(compileValidator(sample, root, frontend).program());
            }
        };
        assertArrayEquals(reference, contract.bytes(CompilerFrontend.JAVAPARSER));
        assertArrayEquals(reference, contract.bytes(CompilerFrontend.JAVAC));
    }

    @Test
    void validatorTestCompilesOnEachFrontend() {
        assertArrayEquals(UplcFlatEncoder.encodeProgram(ValidatorTest.compile(VALIDATOR, CompilerFrontend.JAVAPARSER)),
                UplcFlatEncoder.encodeProgram(ValidatorTest.compile(VALIDATOR, CompilerFrontend.JAVAC)));
    }

    @Test
    void validatorTestReachesTheFrontend() {
        String source = VALIDATOR.replace("return ctx.txInfo().signatories().isEmpty();", "long fee = (short) ctx.txInfo().fee().longValue(); return ctx.txInfo().signatories().isEmpty();");
        ValidatorTest.compile(source, CompilerFrontend.JAVAPARSER);
        var failure = assertThrows(CompilerException.class, () -> ValidatorTest.compile(source, CompilerFrontend.JAVAC));
        assertTrue(isNotPorted(failure), failure::getMessage);
    }
}
