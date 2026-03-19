package com.bloxbean.cardano.julc.vm.truffle;

import com.bloxbean.cardano.julc.core.*;
import com.bloxbean.cardano.julc.core.source.SourceLocation;
import com.bloxbean.cardano.julc.core.source.SourceMap;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;
import com.bloxbean.cardano.julc.vm.trace.ExecutionTraceEntry;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.IdentityHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the execution trace feature.
 */
class ExecutionTraceTest {

    private final TruffleVmProvider provider = new TruffleVmProvider();

    // --- Basic tracing ---

    @Test
    void tracingDisabledByDefault() {
        var program = new Program(1, 0, 0,
                Term.const_(Constant.integer(BigInteger.ONE)));
        provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

        assertTrue(provider.getLastExecutionTrace().isEmpty(),
                "Trace should be empty when tracing is disabled");
    }

    @Test
    void tracingEnabledProducesEntries() {
        // [(\x -> x) 42] — the Apply node is a statement-tagged step
        var constTerm = Term.const_(Constant.integer(BigInteger.valueOf(42)));
        var lamTerm = Term.lam("x", Term.var(new NamedDeBruijn("x", 1)));
        var applyTerm = Term.apply(lamTerm, constTerm);

        var positions = new IdentityHashMap<Term, SourceLocation>();
        positions.put(applyTerm, new SourceLocation("Test.java", 10, 1, "identity(42)"));
        var sourceMap = SourceMap.of(positions);

        provider.setSourceMap(sourceMap);
        provider.setTracingEnabled(true);
        var result = provider.evaluate(
                new Program(1, 0, 0, applyTerm), PlutusLanguage.PLUTUS_V3, null);

        assertInstanceOf(EvalResult.Success.class, result);
        var trace = provider.getLastExecutionTrace();
        assertFalse(trace.isEmpty(), "Trace should have entries when enabled with source map");

        // Should contain an Apply entry for Test.java:10
        boolean hasApply = trace.stream().anyMatch(e ->
                "Apply".equals(e.nodeType()) && "Test.java".equals(e.fileName()) && e.line() == 10);
        assertTrue(hasApply, "Trace should contain Apply entry at Test.java:10. Got: " + trace);
    }

    @Test
    void tracingEnabledButNoSourceMap_emptyTrace() {
        provider.setSourceMap(null);
        provider.setTracingEnabled(true);

        var program = new Program(1, 0, 0,
                Term.apply(
                        Term.lam("x", Term.var(new NamedDeBruijn("x", 1))),
                        Term.const_(Constant.integer(BigInteger.ONE))));
        provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

        assertTrue(provider.getLastExecutionTrace().isEmpty(),
                "Trace should be empty when no source map");
    }

    // --- Deduplication ---

    @Test
    void consecutiveSameLineDeduped() {
        // addInteger 3 4 — two Apply nodes, map both to the same line
        var three = Term.const_(Constant.integer(BigInteger.valueOf(3)));
        var four = Term.const_(Constant.integer(BigInteger.valueOf(4)));
        var innerApply = Term.apply(Term.builtin(DefaultFun.AddInteger), three);
        var outerApply = Term.apply(innerApply, four);

        var positions = new IdentityHashMap<Term, SourceLocation>();
        // Both Apply nodes on the same line
        positions.put(innerApply, new SourceLocation("Math.java", 5, 1, "3 + 4"));
        positions.put(outerApply, new SourceLocation("Math.java", 5, 1, "3 + 4"));
        var sourceMap = SourceMap.of(positions);

        provider.setSourceMap(sourceMap);
        provider.setTracingEnabled(true);
        provider.evaluate(new Program(1, 0, 0, outerApply), PlutusLanguage.PLUTUS_V3, null);

        var trace = provider.getLastExecutionTrace();
        long line5Count = trace.stream()
                .filter(e -> "Math.java".equals(e.fileName()) && e.line() == 5)
                .count();
        assertEquals(1, line5Count,
                "Same file:line should appear only once (deduped). Full trace: " + trace);
    }

    @Test
    void differentLinesNotDeduped() {
        // Two Apply nodes on different lines
        var three = Term.const_(Constant.integer(BigInteger.valueOf(3)));
        var four = Term.const_(Constant.integer(BigInteger.valueOf(4)));
        var innerApply = Term.apply(Term.builtin(DefaultFun.AddInteger), three);
        var outerApply = Term.apply(innerApply, four);

        var positions = new IdentityHashMap<Term, SourceLocation>();
        positions.put(innerApply, new SourceLocation("Math.java", 5, 1, "builtin add"));
        positions.put(outerApply, new SourceLocation("Math.java", 6, 1, "add(3, 4)"));
        var sourceMap = SourceMap.of(positions);

        provider.setSourceMap(sourceMap);
        provider.setTracingEnabled(true);
        provider.evaluate(new Program(1, 0, 0, outerApply), PlutusLanguage.PLUTUS_V3, null);

        var trace = provider.getLastExecutionTrace();
        assertTrue(trace.size() >= 2, "Different lines should produce separate entries. Got: " + trace);
    }

    // --- Budget parity ---

    @Test
    void budgetIdenticalWithAndWithoutTracing() {
        var addTen = Term.lam("y",
                Term.apply(
                        Term.apply(Term.builtin(DefaultFun.AddInteger),
                                Term.var(new NamedDeBruijn("y", 1))),
                        Term.const_(Constant.integer(BigInteger.TEN))));
        var app = Term.apply(addTen, Term.const_(Constant.integer(BigInteger.valueOf(5))));

        var positions = new IdentityHashMap<Term, SourceLocation>();
        positions.put(app, new SourceLocation("Test.java", 1, 1, "addTen(5)"));
        var sourceMap = SourceMap.of(positions);

        // With tracing
        provider.setSourceMap(sourceMap);
        provider.setTracingEnabled(true);
        var resultTraced = provider.evaluate(
                new Program(1, 0, 0, app), PlutusLanguage.PLUTUS_V3, null);

        // Without tracing
        provider.setTracingEnabled(false);
        var resultUntraced = provider.evaluate(
                new Program(1, 0, 0, app), PlutusLanguage.PLUTUS_V3, null);

        assertEquals(resultTraced.budgetConsumed().cpuSteps(),
                resultUntraced.budgetConsumed().cpuSteps(),
                "CPU must be identical with/without tracing");
        assertEquals(resultTraced.budgetConsumed().memoryUnits(),
                resultUntraced.budgetConsumed().memoryUnits(),
                "Memory must be identical with/without tracing");
    }

    // --- Node types ---

    @Test
    void forceNodeProducesForceEntry() {
        var innerConst = Term.const_(Constant.integer(BigInteger.valueOf(7)));
        var delayTerm = Term.delay(innerConst);
        var forceTerm = Term.force(delayTerm);

        var positions = new IdentityHashMap<Term, SourceLocation>();
        positions.put(forceTerm, new SourceLocation("Test.java", 3, 1, "force(delay(7))"));
        var sourceMap = SourceMap.of(positions);

        provider.setSourceMap(sourceMap);
        provider.setTracingEnabled(true);
        provider.evaluate(new Program(1, 0, 0, forceTerm), PlutusLanguage.PLUTUS_V3, null);

        var trace = provider.getLastExecutionTrace();
        boolean hasForce = trace.stream().anyMatch(e -> "Force".equals(e.nodeType()));
        assertTrue(hasForce, "Should have a Force entry. Got: " + trace);
    }

    @Test
    void caseNodeProducesCaseEntry() {
        var scrutinee = Term.constr(1);
        var branch0 = Term.const_(Constant.integer(BigInteger.TEN));
        var branch1 = Term.const_(Constant.integer(BigInteger.valueOf(20)));
        var caseTerm = Term.case_(scrutinee, branch0, branch1);

        var positions = new IdentityHashMap<Term, SourceLocation>();
        positions.put(caseTerm, new SourceLocation("Test.java", 15, 1, "switch(x)"));
        var sourceMap = SourceMap.of(positions);

        provider.setSourceMap(sourceMap);
        provider.setTracingEnabled(true);
        provider.evaluate(new Program(1, 1, 0, caseTerm), PlutusLanguage.PLUTUS_V3, null);

        var trace = provider.getLastExecutionTrace();
        boolean hasCase = trace.stream().anyMatch(e -> "Case".equals(e.nodeType()));
        assertTrue(hasCase, "Should have a Case entry. Got: " + trace);
    }

    @Test
    void errorNodeProducesErrorEntry() {
        var errorTerm = Term.error();

        var positions = new IdentityHashMap<Term, SourceLocation>();
        positions.put(errorTerm, new SourceLocation("Test.java", 42, 1, "error()"));
        var sourceMap = SourceMap.of(positions);

        provider.setSourceMap(sourceMap);
        provider.setTracingEnabled(true);
        var result = provider.evaluate(
                new Program(1, 0, 0, errorTerm), PlutusLanguage.PLUTUS_V3, null);

        assertInstanceOf(EvalResult.Failure.class, result);
        var trace = provider.getLastExecutionTrace();
        boolean hasError = trace.stream().anyMatch(e ->
                "Error".equals(e.nodeType()) && e.line() == 42);
        assertTrue(hasError, "Should have an Error entry at line 42. Got: " + trace);
    }

    // --- Trace isolation between evaluations ---

    @Test
    void traceClearedBetweenEvaluations() {
        var const1 = Term.const_(Constant.integer(BigInteger.ONE));
        var apply1 = Term.apply(
                Term.lam("x", Term.var(new NamedDeBruijn("x", 1))), const1);

        var positions1 = new IdentityHashMap<Term, SourceLocation>();
        positions1.put(apply1, new SourceLocation("First.java", 1, 1, "first"));
        var sourceMap1 = SourceMap.of(positions1);

        provider.setSourceMap(sourceMap1);
        provider.setTracingEnabled(true);
        provider.evaluate(new Program(1, 0, 0, apply1), PlutusLanguage.PLUTUS_V3, null);

        var trace1 = provider.getLastExecutionTrace();
        assertFalse(trace1.isEmpty());
        assertTrue(trace1.stream().anyMatch(e -> "First.java".equals(e.fileName())));

        // Second evaluation
        var const2 = Term.const_(Constant.integer(BigInteger.TWO));
        var apply2 = Term.apply(
                Term.lam("y", Term.var(new NamedDeBruijn("y", 1))), const2);

        var positions2 = new IdentityHashMap<Term, SourceLocation>();
        positions2.put(apply2, new SourceLocation("Second.java", 2, 1, "second"));
        var sourceMap2 = SourceMap.of(positions2);

        provider.setSourceMap(sourceMap2);
        provider.evaluate(new Program(1, 0, 0, apply2), PlutusLanguage.PLUTUS_V3, null);

        var trace2 = provider.getLastExecutionTrace();
        // Second trace should NOT contain First.java entries
        boolean hasFirst = trace2.stream().anyMatch(e -> "First.java".equals(e.fileName()));
        assertFalse(hasFirst, "Second trace should not contain entries from first evaluation");
        assertTrue(trace2.stream().anyMatch(e -> "Second.java".equals(e.fileName())));
    }

    // --- Mixed mapped/unmapped ---

    @Test
    void unmappedNodesProduceNoTraceEntries() {
        // Only map the outer Apply, not the inner one
        var three = Term.const_(Constant.integer(BigInteger.valueOf(3)));
        var four = Term.const_(Constant.integer(BigInteger.valueOf(4)));
        var innerApply = Term.apply(Term.builtin(DefaultFun.AddInteger), three);
        var outerApply = Term.apply(innerApply, four);

        var positions = new IdentityHashMap<Term, SourceLocation>();
        positions.put(outerApply, new SourceLocation("Test.java", 10, 1, "add(3,4)"));
        // innerApply is NOT mapped
        var sourceMap = SourceMap.of(positions);

        provider.setSourceMap(sourceMap);
        provider.setTracingEnabled(true);
        provider.evaluate(new Program(1, 0, 0, outerApply), PlutusLanguage.PLUTUS_V3, null);

        var trace = provider.getLastExecutionTrace();
        // All entries should be from Test.java:10 (the mapped one)
        for (var entry : trace) {
            assertEquals("Test.java", entry.fileName());
            assertEquals(10, entry.line());
        }
    }

    // --- Budget delta capture ---

    @Test
    void budgetDeltaCaptured() {
        // addInteger 3 4 — Apply nodes mapped to source lines should capture budget
        var three = Term.const_(Constant.integer(BigInteger.valueOf(3)));
        var four = Term.const_(Constant.integer(BigInteger.valueOf(4)));
        var innerApply = Term.apply(Term.builtin(DefaultFun.AddInteger), three);
        var outerApply = Term.apply(innerApply, four);

        var positions = new IdentityHashMap<Term, SourceLocation>();
        positions.put(innerApply, new SourceLocation("Math.java", 5, 1, "add builtin"));
        positions.put(outerApply, new SourceLocation("Math.java", 6, 1, "3 + 4"));
        var sourceMap = SourceMap.of(positions);

        provider.setSourceMap(sourceMap);
        provider.setTracingEnabled(true);
        provider.evaluate(new Program(1, 0, 0, outerApply), PlutusLanguage.PLUTUS_V3, null);

        var trace = provider.getLastExecutionTrace();
        assertFalse(trace.isEmpty());
        // At least one entry should have non-zero cpuDelta
        boolean hasNonZeroCpu = trace.stream().anyMatch(e -> e.cpuDelta() > 0);
        assertTrue(hasNonZeroCpu,
                "At least one entry should have non-zero cpuDelta. Got: " + trace);
    }

    @Test
    void deduplicatedEntriesAccumulateBudget() {
        // Two Apply nodes on the same line — they dedup, budget should accumulate
        var three = Term.const_(Constant.integer(BigInteger.valueOf(3)));
        var four = Term.const_(Constant.integer(BigInteger.valueOf(4)));
        var innerApply = Term.apply(Term.builtin(DefaultFun.AddInteger), three);
        var outerApply = Term.apply(innerApply, four);

        var positions = new IdentityHashMap<Term, SourceLocation>();
        // Both on same line — will be deduped
        positions.put(innerApply, new SourceLocation("Math.java", 5, 1, "3 + 4"));
        positions.put(outerApply, new SourceLocation("Math.java", 5, 1, "3 + 4"));
        var sourceMap = SourceMap.of(positions);

        provider.setSourceMap(sourceMap);
        provider.setTracingEnabled(true);
        provider.evaluate(new Program(1, 0, 0, outerApply), PlutusLanguage.PLUTUS_V3, null);

        var trace = provider.getLastExecutionTrace();
        // Should be deduped to 1 entry
        long line5Count = trace.stream()
                .filter(e -> "Math.java".equals(e.fileName()) && e.line() == 5)
                .count();
        assertEquals(1, line5Count, "Same file:line should be deduped. Trace: " + trace);

        // The single entry should have accumulated budget from both Apply nodes
        var entry = trace.stream()
                .filter(e -> "Math.java".equals(e.fileName()) && e.line() == 5)
                .findFirst().orElseThrow();
        assertTrue(entry.cpuDelta() > 0,
                "Deduped entry should accumulate budget. cpuDelta=" + entry.cpuDelta());
    }

    @Test
    void formatSummaryAggregation() {
        // Create entries with budget deltas across two files
        var entries = java.util.List.of(
                new ExecutionTraceEntry("A.java", 10, "foo()", "Apply", 1000, 200),
                new ExecutionTraceEntry("A.java", 20, "bar()", "Apply", 3000, 500),
                new ExecutionTraceEntry("B.java", 5, "baz()", "Force", 500, 100),
                new ExecutionTraceEntry("A.java", 10, "foo()", "Apply", 2000, 300));

        String summary = ExecutionTraceEntry.formatSummary(entries);
        assertTrue(summary.contains("A.java:10"), "Should contain A.java:10");
        assertTrue(summary.contains("A.java:20"), "Should contain A.java:20");
        assertTrue(summary.contains("B.java:5"), "Should contain B.java:5");
        // Line 10 visited twice: CPU=3000 (1000+2000), Mem=500 (200+300)
        assertTrue(summary.contains("2 visits"), "Line 10 should have 2 visits");
        assertTrue(summary.contains("1 visit"), "Line 20/5 should have 1 visit");
        // Total: CPU=6500, Mem=1100
        assertTrue(summary.contains("Total"), "Should have total line");
    }

    // --- Format utility ---

    @Test
    void formatProducesReadableOutput() {
        var entries = java.util.List.of(
                new ExecutionTraceEntry("Validator.java", 10, "ctx.txInfo()", "Apply"),
                new ExecutionTraceEntry("Validator.java", 15, "check(datum)", "Apply"),
                new ExecutionTraceEntry("Validator.java", 20, null, "Force"));

        String formatted = ExecutionTraceEntry.format(entries);
        assertTrue(formatted.contains("3 steps"));
        assertTrue(formatted.contains("Validator.java:10"));
        assertTrue(formatted.contains("[Apply]"));
        assertTrue(formatted.contains("[Force]"));
        assertTrue(formatted.contains("ctx.txInfo()"));
    }

    @Test
    void formatWithBudgetShowsTotals() {
        var entries = java.util.List.of(
                new ExecutionTraceEntry("V.java", 10, "a()", "Apply", 1000, 200),
                new ExecutionTraceEntry("V.java", 20, "b()", "Apply", 3000, 500));

        String formatted = ExecutionTraceEntry.format(entries);
        assertTrue(formatted.contains("2 steps"), "Should show step count");
        assertTrue(formatted.contains("CPU:"), "Should show CPU total in header");
        assertTrue(formatted.contains("Mem:"), "Should show Mem total in header");
        assertTrue(formatted.contains("CPU: +"), "Entries should show CPU delta");
    }

    @Test
    void formatEmptyTrace() {
        String formatted = ExecutionTraceEntry.format(java.util.List.of());
        assertTrue(formatted.contains("empty"));
    }

    @Test
    void formatSummaryEmpty() {
        String summary = ExecutionTraceEntry.formatSummary(java.util.List.of());
        assertTrue(summary.contains("empty"));
    }
}
