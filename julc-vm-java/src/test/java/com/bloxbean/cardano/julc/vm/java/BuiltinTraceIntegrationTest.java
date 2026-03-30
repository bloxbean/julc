package com.bloxbean.cardano.julc.vm.java;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.core.source.SourceLocation;
import com.bloxbean.cardano.julc.core.source.SourceMap;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;
import com.bloxbean.cardano.julc.vm.java.cost.*;
import org.junit.jupiter.api.Test;

import java.util.IdentityHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test verifying that CekMachine captures builtin executions
 * in its trace with fine-grained control over builtin vs execution tracing.
 */
class BuiltinTraceIntegrationTest {

    @Test
    void booleanGuardFailure_capturesComparisonBuiltin() {
        // Build: IfThenElse(LessThanEqualsInteger(5, 3), True, Error)
        // This simulates: return 5 <= 3 → Error branch
        var lessThanEquals = applyBuiltin(DefaultFun.LessThanEqualsInteger,
                new Term.Const(Constant.integer(5)),
                new Term.Const(Constant.integer(3)));
        var ifThenElse = applyBuiltin(DefaultFun.IfThenElse,
                lessThanEquals,
                new Term.Const(Constant.bool(true)),
                new Term.Error());

        // Create a non-empty source map so the collector activates
        var sourceMap = createSourceMap(ifThenElse, "test.java", 1, "5 <= 3");

        var mc = DefaultCostModel.defaultMachineCosts(PlutusLanguage.PLUTUS_V3);
        var bcm = DefaultCostModel.defaultBuiltinCostModel(PlutusLanguage.PLUTUS_V3);
        var costTracker = new CostTracker(mc, bcm, null);
        var machine = new CekMachine(costTracker, PlutusLanguage.PLUTUS_V3, sourceMap, false);

        assertThrows(CekEvaluationException.class, () -> machine.evaluate(ifThenElse));

        var builtinTrace = machine.getBuiltinTrace();
        assertFalse(builtinTrace.isEmpty(), "Builtin trace should not be empty");

        // Should have captured LessThanEqualsInteger → False
        boolean foundComparison = builtinTrace.stream()
                .anyMatch(e -> e.fun() == DefaultFun.LessThanEqualsInteger
                        && "False".equals(e.resultSummary()));
        assertTrue(foundComparison,
                "Should capture LessThanEqualsInteger(5, 3) → False, got: " + builtinTrace);
    }

    @Test
    void successfulEvaluation_capturesBuiltinTrace() {
        // Build: AddInteger(3, 5)
        var add = applyBuiltin(DefaultFun.AddInteger,
                new Term.Const(Constant.integer(3)),
                new Term.Const(Constant.integer(5)));

        var sourceMap = createSourceMap(add, "Test.java", 1, "3 + 5");

        var mc = DefaultCostModel.defaultMachineCosts(PlutusLanguage.PLUTUS_V3);
        var bcm = DefaultCostModel.defaultBuiltinCostModel(PlutusLanguage.PLUTUS_V3);
        var costTracker = new CostTracker(mc, bcm, null);
        var machine = new CekMachine(costTracker, PlutusLanguage.PLUTUS_V3, sourceMap, false);

        CekValue result = machine.evaluate(add);
        assertInstanceOf(CekValue.VCon.class, result);

        var builtinTrace = machine.getBuiltinTrace();
        assertEquals(1, builtinTrace.size());
        assertEquals(DefaultFun.AddInteger, builtinTrace.getFirst().fun());
        assertEquals("3, 5", builtinTrace.getFirst().argSummary());
        assertEquals("8", builtinTrace.getFirst().resultSummary());
    }

    @Test
    void noSourceMap_stillCapturesBuiltinTrace() {
        var add = applyBuiltin(DefaultFun.AddInteger,
                new Term.Const(Constant.integer(1)),
                new Term.Const(Constant.integer(2)));

        var mc = DefaultCostModel.defaultMachineCosts(PlutusLanguage.PLUTUS_V3);
        var bcm = DefaultCostModel.defaultBuiltinCostModel(PlutusLanguage.PLUTUS_V3);
        var costTracker = new CostTracker(mc, bcm, null);
        var machine = new CekMachine(costTracker, PlutusLanguage.PLUTUS_V3, null, false);

        machine.evaluate(add);
        var builtinTrace = machine.getBuiltinTrace();
        assertFalse(builtinTrace.isEmpty(), "Builtin trace should be captured even without source map");
        assertEquals(1, builtinTrace.size());
        assertEquals(DefaultFun.AddInteger, builtinTrace.getFirst().fun());
        assertEquals("1, 2", builtinTrace.getFirst().argSummary());
        assertEquals("3", builtinTrace.getFirst().resultSummary());
    }

    @Test
    void emptySourceMap_stillCapturesBuiltinTrace() {
        var add = applyBuiltin(DefaultFun.AddInteger,
                new Term.Const(Constant.integer(1)),
                new Term.Const(Constant.integer(2)));

        var mc = DefaultCostModel.defaultMachineCosts(PlutusLanguage.PLUTUS_V3);
        var bcm = DefaultCostModel.defaultBuiltinCostModel(PlutusLanguage.PLUTUS_V3);
        var costTracker = new CostTracker(mc, bcm, null);
        var machine = new CekMachine(costTracker, PlutusLanguage.PLUTUS_V3, SourceMap.EMPTY, false);

        machine.evaluate(add);
        var builtinTrace = machine.getBuiltinTrace();
        assertFalse(builtinTrace.isEmpty(), "Builtin trace should be captured even with empty source map");
        assertEquals(1, builtinTrace.size());
        assertEquals(DefaultFun.AddInteger, builtinTrace.getFirst().fun());
    }

    @Test
    void providerChain_exposesBuiltinTrace() {
        var provider = new JavaVmProvider();
        var dummy = new Term.Const(Constant.integer(0));
        var sourceMap = createSourceMap(dummy, "Test.java", 1, "dummy");
        provider.setSourceMap(sourceMap);

        var add = applyBuiltin(DefaultFun.AddInteger,
                new Term.Const(Constant.integer(3)),
                new Term.Const(Constant.integer(5)));
        var program = new com.bloxbean.cardano.julc.core.Program(1, 1, 0, add);

        var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);
        assertInstanceOf(EvalResult.Success.class, result);

        var builtinTrace = provider.getLastBuiltinTrace();
        assertFalse(builtinTrace.isEmpty());
        assertEquals(DefaultFun.AddInteger, builtinTrace.getFirst().fun());

        provider.setSourceMap(null);
    }

    @Test
    void multipleBuiltins_ringBufferCapturesLast5() {
        // Build a chain of additions: ((1+2) + (3+4)) + (5+6)
        // This will execute 3 AddInteger builtins total
        var a1 = applyBuiltin(DefaultFun.AddInteger,
                new Term.Const(Constant.integer(1)), new Term.Const(Constant.integer(2)));
        var a2 = applyBuiltin(DefaultFun.AddInteger,
                new Term.Const(Constant.integer(3)), new Term.Const(Constant.integer(4)));
        var a3 = applyBuiltin(DefaultFun.AddInteger, a1, a2);

        var dummy = new Term.Const(Constant.integer(0));
        var sourceMap = createSourceMap(dummy, "Test.java", 1, "dummy");

        var mc = DefaultCostModel.defaultMachineCosts(PlutusLanguage.PLUTUS_V3);
        var bcm = DefaultCostModel.defaultBuiltinCostModel(PlutusLanguage.PLUTUS_V3);
        var costTracker = new CostTracker(mc, bcm, null);
        var machine = new CekMachine(costTracker, PlutusLanguage.PLUTUS_V3, sourceMap, false);

        machine.evaluate(a3);
        var builtinTrace = machine.getBuiltinTrace();
        assertEquals(3, builtinTrace.size());
        // First: 1+2=3, Second: 3+4=7, Third: 3+7=10
        assertEquals("3", builtinTrace.get(0).resultSummary());
        assertEquals("7", builtinTrace.get(1).resultSummary());
        assertEquals("10", builtinTrace.get(2).resultSummary());
    }

    @Test
    void builtinTraceDisabled_returnsEmptyTrace() {
        var add = applyBuiltin(DefaultFun.AddInteger,
                new Term.Const(Constant.integer(1)),
                new Term.Const(Constant.integer(2)));

        var mc = DefaultCostModel.defaultMachineCosts(PlutusLanguage.PLUTUS_V3);
        var bcm = DefaultCostModel.defaultBuiltinCostModel(PlutusLanguage.PLUTUS_V3);
        var costTracker = new CostTracker(mc, bcm, null);
        // 5-arg constructor: executionTrace=false, builtinTrace=false
        var machine = new CekMachine(costTracker, PlutusLanguage.PLUTUS_V3, null, false, false);

        machine.evaluate(add);
        assertTrue(machine.getBuiltinTrace().isEmpty(),
                "Builtin trace should be empty when explicitly disabled");
    }

    @Test
    void builtinTraceOnly_noExecutionTrace() {
        // Demonstrates enabling builtin trace without execution trace
        var add = applyBuiltin(DefaultFun.AddInteger,
                new Term.Const(Constant.integer(3)),
                new Term.Const(Constant.integer(5)));

        var sourceMap = createSourceMap(add, "Test.java", 1, "3 + 5");

        var mc = DefaultCostModel.defaultMachineCosts(PlutusLanguage.PLUTUS_V3);
        var bcm = DefaultCostModel.defaultBuiltinCostModel(PlutusLanguage.PLUTUS_V3);
        var costTracker = new CostTracker(mc, bcm, null);
        // executionTrace=false, builtinTrace=true
        var machine = new CekMachine(costTracker, PlutusLanguage.PLUTUS_V3, sourceMap, false, true);

        machine.evaluate(add);

        // Builtin trace should be captured
        var builtinTrace = machine.getBuiltinTrace();
        assertEquals(1, builtinTrace.size());
        assertEquals(DefaultFun.AddInteger, builtinTrace.getFirst().fun());

        // Execution trace should be empty (not enabled)
        assertTrue(machine.getExecutionTrace().isEmpty(),
                "Execution trace should be empty when only builtin trace is enabled");
    }

    @Test
    void providerLevel_builtinTraceControl() {
        var provider = new JavaVmProvider();
        var add = applyBuiltin(DefaultFun.AddInteger,
                new Term.Const(Constant.integer(3)),
                new Term.Const(Constant.integer(5)));
        var program = new com.bloxbean.cardano.julc.core.Program(1, 1, 0, add);

        // Default: builtin trace enabled
        var result1 = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);
        assertInstanceOf(EvalResult.Success.class, result1);
        assertFalse(provider.getLastBuiltinTrace().isEmpty(),
                "Builtin trace should be on by default");

        // Disable builtin trace
        provider.setBuiltinTraceEnabled(false);
        var result2 = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);
        assertInstanceOf(EvalResult.Success.class, result2);
        assertTrue(provider.getLastBuiltinTrace().isEmpty(),
                "Builtin trace should be empty when disabled via provider");

        // Re-enable
        provider.setBuiltinTraceEnabled(true);
        var result3 = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);
        assertInstanceOf(EvalResult.Success.class, result3);
        assertFalse(provider.getLastBuiltinTrace().isEmpty(),
                "Builtin trace should be captured again after re-enabling");
    }

    // --- helpers ---

    private static SourceMap createSourceMap(Term term, String file, int line, String fragment) {
        var map = new IdentityHashMap<Term, SourceLocation>();
        map.put(term, new SourceLocation(file, line, 1, fragment));
        return SourceMap.of(map);
    }

    private static Term applyBuiltin(DefaultFun fun, Term... args) {
        var table = com.bloxbean.cardano.julc.vm.java.builtins.BuiltinTable.forLanguage(PlutusLanguage.PLUTUS_V3);
        var sig = table.getSignature(fun);
        Term t = new Term.Builtin(fun);
        for (int i = 0; i < sig.forceCount(); i++) {
            t = new Term.Force(t);
        }
        for (Term arg : args) {
            t = new Term.Apply(t, arg);
        }
        return t;
    }
}
