package com.bloxbean.cardano.plutus.testkit;

import com.bloxbean.cardano.plutus.core.PlutusData;
import com.bloxbean.cardano.plutus.core.Program;
import com.bloxbean.cardano.plutus.vm.EvalResult;
import com.bloxbean.cardano.plutus.vm.PlutusVm;

/**
 * Abstract base class for validator contract tests.
 * <p>
 * Provides pre-configured PlutusVm instance and convenience methods
 * for building script contexts and evaluating validators.
 * <p>
 * Extend this class to write tests for your validators:
 * <pre>{@code
 * class MyValidatorTest extends ContractTest {
 *     @Test
 *     void testAccepts() {
 *         var ctx = scriptContext(PlutusData.integer(42));
 *         var result = evaluate(mySource, ctx);
 *         assertSuccess(result);
 *     }
 * }
 * }</pre>
 */
public abstract class ContractTest {

    /**
     * The PlutusVm instance, lazily initialized on first access.
     */
    private PlutusVm vm;

    /**
     * Returns the shared PlutusVm instance, creating it on first access.
     */
    protected PlutusVm vm() {
        if (vm == null) {
            vm = PlutusVm.create();
        }
        return vm;
    }

    /**
     * Build a minimal mock ScriptContext as PlutusData.
     * <p>
     * ScriptContext = Constr(0, [txInfo, redeemer, scriptInfo])
     * <p>
     * Uses a dummy txInfo (integer 0) and dummy scriptInfo (integer 0).
     * The redeemer is placed at field index 1.
     *
     * @param redeemer the redeemer PlutusData
     * @return a mock ScriptContext PlutusData
     */
    protected PlutusData scriptContext(PlutusData redeemer) {
        return PlutusData.constr(0,
                PlutusData.integer(0),
                redeemer,
                PlutusData.integer(0));
    }

    /**
     * Compile Java source and evaluate with the given arguments.
     *
     * @param javaSource the validator Java source code
     * @param args       the PlutusData arguments
     * @return the evaluation result
     */
    protected EvalResult evaluate(String javaSource, PlutusData... args) {
        return ValidatorTest.evaluate(javaSource, args);
    }

    /**
     * Evaluate a compiled program with the given arguments.
     *
     * @param program the compiled UPLC program
     * @param args    the PlutusData arguments
     * @return the evaluation result
     */
    protected EvalResult evaluate(Program program, PlutusData... args) {
        return ValidatorTest.evaluate(program, args);
    }

    /**
     * Compile Java source to a UPLC program.
     *
     * @param javaSource the validator Java source code
     * @return the compiled Program
     */
    protected Program compile(String javaSource) {
        return ValidatorTest.compile(javaSource);
    }

    /**
     * Assert that the result is a success.
     */
    protected void assertSuccess(EvalResult result) {
        BudgetAssertions.assertSuccess(result);
    }

    /**
     * Assert that the result is a failure.
     */
    protected void assertFailure(EvalResult result) {
        BudgetAssertions.assertFailure(result);
    }

    /**
     * Assert that the budget is under the given limits.
     */
    protected void assertBudgetUnder(EvalResult result, long maxCpu, long maxMem) {
        BudgetAssertions.assertBudgetUnder(result, maxCpu, maxMem);
    }
}
