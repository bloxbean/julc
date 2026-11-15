package org.julclang.compiler;

import org.julclang.compiler.codegen.SharedBindings;
import org.julclang.compiler.uplc.SharedClosedValues;
import org.julclang.compiler.uplc.UplcOptimizer;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.vm.ExBudget;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-076 in the tests of other rules. Its two rules change bytes and budgets at {@code PV11_SAFE} and
 * {@code PV11_COSTED}, so a test that holds another rule to its own byte, size or budget guarantee compiles the same
 * program with both ADR-076 rules off ({@link #off}) and compares that, and holds the program with the rules on to
 * ADR-076's own guarantees: it is the rules-off program with its closed values bound at the root (D2, I1), and its
 * budget is at most the rules-off budget plus the bindings' steps (I2).
 */
final class Adr076Sharing {

    /** Both ADR-076 rules: shared handler bindings (D1) and shared closed values (D2). */
    static final List<String> RULES = List.of(SharedBindings.RULE, UplcOptimizer.SHARED_CLOSED_VALUES_RULE);

    /** One machine step (variable, lambda, application, force, builtin) under the pinned cost models. */
    static final ExBudget STEP = new ExBudget(16_000, 100);

    /** The steps of the costliest binding D2 adds: an application, a lambda, two forces and a builtin. */
    static final int STEPS_PER_SHARED_VALUE = 5;

    private Adr076Sharing() {
    }

    /** {@code options} with both ADR-076 rules disabled. */
    static CompilerOptions off(CompilerOptions options) {
        RULES.forEach(options::disableOptimizationRule);
        return options;
    }

    /**
     * {@code on} is {@code off} with repeated closed values bound at its root, term for term, no larger, and D1 did not
     * apply. Returns how many values D2 bound.
     */
    static int assertSharesOnlyClosedValues(CompileResult on, CompileResult off, String label) {
        assertFalse(on.optimizationReport().appliedRules().contains(SharedBindings.RULE), label + ": D1 applied");
        var term = on.program().term();
        assertEquals(off.program().term(), SharedClosedValues.unshare(term),
                label + ": substituting the shared closed values back gives the program without ADR-076");
        assertTrue(UplcFlatEncoder.encodeProgram(on.program()).length <= UplcFlatEncoder.encodeProgram(off.program()).length,
                label + ": a value is shared only where it saves bits");
        return SharedClosedValues.count(term);
    }

    /** A run of the rules-on program costs at most the rules-off run plus five steps per shared value (I2). */
    static void assertBudgetWithin(ExBudget on, ExBudget off, int sharedValues, String label) {
        long steps = (long) STEPS_PER_SHARED_VALUE * sharedValues;
        assertTrue(on.cpuSteps() <= off.cpuSteps() + steps * STEP.cpuSteps(),
                label + ": CPU beyond ADR-076's " + steps + " binding steps: " + off + " -> " + on);
        assertTrue(on.memoryUnits() <= off.memoryUnits() + steps * STEP.memoryUnits(),
                label + ": memory beyond ADR-076's " + steps + " binding steps: " + off + " -> " + on);
        if (on.cpuSteps() > off.cpuSteps() || on.memoryUnits() > off.memoryUnits()) {
            System.out.println("ADR076_BUDGET_RISE " + label + " shared=" + sharedValues + " "
                    + (on.cpuSteps() - off.cpuSteps()) + " cpu " + (on.memoryUnits() - off.memoryUnits()) + " mem");
        }
    }
}
