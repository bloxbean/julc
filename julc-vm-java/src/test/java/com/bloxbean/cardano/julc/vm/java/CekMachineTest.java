package com.bloxbean.cardano.julc.vm.java;

import com.bloxbean.cardano.julc.core.*;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the CEK machine covering lambda calculus, builtins, and SOPs.
 */
class CekMachineTest {

    private final JavaVmProvider provider = new JavaVmProvider();

    // === Lambda calculus basics ===

    @Test
    void testIdentity() {
        // (\x -> x) 42
        var term = Term.apply(
                Term.lam("x", Term.var(1)),
                Term.const_(Constant.integer(42)));
        var result = evaluate(term);
        assertSuccess(result, 42);
    }

    @Test
    void testConstantFunction() {
        // (\x -> \y -> x) 1 2 → 1
        var term = Term.apply(
                Term.apply(
                        Term.lam("x", Term.lam("y", Term.var(2))),
                        Term.const_(Constant.integer(1))),
                Term.const_(Constant.integer(2)));
        var result = evaluate(term);
        assertSuccess(result, 1);
    }

    @Test
    void testChurchNumeralZero() {
        // Church zero: \f -> \x -> x
        // Apply to two args: (\f -> \x -> x) succ 0 → 0
        var zero = Term.lam("f", Term.lam("x", Term.var(1)));
        var term = Term.apply(Term.apply(zero, Term.lam("n", Term.var(1))),
                Term.const_(Constant.integer(0)));
        var result = evaluate(term);
        assertSuccess(result, 0);
    }

    @Test
    void testDelayForce() {
        // (force (delay 42)) → 42
        var term = Term.force(Term.delay(Term.const_(Constant.integer(42))));
        var result = evaluate(term);
        assertSuccess(result, 42);
    }

    @Test
    void testErrorTerm() {
        var term = Term.error();
        var result = evaluate(term);
        assertInstanceOf(EvalResult.Failure.class, result);
    }

    // === Integer builtins ===

    @Test
    void testAddInteger() {
        // addInteger 3 4 → 7
        var term = Term.apply(
                Term.apply(Term.builtin(DefaultFun.AddInteger),
                        Term.const_(Constant.integer(3))),
                Term.const_(Constant.integer(4)));
        var result = evaluate(term);
        assertSuccess(result, 7);
    }

    @Test
    void testSubtractInteger() {
        var term = Term.apply(
                Term.apply(Term.builtin(DefaultFun.SubtractInteger),
                        Term.const_(Constant.integer(10))),
                Term.const_(Constant.integer(3)));
        var result = evaluate(term);
        assertSuccess(result, 7);
    }

    @Test
    void testMultiplyInteger() {
        var term = Term.apply(
                Term.apply(Term.builtin(DefaultFun.MultiplyInteger),
                        Term.const_(Constant.integer(6))),
                Term.const_(Constant.integer(7)));
        var result = evaluate(term);
        assertSuccess(result, 42);
    }

    @Test
    void testDivideByZero() {
        var term = Term.apply(
                Term.apply(Term.builtin(DefaultFun.DivideInteger),
                        Term.const_(Constant.integer(1))),
                Term.const_(Constant.integer(0)));
        var result = evaluate(term);
        assertInstanceOf(EvalResult.Failure.class, result);
    }

    @Test
    void testEqualsInteger() {
        // equalsInteger 5 5 → True
        var term = Term.apply(
                Term.apply(Term.builtin(DefaultFun.EqualsInteger),
                        Term.const_(Constant.integer(5))),
                Term.const_(Constant.integer(5)));
        var result = evaluate(term);
        assertSuccessBool(result, true);
    }

    @Test
    void testLessThanInteger() {
        var term = Term.apply(
                Term.apply(Term.builtin(DefaultFun.LessThanInteger),
                        Term.const_(Constant.integer(3))),
                Term.const_(Constant.integer(5)));
        var result = evaluate(term);
        assertSuccessBool(result, true);
    }

    // === Control flow ===

    @Test
    void testIfThenElse() {
        // (force (ifThenElse True (delay 1) (delay 2))) → 1
        var term = Term.force(
                Term.apply(
                        Term.apply(
                                Term.apply(
                                        Term.force(Term.builtin(DefaultFun.IfThenElse)),
                                        Term.const_(Constant.bool(true))),
                                Term.delay(Term.const_(Constant.integer(1)))),
                        Term.delay(Term.const_(Constant.integer(2)))));
        var result = evaluate(term);
        assertSuccess(result, 1);
    }

    @Test
    void testIfThenElseFalse() {
        var term = Term.force(
                Term.apply(
                        Term.apply(
                                Term.apply(
                                        Term.force(Term.builtin(DefaultFun.IfThenElse)),
                                        Term.const_(Constant.bool(false))),
                                Term.delay(Term.const_(Constant.integer(1)))),
                        Term.delay(Term.const_(Constant.integer(2)))));
        var result = evaluate(term);
        assertSuccess(result, 2);
    }

    @Test
    void testTrace() {
        // (force (trace "hello" (delay 42))) → 42, traces=["hello"]
        var term = Term.force(
                Term.apply(
                        Term.apply(
                                Term.force(Term.builtin(DefaultFun.Trace)),
                                Term.const_(Constant.string("hello"))),
                        Term.delay(Term.const_(Constant.integer(42)))));
        var result = evaluate(term);
        assertSuccess(result, 42);
        var success = (EvalResult.Success) result;
        assertEquals(List.of("hello"), success.traces());
    }

    // === Data builtins ===

    @Test
    void testIDataUnIData() {
        // unIData (iData 42) → 42
        var term = Term.apply(
                Term.builtin(DefaultFun.UnIData),
                Term.apply(Term.builtin(DefaultFun.IData),
                        Term.const_(Constant.integer(42))));
        var result = evaluate(term);
        assertSuccess(result, 42);
    }

    @Test
    void testEqualsData() {
        var data = PlutusData.integer(42);
        var term = Term.apply(
                Term.apply(Term.builtin(DefaultFun.EqualsData),
                        Term.const_(Constant.data(data))),
                Term.const_(Constant.data(data)));
        var result = evaluate(term);
        assertSuccessBool(result, true);
    }

    // === List builtins ===

    @Test
    void testMkConsHeadList() {
        // headList (mkCons 1 []) → 1
        // force (headList) (force (mkCons) 1 (mkNilData ()))
        var mkNil = Term.apply(Term.builtin(DefaultFun.MkNilData),
                Term.const_(Constant.unit()));
        var cons = Term.apply(
                Term.apply(Term.force(Term.builtin(DefaultFun.MkCons)),
                        Term.const_(Constant.data(PlutusData.integer(1)))),
                mkNil);
        var head = Term.apply(Term.force(Term.builtin(DefaultFun.HeadList)), cons);
        var result = evaluate(head);
        assertInstanceOf(EvalResult.Success.class, result);
    }

    @Test
    void testNullListEmpty() {
        var mkNil = Term.apply(Term.builtin(DefaultFun.MkNilData),
                Term.const_(Constant.unit()));
        var nullList = Term.apply(Term.force(Term.builtin(DefaultFun.NullList)), mkNil);
        var result = evaluate(nullList);
        assertSuccessBool(result, true);
    }

    // === ByteString builtins ===

    @Test
    void testEqualsByteString() {
        var bs = new byte[]{1, 2, 3};
        var term = Term.apply(
                Term.apply(Term.builtin(DefaultFun.EqualsByteString),
                        Term.const_(Constant.byteString(bs))),
                Term.const_(Constant.byteString(bs)));
        var result = evaluate(term);
        assertSuccessBool(result, true);
    }

    @Test
    void testLengthOfByteString() {
        var term = Term.apply(Term.builtin(DefaultFun.LengthOfByteString),
                Term.const_(Constant.byteString(new byte[]{1, 2, 3, 4, 5})));
        var result = evaluate(term);
        assertSuccess(result, 5);
    }

    // === String builtins ===

    @Test
    void testAppendString() {
        var term = Term.apply(
                Term.apply(Term.builtin(DefaultFun.AppendString),
                        Term.const_(Constant.string("hello"))),
                Term.const_(Constant.string(" world")));
        var result = evaluate(term);
        assertSuccessString(result, "hello world");
    }

    // === Constr/Case (SOPs) ===

    @Test
    void testConstrCase() {
        // case (constr 0 42) [(\x -> x), (\x -> 0)] → 42
        var term = Term.case_(
                Term.constr(0, Term.const_(Constant.integer(42))),
                Term.lam("x", Term.var(1)),
                Term.lam("x", Term.const_(Constant.integer(0))));
        var result = evaluate(term);
        assertSuccess(result, 42);
    }

    @Test
    void testConstrCaseSecondBranch() {
        // case (constr 1 99) [(\x -> 0), (\x -> x)] → 99
        var term = Term.case_(
                Term.constr(1, Term.const_(Constant.integer(99))),
                Term.lam("x", Term.const_(Constant.integer(0))),
                Term.lam("x", Term.var(1)));
        var result = evaluate(term);
        assertSuccess(result, 99);
    }

    @Test
    void testConstrNoFields() {
        // case (constr 0) [42, 99] → 42
        var term = Term.case_(
                Term.constr(0),
                Term.const_(Constant.integer(42)),
                Term.const_(Constant.integer(99)));
        var result = evaluate(term);
        assertSuccess(result, 42);
    }

    // === Pair builtins ===

    @Test
    void testFstSndPair() {
        var pair = Term.apply(
                Term.apply(Term.builtin(DefaultFun.MkPairData),
                        Term.const_(Constant.data(PlutusData.integer(1)))),
                Term.const_(Constant.data(PlutusData.integer(2))));
        var fst = Term.apply(Term.force(Term.force(Term.builtin(DefaultFun.FstPair))), pair);
        var result = evaluate(fst);
        assertInstanceOf(EvalResult.Success.class, result);
    }

    // === Closure test ===

    @Test
    void testClosure() {
        // let add = \x -> \y -> addInteger x y
        // in add 3 4 → 7
        var addBody = Term.apply(
                Term.apply(Term.builtin(DefaultFun.AddInteger), Term.var(2)),
                Term.var(1));
        var add = Term.lam("x", Term.lam("y", addBody));
        var term = Term.apply(Term.apply(add, Term.const_(Constant.integer(3))),
                Term.const_(Constant.integer(4)));
        var result = evaluate(term);
        assertSuccess(result, 7);
    }

    @Test
    void testRecursionViaYCombinator() {
        // Factorial via Z combinator:
        // Z = \f -> (\x -> f (\v -> x x v)) (\x -> f (\v -> x x v))
        // fact = Z (\self -> \n -> ifThenElse (equalsInteger n 0) (delay 1) (delay (multiplyInteger n (self (subtractInteger n 1)))))
        // fact 5 → 120

        // self = var 2, n = var 1
        var eq = Term.apply(Term.apply(Term.builtin(DefaultFun.EqualsInteger), Term.var(1)),
                Term.const_(Constant.integer(0)));
        var sub1 = Term.apply(
                Term.apply(Term.builtin(DefaultFun.SubtractInteger), Term.var(1)),
                Term.const_(Constant.integer(1)));
        var recurse = Term.apply(Term.var(2), sub1);
        var mul = Term.apply(
                Term.apply(Term.builtin(DefaultFun.MultiplyInteger), Term.var(1)),
                recurse);

        var ifExpr = Term.force(Term.apply(
                Term.apply(
                        Term.apply(Term.force(Term.builtin(DefaultFun.IfThenElse)), eq),
                        Term.delay(Term.const_(Constant.integer(1)))),
                Term.delay(mul)));

        var factBody = Term.lam("self", Term.lam("n", ifExpr));

        // Z combinator
        // inner = \x -> f (\v -> x x v)
        var inner = Term.lam("x",
                Term.apply(Term.var(2), // f
                        Term.lam("v",
                                Term.apply(Term.apply(Term.var(2), Term.var(2)), Term.var(1)))));
        var z = Term.lam("f", Term.apply(inner, inner));

        var term = Term.apply(Term.apply(z, factBody), Term.const_(Constant.integer(5)));
        var result = evaluate(term);
        assertSuccess(result, 120);
    }

    // === Helpers ===

    private EvalResult evaluate(Term term) {
        return provider.evaluate(Program.plutusV3(term), PlutusLanguage.PLUTUS_V3, null);
    }

    private void assertSuccess(EvalResult result, long expected) {
        assertInstanceOf(EvalResult.Success.class, result,
                () -> "Expected success but got: " + result);
        var success = (EvalResult.Success) result;
        assertInstanceOf(Term.Const.class, success.resultTerm());
        var c = ((Term.Const) success.resultTerm()).value();
        assertInstanceOf(Constant.IntegerConst.class, c);
        assertEquals(BigInteger.valueOf(expected), ((Constant.IntegerConst) c).value());
    }

    private void assertSuccessBool(EvalResult result, boolean expected) {
        assertInstanceOf(EvalResult.Success.class, result,
                () -> "Expected success but got: " + result);
        var success = (EvalResult.Success) result;
        assertInstanceOf(Term.Const.class, success.resultTerm());
        var c = ((Term.Const) success.resultTerm()).value();
        assertInstanceOf(Constant.BoolConst.class, c);
        assertEquals(expected, ((Constant.BoolConst) c).value());
    }

    private void assertSuccessString(EvalResult result, String expected) {
        assertInstanceOf(EvalResult.Success.class, result,
                () -> "Expected success but got: " + result);
        var success = (EvalResult.Success) result;
        assertInstanceOf(Term.Const.class, success.resultTerm());
        var c = ((Term.Const) success.resultTerm()).value();
        assertInstanceOf(Constant.StringConst.class, c);
        assertEquals(expected, ((Constant.StringConst) c).value());
    }
}
