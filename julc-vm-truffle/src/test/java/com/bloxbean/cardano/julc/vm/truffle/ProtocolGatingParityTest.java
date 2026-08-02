package com.bloxbean.cardano.julc.vm.truffle;

import com.bloxbean.cardano.julc.core.*;
import com.bloxbean.cardano.julc.vm.*;
import com.bloxbean.cardano.julc.vm.java.JavaVmProvider;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolGatingParityTest {

    private final JavaVmProvider java = new JavaVmProvider();
    private final TruffleVmProvider truffle = new TruffleVmProvider();

    @Test
    void batch6BoundaryMatchesJava() {
        var term = apply(DefaultFun.ExpModInteger,
                Constant.integer(2), Constant.integer(8), Constant.integer(17));
        assertBackendParity(Program.plutusV3(term), PlutusLanguage.PLUTUS_V3, 10);
        assertBackendParity(Program.plutusV3(term), PlutusLanguage.PLUTUS_V3, 11);
    }

    @Test
    void v2Batch4bBoundaryMatchesJava() {
        var term = apply(DefaultFun.IntegerToByteString,
                Constant.bool(true), Constant.integer(0), Constant.integer(258));
        var program = Program.plutusV2(term);
        assertBackendParity(program, PlutusLanguage.PLUTUS_V2, 9);
        assertBackendParity(program, PlutusLanguage.PLUTUS_V2, 10);
    }

    @Test
    void caseOnBuiltinBoundaryMatchesJava() {
        var term = new Term.Case(Term.const_(Constant.bool(true)), List.of(
                Term.const_(Constant.integer(0)), Term.const_(Constant.integer(1))));
        var program = Program.plutusV3(term);
        assertBackendParity(program, PlutusLanguage.PLUTUS_V3, 10);
        assertBackendParity(program, PlutusLanguage.PLUTUS_V3, 11);
    }

    @Test
    void caseOnConstructorChecksFullWord64TagBeforeNarrowing() {
        var target = LedgerEvaluationTarget.pv10(PlutusLanguage.PLUTUS_V3);
        for (long tag : new long[]{1L << 32, -1L}) {
            var term = new Term.Case(new Term.Constr(tag, List.of()),
                    List.of(Term.const_(Constant.integer(42))));
            var program = Program.plutusV3(term);
            var expectedTag = Long.toUnsignedString(tag);

            assertFailure(java.evaluate(program, target, null),
                    "tag " + expectedTag + " out of range");
            assertFailure(truffle.evaluate(program, target, null),
                    "tag " + expectedTag + " out of range");
        }
    }

    @Test
    void futureBuiltinIsRejectedByBothBackends() {
        var program = Program.plutusV3(Term.builtin(DefaultFun.MultiIndexArray));
        var target = LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3);
        assertFailure(java.evaluate(program, target, null), "MultiIndexArray is not available");
        assertFailure(truffle.evaluate(program, target, null), "MultiIndexArray is not available");
    }

    @Test
    void deRuntimeBoundariesMatchJava() {
        var oversizedInteger = BigInteger.ONE.shiftLeft(262143);
        var add = apply(DefaultFun.AddInteger,
                Constant.integer(oversizedInteger), Constant.integer(0));
        assertBackendParity(Program.plutusV3(add), PlutusLanguage.PLUTUS_V3, 10);
        assertBackendParity(Program.plutusV3(add), PlutusLanguage.PLUTUS_V3, 11);

        var cons = apply(DefaultFun.ConsByteString,
                Constant.integer(256), Constant.byteString(new byte[]{1}));
        assertBackendParity(new Program(1, 0, 0, cons), PlutusLanguage.PLUTUS_V1, 11);
        assertBackendParity(Program.plutusV3(cons), PlutusLanguage.PLUTUS_V3, 11);

        var shift = apply(DefaultFun.ShiftByteString,
                Constant.byteString(new byte[]{1}), Constant.integer(BigInteger.ONE.shiftLeft(63)));
        assertBackendParity(Program.plutusV3(shift), PlutusLanguage.PLUTUS_V3, 11);
    }

    @Test
    void constrDataIntegerAndWord64BoundariesMatchJava() {
        var word64Maximum = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
        var aboveWord64 = word64Maximum.add(BigInteger.ONE);

        assertBackendParity(constrDataRoundTrip(BigInteger.ONE.negate(), true),
                PlutusLanguage.PLUTUS_V3, 10);
        assertBackendParity(constrDataRoundTrip(word64Maximum, true),
                PlutusLanguage.PLUTUS_V3, 11);
        assertBackendParity(constrDataRoundTrip(aboveWord64, true),
                PlutusLanguage.PLUTUS_V3, 11);
        assertBackendParity(constrDataRoundTrip(word64Maximum, false),
                PlutusLanguage.PLUTUS_V1, 11);
    }

    private void assertBackendParity(Program program, PlutusLanguage language, int protocol) {
        var target = new LedgerEvaluationTarget(language, new ProtocolVersion(protocol, 0));
        var javaResult = java.evaluate(program, target, null);
        var truffleResult = truffle.evaluate(program, target, null);
        assertEquals(javaResult.getClass(), truffleResult.getClass(),
                () -> "Java=" + javaResult + ", Truffle=" + truffleResult);
        if (javaResult instanceof EvalResult.Success javaSuccess) {
            var truffleSuccess = (EvalResult.Success) truffleResult;
            assertEquals(javaSuccess.resultTerm(), truffleSuccess.resultTerm());
            assertEquals(javaSuccess.consumed(), truffleSuccess.consumed());
        } else {
            assertEquals(javaResult.budgetConsumed(), truffleResult.budgetConsumed());
        }
    }

    private static Term apply(DefaultFun fun, Constant... args) {
        Term term = Term.builtin(fun);
        for (var arg : args) term = Term.apply(term, Term.const_(arg));
        return term;
    }

    private static Program constrDataRoundTrip(BigInteger tag, boolean plutusV3) {
        var fields = new Constant.ListConst(DefaultUni.DATA, List.of());
        var constr = Term.apply(
                Term.apply(Term.builtin(DefaultFun.ConstrData),
                        Term.const_(Constant.integer(tag))),
                Term.const_(fields));
        var unConstr = Term.apply(Term.builtin(DefaultFun.UnConstrData), constr);
        return plutusV3
                ? Program.plutusV3(unConstr)
                : new Program(1, 0, 0, unConstr);
    }

    private static void assertFailure(EvalResult result, String expected) {
        var failure = assertInstanceOf(EvalResult.Failure.class, result);
        assertTrue(failure.error().contains(expected));
    }
}
