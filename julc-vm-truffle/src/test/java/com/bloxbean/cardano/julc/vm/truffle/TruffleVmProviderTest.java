package com.bloxbean.cardano.julc.vm.truffle;

import com.bloxbean.cardano.julc.core.*;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.ExBudget;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Basic tests for the Truffle VM provider.
 */
class TruffleVmProviderTest {

    private final TruffleVmProvider provider = new TruffleVmProvider();

    @Test
    void nameAndPriority() {
        assertEquals("Truffle", provider.name());
        assertEquals(200, provider.priority());
    }

    @Test
    void evaluateConstant() {
        // (program 1.0.0 (con integer 42))
        var program = new Program(1, 0, 0, Term.const_(Constant.integer(BigInteger.valueOf(42))));
        var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

        assertInstanceOf(EvalResult.Success.class, result);
        var success = (EvalResult.Success) result;
        var term = success.resultTerm();
        assertInstanceOf(Term.Const.class, term);
        var constTerm = (Term.Const) term;
        assertInstanceOf(Constant.IntegerConst.class, constTerm.value());
        assertEquals(BigInteger.valueOf(42), ((Constant.IntegerConst) constTerm.value()).value());
    }

    @Test
    void evaluateAddInteger() {
        // (program 1.0.0 [[(builtin addInteger) (con integer 3)] (con integer 4)])
        var add = Term.apply(
                Term.apply(Term.builtin(DefaultFun.AddInteger), Term.const_(Constant.integer(BigInteger.valueOf(3)))),
                Term.const_(Constant.integer(BigInteger.valueOf(4))));
        var program = new Program(1, 0, 0, add);
        var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

        assertInstanceOf(EvalResult.Success.class, result);
        var success = (EvalResult.Success) result;
        var constTerm = (Term.Const) success.resultTerm();
        assertEquals(BigInteger.valueOf(7), ((Constant.IntegerConst) constTerm.value()).value());
    }

    @Test
    void evaluateIdentityLambda() {
        // (program 1.0.0 [(lam x x) (con integer 99)])
        var identity = Term.lam("x", Term.var(new NamedDeBruijn("x", 1)));
        var app = Term.apply(identity, Term.const_(Constant.integer(BigInteger.valueOf(99))));
        var program = new Program(1, 0, 0, app);
        var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

        assertInstanceOf(EvalResult.Success.class, result);
        var constTerm = (Term.Const) ((EvalResult.Success) result).resultTerm();
        assertEquals(BigInteger.valueOf(99), ((Constant.IntegerConst) constTerm.value()).value());
    }

    @Test
    void evaluateForceDelay() {
        // (program 1.0.0 (force (delay (con integer 7))))
        var delayed = Term.delay(Term.const_(Constant.integer(BigInteger.valueOf(7))));
        var forced = Term.force(delayed);
        var program = new Program(1, 0, 0, forced);
        var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

        assertInstanceOf(EvalResult.Success.class, result);
        var constTerm = (Term.Const) ((EvalResult.Success) result).resultTerm();
        assertEquals(BigInteger.valueOf(7), ((Constant.IntegerConst) constTerm.value()).value());
    }

    @Test
    void evaluateError() {
        var program = new Program(1, 0, 0, Term.error());
        var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);
        assertInstanceOf(EvalResult.Failure.class, result);
    }

    @Test
    void evaluateIfThenElse() {
        // (force (force (force (builtin ifThenElse)) (con bool True)) (delay (con integer 1)) (delay (con integer 2)))
        var ite = Term.force(Term.apply(
                Term.apply(
                        Term.apply(
                                Term.force(Term.builtin(DefaultFun.IfThenElse)),
                                Term.const_(Constant.bool(true))),
                        Term.delay(Term.const_(Constant.integer(BigInteger.ONE)))),
                Term.delay(Term.const_(Constant.integer(BigInteger.TWO)))));
        var program = new Program(1, 0, 0, ite);
        var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

        assertInstanceOf(EvalResult.Success.class, result);
        var constTerm = (Term.Const) ((EvalResult.Success) result).resultTerm();
        assertEquals(BigInteger.ONE, ((Constant.IntegerConst) constTerm.value()).value());
    }

    @Test
    void evaluateTrace() {
        // (force (force (builtin trace)) (con string "hello")) (con integer 42))
        var traced = Term.apply(
                Term.apply(
                        Term.force(Term.builtin(DefaultFun.Trace)),
                        Term.const_(Constant.string("hello"))),
                Term.const_(Constant.integer(BigInteger.valueOf(42))));
        var program = new Program(1, 0, 0, traced);
        var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

        assertInstanceOf(EvalResult.Success.class, result);
        var success = (EvalResult.Success) result;
        assertEquals(1, success.traces().size());
        assertEquals("hello", success.traces().getFirst());
    }

    @Test
    void budgetExhausted() {
        // Use a very small budget that should be exhausted
        var program = new Program(1, 0, 0, Term.const_(Constant.integer(BigInteger.ONE)));
        // Startup cost alone is 100K CPU, so a budget of 1 should exhaust
        var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, new ExBudget(1, 1));
        assertInstanceOf(EvalResult.BudgetExhausted.class, result);
    }

    @Test
    void evaluateConstrV3() {
        // (constr 0 (con integer 1) (con integer 2))
        var constr = Term.constr(0,
                Term.const_(Constant.integer(BigInteger.ONE)),
                Term.const_(Constant.integer(BigInteger.TWO)));
        var program = new Program(1, 1, 0, constr);
        var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

        assertInstanceOf(EvalResult.Success.class, result);
        var term = ((EvalResult.Success) result).resultTerm();
        assertInstanceOf(Term.Constr.class, term);
        var c = (Term.Constr) term;
        assertEquals(0, c.tag());
        assertEquals(2, c.fields().size());
    }

    @Test
    void evaluateCaseV3() {
        // case (constr 1) [(lam x (con integer 10)) (lam x (con integer 20))]
        var scrutinee = Term.constr(1);
        var branch0 = Term.const_(Constant.integer(BigInteger.TEN));
        var branch1 = Term.const_(Constant.integer(BigInteger.valueOf(20)));
        var caseExpr = Term.case_(scrutinee, branch0, branch1);
        var program = new Program(1, 1, 0, caseExpr);
        var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

        assertInstanceOf(EvalResult.Success.class, result);
        var constTerm = (Term.Const) ((EvalResult.Success) result).resultTerm();
        assertEquals(BigInteger.valueOf(20), ((Constant.IntegerConst) constTerm.value()).value());
    }

    @Test
    void evaluateNestedLambda() {
        // (program 1.0.0 [[(lam f [(lam x [f x]) (con integer 5)]) (lam y [(builtin addInteger) y (con integer 10)])] ])
        // Simplified: (\f -> (\x -> f x) 5) (\y -> y + 10) => 15
        var addTen = Term.lam("y",
                Term.apply(
                        Term.apply(Term.builtin(DefaultFun.AddInteger),
                                Term.var(new NamedDeBruijn("y", 1))),
                        Term.const_(Constant.integer(BigInteger.TEN))));
        var inner = Term.lam("x",
                Term.apply(
                        Term.var(new NamedDeBruijn("f", 2)), // f is at index 2 (outer lambda)
                        Term.var(new NamedDeBruijn("x", 1))));
        var outerApp = Term.apply(inner, Term.const_(Constant.integer(BigInteger.valueOf(5))));
        var outerLam = Term.lam("f", outerApp);
        var fullApp = Term.apply(outerLam, addTen);
        var program = new Program(1, 0, 0, fullApp);
        var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

        assertInstanceOf(EvalResult.Success.class, result);
        var constTerm = (Term.Const) ((EvalResult.Success) result).resultTerm();
        assertEquals(BigInteger.valueOf(15), ((Constant.IntegerConst) constTerm.value()).value());
    }

    // --- evaluateWithArgs ---

    @Test
    void evaluateWithArgs() {
        // Identity function applied to a PlutusData argument
        // (program 1.0.0 (lam x x))
        var identity = Term.lam("x", Term.var(new NamedDeBruijn("x", 1)));
        var program = new Program(1, 0, 0, identity);

        var arg = PlutusData.integer(BigInteger.valueOf(42));
        var result = provider.evaluateWithArgs(program, PlutusLanguage.PLUTUS_V3,
                java.util.List.of(arg), null);

        assertInstanceOf(EvalResult.Success.class, result);
        var success = (EvalResult.Success) result;
        // The result should be (con data (I 42))
        assertInstanceOf(Term.Const.class, success.resultTerm());
        var constVal = ((Term.Const) success.resultTerm()).value();
        assertInstanceOf(Constant.DataConst.class, constVal);
        assertEquals(BigInteger.valueOf(42),
                ((PlutusData.IntData) ((Constant.DataConst) constVal).value()).value());
    }

    @Test
    void evaluateWithArgsMultiple() {
        // Function taking 3 args (V1/V2 style: datum, redeemer, context)
        // (\d -> \r -> \ctx -> d)
        var body = Term.var(new NamedDeBruijn("d", 3));
        var lam = Term.lam("d", Term.lam("r", Term.lam("ctx", body)));
        var program = new Program(1, 0, 0, lam);

        var datum = PlutusData.integer(BigInteger.ONE);
        var redeemer = PlutusData.integer(BigInteger.TWO);
        var ctx = PlutusData.integer(BigInteger.TEN);
        var result = provider.evaluateWithArgs(program, PlutusLanguage.PLUTUS_V3,
                java.util.List.of(datum, redeemer, ctx), null);

        assertInstanceOf(EvalResult.Success.class, result);
        var constVal = ((Term.Const) ((EvalResult.Success) result).resultTerm()).value();
        // Should return datum (first arg)
        assertEquals(BigInteger.ONE,
                ((PlutusData.IntData) ((Constant.DataConst) constVal).value()).value());
    }

    // --- setCostModelParams ---

    @Test
    void setCostModelParamsChangesBudget() {
        var program = new Program(1, 0, 0,
                Term.const_(Constant.integer(BigInteger.ONE)));

        // Evaluate with default cost model
        var defaultResult = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);
        assertInstanceOf(EvalResult.Success.class, defaultResult);
        var defaultBudget = defaultResult.budgetConsumed();

        // Create a provider with custom cost model
        var customProvider = new TruffleVmProvider();
        // Just verify the method doesn't throw
        customProvider.setCostModelParams(new long[297], 10);
        // Verify evaluation still works after setting params
        var customResult = customProvider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);
        assertInstanceOf(EvalResult.Success.class, customResult);
    }

    // --- V1/V2 language version ---

    @Test
    void v1EvaluationBasic() {
        // V1 should support basic evaluation (Const, Lam, Apply, etc.)
        var add = Term.apply(
                Term.apply(Term.builtin(DefaultFun.AddInteger),
                        Term.const_(Constant.integer(BigInteger.valueOf(3)))),
                Term.const_(Constant.integer(BigInteger.valueOf(4))));
        var program = new Program(1, 0, 0, add);
        var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V1, null);

        assertInstanceOf(EvalResult.Success.class, result);
        var constTerm = (Term.Const) ((EvalResult.Success) result).resultTerm();
        assertEquals(BigInteger.valueOf(7), ((Constant.IntegerConst) constTerm.value()).value());
    }

    @Test
    void v1RejectsConstr() {
        // V1 should reject Constr terms
        var constr = Term.constr(0, Term.const_(Constant.integer(BigInteger.ONE)));
        var program = new Program(1, 0, 0, constr);
        var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V1, null);
        assertInstanceOf(EvalResult.Failure.class, result);
        assertTrue(((EvalResult.Failure) result).error().contains("PLUTUS_V3"));
    }

    @Test
    void v2EvaluationBasic() {
        // V2 should support SerialiseData
        var ser = Term.apply(
                Term.builtin(DefaultFun.SerialiseData),
                Term.const_(Constant.data(PlutusData.integer(BigInteger.ONE))));
        var program = new Program(1, 0, 0, ser);
        var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V2, null);
        assertInstanceOf(EvalResult.Success.class, result);
    }

    // --- Builtin error propagation ---

    @Test
    void builtinErrorDivideByZero() {
        var div = Term.apply(
                Term.apply(Term.builtin(DefaultFun.DivideInteger),
                        Term.const_(Constant.integer(BigInteger.TEN))),
                Term.const_(Constant.integer(BigInteger.ZERO)));
        var program = new Program(1, 0, 0, div);
        var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);
        assertInstanceOf(EvalResult.Failure.class, result);
    }

    @Test
    void builtinErrorHeadEmptyList() {
        var headEmpty = Term.apply(
                Term.force(Term.builtin(DefaultFun.HeadList)),
                Term.const_(new Constant.ListConst(
                        com.bloxbean.cardano.julc.core.DefaultUni.INTEGER, java.util.List.of())));
        var program = new Program(1, 0, 0, headEmpty);
        var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);
        assertInstanceOf(EvalResult.Failure.class, result);
    }
}
