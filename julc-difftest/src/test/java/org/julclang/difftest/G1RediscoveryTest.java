package org.julclang.difftest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cause checks of G1's rediscoveries (ADR-066 D10.1, M2). For a defect not yet fixed, each shrunk reproducer
 * still shows its defect, and its constrained counterfactual, which changes only the defect's trigger, keeps the JVM
 * observation and agrees. When a fix lands, its reproducer agrees and the check becomes the fix-based one: a
 * regression guard that the reproducer agrees with the JVM at every level, with the counterfactual kept beside it
 * (#291, fixed by ADR-067 M1 round 3; #209, fixed by the loop-break continuation fix).
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

    /**
     * The fix-based check of a defect fixed by a code change (ADR-066 D10.1): the reproducer now agrees with the JVM at
     * every level, and its counterfactual still keeps the JVM observation and agrees.
     */
    private static void fixed(String reproducer, String counterfactual) {
        fixed(saved(reproducer), saved(counterfactual));
    }

    private static void fixed(DiffCase reproducer, DiffCase counterfactual) {
        var found = RUNNER.run(reproducer);
        var changed = RUNNER.run(counterfactual);
        assertEquals(found.jvm(), changed.jvm(), "the counterfactual keeps the JVM observation");
        assertTrue(found.jvm() instanceof Observation.Value, found.describe());
        assertTrue(found.all(Verdict.Kind.AGREE), found.describe());
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

    /**
     * #209, runtime form, fixed by the loop-break continuation fix (#254, #255): UPLC failed reading the field through
     * the branch local's layout; the reproducers now agree. The counterfactual renames the branch local apart.
     */
    @Test
    void localShadowingAFieldNoLongerMakesAReadFail() {
        for (String revision : List.of("", "-r3", "-r4", "-r5")) {
            fixed("diverge-g1-209-runtime-misread" + revision, "agree-g1-209-runtime-misread" + revision + "-counterfactual");
        }
    }

    /**
     * #209, silent form (the issue's own shape), fixed by the loop-break continuation fix: the read returned another
     * component of the same type; the reproducers now agree.
     */
    @Test
    void localShadowingAFieldNoLongerReturnsAnotherComponent() {
        for (String revision : List.of("", "-r3", "-r4", "-r5")) {
            fixed("diverge-g1-209-silent-misread" + revision, "agree-g1-209-silent-misread" + revision + "-counterfactual");
        }
    }

    /**
     * #291, silent and runtime forms, fixed by ADR-067 M1 round 3 (the {@code default} arm block is scoped): the
     * reproducers, a local in the block named like a field read after the switch, now agree; the counterfactual renames
     * the local apart.
     */
    @Test
    void defaultBlockLocalNoLongerMisreadsAField() {
        fixed("diverge-g1-291-silent-misread", "agree-g1-291-silent-misread-counterfactual");
        fixed("diverge-g1-291-runtime-misread", "agree-g1-291-runtime-misread-counterfactual");
    }

    /**
     * #291, second symptom, fixed by ADR-067 M1 round 3: a local declared after a loop in a default arm block of a switch
     * passed to a method, here a second loop's counter and a plain local, was rejected ("Unbound variable"); it now
     * compiles and agrees. The counterfactual assigns the switch to a local first.
     */
    @Test
    void aLocalAfterALoopInADefaultBlockInAnArgumentAgrees() {
        for (String name : List.of("291-default-block-loops", "291-local-after-loop")) {
            fixed("reject-g1-" + name, "agree-g1-" + name + "-counterfactual");
        }
    }

    /**
     * #291, rejection form, fixed by ADR-067 M1 round 3: the read of the field the default arm block's local named was
     * rejected (JULC0055); it now resolves to the field and agrees.
     */
    @Test
    void defaultBlockLocalNoLongerHidesAField() {
        fixed("reject-g1-291-unresolved-read", "agree-g1-291-unresolved-read-counterfactual");
    }

    /**
     * #209, rejection form, found under G1 r1: the counterfactual renames the branch local apart; the reproducer is
     * that program with the local named like the field {@code R0 f}. JuLC kept the local's type {@code BigInteger} for
     * {@code f.v()} after the {@code if} and rejected the program (JULC0055), which Java accepts; since the loop-break
     * continuation fix it resolves the field and agrees.
     */
    @Test
    void localShadowingAFieldNoLongerMakesAReadUnresolvable() {
        var counterfactual = saved("agree-g1-209-rejection-counterfactual");
        String source = counterfactual.source().replace("BigInteger f1 = ", "BigInteger f = ");
        assertNotEquals(counterfactual.source(), source);
        var reproducer = new DiffCase("#209 reproducer", source, counterfactual.className(), counterfactual.method(),
                counterfactual.args(), counterfactual.tags(), counterfactual.profile());
        fixed(reproducer, counterfactual);
    }
}
