package com.bloxbean.cardano.julc.vm.java.cost;

import com.bloxbean.cardano.julc.core.*;
import com.bloxbean.cardano.julc.core.text.UplcParser;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.JulcVmProvider;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;
import com.bloxbean.cardano.julc.vm.java.JavaVmProvider;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cross-validates budget values between Java and Scalus VM backends.
 * Budgets must match exactly.
 */
class BudgetCrossValidationTest {

    private final JavaVmProvider javaProvider = new JavaVmProvider();
    private final JulcVmProvider scalusProvider;

    BudgetCrossValidationTest() {
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
    void constantInteger() {
        // Simplest possible: (con integer 42)
        // Expected: startup(100) + const(16000) = 16100 CPU, 200 mem
        var result = evaluateJava("(program 1.0.0 (con integer 42))");
        assertTrue(result.isSuccess());
        assertEquals(16100, result.budgetConsumed().cpuSteps());
        assertEquals(200, result.budgetConsumed().memoryUnits());
    }

    @Test
    void addIntegerBudget() {
        crossValidateBudget("(program 1.0.0 [[(builtin addInteger) (con integer 3)] (con integer 4)])");
    }

    @Test
    void factorialBudget() {
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
        crossValidateBudget(program);
    }

    @Test
    void sha256Budget() {
        crossValidateBudget("(program 1.0.0 [(builtin sha2_256) (con bytestring #)])");
    }

    @Test
    void dataRoundTripBudget() {
        crossValidateBudget("(program 1.0.0 [(builtin unIData) [(builtin iData) (con integer 42)]])");
    }

    @Test
    void listOpsBudget() {
        crossValidateBudget("""
                (program 1.0.0
                  (force [(builtin nullList)
                    [(builtin mkNilData) (con unit ())]]))""");
    }

    @Test
    void constrCaseBudget() {
        crossValidateBudget("(program 1.1.0 (case (constr 0 (con integer 99)) (lam x x) (lam x (con integer 0))))");
    }

    @Test
    void traceBudget() {
        crossValidateBudget("""
                (program 1.0.0
                  (force [[(force (builtin trace)) (con string "hello")] (delay (con integer 42))]))""");
    }

    @Test
    void javaBudgetIsNonZero() {
        // Just verify that the Java VM now returns non-zero budgets
        var result = evaluateJava("(program 1.0.0 [[(builtin addInteger) (con integer 1)] (con integer 2)])");
        assertTrue(result.isSuccess());
        var budget = result.budgetConsumed();
        assertTrue(budget.cpuSteps() > 0, "CPU should be > 0: " + budget.cpuSteps());
        assertTrue(budget.memoryUnits() > 0, "Mem should be > 0: " + budget.memoryUnits());
    }

    @Test
    void budgetExhaustedResult() {
        // Give very tight budget — should exhaust
        var program = UplcParser.parseProgram(
                "(program 1.0.0 [[(builtin addInteger) (con integer 1)] (con integer 2)])");
        var result = javaProvider.evaluate(program, PlutusLanguage.PLUTUS_V3,
                new com.bloxbean.cardano.julc.vm.ExBudget(10, 10));
        assertTrue(result instanceof EvalResult.BudgetExhausted,
                "Expected BudgetExhausted but got: " + result.getClass().getSimpleName());
    }

    // === V1 budget cross-validation ===

    @Test
    void v1_addIntegerBudget() {
        crossValidateBudgetWithLanguage(
                "(program 1.0.0 [[(builtin addInteger) (con integer 3)] (con integer 4)])",
                PlutusLanguage.PLUTUS_V1);
    }

    @Test
    void v1_factorialBudget() {
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
        crossValidateBudgetWithLanguage(program, PlutusLanguage.PLUTUS_V1);
    }

    @Test
    void v1_sha256Budget() {
        crossValidateBudgetWithLanguage(
                "(program 1.0.0 [(builtin sha2_256) (con bytestring #)])",
                PlutusLanguage.PLUTUS_V1);
    }

    @Test
    void v1_dataRoundTripBudget() {
        crossValidateBudgetWithLanguage(
                "(program 1.0.0 [(builtin unIData) [(builtin iData) (con integer 42)]])",
                PlutusLanguage.PLUTUS_V1);
    }

    @Test
    void v1_divideIntegerBudget() {
        crossValidateBudgetWithLanguage(
                "(program 1.0.0 [[(builtin divideInteger) (con integer 17)] (con integer 5)])",
                PlutusLanguage.PLUTUS_V1);
    }

    // === V2 budget cross-validation ===

    @Test
    void v2_addIntegerBudget() {
        crossValidateBudgetWithLanguage(
                "(program 1.0.0 [[(builtin addInteger) (con integer 3)] (con integer 4)])",
                PlutusLanguage.PLUTUS_V2);
    }

    @Test
    void v2_serialiseDataBudget() {
        crossValidateBudgetWithLanguage(
                "(program 1.0.0 [(builtin serialiseData) (con data (I 42))])",
                PlutusLanguage.PLUTUS_V2);
    }

    @Test
    void v2_divideIntegerBudget() {
        crossValidateBudgetWithLanguage(
                "(program 1.0.0 [[(builtin divideInteger) (con integer 17)] (con integer 5)])",
                PlutusLanguage.PLUTUS_V2);
    }

    @Test
    void v2_factorialBudget() {
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
        crossValidateBudgetWithLanguage(program, PlutusLanguage.PLUTUS_V2);
    }

    // === Off-diagonal division cross-validation (tests ConstAboveDiagonal fix) ===

    @Test
    void divideIntegerAsymmetricLargeNumerator() {
        // x=2 words, y=1 word — triggers below-diagonal path (polynomial)
        crossValidateBudget("(program 1.0.0 [[(builtin divideInteger) (con integer 18446744073709551616)] (con integer 5)])");
    }

    @Test
    void divideIntegerAsymmetricSmallNumerator() {
        // x=1 word, y=2 words — triggers above-diagonal path (constant)
        crossValidateBudget("(program 1.0.0 [[(builtin divideInteger) (con integer 5)] (con integer 18446744073709551616)])");
    }

    @Test
    void modIntegerAsymmetric() {
        crossValidateBudget("(program 1.0.0 [[(builtin modInteger) (con integer 18446744073709551616)] (con integer 5)])");
    }

    @Test
    void quotientIntegerAsymmetric() {
        crossValidateBudget("(program 1.0.0 [[(builtin quotientInteger) (con integer 18446744073709551616)] (con integer 5)])");
    }

    @Test
    void remainderIntegerAsymmetric() {
        crossValidateBudget("(program 1.0.0 [[(builtin remainderInteger) (con integer 5)] (con integer 18446744073709551616)])");
    }

    @Test
    void failureStillReportsBudget() {
        var result = evaluateJava("(program 1.0.0 (error))");
        assertFalse(result.isSuccess());
        // Even failure should have startup cost
        assertTrue(result.budgetConsumed().cpuSteps() > 0);
    }

    private EvalResult evaluateJava(String uplcProgram) {
        Program program = UplcParser.parseProgram(uplcProgram);
        return javaProvider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);
    }

    private void crossValidateBudgetWithLanguage(String uplcProgram, PlutusLanguage language) {
        if (!hasScalus()) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Scalus provider not available");
            return;
        }

        Program program = UplcParser.parseProgram(uplcProgram);
        var javaResult = javaProvider.evaluate(program, language, null);
        var scalusResult = scalusProvider.evaluate(program, language, null);

        assertEquals(scalusResult.isSuccess(), javaResult.isSuccess(),
                language + " success/failure mismatch.\nJava: " + javaResult + "\nScalus: " + scalusResult);

        var javaBudget = javaResult.budgetConsumed();
        var scalusBudget = scalusResult.budgetConsumed();

        assertTrue(javaBudget.cpuSteps() > 0, language + " Java CPU should be > 0");
        assertTrue(scalusBudget.cpuSteps() > 0, language + " Scalus CPU should be > 0");

        assertEquals(scalusBudget.cpuSteps(), javaBudget.cpuSteps(), language + " CPU budget mismatch");
        assertEquals(scalusBudget.memoryUnits(), javaBudget.memoryUnits(), language + " Memory budget mismatch");
    }

    private void crossValidateBudget(String uplcProgram) {
        if (!hasScalus()) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Scalus provider not available");
            return;
        }

        Program program = UplcParser.parseProgram(uplcProgram);
        var javaResult = javaProvider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);
        var scalusResult = scalusProvider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

        assertEquals(scalusResult.isSuccess(), javaResult.isSuccess(),
                "Success/failure mismatch");

        var javaBudget = javaResult.budgetConsumed();
        var scalusBudget = scalusResult.budgetConsumed();

        // Both should have non-zero budgets
        assertTrue(javaBudget.cpuSteps() > 0, "Java CPU should be > 0");
        assertTrue(scalusBudget.cpuSteps() > 0, "Scalus CPU should be > 0");

        assertEquals(scalusBudget.cpuSteps(), javaBudget.cpuSteps(), "CPU budget mismatch");
        assertEquals(scalusBudget.memoryUnits(), javaBudget.memoryUnits(), "Memory budget mismatch");
    }
}
