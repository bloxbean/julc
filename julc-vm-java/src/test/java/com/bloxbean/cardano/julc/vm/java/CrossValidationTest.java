package com.bloxbean.cardano.julc.vm.java;

import com.bloxbean.cardano.julc.core.*;
import com.bloxbean.cardano.julc.core.text.UplcParser;
import com.bloxbean.cardano.julc.core.text.UplcPrinter;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.JulcVm;
import com.bloxbean.cardano.julc.vm.JulcVmProvider;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cross-validation tests: run programs through both Java and Scalus backends
 * and assert identical results.
 */
class CrossValidationTest {

    private final JavaVmProvider javaProvider = new JavaVmProvider();
    private final JulcVmProvider scalusProvider;

    CrossValidationTest() {
        // Find Scalus provider via service loader
        JulcVmProvider found = null;
        for (var provider : ServiceLoader.load(JulcVmProvider.class)) {
            if ("Scalus".equals(provider.name())) {
                found = provider;
                break;
            }
        }
        this.scalusProvider = found;
    }

    private boolean hasScalus() {
        return scalusProvider != null;
    }

    @Test
    void testProviderPriority() {
        assertEquals(100, javaProvider.priority());
        assertEquals("Java", javaProvider.name());
    }

    @Test
    void testAddInteger() {
        crossValidate("(program 1.0.0 [[(builtin addInteger) (con integer 3)] (con integer 4)])");
    }

    @Test
    void testFactorial() {
        // Factorial of 5 via Y combinator
        String program = """
                (program 1.0.0
                  [[(lam f
                    [(lam x [f (lam v [[x x] v])])
                     (lam x [f (lam v [[x x] v])])])
                   (lam self (lam n
                     (force [[(force (builtin ifThenElse))
                       [[(builtin equalsInteger) n] (con integer 0)]]
                       (delay (con integer 1))
                       (delay [[(builtin multiplyInteger) n]
                               [self [[(builtin subtractInteger) n] (con integer 1)]]])])))]
                   (con integer 5)]
                )""";
        crossValidate(program);
    }

    @Test
    void testDataRoundTrip() {
        crossValidate("(program 1.0.0 [(builtin unIData) [(builtin iData) (con integer 42)]])");
    }

    @Test
    void testListOps() {
        crossValidate("""
                (program 1.0.0
                  (force [(builtin nullList)
                    [(builtin mkNilData) (con unit ())]]))""");
    }

    @Test
    void testConstrCase() {
        crossValidate("(program 1.1.0 (case (constr 0 (con integer 99)) (lam x x) (lam x (con integer 0))))");
    }

    @Test
    void testSha256() {
        crossValidate("(program 1.0.0 [(builtin sha2_256) (con bytestring #)])");
    }

    @Test
    void testTrace() {
        crossValidate("""
                (program 1.0.0
                  (force [[(force (builtin trace)) (con string "hello")] (delay (con integer 42))]))""");
    }

    @Test
    void testErrorTerm() {
        crossValidate("(program 1.0.0 (error))");
    }

    @Test
    void testEvaluateWithArgs() {
        if (!hasScalus()) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Scalus provider not available");
            return;
        }

        // Simple identity validator: \ctx -> ctx
        var program = Program.plutusV3(Term.lam("ctx", Term.var(1)));
        var args = List.of(PlutusData.integer(42));

        var javaResult = javaProvider.evaluateWithArgs(program, PlutusLanguage.PLUTUS_V3, args, null);
        var scalusResult = scalusProvider.evaluateWithArgs(program, PlutusLanguage.PLUTUS_V3, args, null);

        assertTrue(javaResult.isSuccess(), "Java result should be success");
        assertTrue(scalusResult.isSuccess(), "Scalus result should be success");

        var javaSuccess = (EvalResult.Success) javaResult;
        var scalusSuccess = (EvalResult.Success) scalusResult;

        String javaOutput = UplcPrinter.print(javaSuccess.resultTerm());
        String scalusOutput = UplcPrinter.print(scalusSuccess.resultTerm());
        assertEquals(scalusOutput, javaOutput, "Result terms should match");
    }

    private void crossValidate(String uplcProgram) {
        if (!hasScalus()) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Scalus provider not available");
            return;
        }

        Program program = UplcParser.parseProgram(uplcProgram);
        PlutusLanguage lang = program.minor() >= 1 ? PlutusLanguage.PLUTUS_V3 : PlutusLanguage.PLUTUS_V3;

        var javaResult = javaProvider.evaluate(program, lang, null);
        var scalusResult = scalusProvider.evaluate(program, lang, null);

        // Both should agree on success/failure
        assertEquals(scalusResult.isSuccess(), javaResult.isSuccess(),
                "Providers disagree on success/failure.\nJava: " + javaResult +
                "\nScalus: " + scalusResult);

        if (javaResult.isSuccess() && scalusResult.isSuccess()) {
            var javaSuccess = (EvalResult.Success) javaResult;
            var scalusSuccess = (EvalResult.Success) scalusResult;

            String javaOutput = UplcPrinter.print(javaSuccess.resultTerm());
            String scalusOutput = UplcPrinter.print(scalusSuccess.resultTerm());
            assertEquals(scalusOutput, javaOutput,
                    "Result terms differ.\nInput: " + uplcProgram);
        }

        // Compare trace messages
        assertEquals(scalusResult.traces(), javaResult.traces(),
                "Traces differ");
    }
}
