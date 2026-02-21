package com.bloxbean.cardano.julc.decompiler;

import com.bloxbean.cardano.julc.core.*;
import com.bloxbean.cardano.julc.decompiler.input.ScriptAnalyzer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ScriptAnalyzer: structural analysis of UPLC programs.
 */
class ScriptAnalyzerTest {

    @Test
    void testSimpleLambdaStats() {
        // (lam x x)
        var program = Program.plutusV3(
                Term.lam("x", Term.var(new NamedDeBruijn("x", 1))));
        var stats = ScriptAnalyzer.analyze(program);

        assertEquals("1.1.0", stats.programVersion());
        assertEquals(2, stats.totalNodes());
        assertEquals(1, stats.estimatedArity());
        assertEquals(1, stats.lamCount());
        assertTrue(stats.builtinsUsed().isEmpty());
        assertFalse(stats.usesSop());
    }

    @Test
    void testBuiltinDetection() {
        // Apply(Apply(Builtin(AddInteger), Const(1)), Const(2))
        var program = Program.plutusV3(
                Term.apply(
                        Term.apply(Term.builtin(DefaultFun.AddInteger),
                                Term.const_(Constant.integer(1))),
                        Term.const_(Constant.integer(2))));
        var stats = ScriptAnalyzer.analyze(program);

        assertTrue(stats.builtinsUsed().contains(DefaultFun.AddInteger));
        assertEquals(1, stats.builtinsUsed().size());
    }

    @Test
    void testMultipleBuiltins() {
        // Force(Force(Builtin(IfThenElse))) applied to AddInteger args
        var ifBuiltin = Term.force(Term.force(Term.builtin(DefaultFun.IfThenElse)));
        var add = Term.apply(
                Term.apply(Term.builtin(DefaultFun.AddInteger),
                        Term.const_(Constant.integer(1))),
                Term.const_(Constant.integer(2)));
        var program = Program.plutusV3(
                Term.force(
                        Term.apply(
                                Term.apply(
                                        Term.apply(ifBuiltin,
                                                Term.const_(Constant.bool(true))),
                                        Term.delay(add)),
                                Term.delay(Term.const_(Constant.integer(0))))));
        var stats = ScriptAnalyzer.analyze(program);

        assertTrue(stats.builtinsUsed().contains(DefaultFun.IfThenElse));
        assertTrue(stats.builtinsUsed().contains(DefaultFun.AddInteger));
    }

    @Test
    void testV3Detection() {
        // V3 uses Constr/Case
        var program = Program.plutusV3(
                Term.constr(0, Term.const_(Constant.integer(42))));
        var stats = ScriptAnalyzer.analyze(program);

        assertEquals(ScriptAnalyzer.PlutusVersion.V3, stats.plutusVersion());
        assertTrue(stats.usesSop());
    }

    @Test
    void testV1Detection() {
        // V1 script: only basic builtins, no SOP, version 1.0.0
        var program = new Program(1, 0, 0,
                Term.apply(
                        Term.apply(Term.builtin(DefaultFun.AddInteger),
                                Term.const_(Constant.integer(1))),
                        Term.const_(Constant.integer(2))));
        var stats = ScriptAnalyzer.analyze(program);

        assertEquals(ScriptAnalyzer.PlutusVersion.V1, stats.plutusVersion());
        assertFalse(stats.usesSop());
    }

    @Test
    void testV2Detection() {
        // V2 uses SerialiseData (flatCode 51)
        var program = new Program(1, 0, 0,
                Term.apply(Term.builtin(DefaultFun.SerialiseData),
                        Term.const_(Constant.data(PlutusData.integer(42)))));
        var stats = ScriptAnalyzer.analyze(program);

        assertEquals(ScriptAnalyzer.PlutusVersion.V2, stats.plutusVersion());
    }

    @Test
    void testArityEstimation() {
        // Two nested lambdas = arity 2
        var program = Program.plutusV3(
                Term.lam("datum", Term.lam("ctx",
                        Term.var(new NamedDeBruijn("ctx", 1)))));
        var stats = ScriptAnalyzer.analyze(program);

        assertEquals(2, stats.estimatedArity());
    }

    @Test
    void testErrorCounting() {
        var program = Program.plutusV3(
                Term.lam("x",
                        Term.force(
                                Term.apply(
                                        Term.apply(
                                                Term.apply(
                                                        Term.force(Term.force(Term.builtin(DefaultFun.IfThenElse))),
                                                        Term.const_(Constant.bool(false))),
                                                Term.delay(Term.var(new NamedDeBruijn("x", 1)))),
                                        Term.delay(Term.error())))));
        var stats = ScriptAnalyzer.analyze(program);

        assertEquals(1, stats.errorCount());
    }

    @Test
    void testSummaryGeneration() {
        var program = Program.plutusV3(
                Term.lam("x", Term.var(new NamedDeBruijn("x", 1))));
        var stats = ScriptAnalyzer.analyze(program);

        String summary = stats.summary();
        assertNotNull(summary);
        assertTrue(summary.contains("Script Analysis"));
        assertTrue(summary.contains("1.1.0"));
        assertTrue(summary.contains("Total AST nodes"));
    }

    @Test
    void testCaseTermAnalysis() {
        // Case(Constr(0, [42]), Lam("x", Var(1)), Lam("x", Error))
        var program = Program.plutusV3(
                Term.case_(
                        Term.constr(0, Term.const_(Constant.integer(42))),
                        Term.lam("x", Term.var(new NamedDeBruijn("x", 1))),
                        Term.lam("x", Term.error())));
        var stats = ScriptAnalyzer.analyze(program);

        assertTrue(stats.usesSop());
        assertEquals(ScriptAnalyzer.PlutusVersion.V3, stats.plutusVersion());
        assertNotNull(stats.nodeCounts().get("Case"));
        assertNotNull(stats.nodeCounts().get("Constr"));
    }
}
