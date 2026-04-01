package com.bloxbean.cardano.julc.vm.truffle;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.core.source.SourceLocation;
import com.bloxbean.cardano.julc.core.source.SourceMap;
import com.bloxbean.cardano.julc.vm.EvalOptions;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;
import org.junit.jupiter.api.Test;

import java.util.IdentityHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for builtin trace collection in the Truffle VM backend.
 */
class TruffleBuiltinTraceTest {

    private final TruffleVmProvider provider = new TruffleVmProvider();

    @Test
    void successfulEvaluation_capturesBuiltinTrace() {
        var add = applyBuiltin(DefaultFun.AddInteger,
                new Term.Const(Constant.integer(3)),
                new Term.Const(Constant.integer(5)));
        var program = new Program(1, 1, 0, add);

        var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

        assertInstanceOf(EvalResult.Success.class, result);
        var builtinTrace = result.builtinTrace();
        assertEquals(1, builtinTrace.size());
        assertEquals(DefaultFun.AddInteger, builtinTrace.getFirst().fun());
        assertEquals("3, 5", builtinTrace.getFirst().argSummary());
        assertEquals("8", builtinTrace.getFirst().resultSummary());
    }

    @Test
    void failureCaptures_comparisonBuiltin() {
        // IfThenElse(LessThanEqualsInteger(5, 3), True, Error)
        var lessThanEquals = applyBuiltin(DefaultFun.LessThanEqualsInteger,
                new Term.Const(Constant.integer(5)),
                new Term.Const(Constant.integer(3)));
        var ifThenElse = applyBuiltin(DefaultFun.IfThenElse,
                lessThanEquals,
                new Term.Const(Constant.bool(true)),
                new Term.Error());
        var program = new Program(1, 1, 0, ifThenElse);

        var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

        assertInstanceOf(EvalResult.Failure.class, result);
        var builtinTrace = result.builtinTrace();
        assertFalse(builtinTrace.isEmpty(), "Builtin trace should capture builtins before failure");

        boolean foundComparison = builtinTrace.stream()
                .anyMatch(e -> e.fun() == DefaultFun.LessThanEqualsInteger
                        && "False".equals(e.resultSummary()));
        assertTrue(foundComparison,
                "Should capture LessThanEqualsInteger(5, 3) → False. Got: " + builtinTrace);
    }

    @Test
    void builtinTraceDisabled_returnsEmpty() {
        var add = applyBuiltin(DefaultFun.AddInteger,
                new Term.Const(Constant.integer(1)),
                new Term.Const(Constant.integer(2)));
        var program = new Program(1, 1, 0, add);

        var options = EvalOptions.DEFAULT.withBuiltinTrace(false);
        var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null, options);

        assertInstanceOf(EvalResult.Success.class, result);
        assertTrue(result.builtinTrace().isEmpty(),
                "Builtin trace should be empty when disabled");
    }

    @Test
    void noSourceMap_stillCapturesBuiltinTrace() {
        var add = applyBuiltin(DefaultFun.AddInteger,
                new Term.Const(Constant.integer(1)),
                new Term.Const(Constant.integer(2)));
        var program = new Program(1, 1, 0, add);

        // No source map, but builtin trace should still work
        var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

        var builtinTrace = result.builtinTrace();
        assertFalse(builtinTrace.isEmpty());
        assertEquals(DefaultFun.AddInteger, builtinTrace.getFirst().fun());
    }

    @Test
    void multipleBuiltins_capturedInOrder() {
        // ((1+2) + (3+4))
        var a1 = applyBuiltin(DefaultFun.AddInteger,
                new Term.Const(Constant.integer(1)), new Term.Const(Constant.integer(2)));
        var a2 = applyBuiltin(DefaultFun.AddInteger,
                new Term.Const(Constant.integer(3)), new Term.Const(Constant.integer(4)));
        var a3 = applyBuiltin(DefaultFun.AddInteger, a1, a2);
        var program = new Program(1, 1, 0, a3);

        var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

        var builtinTrace = result.builtinTrace();
        assertEquals(3, builtinTrace.size());
        assertEquals("3", builtinTrace.get(0).resultSummary());
        assertEquals("7", builtinTrace.get(1).resultSummary());
        assertEquals("10", builtinTrace.get(2).resultSummary());
    }

    @Test
    void builtinTraceIndependentOfExecutionTrace() {
        var add = applyBuiltin(DefaultFun.AddInteger,
                new Term.Const(Constant.integer(3)),
                new Term.Const(Constant.integer(5)));
        var program = new Program(1, 1, 0, add);

        // Builtin trace ON, execution trace OFF (no source map)
        var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

        assertFalse(result.builtinTrace().isEmpty(), "Builtin trace should be captured");
        assertTrue(result.executionTrace().isEmpty(), "Execution trace should be empty (no source map)");
    }

    @Test
    void builtinTraceWithSourceMap() {
        var add = applyBuiltin(DefaultFun.AddInteger,
                new Term.Const(Constant.integer(3)),
                new Term.Const(Constant.integer(5)));
        var program = new Program(1, 1, 0, add);

        var positions = new IdentityHashMap<Term, SourceLocation>();
        positions.put(add, new SourceLocation("Test.java", 1, 1, "3 + 5"));
        var sourceMap = SourceMap.of(positions);

        var options = new EvalOptions(sourceMap, true, true);
        var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null, options);

        // Both traces should be populated
        assertFalse(result.builtinTrace().isEmpty(), "Builtin trace should be captured");
        assertFalse(result.executionTrace().isEmpty(), "Execution trace should be captured with source map");
    }

    // --- helpers ---

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
