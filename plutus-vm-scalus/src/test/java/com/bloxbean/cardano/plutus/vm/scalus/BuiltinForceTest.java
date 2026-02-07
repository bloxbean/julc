package com.bloxbean.cardano.plutus.vm.scalus;

import com.bloxbean.cardano.plutus.core.*;
import com.bloxbean.cardano.plutus.core.flat.UplcFlatEncoder;
import com.bloxbean.cardano.plutus.vm.EvalResult;
import com.bloxbean.cardano.plutus.vm.PlutusLanguage;
import org.junit.jupiter.api.Test;
import scalus.uplc.ProgramFlatCodec$;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for correct force counts on polymorphic builtins.
 * Based on Plutus conformance tests.
 */
class BuiltinForceTest {

    private final ScalusVmProvider provider = new ScalusVmProvider();

    @Test
    void nullListOneForce() {
        // Conformance: (force (builtin nullList)) — 1 force
        var emptyList = Term.const_(new Constant.ListConst(DefaultUni.DATA, List.of()));
        var nullList = Term.force(Term.builtin(DefaultFun.NullList)); // 1 force
        var term = Term.apply(nullList, emptyList);
        var result = provider.evaluate(Program.plutusV3(term), PlutusLanguage.PLUTUS_V3, null);
        assertTrue(result.isSuccess(), "NullList failed: " + result);
        var val = ((Term.Const) ((EvalResult.Success) result).resultTerm()).value();
        assertTrue(((Constant.BoolConst) val).value()); // empty list → true
    }

    @Test
    void headListOneForce() {
        // Conformance: (force (builtin headList)) — 1 force
        var dataList = Term.const_(new Constant.ListConst(DefaultUni.DATA, List.of(
                Constant.data(PlutusData.integer(BigInteger.valueOf(42))))));
        var headList = Term.force(Term.builtin(DefaultFun.HeadList)); // 1 force
        var term = Term.apply(headList, dataList);
        var result = provider.evaluate(Program.plutusV3(term), PlutusLanguage.PLUTUS_V3, null);
        assertTrue(result.isSuccess(), "HeadList failed: " + result);
    }

    @Test
    void tailListOneForce() {
        // Conformance: (force (builtin tailList)) — 1 force
        var dataList = Term.const_(new Constant.ListConst(DefaultUni.DATA, List.of(
                Constant.data(PlutusData.integer(BigInteger.ONE)),
                Constant.data(PlutusData.integer(BigInteger.TWO)))));
        var tailList = Term.force(Term.builtin(DefaultFun.TailList)); // 1 force
        var term = Term.apply(tailList, dataList);
        var result = provider.evaluate(Program.plutusV3(term), PlutusLanguage.PLUTUS_V3, null);
        assertTrue(result.isSuccess(), "TailList failed: " + result);
    }

    @Test
    void mkConsOneForce() {
        // Conformance: (force (builtin mkCons)) — 1 force
        var emptyList = Term.const_(new Constant.ListConst(DefaultUni.INTEGER, List.of()));
        var mkCons = Term.force(Term.builtin(DefaultFun.MkCons)); // 1 force
        var term = Term.apply(Term.apply(mkCons, Term.const_(Constant.integer(BigInteger.ONE))), emptyList);
        var result = provider.evaluate(Program.plutusV3(term), PlutusLanguage.PLUTUS_V3, null);
        assertTrue(result.isSuccess(), "MkCons failed: " + result);
    }

    @Test
    void mkNilDataZeroForce() {
        // Conformance: (builtin mkNilData) — 0 forces
        var mkNilData = Term.builtin(DefaultFun.MkNilData); // 0 forces
        var term = Term.apply(mkNilData, Term.const_(Constant.unit()));
        var result = provider.evaluate(Program.plutusV3(term), PlutusLanguage.PLUTUS_V3, null);
        assertTrue(result.isSuccess(), "MkNilData failed: " + result);
    }

    @Test
    void mkNilPairDataZeroForce() {
        // Conformance: (builtin mkNilPairData) — 0 forces
        var mkNilPairData = Term.builtin(DefaultFun.MkNilPairData); // 0 forces
        var term = Term.apply(mkNilPairData, Term.const_(Constant.unit()));
        var result = provider.evaluate(Program.plutusV3(term), PlutusLanguage.PLUTUS_V3, null);
        assertTrue(result.isSuccess(), "MkNilPairData failed: " + result);
    }

    @Test
    void fstPairTwoForce() {
        // Conformance: (force (force (builtin fstPair))) — 2 forces
        var pair = Term.const_(new Constant.PairConst(
                Constant.integer(BigInteger.TEN),
                Constant.integer(BigInteger.valueOf(20))));
        var fstPair = Term.force(Term.force(Term.builtin(DefaultFun.FstPair))); // 2 forces
        var term = Term.apply(fstPair, pair);
        var result = provider.evaluate(Program.plutusV3(term), PlutusLanguage.PLUTUS_V3, null);
        assertTrue(result.isSuccess(), "FstPair failed: " + result);
        var val = ((Term.Const) ((EvalResult.Success) result).resultTerm()).value();
        assertEquals(BigInteger.TEN, ((Constant.IntegerConst) val).value());
    }

    @Test
    void sndPairTwoForce() {
        // Conformance: (force (force (builtin sndPair))) — 2 forces
        var pair = Term.const_(new Constant.PairConst(
                Constant.integer(BigInteger.TEN),
                Constant.integer(BigInteger.valueOf(20))));
        var sndPair = Term.force(Term.force(Term.builtin(DefaultFun.SndPair))); // 2 forces
        var term = Term.apply(sndPair, pair);
        var result = provider.evaluate(Program.plutusV3(term), PlutusLanguage.PLUTUS_V3, null);
        assertTrue(result.isSuccess(), "SndPair failed: " + result);
        var val = ((Term.Const) ((EvalResult.Success) result).resultTerm()).value();
        assertEquals(BigInteger.valueOf(20), ((Constant.IntegerConst) val).value());
    }

    @Test
    void ifThenElseOneForce() {
        // Conformance: (force (builtin ifThenElse)) — 1 force
        var ifBuiltin = Term.force(Term.builtin(DefaultFun.IfThenElse));
        var term = Term.force(
                Term.apply(
                        Term.apply(
                                Term.apply(ifBuiltin, Term.const_(Constant.bool(true))),
                                Term.delay(Term.const_(Constant.integer(BigInteger.ONE)))),
                        Term.delay(Term.const_(Constant.integer(BigInteger.TWO)))));
        var result = provider.evaluate(Program.plutusV3(term), PlutusLanguage.PLUTUS_V3, null);
        assertTrue(result.isSuccess(), "IfThenElse failed: " + result);
        var val = ((Term.Const) ((EvalResult.Success) result).resultTerm()).value();
        assertEquals(BigInteger.ONE, ((Constant.IntegerConst) val).value());
    }

    @Test
    void chooseListTwoForce() throws Exception {
        // Conformance: (force (force (builtin chooseList))) — 2 forces
        var intList = Term.const_(new Constant.ListConst(DefaultUni.INTEGER, List.of(
                Constant.integer(BigInteger.ZERO))));
        var chooseList = Term.force(Term.force(Term.builtin(DefaultFun.ChooseList))); // 2 forces
        var term = Term.force(Term.apply(
                Term.apply(
                        Term.apply(chooseList, intList),
                        Term.delay(Term.const_(Constant.integer(BigInteger.ONE)))),
                Term.delay(Term.const_(Constant.integer(BigInteger.TWO)))));
        var result = provider.evaluate(Program.plutusV3(term), PlutusLanguage.PLUTUS_V3, null);
        assertTrue(result.isSuccess(), "ChooseList failed: " + result);
        var val = ((Term.Const) ((EvalResult.Success) result).resultTerm()).value();
        assertEquals(BigInteger.TWO, ((Constant.IntegerConst) val).value()); // non-empty → else
    }
}
