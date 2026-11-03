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

/** ADR-068 D4: the testkit's frontend option. A construct not ported stopping {@code javac} alone shows it is honoured. */
class FrontendOptionTest {

    private static final String METHOD = """
            import java.math.BigInteger;
            class C { static BigInteger twice(BigInteger a) { return a.add(a); } }
            """;

    private static final String NOT_PORTED = """
            import java.math.BigInteger;
            class C { static BigInteger twice(BigInteger a) { BigInteger b = (BigInteger) (Object) a; return b.add(b); } }
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

    private static boolean isNotPorted(CompilerException e) {
        return e.diagnostics().stream().anyMatch(d -> DiagnosticCodes.FRONTEND_CONSTRUCT_NOT_PORTED.code().equals(d.code()));
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
        var reference = JulcEval.forSource(source).call("cube", BigInteger.valueOf(-4)).asInteger();
        assertEquals(BigInteger.valueOf(-65), reference);
        assertEquals(reference, JulcEval.forSource(source).frontend(CompilerFrontend.JAVAC).call("cube", BigInteger.valueOf(-4)).asInteger());
    }

    @Test
    void julcEvalReachesTheFrontend() {
        assertEquals(BigInteger.valueOf(42), JulcEval.forSource(NOT_PORTED).call("twice", BigInteger.valueOf(21)).asInteger());
        var eval = JulcEval.forSource(NOT_PORTED).frontend(CompilerFrontend.JAVAC);
        var failure = assertThrows(CompilerException.class, () -> eval.call("twice", BigInteger.ONE));
        assertTrue(isNotPorted(failure), failure::getMessage);
    }

    /**
     * The reference reads a hash in a parameter as Data, and it is bytes by then: Java returns them and the script fails.
     * javac stops the program instead. If this first assertion fails the reference has changed, and the stop can go.
     */
    @Test
    void aHashReadFromAParameterFailsOnTheReferenceAndStopsOnJavac() {
        String source = """
                import org.julclang.ledger.PubKeyHash;
                class C { static byte[] m(PubKeyHash p) { return p.hash(); } }
                """;
        var bytes = new PlutusData.BytesData(new byte[]{1, 2});
        var reference = assertThrows(TermExtractor.ExtractionException.class, () -> JulcEval.forSource(source).call("m", bytes).asData());
        assertTrue(reference.getMessage().contains("UnBData: expected data"), reference::getMessage);
        var javac = assertThrows(CompilerException.class, () -> JulcEval.forSource(source).frontend(CompilerFrontend.JAVAC).call("m", bytes).asData());
        assertTrue(isNotPorted(javac), javac::getMessage);
    }

    /** {@code fromPlutusData} on a hash is an identity there, so what follows meets Data; javac stops it. */
    @Test
    void aHashFromDataFailsOnTheReferenceAndStopsOnJavac() {
        String source = """
                import org.julclang.core.PlutusData;
                import org.julclang.ledger.PubKeyHash;
                import org.julclang.stdlib.Builtins;
                class C { static long m(PlutusData d) { return Builtins.lengthOfByteString(PubKeyHash.fromPlutusData(d).hash()); } }
                """;
        var bytes = new PlutusData.BytesData(new byte[]{1, 2});
        var reference = assertThrows(TermExtractor.ExtractionException.class, () -> JulcEval.forSource(source).call("m", bytes).asInteger());
        assertTrue(reference.getMessage().contains("expected bytestring"), reference::getMessage);
        var javac = assertThrows(CompilerException.class, () -> JulcEval.forSource(source).frontend(CompilerFrontend.JAVAC).call("m", bytes).asInteger());
        assertTrue(isNotPorted(javac), javac::getMessage);
    }

    @Test
    void validatorTestCompilesOnEachFrontend() {
        assertArrayEquals(UplcFlatEncoder.encodeProgram(ValidatorTest.compile(VALIDATOR)),
                UplcFlatEncoder.encodeProgram(ValidatorTest.compile(VALIDATOR, CompilerFrontend.JAVAC)));
    }

    @Test
    void validatorTestReachesTheFrontend() {
        String source = VALIDATOR.replace("return ctx.txInfo().signatories().isEmpty();", "var signers = (Object) ctx.txInfo().signatories(); return ctx.txInfo().signatories().isEmpty();");
        ValidatorTest.compile(source);
        var failure = assertThrows(CompilerException.class, () -> ValidatorTest.compile(source, CompilerFrontend.JAVAC));
        assertTrue(isNotPorted(failure), failure::getMessage);
    }
}
