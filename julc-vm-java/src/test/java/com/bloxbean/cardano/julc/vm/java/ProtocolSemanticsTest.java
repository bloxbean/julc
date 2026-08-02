package com.bloxbean.cardano.julc.vm.java;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.core.DefaultUni;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.LedgerEvaluationTarget;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;
import com.bloxbean.cardano.julc.vm.ProtocolVersion;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolSemanticsTest {

    private static final BigInteger CARDANO_LIMIT = BigInteger.ONE.shiftLeft(262143);
    private final JavaVmProvider provider = new JavaVmProvider();

    @Test
    void cardanoIntegerBoundsApplyOnlyToWrappedDEArguments() {
        BigInteger maximum = CARDANO_LIMIT.subtract(BigInteger.ONE);
        BigInteger minimum = CARDANO_LIMIT.negate();

        assertInteger(evaluate(DefaultFun.AddInteger, PlutusLanguage.PLUTUS_V3, 11,
                Constant.integer(maximum), Constant.integer(0)), maximum);
        assertInteger(evaluate(DefaultFun.AddInteger, PlutusLanguage.PLUTUS_V3, 11,
                Constant.integer(minimum), Constant.integer(0)), minimum);

        BigInteger above = CARDANO_LIMIT;
        BigInteger below = minimum.subtract(BigInteger.ONE);
        assertFailure(evaluate(DefaultFun.AddInteger, PlutusLanguage.PLUTUS_V3, 11,
                Constant.integer(above), Constant.integer(0)), "Integer out of bounds");
        assertFailure(evaluate(DefaultFun.LessThanInteger, PlutusLanguage.PLUTUS_V3, 11,
                Constant.integer(below), Constant.integer(0)), "Integer out of bounds");

        // Variant C accepts the same arbitrary Integer, and EqualsInteger is
        // deliberately unwrapped even in D/E.
        assertInteger(evaluate(DefaultFun.AddInteger, PlutusLanguage.PLUTUS_V3, 10,
                Constant.integer(above), Constant.integer(0)), above);
        assertBool(evaluate(DefaultFun.EqualsInteger, PlutusLanguage.PLUTUS_V3, 11,
                Constant.integer(above), Constant.integer(above)), true);
    }

    @Test
    void cardanoByteStringBoundsApplyOnlyAtDeclaredUnliftingPositions() {
        byte[] maximum = new byte[65536];
        byte[] oversized = new byte[65537];

        assertSuccess(evaluate(DefaultFun.AppendByteString, PlutusLanguage.PLUTUS_V3, 11,
                Constant.byteString(maximum), Constant.byteString(new byte[0])));
        assertFailure(evaluate(DefaultFun.AppendByteString, PlutusLanguage.PLUTUS_V3, 11,
                Constant.byteString(oversized), Constant.byteString(new byte[0])),
                "ByteString overflow");
        assertSuccess(evaluate(DefaultFun.AppendByteString, PlutusLanguage.PLUTUS_V3, 10,
                Constant.byteString(oversized), Constant.byteString(new byte[0])));

        // LengthOfByteString does not use CByteString in the pinned Haskell source.
        assertInteger(evaluate(DefaultFun.LengthOfByteString, PlutusLanguage.PLUTUS_V3, 11,
                Constant.byteString(oversized)), BigInteger.valueOf(oversized.length));
    }

    @Test
    void consByteStringSelectsModuloOrWord8ByVariant() {
        Constant integer256 = Constant.integer(256);
        Constant tail = Constant.byteString(new byte[]{1});

        assertBytes(evaluate(DefaultFun.ConsByteString, PlutusLanguage.PLUTUS_V1, 11,
                integer256, tail), new byte[]{0, 1});       // variant D
        assertBytes(evaluate(DefaultFun.ConsByteString, PlutusLanguage.PLUTUS_V2, 10,
                integer256, tail), new byte[]{0, 1});       // variant B
        assertFailure(evaluate(DefaultFun.ConsByteString, PlutusLanguage.PLUTUS_V3, 11,
                integer256, tail), "out of range");        // variant E
        assertFailure(evaluate(DefaultFun.ConsByteString, PlutusLanguage.PLUTUS_V3, 10,
                integer256, tail), "out of range");        // variant C

        assertBytes(evaluate(DefaultFun.ConsByteString, PlutusLanguage.PLUTUS_V1, 11,
                Constant.integer(-1), tail), new byte[]{(byte) 0xff, 1});

        // Word8 unlifting compares the complete Integer; it must not narrow
        // modulo 2^64 before checking the range. Variant D intentionally does
        // use the historical modulo denotation.
        BigInteger twoTo64 = BigInteger.ONE.shiftLeft(64);
        assertFailure(evaluate(DefaultFun.ConsByteString, PlutusLanguage.PLUTUS_V3, 11,
                Constant.integer(twoTo64), tail), "out of range");
        assertBytes(evaluate(DefaultFun.ConsByteString, PlutusLanguage.PLUTUS_V1, 11,
                Constant.integer(twoTo64), tail), new byte[]{0, 1});
    }

    @Test
    void sliceByteStringUnliftsInt64WithoutInt32Truncation() {
        Constant bytes = Constant.byteString(new byte[]{1, 2, 3, 4, 5});

        assertBytes(evaluate(DefaultFun.SliceByteString, PlutusLanguage.PLUTUS_V3, 10,
                Constant.integer(BigInteger.ONE.shiftLeft(31)),
                Constant.integer(5), bytes), new byte[0]);
        assertBytes(evaluate(DefaultFun.SliceByteString, PlutusLanguage.PLUTUS_V3, 10,
                Constant.integer(0), Constant.integer(BigInteger.ONE.shiftLeft(32)),
                bytes), new byte[]{1, 2, 3, 4, 5});
        assertFailure(evaluate(DefaultFun.SliceByteString, PlutusLanguage.PLUTUS_V3, 10,
                Constant.integer(BigInteger.ONE.shiftLeft(64)),
                Constant.integer(1), bytes), "signed 64-bit Int");
    }

    @Test
    void shiftAndRotateUseSignedInt64OnlyInDE() {
        BigInteger beyondInt64 = BigInteger.ONE.shiftLeft(63);
        Constant bytes = Constant.byteString(new byte[]{1});

        assertSuccess(evaluate(DefaultFun.ShiftByteString, PlutusLanguage.PLUTUS_V3, 10,
                bytes, Constant.integer(beyondInt64)));
        assertFailure(evaluate(DefaultFun.ShiftByteString, PlutusLanguage.PLUTUS_V3, 11,
                bytes, Constant.integer(beyondInt64)), "signed 64-bit Int");
        assertFailure(evaluate(DefaultFun.RotateByteString, PlutusLanguage.PLUTUS_V3, 11,
                bytes, Constant.integer(beyondInt64.negate().subtract(BigInteger.ONE))),
                "signed 64-bit Int");

        assertSuccess(evaluate(DefaultFun.ShiftByteString, PlutusLanguage.PLUTUS_V3, 11,
                bytes, Constant.integer(Long.MIN_VALUE)));
    }

    @Test
    void writeBitsGetsTheDE4096ByteInputLimit() {
        Constant bytes = Constant.byteString(new byte[4097]);
        Constant indices = new Constant.ListConst(DefaultUni.INTEGER, List.of());

        assertSuccess(evaluate(DefaultFun.WriteBits, PlutusLanguage.PLUTUS_V3, 10,
                bytes, indices, Constant.bool(true)));
        assertFailure(evaluate(DefaultFun.WriteBits, PlutusLanguage.PLUTUS_V3, 11,
                bytes, indices, Constant.bool(true)), "4096-byte bound");
    }

    @Test
    void bitwiseIndicesAndReplicateArgumentsNeverWrapOnNarrowing() {
        BigInteger twoTo64 = BigInteger.ONE.shiftLeft(64);
        Constant bytes = Constant.byteString(new byte[]{1});

        assertFailure(evaluate(DefaultFun.ReadBit, PlutusLanguage.PLUTUS_V3, 10,
                bytes, Constant.integer(twoTo64)), "signed 64-bit Int");

        var wrappedIndex = new Constant.ListConst(DefaultUni.INTEGER,
                List.of(Constant.integer(twoTo64)));
        assertFailure(evaluate(DefaultFun.WriteBits, PlutusLanguage.PLUTUS_V3, 10,
                bytes, wrappedIndex, Constant.bool(true)), "out of range");

        assertFailure(evaluate(DefaultFun.ReplicateByte, PlutusLanguage.PLUTUS_V3, 10,
                Constant.integer(BigInteger.ONE.shiftLeft(32)), Constant.integer(65)),
                "length exceeds 8192");
        assertFailure(evaluate(DefaultFun.ReplicateByte, PlutusLanguage.PLUTUS_V3, 10,
                Constant.integer(1), Constant.integer(twoTo64.add(BigInteger.valueOf(65)))),
                "out of range");
    }

    @Test
    void constrDataUsesIntegerInBCAndWord64InDE() {
        BigInteger word64Maximum = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
        BigInteger aboveWord64 = word64Maximum.add(BigInteger.ONE);

        assertConstrTag(roundTripConstrTag(
                PlutusLanguage.PLUTUS_V3, 11, BigInteger.ZERO), BigInteger.ZERO);
        assertConstrTag(roundTripConstrTag(
                PlutusLanguage.PLUTUS_V3, 11, word64Maximum), word64Maximum);
        assertConstrTag(roundTripConstrTag(
                PlutusLanguage.PLUTUS_V1, 11, word64Maximum), word64Maximum);
        assertFailure(roundTripConstrTag(
                PlutusLanguage.PLUTUS_V3, 11, BigInteger.ONE.negate()), "Word64");
        assertFailure(roundTripConstrTag(
                PlutusLanguage.PLUTUS_V3, 11, aboveWord64), "Word64");

        // Pre-D/E variants pass an arbitrary Integer to Data.Constr.
        assertConstrTag(roundTripConstrTag(
                PlutusLanguage.PLUTUS_V3, 10, BigInteger.ONE.negate()),
                BigInteger.ONE.negate());
        assertConstrTag(roundTripConstrTag(
                PlutusLanguage.PLUTUS_V2, 10, aboveWord64), aboveWord64);
    }

    private EvalResult roundTripConstrTag(
            PlutusLanguage language, int protocol, BigInteger tag) {
        var fields = new Constant.ListConst(DefaultUni.DATA, List.of());
        Term constr = Term.apply(
                Term.apply(Term.builtin(DefaultFun.ConstrData),
                        Term.const_(Constant.integer(tag))),
                Term.const_(fields));
        Term unConstr = Term.apply(Term.builtin(DefaultFun.UnConstrData), constr);
        Program program = language == PlutusLanguage.PLUTUS_V3
                ? Program.plutusV3(unConstr)
                : new Program(1, 0, 0, unConstr);
        return provider.evaluate(program, new LedgerEvaluationTarget(
                language, new ProtocolVersion(protocol, 0)), null);
    }

    private EvalResult evaluate(DefaultFun fun, PlutusLanguage language, int protocol,
                                Constant... args) {
        Term term = Term.builtin(fun);
        for (var arg : args) term = Term.apply(term, Term.const_(arg));
        Program program = language == PlutusLanguage.PLUTUS_V3
                ? Program.plutusV3(term)
                : new Program(1, 0, 0, term);
        return provider.evaluate(program, new LedgerEvaluationTarget(
                language, new ProtocolVersion(protocol, 0)), null);
    }

    private static EvalResult.Success assertSuccess(EvalResult result) {
        return assertInstanceOf(EvalResult.Success.class, result,
                () -> "Expected success, got " + result);
    }

    private static void assertFailure(EvalResult result, String message) {
        var failure = assertInstanceOf(EvalResult.Failure.class, result,
                () -> "Expected failure, got " + result);
        assertTrue(failure.error().contains(message), failure::error);
    }

    private static void assertInteger(EvalResult result, BigInteger expected) {
        var term = assertInstanceOf(Term.Const.class, assertSuccess(result).resultTerm());
        var integer = assertInstanceOf(Constant.IntegerConst.class, term.value());
        assertEquals(expected, integer.value());
    }

    private static void assertBool(EvalResult result, boolean expected) {
        var term = assertInstanceOf(Term.Const.class, assertSuccess(result).resultTerm());
        var bool = assertInstanceOf(Constant.BoolConst.class, term.value());
        assertEquals(expected, bool.value());
    }

    private static void assertBytes(EvalResult result, byte[] expected) {
        var term = assertInstanceOf(Term.Const.class, assertSuccess(result).resultTerm());
        var bytes = assertInstanceOf(Constant.ByteStringConst.class, term.value());
        assertArrayEquals(expected, bytes.value());
    }

    private static void assertConstrTag(EvalResult result, BigInteger expected) {
        var term = assertInstanceOf(Term.Const.class, assertSuccess(result).resultTerm());
        var pair = assertInstanceOf(Constant.PairConst.class, term.value());
        var tag = assertInstanceOf(Constant.IntegerConst.class, pair.first());
        assertEquals(expected, tag.value());
    }
}
