package com.bloxbean.cardano.julc.vm.scalus;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.core.DefaultUni;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.core.text.UplcParser;
import com.bloxbean.cardano.julc.vm.EvalOptions;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.ExBudget;
import com.bloxbean.cardano.julc.vm.LedgerEvaluationTarget;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Pins known Scalus 1.1.0 BLS result divergences without weakening the
 * ledger-reference goldens. Passing controls prove that BLS values produced
 * inside the CEK machine can cross the adapter after compression to bytes.
 */
class ScalusKnownUpstreamDivergenceTest {

    static final String SCALUS_HASHTOGROUP_DST_HIGH_BYTE =
            "SCALUS_HASHTOGROUP_DST_HIGH_BYTE";
    static final String SCALUS_MISSING_CARDANO_INTEGER_BOUND_E =
            "SCALUS_MISSING_CARDANO_INTEGER_BOUND_E";
    static final String SCALUS_MISSING_CARDANO_BYTESTRING_BOUND_E =
            "SCALUS_MISSING_CARDANO_BYTESTRING_BOUND_E";
    static final String SCALUS_MISSING_WRITEBITS_4096_BOUND_E =
            "SCALUS_MISSING_WRITEBITS_4096_BOUND_E";
    static final String SCALUS_SLICEBYTESTRING_INT64_NARROWING =
            "SCALUS_SLICEBYTESTRING_INT64_NARROWING";
    static final String SCALUS_NEGATIVE_CONSTR_TAG_C =
            "SCALUS_NEGATIVE_CONSTR_TAG_C";

    private static final LedgerEvaluationTarget PV11 =
            LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3);

    @ParameterizedTest(name = "ledger-serializable BLS control {0}")
    @MethodSource("ledgerSerializableControls")
    void lowByteDstHashToGroupControlsMatchLedgerGolden(
            String fixture, DefaultFun compressBuiltin) throws IOException {
        var input = loadProgram(fixture);
        var golden = expectedCompressedPoint(fixture + ".expected");
        var actual = evaluateBytes(compress(input, compressBuiltin));

        assertArrayEquals(golden, actual,
                "Scalus 1.1.0 must match the ledger golden when the DST has no high bytes");
    }

    @ParameterizedTest(name = "known high-byte DST divergence {0}")
    @MethodSource("highByteDstDivergences")
    void highByteDstHashToGroupDivergenceIsExplicitAndPinned(
            String fixture, DefaultFun compressBuiltin,
            String pinnedCurrentScalusHex) throws IOException {
        var input = loadProgram(fixture);
        var golden = expectedCompressedPoint(fixture + ".expected");
        var actual = evaluateBytes(compress(input, compressBuiltin));

        assertNotEquals(HexFormat.of().formatHex(golden), HexFormat.of().formatHex(actual),
                SCALUS_HASHTOGROUP_DST_HIGH_BYTE
                        + ": ledger golden=" + HexFormat.of().formatHex(golden));
        assertEquals(pinnedCurrentScalusHex, HexFormat.of().formatHex(actual),
                SCALUS_HASHTOGROUP_DST_HIGH_BYTE
                        + ": update only after Scalus/upstream characterization");
    }

    @Test
    void derivedHighByteDstSignatureDivergenceIsExplicitAndPinned() throws IOException {
        String fixture =
                "builtin/semantics/bls12_381-cardano-crypto-tests/signature/large-dst/large-dst.uplc";
        var result = success(evaluate(loadProgram(fixture)));
        var actual = assertInstanceOf(Constant.BoolConst.class,
                assertInstanceOf(Term.Const.class, result.resultTerm()).value()).value();

        assertNotEquals(true, actual,
                SCALUS_HASHTOGROUP_DST_HIGH_BYTE + ": ledger golden=true");
        assertEquals(false, actual,
                SCALUS_HASHTOGROUP_DST_HIGH_BYTE
                        + ": pinned Scalus 1.1.0 result must remain explicit");
        var argsActual = boolResult(evaluateWithEmptyArgs(loadProgram(fixture), PV11));
        assertEquals(actual, argsActual,
                SCALUS_HASHTOGROUP_DST_HIGH_BYTE
                        + ": plain and empty-args paths must agree");

        // Also pin the mismatching point, not merely the final equality result.
        var equality = assertInstanceOf(Term.Apply.class, loadProgram(fixture).term());
        var equalAndHash = assertInstanceOf(Term.Apply.class, equality.function());
        var hashExpression = equalAndHash.argument();
        var uncompressGolden = assertInstanceOf(Term.Apply.class, equality.argument());
        var golden = assertInstanceOf(Constant.ByteStringConst.class,
                assertInstanceOf(Term.Const.class, uncompressGolden.argument()).value()).value();
        var pointProgram = new Program(1, 0, 0,
                new Term.Apply(Term.builtin(DefaultFun.Bls12_381_G1_compress), hashExpression));
        var actualPoint = evaluateBytes(pointProgram);

        assertNotEquals(HexFormat.of().formatHex(golden),
                HexFormat.of().formatHex(actualPoint),
                SCALUS_HASHTOGROUP_DST_HIGH_BYTE
                        + ": ledger golden=" + HexFormat.of().formatHex(golden));
        assertEquals(
                "b980667e641c56abb15943e6e25e682b80ca1c5c3310009291822dec5c7cf412"
                        + "fc7e56b18fc06b600fe4c0ca57896e66",
                HexFormat.of().formatHex(actualPoint),
                SCALUS_HASHTOGROUP_DST_HIGH_BYTE
                        + ": pin current Scalus 1.1.0 compressed result");
    }

    @ParameterizedTest(name = "missing E CInteger bound {0}")
    @MethodSource("cardanoIntegerCases")
    void everyPv11CardanoIntegerPositionMissingBoundIsExplicitAndPinned(
            ScalusBoundaryMatrix.IntegerCase boundaryCase) {
        var above = ScalusBoundaryMatrix.CARDANO_INTEGER_LIMIT;
        var below = ScalusBoundaryMatrix.CARDANO_INTEGER_LIMIT.negate().subtract(BigInteger.ONE);
        for (var value : new BigInteger[]{above, below}) {
            // ProtocolSemanticsTest and Plutus Builtins.hs require a semantic
            // failure at E. Variant C is deliberately unbounded.
            assertSuccessAt(boundaryCase.program().apply(value),
                    LedgerEvaluationTarget.pv10(PlutusLanguage.PLUTUS_V3));
            for (var actual : evaluateBoth(boundaryCase.program().apply(value), PV11)) {
                String golden = "ledger golden=semantic failure; " + boundaryCase;
                assertNotEquals(EvalResult.Failure.class, actual.getClass(),
                        SCALUS_MISSING_CARDANO_INTEGER_BOUND_E + ": " + golden);
                assertInstanceOf(EvalResult.Success.class, actual,
                        SCALUS_MISSING_CARDANO_INTEGER_BOUND_E
                                + ": pinned Scalus 1.1.0 outcome; " + golden);
            }
        }
    }

    @ParameterizedTest(name = "missing E CByteString bound {0}")
    @MethodSource("cardanoByteStringCases")
    void everyPv11CardanoByteStringPositionMissingBoundIsExplicitAndPinned(
            ScalusBoundaryMatrix.ByteStringCase boundaryCase) {
        var above = new byte[ScalusBoundaryMatrix.CARDANO_BYTESTRING_LIMIT + 1];
        assertSuccessAt(boundaryCase.program().apply(above),
                LedgerEvaluationTarget.pv10(PlutusLanguage.PLUTUS_V3));
        for (var actual : evaluateBoth(boundaryCase.program().apply(above), PV11)) {
            String golden = "ledger golden=semantic failure; " + boundaryCase;
            assertNotEquals(EvalResult.Failure.class, actual.getClass(),
                    SCALUS_MISSING_CARDANO_BYTESTRING_BOUND_E + ": " + golden);
            assertInstanceOf(EvalResult.Success.class, actual,
                    SCALUS_MISSING_CARDANO_BYTESTRING_BOUND_E
                            + ": pinned Scalus 1.1.0 outcome; " + golden);
        }
    }

    @Test
    void pv11WriteBits4096ByteLimitDivergenceIsExplicitAndPinned() {
        var above = ScalusBoundaryMatrix.apply(DefaultFun.WriteBits,
                Constant.byteString(new byte[4_097]),
                new Constant.ListConst(com.bloxbean.cardano.julc.core.DefaultUni.INTEGER,
                        java.util.List.of()),
                Constant.bool(true));
        assertSuccessAt(above, LedgerEvaluationTarget.pv10(PlutusLanguage.PLUTUS_V3));
        for (var actual : evaluateBoth(above, PV11)) {
            String golden = "ledger golden=semantic failure; WriteBits arg1 short ByteString "
                    + "bound=4096 Builtins.hs:2191";
            assertNotEquals(EvalResult.Failure.class, actual.getClass(),
                    SCALUS_MISSING_WRITEBITS_4096_BOUND_E + ": " + golden);
            assertInstanceOf(EvalResult.Success.class, actual,
                    SCALUS_MISSING_WRITEBITS_4096_BOUND_E
                            + ": pinned Scalus 1.1.0 outcome; " + golden);
        }
    }

    @ParameterizedTest(name = "SliceByteString narrowing {0}")
    @MethodSource("sliceNarrowingCases")
    void sliceByteStringInt64NarrowingDivergenceIsExplicitAndPinned(
            SliceDivergenceCase divergence) {
        var program = sliceProgram(divergence.position(), divergence.value());
        for (var target : new LedgerEvaluationTarget[]{
                LedgerEvaluationTarget.pv10(PlutusLanguage.PLUTUS_V3), PV11}) {
            for (var result : evaluateBoth(program, target)) {
                String golden = "ledger golden=" + (divergence.ledgerHex() == null
                        ? "semantic failure" : "#" + divergence.ledgerHex())
                        + "; SliceByteString arg" + divergence.position()
                        + " signed Int64 Builtins.hs:1301 value=" + divergence.value();
                if (divergence.ledgerHex() == null) {
                    assertNotEquals(EvalResult.Failure.class, result.getClass(),
                            SCALUS_SLICEBYTESTRING_INT64_NARROWING + ": " + golden);
                } else {
                    assertNotEquals(divergence.ledgerHex(),
                            HexFormat.of().formatHex(byteStringResult(result)),
                            SCALUS_SLICEBYTESTRING_INT64_NARROWING + ": " + golden);
                }
                assertArrayEquals(HexFormat.of().parseHex(divergence.scalusHex()),
                        byteStringResult(result),
                        SCALUS_SLICEBYTESTRING_INT64_NARROWING
                                + ": pinned Scalus 1.1.0 outcome; " + golden);
            }
        }
    }

    @Test
    void negativeRuntimeConstructorTagDivergenceIsExplicitAndPinned() {
        var program = negativeRuntimeConstrDataProgram();
        var pv10 = LedgerEvaluationTarget.pv10(PlutusLanguage.PLUTUS_V3);

        for (var result : evaluateBoth(program, pv10)) {
            var failure = assertInstanceOf(EvalResult.Failure.class, result,
                    SCALUS_NEGATIVE_CONSTR_TAG_C
                            + ": variant C ledger semantics construct Data.Constr(-1, [])");
            assertNotEquals(ExBudget.ZERO,
                    failure.consumed(),
                    SCALUS_NEGATIVE_CONSTR_TAG_C
                            + ": runtime divergence must remain a CEK failure");
        }

        for (var result : evaluateBoth(program, PV11)) {
            assertInstanceOf(EvalResult.Failure.class, result,
                    "variant E rejects negative constructor tags");
        }
    }

    @Test
    void negativeConstructorLiteralDivergenceIsExplicitAndPinned() {
        var negative = new PlutusData.ConstrData(BigInteger.valueOf(-1), List.of());
        var program = Program.plutusV3(Term.const_(Constant.data(negative)));
        var pv10 = LedgerEvaluationTarget.pv10(PlutusLanguage.PLUTUS_V3);

        for (var result : evaluateBoth(program, pv10)) {
            var failure = assertInstanceOf(EvalResult.Failure.class, result,
                    SCALUS_NEGATIVE_CONSTR_TAG_C
                            + ": variant C ledger semantics accept a negative Data literal");
            assertEquals(ExBudget.ZERO,
                    failure.consumed(),
                    SCALUS_NEGATIVE_CONSTR_TAG_C
                            + ": literal is rejected by the Scalus bridge before CEK");
        }

        for (var result : evaluateBoth(program, PV11)) {
            assertInstanceOf(EvalResult.Failure.class, result,
                    "variant E rejects negative constructor-tag literals");
        }
    }

    private static Stream<Arguments> ledgerSerializableControls() {
        return Stream.of(
                Arguments.of(
                        "builtin/semantics/bls12_381_G1_hashToGroup/hash/hash.uplc",
                        DefaultFun.Bls12_381_G1_compress),
                Arguments.of(
                        "builtin/semantics/bls12_381_G1_hashToGroup/hash-empty-dst/hash-empty-dst.uplc",
                        DefaultFun.Bls12_381_G1_compress),
                Arguments.of(
                        "builtin/semantics/bls12_381_G2_hashToGroup/hash/hash.uplc",
                        DefaultFun.Bls12_381_G2_compress),
                Arguments.of(
                        "builtin/semantics/bls12_381_G2_hashToGroup/hash-empty-dst/hash-empty-dst.uplc",
                        DefaultFun.Bls12_381_G2_compress));
    }

    private static Stream<Arguments> highByteDstDivergences() {
        return Stream.of(
                Arguments.of(
                        "builtin/semantics/bls12_381_G1_hashToGroup/hash-dst-len-255/hash-dst-len-255.uplc",
                        DefaultFun.Bls12_381_G1_compress,
                        "b842ac8cc7c022e4c59934611a452c38d49835af61b9e29bbefb3eb56ebbbb43"
                                + "3ba675e836729a871e0c9e240520e1a4"),
                Arguments.of(
                        "builtin/semantics/bls12_381_G2_hashToGroup/hash-dst-len-255/hash-dst-len-255.uplc",
                        DefaultFun.Bls12_381_G2_compress,
                        "ab85034bbbffb6cc4af2ed9a1c717c4e98b2783547fb711c73fa747e12603d55"
                                + "f17aaef5a7e9d9846d17d8e8378ae45904793f155168d39087c400823ad443e2"
                                + "56084b749749ba55cad01dd51e296403b38d9e486c90d6cf0eeeb1ec5438bdb9"));
    }

    private static Stream<ScalusBoundaryMatrix.IntegerCase> cardanoIntegerCases() {
        return ScalusBoundaryMatrix.integerCases();
    }

    private static Stream<ScalusBoundaryMatrix.ByteStringCase> cardanoByteStringCases() {
        return ScalusBoundaryMatrix.byteStringCases();
    }

    private static Stream<SliceDivergenceCase> sliceNarrowingCases() {
        var aboveInt64 = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE);
        var belowInt64 = BigInteger.valueOf(Long.MIN_VALUE).subtract(BigInteger.ONE);
        var aboveInt32 = BigInteger.valueOf(Integer.MAX_VALUE).add(BigInteger.ONE);
        var belowInt32 = BigInteger.valueOf(Integer.MIN_VALUE).subtract(BigInteger.ONE);
        return Stream.of(
                new SliceDivergenceCase(1, BigInteger.valueOf(Long.MAX_VALUE), "", "01"),
                new SliceDivergenceCase(1, aboveInt32, "", "01"),
                new SliceDivergenceCase(1, belowInt32, "01", ""),
                new SliceDivergenceCase(1, aboveInt64, null, "01"),
                new SliceDivergenceCase(1, belowInt64, null, "01"),
                new SliceDivergenceCase(2, BigInteger.valueOf(Long.MAX_VALUE), "0102", ""),
                new SliceDivergenceCase(2, aboveInt32, "0102", ""),
                new SliceDivergenceCase(2, belowInt32, "", "0102"),
                new SliceDivergenceCase(2, aboveInt64, null, ""),
                new SliceDivergenceCase(2, belowInt64, null, ""));
    }

    private static Program compress(Program program, DefaultFun compressBuiltin) {
        return new Program(program.major(), program.minor(), program.patch(),
                new Term.Apply(Term.builtin(compressBuiltin), program.term()));
    }

    private static Program sliceProgram(int position, BigInteger value) {
        var bytes = Constant.byteString(new byte[]{1, 2});
        return position == 1
                ? ScalusBoundaryMatrix.apply(DefaultFun.SliceByteString,
                        Constant.integer(value), Constant.integer(1), bytes)
                : ScalusBoundaryMatrix.apply(DefaultFun.SliceByteString,
                        Constant.integer(0), Constant.integer(value), bytes);
    }

    private static Program negativeRuntimeConstrDataProgram() {
        var emptyDataList = new Constant.ListConst(DefaultUni.DATA, List.of());
        return ScalusBoundaryMatrix.apply(
                DefaultFun.ConstrData,
                Constant.integer(BigInteger.valueOf(-1)),
                emptyDataList);
    }

    private static byte[] expectedCompressedPoint(String fixture) throws IOException {
        var constant = assertInstanceOf(Term.Const.class, loadProgram(fixture).term()).value();
        return switch (constant) {
            case Constant.Bls12_381_G1Element g1 -> g1.value();
            case Constant.Bls12_381_G2Element g2 -> g2.value();
            default -> throw new AssertionError("Expected BLS point, got " + constant.type());
        };
    }

    private static byte[] evaluateBytes(Program program) {
        var plain = byteStringResult(evaluate(program));
        var withArgs = byteStringResult(evaluateWithEmptyArgs(program, PV11));
        assertArrayEquals(plain, withArgs,
                "Plain and empty-args candidate paths must agree");
        return plain;
    }

    private static EvalResult evaluate(Program program) {
        return evaluate(program, PV11);
    }

    private static EvalResult evaluate(
            Program program, LedgerEvaluationTarget target) {
        var provider = configuredProvider(target);
        return provider.evaluateCandidate(program, target, null, EvalOptions.DEFAULT);
    }

    private static EvalResult evaluateWithEmptyArgs(
            Program program, LedgerEvaluationTarget target) {
        var provider = configuredProvider(target);
        return provider.evaluateCandidate(
                program, target, java.util.List.of(), null, EvalOptions.DEFAULT);
    }

    private static java.util.List<EvalResult> evaluateBoth(
            Program program, LedgerEvaluationTarget target) {
        return java.util.List.of(
                evaluate(program, target), evaluateWithEmptyArgs(program, target));
    }

    private static ScalusVmProvider configuredProvider(LedgerEvaluationTarget target) {
        var provider = new ScalusVmProvider();
        var values = new long[target.protocolVersion().major() == 10 ? 297 : 350];
        Arrays.fill(values, 1L);
        provider.setCostModelParams(values, target);
        return provider;
    }

    private static byte[] byteStringResult(EvalResult result) {
        return assertInstanceOf(Constant.ByteStringConst.class,
                assertInstanceOf(Term.Const.class, success(result).resultTerm()).value()).value();
    }

    private static boolean boolResult(EvalResult result) {
        return assertInstanceOf(Constant.BoolConst.class,
                assertInstanceOf(Term.Const.class, success(result).resultTerm()).value()).value();
    }

    private static void assertSuccessAt(
            Program program, LedgerEvaluationTarget target) {
        success(evaluate(program, target));
    }

    private static EvalResult.Success success(EvalResult result) {
        return assertInstanceOf(EvalResult.Success.class, result,
                () -> "Expected success, got " + result);
    }

    private static Program loadProgram(String relative) throws IOException {
        try (var input = Objects.requireNonNull(
                ScalusKnownUpstreamDivergenceTest.class.getResourceAsStream(
                        "/conformance/" + relative), relative)) {
            return UplcParser.parseProgram(new String(
                    input.readAllBytes(), StandardCharsets.UTF_8).trim());
        }
    }

    private record SliceDivergenceCase(
            int position, BigInteger value, String ledgerHex, String scalusHex) {
        @Override public String toString() {
            return "arg" + position + "=" + value;
        }
    }
}
