package com.bloxbean.cardano.julc.vm.scalus;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.vm.EvalOptions;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.ExBudget;
import com.bloxbean.cardano.julc.vm.LedgerEvaluationTarget;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;
import com.bloxbean.cardano.julc.vm.ProtocolVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScalusExplicitTargetPipelineTest {

    @Test
    void certificationSetIsImmutableAndEmptyInMilestoneThree() {
        assertTrue(ScalusVmProvider.CERTIFIED_TARGETS.isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> ScalusVmProvider.CERTIFIED_TARGETS.add(
                        LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3)));
    }

    @ParameterizedTest(name = "public explicit {0} fails at the certification gate")
    @MethodSource("knownTargets")
    void publicExplicitOverloadsFailClosedBeforeBridgeOrCek(
            LedgerEvaluationTarget target) {
        var provider = configuredV3Provider(target);
        var program = Program.plutusV3(new Term.Error());

        var plain = assertThrows(UnsupportedOperationException.class,
                () -> provider.evaluate(program, target, new ExBudget(1, 1)));
        var withArgs = assertThrows(UnsupportedOperationException.class,
                () -> provider.evaluateWithArgs(
                        program, target, Arrays.asList((PlutusData) null),
                        new ExBudget(1, 1)));

        assertTrue(plain.getMessage().startsWith(
                "Scalus provider does not support protocol-aware evaluation for " + target));
        assertTrue(plain.getMessage().endsWith(": not certified"));
        assertEquals(plain.getMessage(), withArgs.getMessage());
    }

    @Test
    void registryFailuresPrecedeTheCertificationGate() {
        var provider = new ScalusVmProvider();
        var pv12 = target(PlutusLanguage.PLUTUS_V3, 12);
        var beforeIntroduction = target(PlutusLanguage.PLUTUS_V3, 8);
        var program = Program.plutusV3(new Term.Error());

        assertPreExecutionFailure(provider.evaluate(program, pv12, null), pv12,
                "newer than the supported PV11 profile");
        assertPreExecutionFailure(provider.evaluate(program, beforeIntroduction, null),
                beforeIntroduction, "introduced in PV9");
    }

    @Test
    void testkitShapedNegativeAssertionCannotTreatUncertifiedProfileAsScriptFailure() {
        var provider = new ScalusVmProvider();
        var target = LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3);
        var validator = Program.plutusV3(Term.lam("ctx", new Term.Error()));
        var args = List.of(PlutusData.integer(42));

        assertThrows(UnsupportedOperationException.class,
                () -> provider.evaluateWithArgs(
                        validator, target, args, null, EvalOptions.DEFAULT));
    }

    @Test
    void candidateRequiresMatchingConfiguredCostModelWithoutBundledFallback() {
        var provider = new ScalusVmProvider();
        var target = LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3);

        var result = provider.evaluateCandidate(
                Program.plutusV3(new Term.Error()), target, null, EvalOptions.DEFAULT);

        assertPreExecutionFailure(result, target,
                "no matching configured cost model; call setCostModelParams first");
    }

    @ParameterizedTest(name = "configured PV{0} cannot evaluate candidate PV{1}")
    @MethodSource("mismatchedV3Targets")
    void candidateRejectsConfiguredRequestedMajorMismatch(
            int configuredMajor, int requestedMajor) {
        var provider = new ScalusVmProvider();
        var configured = target(PlutusLanguage.PLUTUS_V3, configuredMajor);
        var requested = target(PlutusLanguage.PLUTUS_V3, requestedMajor);
        provider.setCostModelParams(
                ones(configuredMajor == 10 ? 297 : 350), configured);

        var result = provider.evaluateCandidate(
                Program.plutusV3(new Term.Error()), requested, null, EvalOptions.DEFAULT);

        assertPreExecutionFailure(result, requested,
                "configured cost model targets " + configured);
    }

    @Test
    void candidateValidationRejectsUnavailableBuiltinBeforeBridge() {
        var provider = configuredV3Provider(
                LedgerEvaluationTarget.pv10(PlutusLanguage.PLUTUS_V3));
        var target = LedgerEvaluationTarget.pv10(PlutusLanguage.PLUTUS_V3);
        var program = Program.plutusV3(Term.builtin(DefaultFun.ExpModInteger));

        var result = provider.evaluateCandidate(
                program, target, null, EvalOptions.DEFAULT);

        assertPreExecutionFailure(result, target, "ExpModInteger is not available");
    }

    @Test
    void candidateValidationRejectsIllegalUplcVersionAndConstrForm() {
        var provider = configuredV3Provider(
                LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3));
        var target = LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3);
        var illegalVersion = new Program(1, 2, 0, Term.const_(Constant.unit()));
        var illegalConstr = new Program(1, 0, 0, new Term.Constr(0, List.of()));

        assertPreExecutionFailure(provider.evaluateCandidate(
                        illegalVersion, target, null, EvalOptions.DEFAULT),
                target, "UPLC 1.2.0 is not available");
        assertPreExecutionFailure(provider.evaluateCandidate(
                        illegalConstr, target, null, EvalOptions.DEFAULT),
                target, "Constr/Case terms require UPLC 1.1.0");
    }

    @ParameterizedTest(name = "candidate rejects literal {1}")
    @MethodSource("blsLiterals")
    void candidateRejectsEveryNonLedgerSerializableBlsLiteral(
            Constant literal, String expectedType) {
        var target = LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3);
        var provider = configuredV3Provider(target);
        var nested = new Constant.ListConst(literal.type(), List.of(literal));
        var program = Program.plutusV3(Term.const_(nested));
        var invalidArgument = new PlutusData.ConstrData(BigInteger.ONE.negate(), List.of());

        var result = provider.evaluateCandidate(
                program, target, List.of(invalidArgument), null, EvalOptions.DEFAULT);

        assertPreExecutionFailure(result, target,
                "non-ledger-serializable constant type " + expectedType);
    }

    @ParameterizedTest(name = "configured candidate {0} remains unsupported")
    @MethodSource("configuredV1V2Targets")
    void candidateV1AndV2PathRemainsUnsupported(
            LedgerEvaluationTarget target, int parameterCount) {
        var provider = new ScalusVmProvider();
        provider.setCostModelParams(ones(parameterCount), target);

        var result = provider.evaluateCandidate(
                additionProgram(target.ledgerLanguage()),
                target, null, EvalOptions.DEFAULT);

        assertPreExecutionFailure(result, target,
                ScalusVmProvider.SCALUS_V1V2_PV11_REFERENCE_FILL);
        assertPreExecutionFailure(result, target, "no pinned corpus");
    }

    @ParameterizedTest(name = "configured public {0} remains uncertified")
    @MethodSource("configuredV1V2Targets")
    void publicV1AndV2TargetsRemainUncertifiedWithReadyConfiguration(
            LedgerEvaluationTarget target, int parameterCount) {
        var provider = new ScalusVmProvider();
        provider.setCostModelParams(ones(parameterCount), target);

        var plain = assertThrows(UnsupportedOperationException.class,
                () -> provider.evaluate(
                        additionProgram(target.ledgerLanguage()), target, null));
        var withArgs = assertThrows(UnsupportedOperationException.class,
                () -> provider.evaluateWithArgs(
                        additionProgram(target.ledgerLanguage()), target, List.of(), null));

        assertTrue(plain.getMessage().startsWith(
                "Scalus provider does not support protocol-aware evaluation for " + target));
        assertTrue(plain.getMessage().contains(
                ScalusVmProvider.SCALUS_V1V2_PV11_REFERENCE_FILL));
        assertTrue(plain.getMessage().contains("no pinned corpus"));
        assertEquals(plain.getMessage(), withArgs.getMessage());
    }

    @ParameterizedTest(name = "candidate plain and args paths agree for V3/PV{0}")
    @MethodSource("v3Profiles")
    void plainAndArgumentCandidatePathsHaveIdenticalSuccessfulOutcomes(
            int protocolMajor) {
        var target = target(PlutusLanguage.PLUTUS_V3, protocolMajor);
        var provider = configuredV3Provider(target);
        var program = additionProgram();

        var plain = provider.evaluateCandidate(
                program, target, null, EvalOptions.DEFAULT);
        var withArgs = provider.evaluateCandidate(
                program, target, List.of(), null, EvalOptions.DEFAULT);

        assertEquals(plain, withArgs);
        assertInstanceOf(EvalResult.Success.class, plain);
        assertEquals(new ExBudget(8, 8), plain.budgetConsumed());
    }

    @ParameterizedTest(name = "candidate args are applied after selection for V3/PV{0}")
    @MethodSource("v3Profiles")
    void argumentBearingCandidateAppliesDataAfterTargetSelection(int protocolMajor) {
        var target = target(PlutusLanguage.PLUTUS_V3, protocolMajor);
        var provider = configuredV3Provider(target);
        var identity = Program.plutusV3(Term.lam("arg", Term.var(1)));
        var argument = PlutusData.integer(42);

        var result = provider.evaluateCandidate(
                identity, target, List.of(argument), null, EvalOptions.DEFAULT);

        var success = assertInstanceOf(EvalResult.Success.class, result);
        var resultConstant = assertInstanceOf(Term.Const.class, success.resultTerm());
        assertEquals(argument,
                assertInstanceOf(Constant.DataConst.class, resultConstant.value()).value());
    }

    @Test
    void protocolMinorDifferenceRetainsTheConfiguredMajorSemantics() {
        var configured = LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3);
        var requested = new LedgerEvaluationTarget(
                PlutusLanguage.PLUTUS_V3, new ProtocolVersion(11, 7));
        var provider = configuredV3Provider(configured);

        var result = provider.evaluateCandidate(
                additionProgram(), requested, null, EvalOptions.DEFAULT);

        assertInstanceOf(EvalResult.Success.class, result);
        assertEquals(new ExBudget(8, 8), result.budgetConsumed());
    }

    @Test
    void pv11OnlyBuiltinSucceedsAtPv11AndFailsValidationAtPv10OnBothPaths() {
        var program = expModProgram();
        var pv10 = LedgerEvaluationTarget.pv10(PlutusLanguage.PLUTUS_V3);
        var pv11 = LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3);
        var provider10 = configuredV3Provider(pv10);
        var provider11 = configuredV3Provider(pv11);

        for (var result : List.of(
                provider10.evaluateCandidate(program, pv10, null, EvalOptions.DEFAULT),
                provider10.evaluateCandidate(
                        program, pv10, List.of(), null, EvalOptions.DEFAULT))) {
            assertPreExecutionFailure(result, pv10, "ExpModInteger is not available");
        }

        var plain11 = provider11.evaluateCandidate(
                program, pv11, null, EvalOptions.DEFAULT);
        var withArgs11 = provider11.evaluateCandidate(
                program, pv11, List.of(), null, EvalOptions.DEFAULT);
        assertEquals(plain11, withArgs11);
        var success = assertInstanceOf(EvalResult.Success.class, plain11);
        var resultConstant = assertInstanceOf(Term.Const.class, success.resultTerm());
        assertEquals(BigInteger.ONE,
                assertInstanceOf(Constant.IntegerConst.class, resultConstant.value()).value());
    }

    @Test
    void configuredLanguageOnlyPathValidatesBuiltinAvailabilityBeforeBridge() {
        var program = expModProgram();
        var pv10 = LedgerEvaluationTarget.pv10(PlutusLanguage.PLUTUS_V3);
        var pv11 = LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3);
        var provider10 = configuredV3Provider(pv10);
        var provider11 = configuredV3Provider(pv11);

        for (var result : List.of(
                provider10.evaluate(program, PlutusLanguage.PLUTUS_V3, null),
                provider10.evaluateWithArgs(
                        program, PlutusLanguage.PLUTUS_V3, List.of(), null))) {
            var failure = assertInstanceOf(EvalResult.Failure.class, result);
            assertEquals(ExBudget.ZERO, failure.consumed());
            assertTrue(failure.error().contains("ExpModInteger is not available"));
        }

        assertInstanceOf(EvalResult.Success.class,
                provider11.evaluate(program, PlutusLanguage.PLUTUS_V3, null));
        assertInstanceOf(EvalResult.Success.class,
                provider11.evaluateWithArgs(
                        program, PlutusLanguage.PLUTUS_V3, List.of(), null));
    }

    @Test
    void deeplyNestedProgramsFailDeterministicallyAcrossScalusBridgePaths()
            throws InterruptedException {
        var target = LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3);
        var provider = configuredV3Provider(target);
        var program = deeplyNestedProgram(6_000);

        var legacyPlain = evaluateOnSmallStack(() -> provider.evaluate(
                program, PlutusLanguage.PLUTUS_V3, null));
        var legacyArgs = evaluateOnSmallStack(() -> provider.evaluateWithArgs(
                program, PlutusLanguage.PLUTUS_V3, List.of(), null));
        var candidate = evaluateOnSmallStack(() -> provider.evaluateCandidate(
                program, target, null, EvalOptions.DEFAULT));

        assertSerializationDepthFailure(legacyPlain, null);
        assertSerializationDepthFailure(legacyArgs, null);
        assertSerializationDepthFailure(candidate, target);
    }

    @Test
    void sentinelIndexTracksScalusPlutusV3ParameterDeclarationOrder() {
        long marker = 987_654_321L;
        var values = ones(350);
        values[ScalusVmProvider.V3_PV11_DROP_LIST_CPU_INTERCEPT_INDEX] = marker;
        var builder = scala.collection.immutable.Vector$.MODULE$.<Object>newBuilder();
        for (long value : values) builder.addOne(Long.valueOf(value));

        var params = scalus.uplc.PlutusV3Params.fromSeq().apply(builder.result());

        assertEquals(marker,
                params.dropList$minuscpu$minusarguments$minusintercept());
    }

    private static Stream<Arguments> knownTargets() {
        return Stream.of(
                Arguments.of(LedgerEvaluationTarget.pv10(PlutusLanguage.PLUTUS_V3)),
                Arguments.of(LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3)));
    }

    private static Stream<Arguments> mismatchedV3Targets() {
        return Stream.of(Arguments.of(10, 11), Arguments.of(11, 10));
    }

    private static Stream<Arguments> blsLiterals() {
        return Stream.of(
                Arguments.of(new Constant.Bls12_381_G1Element(new byte[]{1}),
                        "bls12_381_G1_element"),
                Arguments.of(new Constant.Bls12_381_G2Element(new byte[]{2}),
                        "bls12_381_G2_element"),
                Arguments.of(new Constant.Bls12_381_MlResult(new byte[]{3}),
                        "bls12_381_mlresult"));
    }

    private static Stream<Arguments> configuredV1V2Targets() {
        return Stream.of(
                Arguments.of(LedgerEvaluationTarget.pv10(PlutusLanguage.PLUTUS_V1), 166),
                Arguments.of(LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V1), 332),
                Arguments.of(LedgerEvaluationTarget.pv10(PlutusLanguage.PLUTUS_V2), 185),
                Arguments.of(LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V2), 332));
    }

    private static Stream<Arguments> v1V2Targets() {
        return configuredV1V2Targets().map(arguments ->
                Arguments.of(arguments.get()[0]));
    }

    private static Stream<Arguments> v3Profiles() {
        return Stream.of(Arguments.of(10), Arguments.of(11));
    }

    private static ScalusVmProvider configuredV3Provider(
            LedgerEvaluationTarget target) {
        var provider = new ScalusVmProvider();
        provider.setCostModelParams(
                ones(target.protocolVersion().major() == 10 ? 297 : 350), target);
        return provider;
    }

    private static LedgerEvaluationTarget target(
            PlutusLanguage language, int protocolMajor) {
        return new LedgerEvaluationTarget(language, new ProtocolVersion(protocolMajor, 0));
    }

    private static long[] ones(int count) {
        var values = new long[count];
        Arrays.fill(values, 1L);
        return values;
    }

    private static Program additionProgram() {
        return additionProgram(PlutusLanguage.PLUTUS_V3);
    }

    private static Program additionProgram(PlutusLanguage language) {
        var term = Term.apply(
                Term.apply(Term.builtin(DefaultFun.AddInteger),
                        Term.const_(Constant.integer(2))),
                Term.const_(Constant.integer(3)));
        return switch (language) {
            case PLUTUS_V1 -> Program.plutusV1(term);
            case PLUTUS_V2 -> Program.plutusV2(term);
            case PLUTUS_V3 -> Program.plutusV3(term);
        };
    }

    private static Program expModProgram() {
        Term term = Term.builtin(DefaultFun.ExpModInteger);
        for (var constant : List.of(
                Constant.integer(2), Constant.integer(8), Constant.integer(17))) {
            term = Term.apply(term, Term.const_(constant));
        }
        return Program.plutusV3(term);
    }

    private static Program deeplyNestedProgram(int depth) {
        Term term = Term.const_(Constant.unit());
        for (int i = 0; i < depth; i++) {
            term = Term.delay(term);
        }
        return Program.plutusV3(term);
    }

    private static EvalResult evaluateOnSmallStack(Supplier<EvalResult> evaluation)
            throws InterruptedException {
        var result = new AtomicReference<EvalResult>();
        var thrown = new AtomicReference<Throwable>();
        var thread = new Thread(null, () -> {
            try {
                result.set(evaluation.get());
            } catch (Throwable t) {
                thrown.set(t);
            }
        }, "scalus-serialization-depth", 512 * 1024L);
        thread.start();
        thread.join(10_000);
        assertFalse(thread.isAlive(), "deep-program evaluation did not terminate");
        if (thrown.get() != null) {
            fail("Scalus provider leaked an error from its bridge", thrown.get());
        }
        return result.get();
    }

    private static void assertSerializationDepthFailure(
            EvalResult result, LedgerEvaluationTarget target) {
        var failure = assertInstanceOf(EvalResult.Failure.class, result);
        assertEquals(ExBudget.ZERO, failure.consumed());
        if (target == null) {
            assertEquals(ScalusVmProvider.PROGRAM_NESTING_EXCEEDS_SERIALIZATION_DEPTH,
                    failure.error());
        } else {
            assertEquals(ScalusVmProvider.UNSUPPORTED_TARGET_PREFIX + target + ": "
                            + ScalusVmProvider.PROGRAM_NESTING_EXCEEDS_SERIALIZATION_DEPTH,
                    failure.error());
        }
    }

    private static void assertPreExecutionFailure(
            EvalResult result, LedgerEvaluationTarget target, String expectedDetail) {
        var failure = assertInstanceOf(EvalResult.Failure.class, result,
                () -> "Expected pre-execution failure, got " + result);
        assertEquals(ExBudget.ZERO, failure.consumed());
        assertTrue(failure.error().startsWith(
                ScalusVmProvider.UNSUPPORTED_TARGET_PREFIX + target));
        assertTrue(failure.error().contains(expectedDetail),
                () -> "Expected <" + expectedDetail + "> in <" + failure.error() + ">");
    }
}
