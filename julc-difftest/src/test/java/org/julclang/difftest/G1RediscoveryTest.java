package org.julclang.difftest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cause checks of G1's provisional rediscoveries (ADR-066 D10.1, M2) for defects not yet fixed: each shrunk
 * reproducer still shows its defect, and its constrained counterfactual, which changes only the defect's trigger,
 * keeps the JVM observation and agrees. When a fix lands, its reproducer agrees and this test is updated with the
 * fix-based check.
 */
class G1RediscoveryTest {

    private static final DiffRunner RUNNER = DiffRunner.standard();

    private static DiffCase saved(String name) {
        return RegressionStore.read(RegressionCasesTest.CASES.resolve(name + ".diffcase")).diffCase();
    }

    private static void counterfactualAgrees(DiffCase reproducer, DiffCase counterfactual, Verdict.Kind defect) {
        var found = RUNNER.run(reproducer);
        var changed = RUNNER.run(counterfactual);
        assertEquals(found.jvm(), changed.jvm(), "the counterfactual keeps the JVM observation");
        assertTrue(found.all(defect), found.describe());
        assertTrue(changed.all(Verdict.Kind.AGREE), changed.describe());
    }

    private void pinned(String name, Verdict.Kind defect) {
        counterfactualAgrees(saved("diverge-g1-" + name), saved("agree-g1-" + name + "-counterfactual"), defect);
    }

    /** #280: the helper gets an unused parameter. */
    @Test
    void zeroArgumentHelper() {
        pinned("280-uncalled-zero-arg-helper", Verdict.Kind.MISMATCH);
    }

    /** #146: the traced operand is bound to a local before the comparison. */
    @Test
    void tracedComparisonOperand() {
        pinned("146-traced-compare-operand", Verdict.Kind.MISMATCH);
    }

    /** #209, runtime form: the branch local is renamed apart; UPLC fails reading the field through the wrong layout. */
    @Test
    void localShadowingAFieldMakesAReadFail() {
        pinned("209-runtime-misread", Verdict.Kind.MISMATCH);
    }

    /** #209, silent form (the issue's own shape): the read returns another component of the same type. */
    @Test
    void localShadowingAFieldMakesAReadReturnAnotherComponent() {
        pinned("209-silent-misread", Verdict.Kind.MISMATCH);
    }

    /**
     * #209, rejection form, found under G1 r1: the counterfactual renames the branch local apart; the reproducer is
     * that program with the local named like the field {@code R0 f}. JuLC keeps the local's type {@code BigInteger} for
     * {@code f.v()} after the {@code if} and rejects the program, which Java accepts.
     */
    @Test
    void localShadowingAFieldMakesAReadUnresolvable() {
        var counterfactual = saved("agree-g1-209-rejection-counterfactual");
        String source = counterfactual.source().replace("BigInteger f1 = ", "BigInteger f = ");
        assertNotEquals(counterfactual.source(), source);
        var reproducer = new DiffCase("#209 reproducer", source, counterfactual.className(), counterfactual.method(),
                counterfactual.args(), counterfactual.tags(), counterfactual.profile());
        counterfactualAgrees(reproducer, counterfactual, Verdict.Kind.REJECTED);
        RUNNER.run(reproducer).results().values().forEach(result ->
                assertEquals("JULC0055", ((Observation.Rejected) result.uplc()).code()));
    }
}
