package org.julclang.testkit;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerFrontend;
import org.julclang.compiler.error.DiagnosticCodes;
import org.julclang.core.flat.UplcFlatEncoder;
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
            class C { static BigInteger twice(BigInteger a) { var b = a; return b.add(b); } }
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

    @Test
    void julcEvalReachesTheFrontend() {
        assertEquals(BigInteger.valueOf(42), JulcEval.forSource(NOT_PORTED).call("twice", BigInteger.valueOf(21)).asInteger());
        var eval = JulcEval.forSource(NOT_PORTED).frontend(CompilerFrontend.JAVAC);
        var failure = assertThrows(CompilerException.class, () -> eval.call("twice", BigInteger.ONE));
        assertTrue(isNotPorted(failure), failure::getMessage);
    }

    @Test
    void validatorTestCompilesOnEachFrontend() {
        assertArrayEquals(UplcFlatEncoder.encodeProgram(ValidatorTest.compile(VALIDATOR)),
                UplcFlatEncoder.encodeProgram(ValidatorTest.compile(VALIDATOR, CompilerFrontend.JAVAC)));
    }

    @Test
    void validatorTestReachesTheFrontend() {
        String source = VALIDATOR.replace("return ctx.txInfo().signatories().isEmpty();", "var s = ctx.txInfo().signatories(); return s.isEmpty();");
        ValidatorTest.compile(source);
        var failure = assertThrows(CompilerException.class, () -> ValidatorTest.compile(source, CompilerFrontend.JAVAC));
        assertTrue(isNotPorted(failure), failure::getMessage);
    }
}
