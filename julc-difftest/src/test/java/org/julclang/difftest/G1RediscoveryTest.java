package org.julclang.difftest;

import org.junit.jupiter.api.Test;

import java.util.List;

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
        pinned("diverge-g1-" + name, "agree-g1-" + name + "-counterfactual", defect);
    }

    private void pinned(String reproducer, String counterfactual, Verdict.Kind defect) {
        counterfactualAgrees(saved(reproducer), saved(counterfactual), defect);
    }

    /**
     * #280: the helper gets an unused parameter. The cases found by earlier G1 revisions stay (ADR-063 D8): G1 r1's,
     * then one per later revision, with its own provenance (r4 found r2's program again).
     */
    @Test
    void zeroArgumentHelper() {
        pinned("diverge-g1-280-uncalled-zero-arg-helper", "agree-g1-280-counterfactual", Verdict.Kind.MISMATCH);
        pinned("diverge-g1-280-uncalled-zero-arg-helper-r2", "agree-g1-280-uncalled-zero-arg-helper-counterfactual",
                Verdict.Kind.MISMATCH);
        for (String revision : List.of("-r3", "-r4", "-r5")) pinned("280-uncalled-zero-arg-helper" + revision, Verdict.Kind.MISMATCH);
    }

    /** #146: the traced operand is bound to a local before the comparison. */
    @Test
    void tracedComparisonOperand() {
        pinned("diverge-g1-146-traced-compare-operand", "agree-g1-146-counterfactual", Verdict.Kind.MISMATCH);
        pinned("diverge-g1-146-traced-compare-operand-r2", "agree-g1-146-traced-compare-operand-counterfactual",
                Verdict.Kind.MISMATCH);
        for (String revision : List.of("-r3", "-r4", "-r5")) pinned("146-traced-compare-operand" + revision, Verdict.Kind.MISMATCH);
    }

    /** #209, runtime form: the branch local is renamed apart; UPLC fails reading the field through the wrong layout. */
    @Test
    void localShadowingAFieldMakesAReadFail() {
        for (String revision : List.of("", "-r3", "-r4", "-r5")) pinned("209-runtime-misread" + revision, Verdict.Kind.MISMATCH);
    }

    /** #209, silent form (the issue's own shape): the read returns another component of the same type. */
    @Test
    void localShadowingAFieldMakesAReadReturnAnotherComponent() {
        for (String revision : List.of("", "-r3", "-r4", "-r5")) pinned("209-silent-misread" + revision, Verdict.Kind.MISMATCH);
    }

    /** #291, silent and runtime forms: the default arm block's local is renamed apart. */
    @Test
    void defaultBlockLocalMakesAReadMisread() {
        pinned("291-silent-misread", Verdict.Kind.MISMATCH);
        pinned("291-runtime-misread", Verdict.Kind.MISMATCH);
    }

    /**
     * #291, second symptom: a local declared after a loop in a default arm block of a switch passed to a method, here
     * a second loop's counter and a plain local, is rejected with a diagnostic that has no code; the counterfactual
     * assigns the switch to a local first.
     */
    @Test
    void aLocalAfterALoopInADefaultBlockInAnArgumentIsRejected() {
        for (String name : List.of("291-default-block-loops", "291-local-after-loop")) {
            pinned("reject-g1-" + name, "agree-g1-" + name + "-counterfactual", Verdict.Kind.REJECTED);
            RUNNER.run(saved("reject-g1-" + name)).results().values().forEach(result -> {
                var rejected = (Observation.Rejected) result.uplc();
                assertEquals(null, rejected.code());
                assertTrue(rejected.message().startsWith("Unbound variable"), rejected.message());
            });
        }
    }

    /** #291, rejection form: JuLC rejects the read of the field the default arm block's local named (JULC0055). */
    @Test
    void defaultBlockLocalMakesAReadUnresolvable() {
        pinned("reject-g1-291-unresolved-read", "agree-g1-291-unresolved-read-counterfactual", Verdict.Kind.REJECTED);
        RUNNER.run(saved("reject-g1-291-unresolved-read")).results().values().forEach(result ->
                assertEquals("JULC0055", ((Observation.Rejected) result.uplc()).code()));
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
