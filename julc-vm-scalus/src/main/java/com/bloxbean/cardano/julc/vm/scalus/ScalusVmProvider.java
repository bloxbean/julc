package com.bloxbean.cardano.julc.vm.scalus;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.core.flat.UplcFlatEncoder;
import com.bloxbean.cardano.julc.vm.*;
import scalus.cardano.ledger.CostModels;
import scalus.cardano.ledger.Language;
import scalus.cardano.ledger.MajorProtocolVersion;
import scalus.uplc.ProgramFlatCodec$;
import scalus.uplc.eval.CountingBudgetSpender;
import scalus.uplc.eval.Log;
import scalus.uplc.eval.MachineParams;
import scalus.uplc.eval.PlutusVM;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * {@link JulcVmProvider} implementation backed by the Scalus CEK machine.
 * <p>
 * Uses FLAT serialization as the bridge between plutus-java and Scalus types.
 * Our {@link Program} is FLAT-encoded, then decoded by Scalus into its own
 * {@link DeBruijnedProgram} for evaluation. This avoids all Scala/Java interop
 * issues with collections, reserved keywords, and type conversions.
 * <p>
 * Discovered via {@link java.util.ServiceLoader} when {@code plutus-vm-scalus}
 * is on the classpath.
 * <p>
 * Note: This provider does not support source maps, execution tracing, or builtin
 * trace collection. {@link EvalOptions} is accepted but ignored — traces in the
 * returned {@link EvalResult} will always be empty.
 *
 * <p>Protocol-aware cost-model configuration is target-bound and published
 * atomically. Explicit-target evaluation remains fail-closed until the
 * certification gates in ADR-033 are complete.</p>
 */
public class ScalusVmProvider implements JulcVmProvider {

    private static final System.Logger LOGGER =
            System.getLogger(ScalusVmProvider.class.getName());

    static final int V3_PV11_DROP_LIST_CPU_INTERCEPT_INDEX = 302;
    static final long SCALUS_MISSING_PARAMETER_SENTINEL = 300_000_000L;
    static final String UNSUPPORTED_TARGET_PREFIX = "Unsupported Scalus ledger target: ";

    // Package-private so tests can assert publication and snapshot identity.
    volatile ScalusConfiguration plutusV1Configuration;
    volatile ScalusConfiguration plutusV2Configuration;
    volatile ScalusConfiguration plutusV3Configuration;

    @Override
    public void setCostModelParams(long[] costModelValues, PlutusLanguage language,
                                   int protocolMajorVersion, int protocolMinorVersion) {
        setCostModelParams(costModelValues, new LedgerEvaluationTarget(
                language, new ProtocolVersion(protocolMajorVersion, protocolMinorVersion)));
    }

    @Override
    public void setCostModelParams(long[] costModelValues, LedgerEvaluationTarget target) {
        Objects.requireNonNull(target, "target");

        // Resolve first. Unknown/future targets throw exactly as the Java provider does.
        var profile = ProtocolFeatureRegistry.resolve(target);
        var scalusLanguage = toScalusLanguage(target.ledgerLanguage());
        var scalusProtocol = new MajorProtocolVersion(target.protocolVersion().major());

        // Scalus 1.1.0 cannot faithfully carry every supplied V1/V2 cost. Publish an
        // explicit unsupported state so configuring all transaction languages remains safe.
        if (target.ledgerLanguage() != PlutusLanguage.PLUTUS_V3) {
            var unsupported = new UnsupportedScalusConfiguration(
                    target,
                    profile,
                    unsupportedReason(target,
                            "supplied V1/V2 cost models are not certified by this adapter"));
            publishConfiguration(target.ledgerLanguage(), unsupported);
            return;
        }

        var normalized = normalizeCostModelParams(costModelValues, target, profile);
        rejectScalusSentinel(normalized, target);
        var machineParams = machineParamsFrom(normalized, scalusLanguage, scalusProtocol);
        var ready = new ReadyScalusConfiguration(
                target, profile, scalusLanguage, scalusProtocol, machineParams);
        verifyReadyConfiguration(ready);

        // This is the only publication point for a ready state. Any exception above
        // leaves the previously published state untouched.
        publishConfiguration(target.ledgerLanguage(), ready);
    }

    static long[] normalizeCostModelParams(
            long[] values, LedgerEvaluationTarget target, ProtocolFeatureProfile profile) {
        Objects.requireNonNull(values, "Cost model parameters must not be null");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(profile, "profile");
        if (!profile.target().equals(target)) {
            throw new IllegalArgumentException("Profile target does not match " + target);
        }
        int expected = profile.costModelSchema().parameterCount();
        if (values.length > expected) {
            LOGGER.log(System.Logger.Level.WARNING,
                    target + " cost model has too many parameters: expected " + expected
                            + ", got " + values.length
                            + "; excess trailing parameters were ignored");
            return Arrays.copyOf(values, expected);
        }

        var normalized = Arrays.copyOf(values, expected);
        if (values.length < expected) {
            Arrays.fill(normalized, values.length, expected, Long.MAX_VALUE);
            LOGGER.log(System.Logger.Level.WARNING,
                    target + " cost model has too few parameters: expected " + expected
                            + ", got " + values.length
                            + "; missing trailing parameters were set to Long.MAX_VALUE");
        }
        return normalized;
    }

    private static void rejectScalusSentinel(
            long[] normalized, LedgerEvaluationTarget target) {
        if (target.ledgerLanguage() == PlutusLanguage.PLUTUS_V3
                && target.protocolVersion().major() == 11
                && normalized[V3_PV11_DROP_LIST_CPU_INTERCEPT_INDEX]
                == SCALUS_MISSING_PARAMETER_SENTINEL) {
            throw new IllegalArgumentException(
                    "Invalid Scalus cost model for " + target
                            + ": dropList-cpu-arguments-intercept must not equal "
                            + SCALUS_MISSING_PARAMETER_SENTINEL
                            + " because Scalus 1.1.0 treats it as a missing-parameter sentinel");
        }
    }

    private static MachineParams machineParamsFrom(
            long[] normalized, Language scalusLanguage,
            MajorProtocolVersion scalusProtocol) {
        var builder = scala.collection.immutable.Vector$.MODULE$.<Object>newBuilder();
        for (long v : normalized) {
            builder.addOne(Long.valueOf(v));
        }
        scala.collection.immutable.IndexedSeq<Object> indexedSeq = builder.result();

        scala.collection.immutable.Map<Object, scala.collection.immutable.IndexedSeq<Object>> map =
                scala.collection.immutable.Map$.MODULE$.<Object, scala.collection.immutable.IndexedSeq<Object>>empty()
                        .updated(scalusLanguage.languageId(), indexedSeq);

        CostModels costModels = new CostModels(map);
        return MachineParams.fromCostModels(costModels, scalusLanguage, scalusProtocol);
    }

    @Override
    public EvalResult evaluate(Program program, PlutusLanguage language, ExBudget budget,
                               EvalOptions options) {
        var configuration = configurationForLanguage(language);
        var failure = compatibilityConfigurationFailure(configuration, language);
        if (failure != null) return failure;
        return evaluateInternal(program, language, asReady(configuration));
    }

    @Override
    public EvalResult evaluateWithArgs(Program program, PlutusLanguage language,
                                       List<PlutusData> args, ExBudget budget,
                                       EvalOptions options) {
        var configuration = configurationForLanguage(language);
        var failure = compatibilityConfigurationFailure(configuration, language);
        if (failure != null) return failure;
        try {
            // FLAT-encode the base program (without args) to bridge to Scalus types
            byte[] flatBytes = UplcFlatEncoder.encodeProgram(program);
            var dbProgram = ProgramFlatCodec$.MODULE$.decodeFlat(flatBytes);

            // Apply each argument directly as a Scalus Data constant.
            // This bypasses CBOR encoding, avoiding Scalus's 64-byte bytestring limit.
            scalus.uplc.Term scalusTerm = dbProgram.term();
            for (var arg : args) {
                scalus.uplc.builtin.Data scalusData = DataConverter.toScalus(arg);
                scalus.uplc.Constant dataConst = new scalus.uplc.Constant.Data(scalusData);
                var emptyAnn = scalus.uplc.UplcAnnotation.empty();
                scalusTerm = scalus.uplc.Term.Apply.apply(
                        scalusTerm,
                        scalus.uplc.Term.Const.apply(dataConst, emptyAnn),
                        emptyAnn);
            }

            return evaluateScalusTerm(scalusTerm, language, asReady(configuration));
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new EvalResult.Failure(errorMsg, ExBudget.ZERO, List.of());
        }
    }

    private EvalResult evaluateInternal(Program program, PlutusLanguage language,
                                        ReadyScalusConfiguration configuration) {
        try {
            // FLAT-encode our Program to bytes
            byte[] flatBytes = UplcFlatEncoder.encodeProgram(program);

            // Decode via Scalus FLAT codec -> DeBruijnedProgram
            var dbProgram = ProgramFlatCodec$.MODULE$.decodeFlat(flatBytes);

            return evaluateScalusTerm(dbProgram.term(), language, configuration);
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new EvalResult.Failure(errorMsg, ExBudget.ZERO, List.of());
        }
    }

    private EvalResult evaluateScalusTerm(scalus.uplc.Term scalusTerm, PlutusLanguage language,
                                          ReadyScalusConfiguration configuration) {
        // Create budget/logger outside try so we can capture partial budget on error
        var budgetSpender = new CountingBudgetSpender();
        var logger = new Log();
        try {
            // Create the appropriate VM
            PlutusVM vm = createVm(language, configuration);

            // Evaluate using evaluateDeBruijnedTerm (general evaluation,
            // does not enforce script return-value semantics like evaluateScriptDebug)
            // Scalus 0.17.0 added a 4th 'tracing' boolean (default false); pass false to preserve
            // the prior 3-arg behaviour (no script return-value enforcement / tracing).
            scalus.uplc.Term scalusResult = vm.evaluateDeBruijnedTerm(
                    scalusTerm, budgetSpender, logger, false);

            // Convert result
            Term resultTerm = TermConverter.fromScalus(scalusResult);
            var budget = budgetSpender.getSpentBudget();
            var consumed = new ExBudget(budget.steps(), budget.memory());
            var traces = List.of(logger.getLogs());

            return new EvalResult.Success(resultTerm, consumed, traces);
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            var budget = budgetSpender.getSpentBudget();
            var consumed = new ExBudget(budget.steps(), budget.memory());
            var traces = List.of(logger.getLogs());
            return new EvalResult.Failure(errorMsg, consumed, traces);
        }
    }

    private PlutusVM createVm(PlutusLanguage language,
                              ReadyScalusConfiguration configuration) {
        if (configuration != null) {
            return createConfiguredVm(configuration);
        }
        return switch (language) {
            case PLUTUS_V1 -> PlutusVM.makePlutusV1VM();
            case PLUTUS_V2 -> PlutusVM.makePlutusV2VM();
            case PLUTUS_V3 -> PlutusVM.makePlutusV3VM();
        };
    }

    private static PlutusVM createConfiguredVm(ReadyScalusConfiguration configuration) {
        if (configuration.scalusLanguage() == Language.PlutusV1) {
            return PlutusVM.makePlutusV1VM(
                    configuration.machineParams(), configuration.scalusProtocol());
        }
        if (configuration.scalusLanguage() == Language.PlutusV2) {
            return PlutusVM.makePlutusV2VM(
                    configuration.machineParams(), configuration.scalusProtocol());
        }
        if (configuration.scalusLanguage() == Language.PlutusV3) {
            return PlutusVM.makePlutusV3VM(
                    configuration.machineParams(), configuration.scalusProtocol());
        }
        throw new IllegalArgumentException(
                "Unsupported Scalus language: " + configuration.scalusLanguage());
    }

    private static void verifyReadyConfiguration(ReadyScalusConfiguration configuration) {
        var vm = createConfiguredVm(configuration);
        if (vm.language() != configuration.scalusLanguage()
                || vm.protocolVersion().version()
                != configuration.target().protocolVersion().major()
                || !vm.semanticVariant().toString()
                .equals(configuration.profile().semanticsVariant().name())) {
            throw new IllegalArgumentException(
                    "Scalus configuration does not match resolved target "
                            + configuration.target());
        }
    }

    static Language toScalusLanguage(PlutusLanguage language) {
        return switch (language) {
            case PLUTUS_V1 -> Language.PlutusV1;
            case PLUTUS_V2 -> Language.PlutusV2;
            case PLUTUS_V3 -> Language.PlutusV3;
        };
    }

    ScalusConfiguration configurationForEvaluation(LedgerEvaluationTarget target) {
        var requestedProfile = ProtocolFeatureRegistry.resolve(target);
        var configuration = configurationForLanguage(target.ledgerLanguage());
        if (configuration == null
                || configuration.target().hasSamePlutusSemantics(target)) {
            return configuration;
        }
        return new UnsupportedScalusConfiguration(
                target,
                requestedProfile,
                unsupportedReason(target,
                        "configured cost model targets " + configuration.target()));
    }

    private ScalusConfiguration configurationForLanguage(PlutusLanguage language) {
        return switch (language) {
            case PLUTUS_V1 -> plutusV1Configuration;
            case PLUTUS_V2 -> plutusV2Configuration;
            case PLUTUS_V3 -> plutusV3Configuration;
        };
    }

    private void publishConfiguration(
            PlutusLanguage language, ScalusConfiguration configuration) {
        switch (language) {
            case PLUTUS_V1 -> plutusV1Configuration = configuration;
            case PLUTUS_V2 -> plutusV2Configuration = configuration;
            case PLUTUS_V3 -> plutusV3Configuration = configuration;
        }
    }

    private static ReadyScalusConfiguration asReady(ScalusConfiguration configuration) {
        return configuration instanceof ReadyScalusConfiguration ready ? ready : null;
    }

    private static EvalResult.Failure compatibilityConfigurationFailure(
            ScalusConfiguration configuration, PlutusLanguage language) {
        if (configuration instanceof UnsupportedScalusConfiguration unsupported) {
            return new EvalResult.Failure(unsupported.reason(), ExBudget.ZERO, List.of());
        }
        if (configuration instanceof ReadyScalusConfiguration ready
                && ready.target().ledgerLanguage() != language) {
            return new EvalResult.Failure(
                    unsupportedReason(ready.target(),
                            "configured language does not match requested " + language),
                    ExBudget.ZERO,
                    List.of());
        }
        return null;
    }

    private static String unsupportedReason(
            LedgerEvaluationTarget target, String detail) {
        return UNSUPPORTED_TARGET_PREFIX + target + ": " + detail;
    }

    @Override
    public String name() {
        return "Scalus";
    }

    @Override
    public int priority() {
        return 50;
    }
}
