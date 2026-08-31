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
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScalusProtocolBehaviorTest {

    private static final LedgerEvaluationTarget PV10 =
            LedgerEvaluationTarget.pv10(PlutusLanguage.PLUTUS_V3);
    private static final LedgerEvaluationTarget PV11 =
            LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3);
    private static final BigInteger CARDANO_INTEGER_LIMIT = BigInteger.ONE.shiftLeft(262_143);

    @ParameterizedTest(name = "Batch 6 {0}")
    @MethodSource("batch6Cases")
    void batch6IsRejectedAtPv10AndReachesPinnedPv11Semantics(
            DefaultFun builtin, String fixture) throws IOException {
        Program program;
        Term expected = null;
        if (fixture == null) {
            // A fully saturated MSM needs a list whose element type is BLS,
            // which is not ledger-FLAT-serializable. Apply the serializable
            // scalar-list argument so availability and partial application
            // still cross the production bridge; dedicated compressed-result
            // BLS tests exercise saturated ledger-serializable operations.
            program = Program.plutusV3(new Term.Apply(
                    Term.builtin(builtin),
                    Term.const_(new Constant.ListConst(
                            DefaultUni.INTEGER, List.of(Constant.integer(1))))));
            expected = program.term();
        } else {
            program = loadProgram(fixture);
            expected = loadProgram(fixture + ".expected").term();
        }

        forBothPaths(program, PV10, result -> {
            var failure = assertZeroFailure(result);
            assertTrue(failure.error().contains(PV10.toString()), failure::error);
            assertTrue(failure.error().contains(builtin.toString()), failure::error);
        });

        var pinnedExpected = expected;
        forBothPaths(program, PV11,
                result -> assertEquals(pinnedExpected, success(result).resultTerm()));
    }

    @Test
    void unreleasedMultiIndexArrayIsRejectedAtBothTargets() {
        var program = Program.plutusV3(Term.builtin(DefaultFun.MultiIndexArray));
        for (var target : List.of(PV10, PV11)) {
            forBothPaths(program, target, result -> {
                var failure = assertZeroFailure(result);
                assertTrue(failure.error().contains(target.toString()), failure::error);
                assertTrue(failure.error().contains("MultiIndexArray"), failure::error);
            });
        }
    }

    @Test
    void uplc11ConstrCaseIsAcceptedAtBothTargetsAndUplc12IsRejected() throws IOException {
        // Result 5 is copied from conformance term/case/case-03.
        var caseProgram = loadProgram("term/case/case-03/case-03.uplc");
        var caseExpected = loadProgram("term/case/case-03/case-03.uplc.expected").term();
        for (var target : List.of(PV10, PV11)) {
            forBothPaths(caseProgram, target,
                    result -> assertEquals(caseExpected, success(result).resultTerm()));

            var uplc12 = new Program(1, 2, 0, Term.const_(Constant.unit()));
            forBothPaths(uplc12, target, result -> {
                var failure = assertZeroFailure(result);
                assertTrue(failure.error().contains(target.toString()), failure::error);
                assertTrue(failure.error().contains("UPLC 1.2.0 is not available"),
                        failure::error);
            });
        }
    }

    @ParameterizedTest(name = "case-on-builtin fixture {0}")
    @MethodSource("caseOnBuiltinFixtures")
    void caseOnBuiltinConstantsFailInCekAtPv10AndEvaluateAtPv11(
            String fixture) throws IOException {
        var program = loadProgram(fixture);
        var expected = loadProgram(fixture + ".expected").term();

        forBothPaths(program, PV10, result -> {
            var failure = assertInstanceOf(EvalResult.Failure.class, result);
            assertTrue(failure.consumed().cpuSteps() > 0, failure::error);
            assertTrue(!failure.error().contains("Unsupported Scalus ledger target:"),
                    failure::error);
            assertTrue(failure.error().contains("non-constructor value was scrutinized"),
                    failure::error);
        });
        forBothPaths(program, PV11,
                result -> assertEquals(expected, success(result).resultTerm()));
    }

    @Test
    void caseOnDataFailsInCekAtBothTargets() {
        var program = Program.plutusV3(new Term.Case(
                Term.const_(Constant.data(PlutusData.integer(42))),
                List.of(Term.const_(Constant.integer(0)))));

        forBothPaths(program, PV10, result -> {
            var failure = assertInstanceOf(EvalResult.Failure.class, result);
            assertTrue(failure.consumed().cpuSteps() > 0, failure::error);
            assertTrue(!failure.error().contains("Unsupported Scalus ledger target:"),
                    failure::error);
            assertTrue(failure.error().contains("non-constructor value was scrutinized"),
                    failure::error);
        });
        forBothPaths(program, PV11, result -> {
            var failure = assertInstanceOf(EvalResult.Failure.class, result);
            assertTrue(failure.consumed().cpuSteps() > 0, failure::error);
            assertTrue(!failure.error().contains("Case on builtin constant"), failure::error);
        });
    }

    @Test
    void caseOnBoolPinsBranchOrderLazinessAndOutOfRangeFailure() throws IOException {
        // Branch order is copied from constant-case/bool/bool-01 (False -> 0)
        // and bool-02 (True -> 1).
        for (var fixture : List.of(
                "term/constant-case/bool/bool-01/bool-01.uplc",
                "term/constant-case/bool/bool-02/bool-02.uplc")) {
            var program = loadProgram(fixture);
            var expected = loadProgram(fixture + ".expected").term();
            forBothPaths(program, PV11,
                    result -> assertEquals(expected, success(result).resultTerm()));
        }

        // The selected False branch follows bool-01; putting Error in the True
        // branch proves that the unselected branch stays lazy.
        var lazy = Program.plutusV3(new Term.Case(
                Term.const_(Constant.bool(false)),
                List.of(Term.const_(Constant.integer(0)), new Term.Error())));
        forBothPaths(lazy, PV11, result -> assertInteger(result, BigInteger.ZERO));

        // Out-of-range behavior is copied from constant-case/integer/integer-03.
        var outOfRange = loadProgram(
                "term/constant-case/integer/integer-03/integer-03.uplc");
        forBothPaths(outOfRange, PV11, result -> {
            var failure = assertInstanceOf(EvalResult.Failure.class, result);
            assertTrue(failure.consumed().cpuSteps() > 0, failure::error);
        });
    }

    @Test
    void variantCAndEAreSelectedByBoundedIntegerAndByteStringUnlifting() {
        // Expectations copied from julc-vm-java ProtocolSemanticsTest:
        // cardanoIntegerBoundsApplyOnlyToWrappedDEArguments.
        var hugeAdd = apply(DefaultFun.AddInteger,
                Constant.integer(CARDANO_INTEGER_LIMIT), Constant.integer(0));
        forBothPaths(hugeAdd, PV10,
                result -> assertInteger(result, CARDANO_INTEGER_LIMIT));
        // SCALUS_MISSING_CARDANO_INTEGER_BOUND_E: Scalus 1.1.0 omits
        // the E Cardano-integer argument bound; the dedicated divergence
        // matrix pins every affected position explicitly.
        forBothPaths(hugeAdd, PV11,
                result -> assertInteger(result, CARDANO_INTEGER_LIMIT));

        // Expectations copied from ProtocolSemanticsTest:
        // cardanoByteStringBoundsApplyOnlyAtDeclaredUnliftingPositions.
        var oversized = new byte[65_537];
        var append = apply(DefaultFun.AppendByteString,
                Constant.byteString(oversized), Constant.byteString(new byte[0]));
        forBothPaths(append, PV10,
                result -> assertBytes(result, oversized));
        // SCALUS_MISSING_CARDANO_BYTESTRING_BOUND_E: Scalus 1.1.0 omits
        // the E Cardano-ByteString argument bound; the dedicated divergence
        // matrix keeps the ledger expectation visible.
        forBothPaths(append, PV11,
                result -> assertBytes(result, oversized));
    }

    @ParameterizedTest(name = "CInteger in-range boundary {0}")
    @MethodSource("cardanoIntegerCases")
    void everyCardanoIntegerPositionAcceptsInclusiveBounds(
            ScalusBoundaryMatrix.IntegerCase boundaryCase) {
        var maximum = ScalusBoundaryMatrix.CARDANO_INTEGER_LIMIT.subtract(BigInteger.ONE);
        var minimum = ScalusBoundaryMatrix.CARDANO_INTEGER_LIMIT.negate();
        for (var target : List.of(PV10, PV11)) {
            forBothPaths(boundaryCase.program().apply(maximum), target,
                    ScalusProtocolBehaviorTest::assertSuccess);
            forBothPaths(boundaryCase.program().apply(minimum), target,
                    ScalusProtocolBehaviorTest::assertSuccess);
        }
    }

    @ParameterizedTest(name = "CByteString in-range boundary {0}")
    @MethodSource("cardanoByteStringCases")
    void everyCardanoByteStringPositionAcceptsInclusiveMaximum(
            ScalusBoundaryMatrix.ByteStringCase boundaryCase) {
        var maximum = new byte[ScalusBoundaryMatrix.CARDANO_BYTESTRING_LIMIT];
        for (var target : List.of(PV10, PV11)) {
            forBothPaths(boundaryCase.program().apply(maximum), target,
                    ScalusProtocolBehaviorTest::assertSuccess);
        }
    }

    @Test
    void variantSpecificIntAndByteBoundsMatchJavaReferenceExpectations() {
        BigInteger twoTo64 = BigInteger.ONE.shiftLeft(64);
        BigInteger twoTo31 = BigInteger.ONE.shiftLeft(31);
        var oneByte = Constant.byteString(new byte[]{1});

        // Expectations copied from ProtocolSemanticsTest:
        // shiftAndRotateUseSignedInt64OnlyInDE.
        var shift = apply(DefaultFun.ShiftByteString, oneByte,
                Constant.integer(BigInteger.ONE.shiftLeft(63)));
        forBothPaths(shift, PV10, ScalusProtocolBehaviorTest::assertSuccess);
        forBothPaths(shift, PV11,
                result -> assertSemanticFailure(result, null));

        // Expectations copied from ProtocolSemanticsTest:
        // writeBitsGetsTheDE4096ByteInputLimit.
        var writeBitsMaximum = apply(DefaultFun.WriteBits,
                Constant.byteString(new byte[4_096]),
                new Constant.ListConst(DefaultUni.INTEGER, List.of()),
                Constant.bool(true));
        for (var target : List.of(PV10, PV11)) {
            forBothPaths(writeBitsMaximum, target,
                    ScalusProtocolBehaviorTest::assertSuccess);
        }
        var writeBitsLarge = apply(DefaultFun.WriteBits,
                Constant.byteString(new byte[4_097]),
                new Constant.ListConst(DefaultUni.INTEGER, List.of()),
                Constant.bool(true));
        forBothPaths(writeBitsLarge, PV10, ScalusProtocolBehaviorTest::assertSuccess);
        // ScalusKnownUpstreamDivergenceTest pins Scalus 1.1.0's missing E limit.

        var sliceBytes = Constant.byteString(new byte[]{1, 2});
        for (var target : List.of(PV10, PV11)) {
            for (var start : new BigInteger[]{
                    BigInteger.valueOf(Integer.MIN_VALUE),
                    BigInteger.valueOf(Integer.MAX_VALUE)}) {
                var slice = apply(DefaultFun.SliceByteString,
                        Constant.integer(start), Constant.integer(1), sliceBytes);
                var expected = start.signum() < 0 ? new byte[]{1} : new byte[0];
                forBothPaths(slice, target, result -> assertBytes(result, expected));
            }
            for (var length : new BigInteger[]{
                    BigInteger.valueOf(Integer.MIN_VALUE),
                    BigInteger.valueOf(Integer.MAX_VALUE)}) {
                var slice = apply(DefaultFun.SliceByteString,
                        Constant.integer(0), Constant.integer(length), sliceBytes);
                var expected = length.signum() < 0 ? new byte[0] : new byte[]{1, 2};
                forBothPaths(slice, target, result -> assertBytes(result, expected));
            }
            // Long.MIN_VALUE happens to narrow to zero and therefore matches
            // the ledger result for these two short-input shapes.
            forBothPaths(apply(DefaultFun.SliceByteString,
                            Constant.integer(Long.MIN_VALUE), Constant.integer(1), sliceBytes),
                    target, result -> assertBytes(result, new byte[]{1}));
            forBothPaths(apply(DefaultFun.SliceByteString,
                            Constant.integer(0), Constant.integer(Long.MIN_VALUE), sliceBytes),
                    target, result -> assertBytes(result, new byte[0]));
        }

        // These all fail in both C and E; expectations copied from
        // ProtocolSemanticsTest consByteStringSelectsModuloOrWord8ByVariant,
        // sliceByteStringUnliftsInt64WithoutInt32Truncation, and
        // bitwiseIndicesAndReplicateArgumentsNeverWrapOnNarrowing, plus
        // BitwiseBuiltinsTest.integerToByteStringRejectsWidthThatWouldOverflowInt.
        var alwaysRejected = List.of(
                new RejectedCase("ConsByteString Word8", apply(DefaultFun.ConsByteString,
                        Constant.integer(256), oneByte)),
                new RejectedCase("ConsByteString 32-bit band", apply(DefaultFun.ConsByteString,
                        Constant.integer(twoTo31), oneByte)),
                new RejectedCase("IndexByteString 32-bit band", apply(
                        DefaultFun.IndexByteString, oneByte, Constant.integer(twoTo31))),
                new RejectedCase("ReadBit Int64", apply(
                        DefaultFun.ReadBit, oneByte, Constant.integer(twoTo64))),
                new RejectedCase("ReadBit 32-bit band", apply(
                        DefaultFun.ReadBit, oneByte, Constant.integer(twoTo31))),
                new RejectedCase("WriteBits index", apply(DefaultFun.WriteBits, oneByte,
                        new Constant.ListConst(DefaultUni.INTEGER,
                                List.of(Constant.integer(twoTo64))),
                        Constant.bool(true))),
                new RejectedCase("WriteBits 32-bit band", apply(DefaultFun.WriteBits, oneByte,
                        new Constant.ListConst(DefaultUni.INTEGER,
                                List.of(Constant.integer(twoTo31))),
                        Constant.bool(true))),
                new RejectedCase("ReplicateByte length", apply(DefaultFun.ReplicateByte,
                        Constant.integer(BigInteger.ONE.shiftLeft(32)),
                        Constant.integer(65))),
                new RejectedCase("ReplicateByte length 32-bit band", apply(
                        DefaultFun.ReplicateByte,
                        Constant.integer(twoTo31), Constant.integer(65))),
                new RejectedCase("ReplicateByte Word8", apply(DefaultFun.ReplicateByte,
                        Constant.integer(1),
                        Constant.integer(twoTo64.add(BigInteger.valueOf(65))))),
                new RejectedCase("ReplicateByte Word8 32-bit band", apply(
                        DefaultFun.ReplicateByte,
                        Constant.integer(1), Constant.integer(twoTo31))),
                new RejectedCase("IntegerToByteString width", apply(DefaultFun.IntegerToByteString,
                        Constant.bool(true), Constant.integer(BigInteger.ONE.shiftLeft(32)),
                        Constant.integer(0))),
                new RejectedCase("IntegerToByteString width 32-bit band", apply(
                        DefaultFun.IntegerToByteString,
                        Constant.bool(true), Constant.integer(twoTo31), Constant.integer(0))));
        for (var rejected : alwaysRejected) {
            for (var target : List.of(PV10, PV11)) {
                forBothPaths(rejected.program(), target, result -> {
                    var failure = assertInstanceOf(EvalResult.Failure.class, result,
                            () -> rejected.label() + " at " + target
                                    + " expected semantic failure, got " + result);
                    assertTrue(failure.consumed().cpuSteps() > 0, failure::error);
                });
            }
        }
    }

    @Test
    void multibyteStringResultsStayStableAcrossCostSizingVariants() {
        // ProtocolGatingTest and BuiltinCostSizingTest pin different C/E size
        // metrics; result semantics remain the same and exact costs belong to M6.
        var append = apply(DefaultFun.AppendString,
                Constant.string("é"), Constant.string("😀"));
        var encode = apply(DefaultFun.EncodeUtf8, Constant.string("é😀"));
        for (var target : List.of(PV10, PV11)) {
            forBothPaths(append, target,
                    result -> assertString(result, "é😀"));
            forBothPaths(encode, target,
                    result -> assertBytes(result,
                            "é😀".getBytes(StandardCharsets.UTF_8)));
        }
    }

    private static Stream<Arguments> batch6Cases() {
        return Stream.of(
                batch(DefaultFun.ExpModInteger,
                        "builtin/semantics/expModInteger/expMod-01/expMod-01.uplc"),
                batch(DefaultFun.DropList,
                        "builtin/semantics/dropList/dropList-01/dropList-01.uplc"),
                batch(DefaultFun.LengthOfArray,
                        "builtin/semantics/lengthOfArray/lengthOfArray-01/lengthOfArray-01.uplc"),
                batch(DefaultFun.ListToArray,
                        "builtin/semantics/listToArray/listToArray-01/listToArray-01.uplc"),
                batch(DefaultFun.IndexArray,
                        "builtin/semantics/indexArray/indexArray-01/indexArray-01.uplc"),
                batch(DefaultFun.Bls12_381_G1_multiScalarMul, null),
                batch(DefaultFun.Bls12_381_G2_multiScalarMul, null),
                batch(DefaultFun.InsertCoin,
                        "builtin/semantics/insertCoin/long-key-zero-1/long-key-zero-1.uplc"),
                batch(DefaultFun.LookupCoin,
                        "builtin/semantics/lookupCoin/absent/absent.uplc"),
                batch(DefaultFun.UnionValue,
                        "builtin/semantics/unionValue/cancel-01/cancel-01.uplc"),
                batch(DefaultFun.ValueContains,
                        "builtin/semantics/valueContains/ccy-missing/ccy-missing.uplc"),
                batch(DefaultFun.ValueData,
                        "builtin/semantics/valueData/empty/empty.uplc"),
                batch(DefaultFun.UnValueData,
                        "builtin/semantics/unValueData/empty/empty.uplc"),
                batch(DefaultFun.ScaleValue,
                        "builtin/semantics/scaleValue/by-neg/by-neg.uplc"));
    }

    private static Stream<ScalusBoundaryMatrix.IntegerCase> cardanoIntegerCases() {
        return ScalusBoundaryMatrix.integerCases();
    }

    private static Stream<ScalusBoundaryMatrix.ByteStringCase> cardanoByteStringCases() {
        return ScalusBoundaryMatrix.byteStringCases();
    }

    private static Arguments batch(DefaultFun fun, String fixture) {
        return Arguments.of(fun, fixture);
    }

    private static Stream<Arguments> caseOnBuiltinFixtures() {
        return Stream.of(
                "term/constant-case/integer/integer-02/integer-02.uplc",
                "term/constant-case/bool/bool-02/bool-02.uplc",
                "term/constant-case/unit/unit-01/unit-01.uplc",
                "term/constant-case/list/list-01/list-01.uplc",
                "term/constant-case/pair/pair-01/pair-01.uplc")
                .map(Arguments::of);
    }

    private static Program loadProgram(String relative) throws IOException {
        try (var input = Objects.requireNonNull(
                ScalusProtocolBehaviorTest.class.getResourceAsStream(
                        "/conformance/" + relative), relative)) {
            return UplcParser.parseProgram(new String(
                    input.readAllBytes(), StandardCharsets.UTF_8).trim());
        }
    }

    private static void forBothPaths(
            Program program, LedgerEvaluationTarget target,
            Consumer<EvalResult> assertion) {
        var provider = configuredProvider(target);
        assertion.accept(provider.evaluateCandidate(
                program, target, null, EvalOptions.DEFAULT));
        assertion.accept(provider.evaluateCandidate(
                program, target, List.of(), null, EvalOptions.DEFAULT));
    }

    private static ScalusVmProvider configuredProvider(LedgerEvaluationTarget target) {
        var provider = new ScalusVmProvider();
        var values = new long[target.protocolVersion().major() == 10 ? 297 : 350];
        Arrays.fill(values, 1L);
        provider.setCostModelParams(values, target);
        return provider;
    }

    private static Program apply(DefaultFun fun, Constant... args) {
        Term term = Term.builtin(fun);
        for (var arg : args) {
            term = Term.apply(term, Term.const_(arg));
        }
        return Program.plutusV3(term);
    }

    private static EvalResult.Success success(EvalResult result) {
        return assertInstanceOf(EvalResult.Success.class, result,
                () -> "Expected success, got " + result);
    }

    private static void assertSuccess(EvalResult result) {
        success(result);
    }

    private static EvalResult.Failure assertZeroFailure(EvalResult result) {
        var failure = assertInstanceOf(EvalResult.Failure.class, result,
                () -> "Expected zero-budget failure, got " + result);
        assertEquals(ExBudget.ZERO, failure.consumed());
        return failure;
    }

    private static void assertSemanticFailure(EvalResult result, String detail) {
        var failure = assertInstanceOf(EvalResult.Failure.class, result,
                () -> "Expected semantic failure, got " + result);
        assertTrue(failure.consumed().cpuSteps() > 0, failure::error);
        if (detail != null) {
            assertTrue(failure.error().contains(detail), failure::error);
        }
    }

    private static void assertInteger(EvalResult result, BigInteger expected) {
        var term = assertInstanceOf(Term.Const.class, success(result).resultTerm());
        assertEquals(expected,
                assertInstanceOf(Constant.IntegerConst.class, term.value()).value());
    }

    private static void assertBytes(EvalResult result, byte[] expected) {
        var term = assertInstanceOf(Term.Const.class, success(result).resultTerm());
        assertArrayEquals(expected,
                assertInstanceOf(Constant.ByteStringConst.class, term.value()).value());
    }

    private static void assertString(EvalResult result, String expected) {
        var term = assertInstanceOf(Term.Const.class, success(result).resultTerm());
        assertEquals(expected,
                assertInstanceOf(Constant.StringConst.class, term.value()).value());
    }

    private record RejectedCase(String label, Program program) {}
}
