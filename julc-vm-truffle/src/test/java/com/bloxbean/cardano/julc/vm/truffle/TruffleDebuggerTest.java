package com.bloxbean.cardano.julc.vm.truffle;

import com.bloxbean.cardano.julc.core.*;
import com.bloxbean.cardano.julc.core.source.SourceLocation;
import com.bloxbean.cardano.julc.core.source.SourceMap;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.ExBudget;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;
import com.bloxbean.cardano.julc.vm.truffle.convert.TermToNodeConverter;
import com.bloxbean.cardano.julc.vm.truffle.node.*;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.IdentityHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Truffle debugger/instrumentation support.
 */
class TruffleDebuggerTest {

    private final TruffleVmProvider provider = new TruffleVmProvider();

    // --- Source section attachment ---

    @Test
    void sourceSectionAttachedFromSourceMap() {
        // Build a simple term: [(\x -> x) 42]
        var constTerm = Term.const_(Constant.integer(BigInteger.valueOf(42)));
        var varTerm = Term.var(new NamedDeBruijn("x", 1));
        var lamTerm = Term.lam("x", varTerm);
        var applyTerm = Term.apply(lamTerm, constTerm);

        // Create source map mapping apply and const to locations
        var positions = new IdentityHashMap<Term, SourceLocation>();
        positions.put(applyTerm, new SourceLocation("MyValidator.java", 10, 5, "identity(42)"));
        positions.put(constTerm, new SourceLocation("MyValidator.java", 10, 14, "42"));
        var sourceMap = SourceMap.of(positions);

        // Convert with source map
        UplcNode root = TermToNodeConverter.convert(applyTerm, sourceMap);

        // The root (ApplyNode) should have a source section
        assertNotNull(root.getSourceSection(), "ApplyNode should have a source section");
        assertEquals("MyValidator.java", root.getSourceSection().getSource().getName());
        assertEquals(10, root.getSourceSection().getStartLine());
        assertTrue(root.isInstrumentable(), "ApplyNode with source section should be instrumentable");
    }

    @Test
    void noSourceMapMeansNotInstrumentable() {
        var term = Term.const_(Constant.integer(BigInteger.ONE));
        UplcNode node = TermToNodeConverter.convert(term);

        assertNull(node.getSourceSection(), "No source map → no source section");
        assertFalse(node.isInstrumentable(), "No source section → not instrumentable");
    }

    @Test
    void emptySourceMapMeansNotInstrumentable() {
        var term = Term.const_(Constant.integer(BigInteger.ONE));
        UplcNode node = TermToNodeConverter.convert(term, SourceMap.EMPTY);

        assertNull(node.getSourceSection(), "Empty source map → no source section");
        assertFalse(node.isInstrumentable());
    }

    @Test
    void unmappedTermsNotInstrumentable() {
        var constTerm = Term.const_(Constant.integer(BigInteger.ONE));
        var applyTerm = Term.apply(
                Term.lam("x", Term.var(new NamedDeBruijn("x", 1))),
                constTerm);

        // Only map the const, not the apply
        var positions = new IdentityHashMap<Term, SourceLocation>();
        positions.put(constTerm, new SourceLocation("Test.java", 5, 1, "1"));
        var sourceMap = SourceMap.of(positions);

        UplcNode root = TermToNodeConverter.convert(applyTerm, sourceMap);

        // ApplyNode should NOT have a source section (not mapped)
        assertNull(root.getSourceSection());
        assertFalse(root.isInstrumentable());
    }

    @Test
    void multipleFilesInSourceMap() {
        var const1 = Term.const_(Constant.integer(BigInteger.ONE));
        var const2 = Term.const_(Constant.integer(BigInteger.TWO));
        var applyTerm = Term.apply(
                Term.lam("x", Term.var(new NamedDeBruijn("x", 1))),
                const1);

        var positions = new IdentityHashMap<Term, SourceLocation>();
        positions.put(const1, new SourceLocation("FileA.java", 1, 1, "1"));
        positions.put(applyTerm, new SourceLocation("FileB.java", 20, 3, "apply"));
        var sourceMap = SourceMap.of(positions);

        UplcNode root = TermToNodeConverter.convert(applyTerm, sourceMap);

        assertEquals("FileB.java", root.getSourceSection().getSource().getName());
        assertEquals(20, root.getSourceSection().getStartLine());
    }

    // --- Wrapper execution correctness ---

    @Test
    void wrapperProducesCorrectResult() {
        // Evaluate a simple constant with source map — wrapper should not change result
        var constTerm = Term.const_(Constant.integer(BigInteger.valueOf(42)));
        var program = new Program(1, 0, 0, constTerm);

        var positions = new IdentityHashMap<Term, SourceLocation>();
        positions.put(constTerm, new SourceLocation("Test.java", 1, 1, "42"));
        var sourceMap = SourceMap.of(positions);

        provider.setSourceMap(sourceMap);
        var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);
        provider.setSourceMap(null); // cleanup

        assertInstanceOf(EvalResult.Success.class, result);
        var success = (EvalResult.Success) result;
        var term = (Term.Const) success.resultTerm();
        assertEquals(BigInteger.valueOf(42), ((Constant.IntegerConst) term.value()).value());
    }

    @Test
    void wrapperPreservesApplyResult() {
        // [addInteger 3 4] with source map
        var three = Term.const_(Constant.integer(BigInteger.valueOf(3)));
        var four = Term.const_(Constant.integer(BigInteger.valueOf(4)));
        var add = Term.apply(Term.apply(Term.builtin(DefaultFun.AddInteger), three), four);
        var program = new Program(1, 0, 0, add);

        var positions = new IdentityHashMap<Term, SourceLocation>();
        positions.put(add, new SourceLocation("Test.java", 5, 1, "3 + 4"));
        var sourceMap = SourceMap.of(positions);

        provider.setSourceMap(sourceMap);
        var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);
        provider.setSourceMap(null);

        assertInstanceOf(EvalResult.Success.class, result);
        var constTerm = (Term.Const) ((EvalResult.Success) result).resultTerm();
        assertEquals(BigInteger.valueOf(7), ((Constant.IntegerConst) constTerm.value()).value());
    }

    // --- Error with source location ---

    @Test
    void errorIncludesSourceLocation() {
        var errorTerm = Term.error();
        var program = new Program(1, 0, 0, errorTerm);

        var positions = new IdentityHashMap<Term, SourceLocation>();
        positions.put(errorTerm, new SourceLocation("Validator.java", 42, 10, "error()"));
        var sourceMap = SourceMap.of(positions);

        provider.setSourceMap(sourceMap);
        var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);
        provider.setSourceMap(null);

        assertInstanceOf(EvalResult.Failure.class, result);
        var failure = (EvalResult.Failure) result;
        assertTrue(failure.error().contains("Validator.java"),
                "Error should contain source file name: " + failure.error());
        assertTrue(failure.error().contains("42"),
                "Error should contain line number: " + failure.error());
    }

    // --- Budget parity ---

    @Test
    void budgetParityWithAndWithoutSourceMap() {
        // Complex program: nested lambda application
        var addTen = Term.lam("y",
                Term.apply(
                        Term.apply(Term.builtin(DefaultFun.AddInteger),
                                Term.var(new NamedDeBruijn("y", 1))),
                        Term.const_(Constant.integer(BigInteger.TEN))));
        var app = Term.apply(addTen, Term.const_(Constant.integer(BigInteger.valueOf(5))));
        var program = new Program(1, 0, 0, app);

        // Without source map
        provider.setSourceMap(null);
        var resultNoMap = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);
        assertInstanceOf(EvalResult.Success.class, resultNoMap);

        // With source map (map all terms)
        var positions = new IdentityHashMap<Term, SourceLocation>();
        positions.put(app, new SourceLocation("Test.java", 1, 1, "addTen(5)"));
        var sourceMap = SourceMap.of(positions);

        provider.setSourceMap(sourceMap);
        var resultWithMap = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);
        provider.setSourceMap(null);

        assertInstanceOf(EvalResult.Success.class, resultWithMap);

        // Budgets must be identical
        assertEquals(resultNoMap.budgetConsumed().cpuSteps(), resultWithMap.budgetConsumed().cpuSteps(),
                "CPU budget must be identical with and without source map");
        assertEquals(resultNoMap.budgetConsumed().memoryUnits(), resultWithMap.budgetConsumed().memoryUnits(),
                "Memory budget must be identical with and without source map");
    }

    // --- Backward compatibility: existing evaluation without source map ---

    @Test
    void existingEvaluationUnchanged() {
        // All basic operations should work exactly as before
        var program = new Program(1, 0, 0,
                Term.const_(Constant.integer(BigInteger.valueOf(99))));
        var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

        assertInstanceOf(EvalResult.Success.class, result);
        var constTerm = (Term.Const) ((EvalResult.Success) result).resultTerm();
        assertEquals(BigInteger.valueOf(99), ((Constant.IntegerConst) constTerm.value()).value());
    }

    @Test
    void forceDelayWithSourceMap() {
        var innerConst = Term.const_(Constant.integer(BigInteger.valueOf(7)));
        var delayTerm = Term.delay(innerConst);
        var forceTerm = Term.force(delayTerm);
        var program = new Program(1, 0, 0, forceTerm);

        var positions = new IdentityHashMap<Term, SourceLocation>();
        positions.put(forceTerm, new SourceLocation("Test.java", 3, 1, "force(delay(7))"));
        positions.put(delayTerm, new SourceLocation("Test.java", 3, 7, "delay(7)"));
        var sourceMap = SourceMap.of(positions);

        provider.setSourceMap(sourceMap);
        var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);
        provider.setSourceMap(null);

        assertInstanceOf(EvalResult.Success.class, result);
        var constTerm = (Term.Const) ((EvalResult.Success) result).resultTerm();
        assertEquals(BigInteger.valueOf(7), ((Constant.IntegerConst) constTerm.value()).value());
    }

    @Test
    void caseWithSourceMap() {
        // case (constr 1) [10, 20] → 20
        var scrutinee = Term.constr(1);
        var branch0 = Term.const_(Constant.integer(BigInteger.TEN));
        var branch1 = Term.const_(Constant.integer(BigInteger.valueOf(20)));
        var caseTerm = Term.case_(scrutinee, branch0, branch1);
        var program = new Program(1, 1, 0, caseTerm);

        var positions = new IdentityHashMap<Term, SourceLocation>();
        positions.put(caseTerm, new SourceLocation("Test.java", 15, 1, "switch(constr)"));
        var sourceMap = SourceMap.of(positions);

        provider.setSourceMap(sourceMap);
        var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);
        provider.setSourceMap(null);

        assertInstanceOf(EvalResult.Success.class, result);
        var constTerm = (Term.Const) ((EvalResult.Success) result).resultTerm();
        assertEquals(BigInteger.valueOf(20), ((Constant.IntegerConst) constTerm.value()).value());
    }

    // --- hasTag checks ---

    @Test
    void applyNodeHasStatementTag() {
        var applyTerm = Term.apply(
                Term.lam("x", Term.var(new NamedDeBruijn("x", 1))),
                Term.const_(Constant.integer(BigInteger.ONE)));
        UplcNode node = TermToNodeConverter.convert(applyTerm);
        assertInstanceOf(ApplyNode.class, node);
        assertTrue(node.hasTag(com.oracle.truffle.api.instrumentation.StandardTags.StatementTag.class));
    }

    @Test
    void constNodeDoesNotHaveStatementTag() {
        var constTerm = Term.const_(Constant.integer(BigInteger.ONE));
        UplcNode node = TermToNodeConverter.convert(constTerm);
        assertInstanceOf(ConstNode.class, node);
        assertFalse(node.hasTag(com.oracle.truffle.api.instrumentation.StandardTags.StatementTag.class));
    }

    @Test
    void errorNodeHasStatementTag() {
        var errorTerm = Term.error();
        UplcNode node = TermToNodeConverter.convert(errorTerm);
        assertInstanceOf(ErrorNode.class, node);
        assertTrue(node.hasTag(com.oracle.truffle.api.instrumentation.StandardTags.StatementTag.class));
    }
}
