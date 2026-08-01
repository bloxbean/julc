package com.bloxbean.cardano.julc.vm.java;

import com.bloxbean.cardano.julc.core.*;
import com.bloxbean.cardano.julc.vm.*;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolGatingTest {

    private final JavaVmProvider provider = new JavaVmProvider();

    @Test
    void batch6FailsBelowPv11AndSucceedsAtPv11() {
        var expMod = apply(DefaultFun.ExpModInteger,
                Constant.integer(2), Constant.integer(8), Constant.integer(17));

        assertFailureContains(evaluate(expMod, PlutusLanguage.PLUTUS_V3, 10),
                "ExpModInteger is not available");
        assertInteger(evaluate(expMod, PlutusLanguage.PLUTUS_V3, 11), 1);
    }

    @Test
    void multiIndexArrayIsRejectedAtPv11() {
        var program = Program.plutusV3(Term.builtin(DefaultFun.MultiIndexArray));
        var result = provider.evaluate(program,
                LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3), null);
        assertFailureContains(result, "MultiIndexArray is not available");
        assertEquals(ExBudget.ZERO, result.budgetConsumed());
    }

    @Test
    void v2Pv10AcceptsBatch4bButNotBatch4a() {
        var integerToBytes = apply(DefaultFun.IntegerToByteString,
                Constant.bool(true), Constant.integer(0), Constant.integer(258));
        assertInstanceOf(EvalResult.Success.class,
                evaluate(integerToBytes, PlutusLanguage.PLUTUS_V2, 10));

        var blsReference = Program.plutusV2(Term.builtin(DefaultFun.Bls12_381_G1_add));
        assertFailureContains(provider.evaluate(blsReference,
                        target(PlutusLanguage.PLUTUS_V2, 10), null),
                "Bls12_381_G1_add is not available");
    }

    @Test
    void pv11MakesReleasedBuiltinsAvailableToV1() {
        var serialise = apply(DefaultFun.SerialiseData,
                Constant.data(new PlutusData.IntData(BigInteger.ONE)));
        assertFailureContains(evaluate(serialise, PlutusLanguage.PLUTUS_V1, 10),
                "SerialiseData is not available");
        assertInstanceOf(EvalResult.Success.class,
                evaluate(serialise, PlutusLanguage.PLUTUS_V1, 11));
    }

    @Test
    void v1Pv11AcceptsUplc11ConstrAndCase() {
        var term = new Term.Case(
                new Term.Constr(0, List.of(Term.const_(Constant.integer(42)))),
                List.of(Term.lam("x", Term.var(1))));
        var program = new Program(1, 1, 0, term);

        assertInteger(provider.evaluate(program,
                LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V1), null), 42);
        assertFailureContains(provider.evaluate(program,
                        LedgerEvaluationTarget.pv10(PlutusLanguage.PLUTUS_V1), null),
                "UPLC 1.1.0 is not available");
    }

    @Test
    void caseOnBuiltinIsPv11OnlyButConstructorCaseRemainsAvailable() {
        var boolCase = new Term.Case(Term.const_(Constant.bool(true)), List.of(
                Term.const_(Constant.integer(0)), Term.const_(Constant.integer(1))));
        assertFailureContains(evaluate(boolCase, PlutusLanguage.PLUTUS_V3, 10),
                "Case on builtin constants is not available");
        assertInteger(evaluate(boolCase, PlutusLanguage.PLUTUS_V3, 11), 1);

        var constrCase = new Term.Case(new Term.Constr(0, List.of()),
                List.of(Term.const_(Constant.integer(7))));
        assertInteger(evaluate(constrCase, PlutusLanguage.PLUTUS_V3, 10), 7);
    }

    @Test
    void programVersionControlsConstrAndCaseSyntax() {
        var invalid = new Program(1, 0, 0, new Term.Constr(0, List.of()));
        var result = provider.evaluate(invalid,
                LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3), null);
        assertFailureContains(result, "Constr/Case terms require UPLC 1.1.0");
        assertEquals(ExBudget.ZERO, result.budgetConsumed());
    }

    @Test
    void stringCostingMatchesPinnedPv10AndPv11Budgets() {
        var append = apply(DefaultFun.AppendString,
                Constant.string("Ola"), Constant.string(" mundo!"));
        assertBudget(evaluate(append, PlutusLanguage.PLUTUS_V3, 10), 680670, 614);
        assertBudget(evaluate(append, PlutusLanguage.PLUTUS_V3, 11), 141057, 605);

        var equals = apply(DefaultFun.EqualsString,
                Constant.string("Ola"), Constant.string("Ola"));
        assertBudget(evaluate(equals, PlutusLanguage.PLUTUS_V3, 10), 262882, 601);
        assertBudget(evaluate(equals, PlutusLanguage.PLUTUS_V3, 11), 81100, 601);

        var encode = apply(DefaultFun.EncodeUtf8, Constant.string("Ola"));
        assertBudget(evaluate(encode, PlutusLanguage.PLUTUS_V3, 10), 177863, 410);
        assertBudget(evaluate(encode, PlutusLanguage.PLUTUS_V3, 11), 49100, 404);
    }

    @Test
    void pv11StringCostingUsesUtf8BytesForMultibyteText() {
        var append = apply(DefaultFun.AppendString,
                Constant.string("é"), Constant.string("😀"));
        assertBudget(evaluate(append, PlutusLanguage.PLUTUS_V3, 10), 201014, 606);
        assertBudget(evaluate(append, PlutusLanguage.PLUTUS_V3, 11), 141057, 605);

        var encode = apply(DefaultFun.EncodeUtf8, Constant.string("é😀"));
        assertBudget(evaluate(encode, PlutusLanguage.PLUTUS_V3, 11), 92021, 406);
    }

    @Test
    void v1Pv11ChargesConstrAndCaseMachineSteps() {
        var pv10 = ProtocolFeatureRegistry.resolve(
                LedgerEvaluationTarget.pv10(PlutusLanguage.PLUTUS_V1));
        var pv11 = ProtocolFeatureRegistry.resolve(
                LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V1));

        assertEquals(0, com.bloxbean.cardano.julc.vm.java.cost.DefaultCostModel
                .defaultMachineCosts(pv10).constrCpu());
        assertEquals(16000, com.bloxbean.cardano.julc.vm.java.cost.DefaultCostModel
                .defaultMachineCosts(pv11).constrCpu());
        assertEquals(16000, com.bloxbean.cardano.julc.vm.java.cost.DefaultCostModel
                .defaultMachineCosts(pv11).caseCpu());
    }

    private EvalResult evaluate(Term term, PlutusLanguage language, int protocol) {
        var program = language == PlutusLanguage.PLUTUS_V3
                ? Program.plutusV3(term)
                : new Program(1, 0, 0, term);
        return provider.evaluate(program, target(language, protocol), null);
    }

    private static LedgerEvaluationTarget target(PlutusLanguage language, int protocol) {
        return new LedgerEvaluationTarget(language, new ProtocolVersion(protocol, 0));
    }

    private static Term apply(DefaultFun fun, Constant... args) {
        Term term = Term.builtin(fun);
        for (var arg : args) term = Term.apply(term, Term.const_(arg));
        return term;
    }

    private static void assertInteger(EvalResult result, long expected) {
        var success = assertInstanceOf(EvalResult.Success.class, result,
                () -> "Expected success, got " + result);
        var constant = assertInstanceOf(Term.Const.class, success.resultTerm());
        var integer = assertInstanceOf(Constant.IntegerConst.class, constant.value());
        assertEquals(BigInteger.valueOf(expected), integer.value());
    }

    private static void assertFailureContains(EvalResult result, String expected) {
        var failure = assertInstanceOf(EvalResult.Failure.class, result,
                () -> "Expected failure, got " + result);
        assertTrue(failure.error().contains(expected),
                () -> "Expected <" + expected + "> in <" + failure.error() + ">");
    }

    private static void assertBudget(EvalResult result, long cpu, long memory) {
        assertInstanceOf(EvalResult.Success.class, result,
                () -> "Expected success, got " + result);
        assertEquals(new ExBudget(cpu, memory), result.budgetConsumed());
    }
}
