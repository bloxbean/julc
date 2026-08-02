package com.bloxbean.cardano.julc.vm.java.cost;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.vm.BuiltinSemanticsVariant;
import com.bloxbean.cardano.julc.vm.LedgerEvaluationTarget;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;
import com.bloxbean.cardano.julc.vm.ProtocolFeatureRegistry;
import com.bloxbean.cardano.julc.vm.java.CekValue;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for issue #60: builtin-specific argument sizing must match
 * Plutus semantics ({@code NumBytesCostedAsNumWords}, {@code IntegerCostedLiterally},
 * {@code ValueMaxDepth}, {@code DataNodeCount}) instead of generic integer sizing.
 * <p>
 * Expected charge values are computed from the PV10/PV11 default cost model
 * parameters and cross-checked against the official Plutus conformance suite
 * budgets (see {@code PlutusConformanceTest}).
 */
class BuiltinCostSizingTest {

    // === Sizing function unit tests ===

    @Test
    void literalByteSize_matchesNumBytesCostedAsNumWords() {
        // Plutus: ((abs n - 1) `div` 8) + 1, with 0 giving 0
        assertEquals(0, BuiltinCostModel.literalByteSize(BigInteger.ZERO));
        assertEquals(1, BuiltinCostModel.literalByteSize(BigInteger.ONE));
        assertEquals(1, BuiltinCostModel.literalByteSize(BigInteger.valueOf(8)));
        assertEquals(2, BuiltinCostModel.literalByteSize(BigInteger.valueOf(9)));
        assertEquals(4, BuiltinCostModel.literalByteSize(BigInteger.valueOf(32)));
        assertEquals(8, BuiltinCostModel.literalByteSize(BigInteger.valueOf(64)));
        assertEquals(1024, BuiltinCostModel.literalByteSize(BigInteger.valueOf(8192)));
        // Negative values are costed by absolute value (the builtin fails, but
        // the charge happens first)
        assertEquals(1, BuiltinCostModel.literalByteSize(BigInteger.valueOf(-5)));
        assertEquals(8, BuiltinCostModel.literalByteSize(BigInteger.valueOf(-64)));
        // Beyond long range: exact words while they fit, then saturation
        assertEquals(2305843009213693952L,
                BuiltinCostModel.literalByteSize(BigInteger.TWO.pow(64)));
        assertEquals(Long.MAX_VALUE,
                BuiltinCostModel.literalByteSize(BigInteger.TWO.pow(70)));
    }

    @Test
    void literalValue_matchesIntegerCostedLiterally() {
        assertEquals(0, BuiltinCostModel.literalValue(BigInteger.ZERO));
        assertEquals(42, BuiltinCostModel.literalValue(BigInteger.valueOf(42)));
        assertEquals(42, BuiltinCostModel.literalValue(BigInteger.valueOf(-42)));
        assertEquals(Long.MAX_VALUE,
                BuiltinCostModel.literalValue(BigInteger.valueOf(Long.MAX_VALUE)));
        // Saturates instead of wrapping
        assertEquals(Long.MAX_VALUE, BuiltinCostModel.literalValue(BigInteger.TWO.pow(63)));
        assertEquals(Long.MAX_VALUE, BuiltinCostModel.literalValue(BigInteger.TWO.pow(100).negate()));
    }

    @Test
    void integerSize_usesAbsoluteValue() {
        assertEquals(1, BuiltinCostModel.integerSize(BigInteger.ZERO));
        assertEquals(1, BuiltinCostModel.integerSize(BigInteger.ONE));
        assertEquals(1, BuiltinCostModel.integerSize(BigInteger.valueOf(-1)));
        assertEquals(1, BuiltinCostModel.integerSize(BigInteger.TWO.pow(64).subtract(BigInteger.ONE)));
        assertEquals(2, BuiltinCostModel.integerSize(BigInteger.TWO.pow(64)));
        // Java's bitLength() on -2^64 is 64, not 65 — abs() must be applied first
        assertEquals(2, BuiltinCostModel.integerSize(BigInteger.TWO.pow(64).negate()));
        assertEquals(1, BuiltinCostModel.integerSize(BigInteger.TWO.pow(64).subtract(BigInteger.ONE).negate()));
    }

    @Test
    void valueMaxDepth_matchesPlutusBinarySearchDepth() {
        // (integerLog2(outerSize) + 1) + (integerLog2(maxInnerSize) + 1), 0 when empty
        assertEquals(0, BuiltinCostModel.valueMaxDepth(value()));
        assertEquals(2, BuiltinCostModel.valueMaxDepth(value(1)));           // 1 + 1
        assertEquals(3, BuiltinCostModel.valueMaxDepth(value(1, 1)));        // 2 + 1
        assertEquals(3, BuiltinCostModel.valueMaxDepth(value(3)));           // 1 + 2
        assertEquals(7, BuiltinCostModel.valueMaxDepth(value(8, 2, 1, 1)));  // 3 + 4
    }

    @Test
    void dataNodeCount_countsEveryNode() {
        assertEquals(1, BuiltinCostModel.dataNodeCount(new PlutusData.IntData(BigInteger.ONE)));
        assertEquals(1, BuiltinCostModel.dataNodeCount(new PlutusData.BytesData(new byte[32])));
        assertEquals(1, BuiltinCostModel.dataNodeCount(new PlutusData.MapData(List.of())));
        // Map [(B #, Map [(B #, I 1)])] = outer map + key + inner map + key + int = 5
        var inner = new PlutusData.MapData(List.of(new PlutusData.Pair(
                new PlutusData.BytesData(new byte[0]),
                new PlutusData.IntData(BigInteger.ONE))));
        var outer = new PlutusData.MapData(List.of(new PlutusData.Pair(
                new PlutusData.BytesData(new byte[0]), inner)));
        assertEquals(5, BuiltinCostModel.dataNodeCount(outer));
        assertEquals(4, BuiltinCostModel.dataNodeCount(new PlutusData.ConstrData(0, List.of(
                new PlutusData.IntData(BigInteger.ONE),
                new PlutusData.ListData(List.of(new PlutusData.IntData(BigInteger.TWO)))))));
    }

    @Test
    void saturatingArithmetic() {
        assertEquals(Long.MAX_VALUE, CostFunction.satAdd(Long.MAX_VALUE, 1));
        assertEquals(Long.MIN_VALUE, CostFunction.satAdd(Long.MIN_VALUE, -1));
        assertEquals(Long.MAX_VALUE - 1, CostFunction.satAdd(Long.MAX_VALUE, -1));
        assertEquals(Long.MAX_VALUE, CostFunction.satMul(Long.MAX_VALUE, 2));
        assertEquals(Long.MAX_VALUE, CostFunction.satMul(Long.MIN_VALUE, -1));
        assertEquals(Long.MAX_VALUE, CostFunction.satMul(-1, Long.MIN_VALUE));
        assertEquals(Long.MIN_VALUE, CostFunction.satMul(Long.MAX_VALUE, -2));
        assertEquals(0, CostFunction.satMul(0, Long.MAX_VALUE));
    }

    @Test
    void stringSize_countsUnicodeCodePoints() {
        // Plutus Text.length counts code points; Java String.length() counts
        // UTF-16 units — a surrogate-pair emoji must size as 1, not 2
        assertEquals(5, BuiltinCostModel.sizeOfConstant(Constant.string("hello")));
        assertEquals(1, BuiltinCostModel.sizeOfConstant(Constant.string("😀")));
        assertEquals(0, BuiltinCostModel.sizeOfConstant(Constant.string("")));
    }

    @Test
    void textCostedByByteLength_isSelectedOnlyForDEStringArguments() {
        var args = List.<CekValue>of(
                new CekValue.VCon(Constant.string("é")),
                new CekValue.VCon(Constant.string("😀")));

        assertArrayEquals(new long[]{1, 1}, BuiltinCostModel.argSizes(
                BuiltinSemanticsVariant.C, DefaultFun.AppendString, args));
        assertArrayEquals(new long[]{0, 1}, BuiltinCostModel.argSizes(
                BuiltinSemanticsVariant.D, DefaultFun.AppendString, args));
        assertArrayEquals(new long[]{0, 1}, BuiltinCostModel.argSizes(
                BuiltinSemanticsVariant.E, DefaultFun.EqualsString, args));

        var encodeArgs = List.<CekValue>of(
                new CekValue.VCon(Constant.string("é😀")));
        assertArrayEquals(new long[]{2}, BuiltinCostModel.argSizes(
                BuiltinSemanticsVariant.C, DefaultFun.EncodeUtf8, encodeArgs));
        assertArrayEquals(new long[]{1}, BuiltinCostModel.argSizes(
                BuiltinSemanticsVariant.E, DefaultFun.EncodeUtf8, encodeArgs));
    }

    @Test
    void pairSize_usesMaxBoundPoisonValue() {
        var pair = new Constant.PairConst(Constant.integer(1), Constant.integer(2));
        assertEquals(Long.MAX_VALUE, BuiltinCostModel.sizeOfConstant(pair));
    }

    // === Exact charge tests (issue #60 reproduction) ===

    @Test
    void issue60ByteLengthWrappersAreProtocolIndependent() {
        var replicateArgs = List.<CekValue>of(intArg(64), intArg(0));
        var integerToBytesArgs = List.<CekValue>of(
                boolArg(true), intArg(32), intArg(42));

        for (var variant : BuiltinSemanticsVariant.values()) {
            assertArrayEquals(new long[]{8, 1}, BuiltinCostModel.argSizes(
                    variant, DefaultFun.ReplicateByte, replicateArgs));
            assertArrayEquals(new long[]{1, 4, 1}, BuiltinCostModel.argSizes(
                    variant, DefaultFun.IntegerToByteString, integerToBytesArgs));
        }
    }

    @Test
    void replicateByte_chargedByLiteralByteSize() {
        // ReplicateByte cpu = 180194 + 159 * x, mem = 1 + 1 * x
        // with x = ceil(64 / 8) = 8 words, NOT integerSize(64) = 1
        var consumed = charge(DefaultFun.ReplicateByte, intArg(64), intArg(0));
        assertEquals(180194 + 159 * 8, consumed.cpuSteps());
        assertEquals(1 + 8, consumed.memoryUnits());
    }

    @Test
    void replicateByte_issue60Delta() {
        // The BBS validator underspend: 3 × replicateByte(64, 0) was undercharged
        // by cpu 159*7 = 1113 and mem 7 per call (sized 1 instead of 8)
        var correct = charge(DefaultFun.ReplicateByte, intArg(64), intArg(0));
        var oneWord = charge(DefaultFun.ReplicateByte, intArg(8), intArg(0));
        assertEquals(159 * 7, correct.cpuSteps() - oneWord.cpuSteps());
        assertEquals(7, correct.memoryUnits() - oneWord.memoryUnits());
    }

    @Test
    void integerToByteString_widthChargedByLiteralByteSize() {
        // mem = LiteralInYOrLinearInZ: width 32 -> ceil(32/8) = 4 words, NOT 1
        var consumed = charge(DefaultFun.IntegerToByteString,
                boolArg(true), intArg(32), intArg(42));
        // cpu = QuadraticInZ(1293828, 28716, 63) with z = integerSize(42) = 1
        assertEquals(1293828 + 28716 + 63, consumed.cpuSteps());
        assertEquals(4, consumed.memoryUnits());
    }

    @Test
    void integerToByteString_zeroWidthFallsBackToLinearInZ() {
        var consumed = charge(DefaultFun.IntegerToByteString,
                boolArg(true), intArg(0), intArg(42));
        // mem: y == 0 -> intercept 0 + slope 1 * z = integerSize(42) = 1
        assertEquals(1, consumed.memoryUnits());
    }

    @Test
    void dropList_chargedByLiteralCount() {
        // DropList cpu = 116711 + 1957 * n (literal), mem = 4
        var consumed = charge(DefaultFun.DropList,
                intArg(100), listArg(3));
        assertEquals(116711 + 1957 * 100, consumed.cpuSteps());
        assertEquals(4, consumed.memoryUnits());
    }

    @Test
    void dropList_hugeCountSaturates() {
        var consumed = charge(DefaultFun.DropList,
                new CekValue.VCon(Constant.integer(BigInteger.valueOf(Long.MAX_VALUE))), listArg(3));
        assertEquals(Long.MAX_VALUE, consumed.cpuSteps());
    }

    @Test
    void shiftByteString_shiftChargedLiterally() {
        // cpu/mem = LinearInX on the bytestring — the shift size must not affect
        // the charge, but must be sized literally (relevant for future models)
        var small = charge(DefaultFun.ShiftByteString, bytesArg(16), intArg(3));
        var large = charge(DefaultFun.ShiftByteString, bytesArg(16), intArg(1_000_000));
        assertEquals(small.cpuSteps(), large.cpuSteps());
        assertEquals(158519 + 8942 * 2, small.cpuSteps());
        assertEquals(2, small.memoryUnits());
    }

    @Test
    void insertCoin_valueChargedByMaxDepth() {
        // InsertCoin cpu = 356924 + 18413 * depth, mem = 45 + 21 * depth
        // 2 policies × max 1 token -> depth = 2 + 1 = 3
        var consumed = charge(DefaultFun.InsertCoin,
                bytesArg(1), bytesArg(1), intArg(5),
                new CekValue.VCon(value(1, 1)));
        assertEquals(356924 + 18413 * 3, consumed.cpuSteps());
        assertEquals(45 + 21 * 3, consumed.memoryUnits());
    }

    @Test
    void lookupCoin_valueChargedByMaxDepth() {
        // LookupCoin cpu = 219951 + 9444 * depth, mem = 1
        var consumed = charge(DefaultFun.LookupCoin,
                bytesArg(1), bytesArg(1),
                new CekValue.VCon(value(3)));
        assertEquals(219951 + 9444 * 3, consumed.cpuSteps());
        assertEquals(1, consumed.memoryUnits());
    }

    @Test
    void unValueData_chargedByDataNodeCount() {
        // UnValueData cpu = 1000 + 95933 * n + n^2, mem = 1 + 11 * n
        // (params per builtinCostModelC.json after plutus #7617)
        // Map [(B #, Map [(B #, I 1)])] -> 5 nodes
        var inner = new PlutusData.MapData(List.of(new PlutusData.Pair(
                new PlutusData.BytesData(new byte[0]),
                new PlutusData.IntData(BigInteger.ONE))));
        var outer = new PlutusData.MapData(List.of(new PlutusData.Pair(
                new PlutusData.BytesData(new byte[0]), inner)));
        var consumed = charge(DefaultFun.UnValueData,
                new CekValue.VCon(Constant.data(outer)));
        assertEquals(1000 + 95933 * 5 + 25, consumed.cpuSteps());
        assertEquals(1 + 11 * 5, consumed.memoryUnits());
    }

    @Test
    void expModInteger_sizesNegativePowerOfTwoExponentByAbs() {
        // e = -2^64 must be sized 2 words (abs), not 1
        var m = new BigInteger("63587797161187827317");
        var consumed = charge(DefaultFun.ExpModInteger,
                intArg(1654807132907L),
                new CekValue.VCon(Constant.integer(BigInteger.TWO.pow(64).negate())),
                new CekValue.VCon(Constant.integer(m)));
        // cost0 = 607153 + 231697*e*m + 53144*e*m*m with e = 2, m = 2
        assertEquals(607153 + 231697 * 4 + 53144 * 8, consumed.cpuSteps());
    }

    // === Helpers ===

    private static com.bloxbean.cardano.julc.vm.ExBudget charge(DefaultFun fun, CekValue... args) {
        var profile = ProtocolFeatureRegistry.resolve(
                LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3));
        var tracker = new CostTracker(
                DefaultCostModel.defaultMachineCosts(profile),
                DefaultCostModel.defaultBuiltinCostModel(profile),
                profile,
                null);
        tracker.chargeBuiltin(fun, List.of(args));
        return tracker.consumed();
    }

    private static CekValue intArg(long v) {
        return new CekValue.VCon(Constant.integer(v));
    }

    private static CekValue boolArg(boolean v) {
        return new CekValue.VCon(Constant.bool(v));
    }

    private static CekValue bytesArg(int len) {
        return new CekValue.VCon(Constant.byteString(new byte[len]));
    }

    private static CekValue listArg(int len) {
        var items = new java.util.ArrayList<Constant>();
        for (int i = 0; i < len; i++) items.add(Constant.integer(i));
        return new CekValue.VCon(new Constant.ListConst(
                com.bloxbean.cardano.julc.core.DefaultUni.INTEGER, items));
    }

    /** Build a ValueConst with one policy per argument, each holding that many tokens. */
    private static Constant.ValueConst value(int... tokensPerPolicy) {
        var entries = new java.util.ArrayList<Constant.ValueConst.ValueEntry>();
        for (int p = 0; p < tokensPerPolicy.length; p++) {
            var tokens = new java.util.ArrayList<Constant.ValueConst.TokenEntry>();
            for (int t = 0; t < tokensPerPolicy[p]; t++) {
                tokens.add(new Constant.ValueConst.TokenEntry(
                        new byte[] {(byte) t}, BigInteger.ONE));
            }
            entries.add(new Constant.ValueConst.ValueEntry(new byte[] {(byte) p}, tokens));
        }
        return new Constant.ValueConst(entries);
    }
}
