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

    // === V1 cross-validation ===

    @Test
    void testV1_addInteger() {
        crossValidateWithLanguage(
                "(program 1.0.0 [[(builtin addInteger) (con integer 3)] (con integer 4)])",
                PlutusLanguage.PLUTUS_V1);
    }

    @Test
    void testV1_factorial() {
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
        crossValidateWithLanguage(program, PlutusLanguage.PLUTUS_V1);
    }

    @Test
    void testV1_dataRoundTrip() {
        crossValidateWithLanguage(
                "(program 1.0.0 [(builtin unIData) [(builtin iData) (con integer 42)]])",
                PlutusLanguage.PLUTUS_V1);
    }

    @Test
    void testV1_sha256() {
        crossValidateWithLanguage(
                "(program 1.0.0 [(builtin sha2_256) (con bytestring #)])",
                PlutusLanguage.PLUTUS_V1);
    }

    @Test
    void testV1_listOps() {
        crossValidateWithLanguage("""
                (program 1.0.0
                  (force [(builtin nullList)
                    [(builtin mkNilData) (con unit ())]]))""",
                PlutusLanguage.PLUTUS_V1);
    }

    @Test
    void testV1_trace() {
        crossValidateWithLanguage("""
                (program 1.0.0
                  (force [[(force (builtin trace)) (con string "hello")] (delay (con integer 42))]))""",
                PlutusLanguage.PLUTUS_V1);
    }

    @Test
    void testV1_error() {
        crossValidateWithLanguage("(program 1.0.0 (error))", PlutusLanguage.PLUTUS_V1);
    }

    @Test
    void testV1_divideInteger() {
        crossValidateWithLanguage(
                "(program 1.0.0 [[(builtin divideInteger) (con integer 17)] (con integer 5)])",
                PlutusLanguage.PLUTUS_V1);
    }

    @Test
    void testV1_equalsByteString() {
        crossValidateWithLanguage(
                "(program 1.0.0 [[(builtin equalsByteString) (con bytestring #deadbeef)] (con bytestring #deadbeef)])",
                PlutusLanguage.PLUTUS_V1);
    }

    // === V2 cross-validation ===

    @Test
    void testV2_addInteger() {
        crossValidateWithLanguage(
                "(program 1.0.0 [[(builtin addInteger) (con integer 100)] (con integer 200)])",
                PlutusLanguage.PLUTUS_V2);
    }

    @Test
    void testV2_serialiseData() {
        crossValidateWithLanguage(
                "(program 1.0.0 [(builtin serialiseData) (con data (I 42))])",
                PlutusLanguage.PLUTUS_V2);
    }

    @Test
    void testV2_factorial() {
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
        crossValidateWithLanguage(program, PlutusLanguage.PLUTUS_V2);
    }

    @Test
    void testV2_divideInteger() {
        crossValidateWithLanguage(
                "(program 1.0.0 [[(builtin divideInteger) (con integer 17)] (con integer 5)])",
                PlutusLanguage.PLUTUS_V2);
    }

    // === V1/V2 evaluateWithArgs ===

    @Test
    void testV1_evaluateWithArgs() {
        if (!hasScalus()) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Scalus provider not available");
            return;
        }

        // Simple V1 validator: \datum -> \redeemer -> \ctx -> datum
        var program = Program.plutusV1(
                Term.lam("d", Term.lam("r", Term.lam("ctx", Term.var(3)))));
        var args = List.of(PlutusData.integer(42), PlutusData.integer(0), PlutusData.integer(0));

        var javaResult = javaProvider.evaluateWithArgs(program, PlutusLanguage.PLUTUS_V1, args, null);
        var scalusResult = scalusProvider.evaluateWithArgs(program, PlutusLanguage.PLUTUS_V1, args, null);

        assertTrue(javaResult.isSuccess(), "Java V1 result should be success: " + javaResult);
        assertTrue(scalusResult.isSuccess(), "Scalus V1 result should be success: " + scalusResult);

        String javaOutput = UplcPrinter.print(((EvalResult.Success) javaResult).resultTerm());
        String scalusOutput = UplcPrinter.print(((EvalResult.Success) scalusResult).resultTerm());
        assertEquals(scalusOutput, javaOutput, "V1 evaluateWithArgs results should match");
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

    private void crossValidateWithLanguage(String uplcProgram, PlutusLanguage language) {
        if (!hasScalus()) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Scalus provider not available");
            return;
        }

        Program program = UplcParser.parseProgram(uplcProgram);

        var javaResult = javaProvider.evaluate(program, language, null);
        var scalusResult = scalusProvider.evaluate(program, language, null);

        // Both should agree on success/failure
        assertEquals(scalusResult.isSuccess(), javaResult.isSuccess(),
                "Providers disagree on success/failure for " + language + ".\nJava: " + javaResult +
                "\nScalus: " + scalusResult);

        if (javaResult.isSuccess() && scalusResult.isSuccess()) {
            var javaSuccess = (EvalResult.Success) javaResult;
            var scalusSuccess = (EvalResult.Success) scalusResult;

            String javaOutput = UplcPrinter.print(javaSuccess.resultTerm());
            String scalusOutput = UplcPrinter.print(scalusSuccess.resultTerm());
            assertEquals(scalusOutput, javaOutput,
                    "Result terms differ for " + language + ".\nInput: " + uplcProgram);
        }

        // Compare trace messages
        assertEquals(scalusResult.traces(), javaResult.traces(),
                "Traces differ for " + language);
    }

    private void crossValidate(String uplcProgram) {
        if (!hasScalus()) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Scalus provider not available");
            return;
        }

        Program program = UplcParser.parseProgram(uplcProgram);
        // UPLC version alone doesn't determine Plutus version — default to V3.
        // Use crossValidateWithLanguage() for explicit V1/V2 testing.
        PlutusLanguage lang = PlutusLanguage.PLUTUS_V3;

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
