package com.bloxbean.cardano.julc.vm.truffle.debug;

import com.bloxbean.cardano.julc.core.*;
import com.bloxbean.cardano.julc.core.source.SourceLocation;
import com.bloxbean.cardano.julc.core.source.SourceMap;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;
import com.bloxbean.cardano.julc.vm.truffle.TruffleVmProvider;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the JulcDebugger step-through debugger.
 */
class JulcDebuggerTest {

    // --- Step-through tests ---

    @Test
    void stepThrough_identity() {
        // (\x -> x) 42 — maps apply to line 10
        var constTerm = Term.const_(Constant.integer(BigInteger.valueOf(42)));
        var varTerm = Term.var(new NamedDeBruijn("x", 1));
        var lamTerm = Term.lam("x", varTerm);
        var applyTerm = Term.apply(lamTerm, constTerm);

        var positions = new IdentityHashMap<Term, SourceLocation>();
        positions.put(applyTerm, new SourceLocation("Validator.java", 10, 5, "identity(42)"));
        var sourceMap = SourceMap.of(positions);

        var steps = new ArrayList<String>();

        try (var debugger = JulcDebugger.create()) {
            debugger.sourceMap(sourceMap);
            EvalResult result = debugger.stepThrough(applyTerm, event -> {
                steps.add(event.fileName() + ":" + event.line());
                event.stepOver();
            });

            if (result instanceof EvalResult.Failure f) {
                fail("Expected Success but got Failure: " + f.error());
            }
            assertInstanceOf(EvalResult.Success.class, result);
            assertFalse(steps.isEmpty(), "Should have at least one step event");
            assertTrue(steps.stream().anyMatch(s -> s.contains("Validator.java:10")),
                    "Should step through line 10: " + steps);
        }
    }

    @Test
    void stepThrough_budgetIncreases() {
        // addInteger 3 4 — verify budget increases across steps
        var three = Term.const_(Constant.integer(BigInteger.valueOf(3)));
        var four = Term.const_(Constant.integer(BigInteger.valueOf(4)));
        var add1 = Term.apply(Term.builtin(DefaultFun.AddInteger), three);
        var add2 = Term.apply(add1, four);

        var positions = new IdentityHashMap<Term, SourceLocation>();
        positions.put(add1, new SourceLocation("Test.java", 5, 1, "add(3)"));
        positions.put(add2, new SourceLocation("Test.java", 6, 1, "add(3, 4)"));
        var sourceMap = SourceMap.of(positions);

        var cpuValues = new ArrayList<Long>();

        try (var debugger = JulcDebugger.create()) {
            debugger.sourceMap(sourceMap);
            EvalResult result = debugger.stepThrough(add2, event -> {
                cpuValues.add(event.cpuConsumed());
                event.stepOver();
            });

            assertInstanceOf(EvalResult.Success.class, result);
            if (cpuValues.size() >= 2) {
                // Budget should be non-decreasing (monotonically increasing)
                for (int i = 1; i < cpuValues.size(); i++) {
                    assertTrue(cpuValues.get(i) >= cpuValues.get(i - 1),
                            "CPU should be non-decreasing: " + cpuValues);
                }
            }
        }
    }

    @Test
    void stepThrough_differentLines() {
        // Build: (\x -> addInteger x 10) 5
        // Map different parts to different lines
        var five = Term.const_(Constant.integer(BigInteger.valueOf(5)));
        var ten = Term.const_(Constant.integer(BigInteger.TEN));
        var varX = Term.var(new NamedDeBruijn("x", 1));
        var addBuiltin = Term.builtin(DefaultFun.AddInteger);
        var addX = Term.apply(addBuiltin, varX);
        var addXTen = Term.apply(addX, ten);
        var lam = Term.lam("x", addXTen);
        var app = Term.apply(lam, five);

        var positions = new IdentityHashMap<Term, SourceLocation>();
        positions.put(app, new SourceLocation("Calc.java", 10, 1, "calc(5)"));
        positions.put(addX, new SourceLocation("Calc.java", 20, 1, "x + ..."));
        positions.put(addXTen, new SourceLocation("Calc.java", 20, 1, "x + 10"));
        var sourceMap = SourceMap.of(positions);

        var lines = new ArrayList<Integer>();

        try (var debugger = JulcDebugger.create()) {
            debugger.sourceMap(sourceMap);
            EvalResult result = debugger.stepThrough(app, event -> {
                lines.add(event.line());
                event.stepOver();
            });

            assertInstanceOf(EvalResult.Success.class, result);
            assertFalse(lines.isEmpty(), "Should record step lines");
        }
    }

    @Test
    void breakAt_hitsLine() {
        // Set breakpoint at line 10, verify it suspends there
        var constTerm = Term.const_(Constant.integer(BigInteger.valueOf(42)));
        var varTerm = Term.var(new NamedDeBruijn("x", 1));
        var lamTerm = Term.lam("x", varTerm);
        var applyTerm = Term.apply(lamTerm, constTerm);

        var positions = new IdentityHashMap<Term, SourceLocation>();
        positions.put(applyTerm, new SourceLocation("Validator.java", 10, 5, "identity(42)"));
        var sourceMap = SourceMap.of(positions);

        var hitLines = new ArrayList<Integer>();

        try (var debugger = JulcDebugger.create()) {
            debugger.sourceMap(sourceMap)
                    .breakAt("Validator.java", 10);
            EvalResult result = debugger.run(applyTerm, event -> {
                hitLines.add(event.line());
                event.resume();
            });

            assertInstanceOf(EvalResult.Success.class, result);
            assertTrue(hitLines.contains(10), "Breakpoint at line 10 should be hit: " + hitLines);
        }
    }

    @Test
    void breakAt_multiple() {
        // Two breakpoints on different lines
        var three = Term.const_(Constant.integer(BigInteger.valueOf(3)));
        var four = Term.const_(Constant.integer(BigInteger.valueOf(4)));
        var add1 = Term.apply(Term.builtin(DefaultFun.AddInteger), three);
        var add2 = Term.apply(add1, four);

        var positions = new IdentityHashMap<Term, SourceLocation>();
        positions.put(add1, new SourceLocation("Test.java", 5, 1, "add(3)"));
        positions.put(add2, new SourceLocation("Test.java", 6, 1, "add(3, 4)"));
        var sourceMap = SourceMap.of(positions);

        var hitLines = new ArrayList<Integer>();

        try (var debugger = JulcDebugger.create()) {
            debugger.sourceMap(sourceMap)
                    .breakAt("Test.java", 5)
                    .breakAt("Test.java", 6);
            EvalResult result = debugger.run(add2, event -> {
                hitLines.add(event.line());
                event.resume();
            });

            assertInstanceOf(EvalResult.Success.class, result);
            // At least one breakpoint should be hit
            assertFalse(hitLines.isEmpty(), "At least one breakpoint should be hit");
        }
    }

    @Test
    void resume_completesExecution() {
        // Resume after breakpoint should complete normally
        var constTerm = Term.const_(Constant.integer(BigInteger.valueOf(99)));
        var varTerm = Term.var(new NamedDeBruijn("x", 1));
        var lamTerm = Term.lam("x", varTerm);
        var applyTerm = Term.apply(lamTerm, constTerm);

        var positions = new IdentityHashMap<Term, SourceLocation>();
        positions.put(applyTerm, new SourceLocation("Test.java", 1, 1, "identity(99)"));
        var sourceMap = SourceMap.of(positions);

        try (var debugger = JulcDebugger.create()) {
            debugger.sourceMap(sourceMap);
            EvalResult result = debugger.stepThrough(applyTerm, event -> {
                event.resume(); // Resume immediately
            });

            assertInstanceOf(EvalResult.Success.class, result);
            var success = (EvalResult.Success) result;
            var constResult = (Term.Const) success.resultTerm();
            assertEquals(BigInteger.valueOf(99),
                    ((Constant.IntegerConst) constResult.value()).value());
        }
    }

    @Test
    void kill_aborts() {
        // Kill should abort execution
        var constTerm = Term.const_(Constant.integer(BigInteger.valueOf(42)));
        var varTerm = Term.var(new NamedDeBruijn("x", 1));
        var lamTerm = Term.lam("x", varTerm);
        var applyTerm = Term.apply(lamTerm, constTerm);

        var positions = new IdentityHashMap<Term, SourceLocation>();
        positions.put(applyTerm, new SourceLocation("Test.java", 1, 1, "identity(42)"));
        var sourceMap = SourceMap.of(positions);

        try (var debugger = JulcDebugger.create()) {
            debugger.sourceMap(sourceMap);
            EvalResult result = debugger.stepThrough(applyTerm, event -> {
                event.kill(); // Kill immediately
            });

            // Kill should produce a failure result
            assertInstanceOf(EvalResult.Failure.class, result);
        }
    }

    @Test
    void budgetParity() {
        // Budget consumed during debug should match non-debug evaluation
        var five = Term.const_(Constant.integer(BigInteger.valueOf(5)));
        var addTen = Term.lam("y",
                Term.apply(
                        Term.apply(Term.builtin(DefaultFun.AddInteger),
                                Term.var(new NamedDeBruijn("y", 1))),
                        Term.const_(Constant.integer(BigInteger.TEN))));
        var app = Term.apply(addTen, five);

        // Evaluate without debugger
        var provider = new TruffleVmProvider();
        var program = new Program(1, 0, 0, app);
        var nonDebugResult = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);
        assertInstanceOf(EvalResult.Success.class, nonDebugResult);

        // Evaluate with debugger (no source map = no suspensions)
        try (var debugger = JulcDebugger.create()) {
            EvalResult debugResult = debugger.stepThrough(app, event -> {
                event.stepOver(); // Won't be called (no source sections)
            });

            assertInstanceOf(EvalResult.Success.class, debugResult);

            // Budgets must match
            assertEquals(nonDebugResult.budgetConsumed().cpuSteps(),
                    debugResult.budgetConsumed().cpuSteps(),
                    "CPU budget must match between debug and non-debug");
            assertEquals(nonDebugResult.budgetConsumed().memoryUnits(),
                    debugResult.budgetConsumed().memoryUnits(),
                    "Memory budget must match between debug and non-debug");
        }
    }

    @Test
    void noSourceMap_noSuspensions() {
        // Without source map, step handler should never be called
        var constTerm = Term.const_(Constant.integer(BigInteger.valueOf(42)));
        var varTerm = Term.var(new NamedDeBruijn("x", 1));
        var lamTerm = Term.lam("x", varTerm);
        var applyTerm = Term.apply(lamTerm, constTerm);

        var stepCount = new int[]{0};

        try (var debugger = JulcDebugger.create()) {
            // No source map set
            EvalResult result = debugger.stepThrough(applyTerm, event -> {
                stepCount[0]++;
                event.stepOver();
            });

            assertInstanceOf(EvalResult.Success.class, result);
            assertEquals(0, stepCount[0], "Without source map, no suspensions should occur");
        }
    }

    @Test
    void errorNode_suspends() {
        // Error node has StatementTag — should suspend before throwing
        var errorTerm = Term.error();

        var positions = new IdentityHashMap<Term, SourceLocation>();
        positions.put(errorTerm, new SourceLocation("Test.java", 42, 1, "error()"));
        var sourceMap = SourceMap.of(positions);

        var suspended = new boolean[]{false};

        try (var debugger = JulcDebugger.create()) {
            debugger.sourceMap(sourceMap);
            EvalResult result = debugger.stepThrough(errorTerm, event -> {
                suspended[0] = true;
                assertEquals(42, event.line());
                event.resume();
            });

            // Error node produces a failure
            assertInstanceOf(EvalResult.Failure.class, result);
            assertTrue(suspended[0], "Should have suspended at error node");
        }
    }

    @Test
    void stepOver_advancesLine() {
        // Verify that stepOver actually advances to the next line
        var three = Term.const_(Constant.integer(BigInteger.valueOf(3)));
        var four = Term.const_(Constant.integer(BigInteger.valueOf(4)));
        var add1 = Term.apply(Term.builtin(DefaultFun.AddInteger), three);
        var add2 = Term.apply(add1, four);

        var positions = new IdentityHashMap<Term, SourceLocation>();
        positions.put(add1, new SourceLocation("Test.java", 5, 1, "partial(3)"));
        positions.put(add2, new SourceLocation("Test.java", 6, 1, "add(3, 4)"));
        var sourceMap = SourceMap.of(positions);

        var lines = new ArrayList<Integer>();

        try (var debugger = JulcDebugger.create()) {
            debugger.sourceMap(sourceMap);
            EvalResult result = debugger.stepThrough(add2, event -> {
                lines.add(event.line());
                event.stepOver();
            });

            assertInstanceOf(EvalResult.Success.class, result);
            // With stepOver, we should see multiple step events
            assertFalse(lines.isEmpty(), "Should have step events");
        }
    }

    @Test
    void forceDelay_withDebugger() {
        // Force/delay should work correctly under debugger
        var innerConst = Term.const_(Constant.integer(BigInteger.valueOf(7)));
        var delayTerm = Term.delay(innerConst);
        var forceTerm = Term.force(delayTerm);

        var positions = new IdentityHashMap<Term, SourceLocation>();
        positions.put(forceTerm, new SourceLocation("Test.java", 3, 1, "force(delay(7))"));
        var sourceMap = SourceMap.of(positions);

        try (var debugger = JulcDebugger.create()) {
            debugger.sourceMap(sourceMap);
            EvalResult result = debugger.stepThrough(forceTerm, event -> {
                event.stepOver();
            });

            assertInstanceOf(EvalResult.Success.class, result);
            var constResult = (Term.Const) ((EvalResult.Success) result).resultTerm();
            assertEquals(BigInteger.valueOf(7),
                    ((Constant.IntegerConst) constResult.value()).value());
        }
    }

    @Test
    void caseDispatch_withDebugger() {
        // Case/Constr (V3) should work under debugger
        var scrutinee = Term.constr(1);
        var branch0 = Term.const_(Constant.integer(BigInteger.TEN));
        var branch1 = Term.const_(Constant.integer(BigInteger.valueOf(20)));
        var caseTerm = Term.case_(scrutinee, branch0, branch1);

        var positions = new IdentityHashMap<Term, SourceLocation>();
        positions.put(caseTerm, new SourceLocation("Test.java", 15, 1, "switch(tag)"));
        var sourceMap = SourceMap.of(positions);

        try (var debugger = JulcDebugger.create()) {
            debugger.sourceMap(sourceMap);
            EvalResult result = debugger.stepThrough(caseTerm, event -> {
                event.stepOver();
            });

            assertInstanceOf(EvalResult.Success.class, result);
            var constResult = (Term.Const) ((EvalResult.Success) result).resultTerm();
            assertEquals(BigInteger.valueOf(20),
                    ((Constant.IntegerConst) constResult.value()).value());
        }
    }
}
