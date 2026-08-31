package com.bloxbean.cardano.julc.vm.scalus;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.core.flat.UplcFlatEncoder;
import com.bloxbean.cardano.julc.vm.*;
import scalus.cardano.ledger.CostModels;
import scalus.cardano.ledger.ExUnits;
import scalus.cardano.ledger.Language;
import scalus.cardano.ledger.MajorProtocolVersion;
import scalus.uplc.ProgramFlatCodec$;
import scalus.uplc.eval.BudgetSpender;
import scalus.uplc.eval.CountingBudgetSpender;
import scalus.uplc.eval.Log;
import scalus.uplc.eval.MachineParams;
import scalus.uplc.eval.OutOfExBudgetError;
import scalus.uplc.eval.PlutusVM;
import scalus.uplc.eval.RestrictingBudgetSpender;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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
 * atomically. The candidate matrix maps V3/PV10 to semantics C and V3/PV11 to
 * semantics E, but neither profile is certified with Scalus 1.1.0. Public
 * explicit-target evaluation therefore fails closed with zero consumed budget.
 * V1/V2 explicit targets are also uncertified because there is no pinned
 * corpus. Their language-only compatibility paths nevertheless retain the
 * caller's live cost-model array and protocol target. Scalus 1.1.0 consumes
 * the legacy prices from those arrays but reference-fills PV11-only builtin
 * costs and ignores supplied Constr/Case costs
 * ({@code SCALUS_V1V2_PV11_REFERENCE_FILL}).</p>
 *
 * <p>The five upstream divergences blocking certification are
 * {@code SCALUS_HASHTOGROUP_DST_HIGH_BYTE} and
 * {@code SCALUS_SLICEBYTESTRING_INT64_NARROWING} for both V3 targets, plus
 * {@code SCALUS_MISSING_CARDANO_INTEGER_BOUND_E},
 * {@code SCALUS_MISSING_CARDANO_BYTESTRING_BOUND_E}, and
 * {@code SCALUS_MISSING_WRITEBITS_4096_BOUND_E} for V3/PV11/E. They are pinned
 * by ledger-serializable tests; the adapter does not pre-scan runtime values or
 * otherwise implement partial builtin semantics to conceal them.</p>
 *
 * <p>A configured candidate uses only the normalized model associated with its
 * exact language/protocol-major target. V3/PV11 rejects Scalus's ambiguous
 * {@code 300_000_000} DropList sentinel before publication. The Scalus 1.1.0
 * bundled epoch-645 snapshot is exact for V3/PV11/E, but may become available
 * to the explicit path only together with that target's future certification.
 * Interpreted as V3/PV10/C it has 37 budget mismatches caused by seven snapshot
 * parameters, so that target requires a matching configured model even after
 * its semantic blockers are resolved.</p>
 *
 * <p>Language-only overloads remain an experimental compatibility surface:
 * configured V3 uses its retained target/model, while unconfigured V3 uses
 * Scalus's PV11/E bundled default (unlike Java/Truffle's PV10 default).
 * Configured V1/V2 language-only calls use target-bound Scalus VMs built from
 * the live supplied arrays; this preserves compatibility but is not an exact
 * ledger-parity claim for the reference-filled PV11 cost centres.
 * Unconfigured V1/V2 retain Scalus defaults. A non-null {@link ExBudget}
 * restricts both legacy and candidate execution. Exhaustion reports a {@code null}
 * {@link EvalResult.BudgetExhausted#failedTerm()} because Scalus 1.1.0 does not
 * expose the term whose budget charge failed.</p>
 */
public class ScalusVmProvider implements JulcVmProvider {

    private static final System.Logger LOGGER =
            System.getLogger(ScalusVmProvider.class.getName());

    static final int V3_PV11_DROP_LIST_CPU_INTERCEPT_INDEX = 302;
    static final long SCALUS_MISSING_PARAMETER_SENTINEL = 300_000_000L;
    static final String UNSUPPORTED_TARGET_PREFIX = "Unsupported Scalus ledger target: ";
    static final String SCALUS_V1V2_PV11_REFERENCE_FILL =
            "SCALUS_V1V2_PV11_REFERENCE_FILL";
    static final Set<LedgerEvaluationTarget> CERTIFIED_TARGETS = Set.of();

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

        var normalized = normalizeCostModelParams(costModelValues, target, profile);
        rejectScalusSentinel(normalized, target);
        var machineParams = machineParamsFrom(normalized, scalusLanguage, scalusProtocol);
        var ready = new ReadyScalusConfiguration(
                target, profile, scalusLanguage, scalusProtocol, machineParams);
        verifyReadyConfiguration(ready);
        warnAboutScalusV1V2Pv11ReferenceFill(target);

        // This is the only publication point for a ready state. Any exception above
        // leaves the previously published state untouched.
        publishConfiguration(target.ledgerLanguage(), ready);
    }

    private static void warnAboutScalusV1V2Pv11ReferenceFill(
            LedgerEvaluationTarget target) {
        if (target.ledgerLanguage() != PlutusLanguage.PLUTUS_V3
                && target.protocolVersion().major() >= 11) {
            int consumedLegacyParameters =
                    target.ledgerLanguage() == PlutusLanguage.PLUTUS_V1 ? 166 : 185;
            LOGGER.log(System.Logger.Level.WARNING,
                    SCALUS_V1V2_PV11_REFERENCE_FILL + ": " + target
                            + " received the live normalized cost model, but Scalus 1.1.0 "
                            + "consumes only its first " + consumedLegacyParameters
                            + " V1/V2 parameters and uses vanRossemReferenceD for "
                            + "PV11-only builtin prices");
        }
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
        return evaluateInternal(program, language, asReady(configuration), budget);
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

            return evaluateScalusTerm(
                    scalusTerm, language, asReady(configuration), budget);
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new EvalResult.Failure(errorMsg, ExBudget.ZERO, List.of());
        }
    }

    /**
     * Evaluates for an explicit ledger target. A non-null budget restricts
     * execution; a null budget counts without imposing a limit.
     */
    @Override
    public EvalResult evaluate(Program program, LedgerEvaluationTarget target,
                               ExBudget budget, EvalOptions options) {
        return evaluateExplicitTarget(program, target, List.of(), budget, options, false);
    }

    /**
     * Evaluates with arguments for an explicit ledger target. A non-null budget
     * restricts execution; a null budget counts without imposing a limit.
     */
    @Override
    public EvalResult evaluateWithArgs(Program program, LedgerEvaluationTarget target,
                                       List<PlutusData> args, ExBudget budget,
                                       EvalOptions options) {
        return evaluateExplicitTarget(program, target, args, budget, options, false);
    }

    /**
     * Exercises a candidate profile through the production explicit-target
     * pipeline while deliberately skipping only the certification gate.
     */
    EvalResult evaluateCandidate(Program program, LedgerEvaluationTarget target,
                                 List<PlutusData> args, ExBudget budget,
                                 EvalOptions options) {
        return evaluateExplicitTarget(program, target, args, budget, options, true);
    }

    EvalResult evaluateCandidate(Program program, LedgerEvaluationTarget target,
                                 ExBudget budget, EvalOptions options) {
        return evaluateCandidate(program, target, List.of(), budget, options);
    }

    private EvalResult evaluateExplicitTarget(
            Program program, LedgerEvaluationTarget target, List<PlutusData> args,
            ExBudget budget, EvalOptions options, boolean skipCertificationGate) {
        ReadyScalusConfiguration ready;
        List<PlutusData> argumentSnapshot;
        try {
            Objects.requireNonNull(target, "target");
            var profile = ProtocolFeatureRegistry.resolve(target);
            if (target.ledgerLanguage() != PlutusLanguage.PLUTUS_V3) {
                return preExecutionFailure(target,
                        SCALUS_V1V2_PV11_REFERENCE_FILL
                                + ": explicit V1/V2 profiles remain unsupported; "
                                + "no pinned corpus proves exact budgets, and Scalus 1.1.0 "
                                + "reference-fills or ignores PV11-only costs at PV11");
            }
            if (!skipCertificationGate && !isCertified(target)) {
                return preExecutionFailure(target, "not certified");
            }

            Objects.requireNonNull(program, "program");
            ProgramValidator.validate(program, profile);
            var blsLiteralType = firstBlsLiteralType(program.term());
            if (blsLiteralType != null) {
                return preExecutionFailure(target,
                        "non-ledger-serializable constant type " + blsLiteralType);
            }

            var configuration = configurationForEvaluation(target);
            if (configuration == null) {
                return preExecutionFailure(target,
                        "no matching configured cost model; call setCostModelParams first");
            }
            if (configuration instanceof UnsupportedScalusConfiguration unsupported) {
                return new EvalResult.Failure(
                        targetAwareMessage(target, unsupported.reason()),
                        ExBudget.ZERO, List.of());
            }
            ready = (ReadyScalusConfiguration) configuration;
            argumentSnapshot = List.copyOf(Objects.requireNonNull(args, "args"));
        } catch (RuntimeException e) {
            return preExecutionFailure(target, messageOf(e));
        }

        try {
            byte[] flatBytes = UplcFlatEncoder.encodeProgram(program);
            var dbProgram = ProgramFlatCodec$.MODULE$.decodeFlat(flatBytes);
            scalus.uplc.Term scalusTerm = dbProgram.term();

            // Validation and target/configuration selection intentionally precede
            // argument application so both public overloads share one fail-closed path.
            for (var arg : argumentSnapshot) {
                scalus.uplc.builtin.Data scalusData = DataConverter.toScalus(arg);
                scalus.uplc.Constant dataConst = new scalus.uplc.Constant.Data(scalusData);
                var emptyAnn = scalus.uplc.UplcAnnotation.empty();
                scalusTerm = scalus.uplc.Term.Apply.apply(
                        scalusTerm,
                        scalus.uplc.Term.Const.apply(dataConst, emptyAnn),
                        emptyAnn);
            }

            return evaluateScalusTerm(
                    scalusTerm, target.ledgerLanguage(), ready, budget);
        } catch (Exception e) {
            return new EvalResult.Failure(messageOf(e), ExBudget.ZERO, List.of());
        }
    }

    private static boolean isCertified(LedgerEvaluationTarget target) {
        return CERTIFIED_TARGETS.stream().anyMatch(target::hasSamePlutusSemantics);
    }

    private static EvalResult.Failure preExecutionFailure(
            LedgerEvaluationTarget target, String detail) {
        return new EvalResult.Failure(
                targetAwareMessage(target, detail), ExBudget.ZERO, List.of());
    }

    private static String targetAwareMessage(
            LedgerEvaluationTarget target, String detail) {
        if (detail != null && detail.startsWith(UNSUPPORTED_TARGET_PREFIX)) {
            return detail;
        }
        return unsupportedReason(target, detail == null ? "unknown failure" : detail);
    }

    private static String messageOf(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private static String firstBlsLiteralType(Term term) {
        return switch (term) {
            case Term.Var _, Term.Error _, Term.Builtin _ -> null;
            case Term.Lam lam -> firstBlsLiteralType(lam.body());
            case Term.Apply apply -> firstNonNull(
                    firstBlsLiteralType(apply.function()),
                    firstBlsLiteralType(apply.argument()));
            case Term.Force force -> firstBlsLiteralType(force.term());
            case Term.Delay delay -> firstBlsLiteralType(delay.term());
            case Term.Const constant -> firstBlsLiteralType(constant.value());
            case Term.Constr constr -> constr.fields().stream()
                    .map(ScalusVmProvider::firstBlsLiteralType)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
            case Term.Case caseTerm -> firstNonNull(
                    firstBlsLiteralType(caseTerm.scrutinee()),
                    caseTerm.branches().stream()
                            .map(ScalusVmProvider::firstBlsLiteralType)
                            .filter(Objects::nonNull)
                            .findFirst()
                            .orElse(null));
        };
    }

    private static String firstBlsLiteralType(Constant constant) {
        return switch (constant) {
            case Constant.Bls12_381_G1Element _ -> "bls12_381_G1_element";
            case Constant.Bls12_381_G2Element _ -> "bls12_381_G2_element";
            case Constant.Bls12_381_MlResult _ -> "bls12_381_mlresult";
            case Constant.ListConst list -> list.values().stream()
                    .map(ScalusVmProvider::firstBlsLiteralType)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
            case Constant.PairConst pair -> firstNonNull(
                    firstBlsLiteralType(pair.first()),
                    firstBlsLiteralType(pair.second()));
            case Constant.ArrayConst array -> array.values().stream()
                    .map(ScalusVmProvider::firstBlsLiteralType)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
            default -> null;
        };
    }

    private static String firstNonNull(String first, String second) {
        return first != null ? first : second;
    }

    private EvalResult evaluateInternal(Program program, PlutusLanguage language,
                                        ReadyScalusConfiguration configuration,
                                        ExBudget budget) {
        try {
            // FLAT-encode our Program to bytes
            byte[] flatBytes = UplcFlatEncoder.encodeProgram(program);

            // Decode via Scalus FLAT codec -> DeBruijnedProgram
            var dbProgram = ProgramFlatCodec$.MODULE$.decodeFlat(flatBytes);

            return evaluateScalusTerm(
                    dbProgram.term(), language, configuration, budget);
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new EvalResult.Failure(errorMsg, ExBudget.ZERO, List.of());
        }
    }

    private EvalResult evaluateScalusTerm(scalus.uplc.Term scalusTerm, PlutusLanguage language,
                                          ReadyScalusConfiguration configuration,
                                          ExBudget budgetLimit) {
        // Create budget/logger outside try so we can capture partial budget on error
        BudgetSpender budgetSpender = createBudgetSpender(budgetLimit);
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
            var consumed = consumedBudget(budgetSpender);
            var traces = List.of(logger.getLogs());

            return new EvalResult.Success(resultTerm, consumed, traces);
        } catch (OutOfExBudgetError e) {
            var consumed = consumedBudget(budgetSpender);
            var traces = List.of(logger.getLogs());
            // Scalus exposes the CEK environment/source position but not the
            // term whose charge failed, so failedTerm is intentionally null.
            return new EvalResult.BudgetExhausted(consumed, traces, null);
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            var consumed = consumedBudget(budgetSpender);
            var traces = List.of(logger.getLogs());
            return new EvalResult.Failure(errorMsg, consumed, traces);
        }
    }

    private static BudgetSpender createBudgetSpender(ExBudget budgetLimit) {
        if (budgetLimit == null) {
            return new CountingBudgetSpender();
        }
        long memoryLimit = budgetLimit.memoryUnits();
        long stepLimit = budgetLimit.cpuSteps();
        return new RestrictingBudgetSpender(new ExUnits(memoryLimit, stepLimit));
    }

    private static ExBudget consumedBudget(BudgetSpender budgetSpender) {
        var spent = budgetSpender.getSpentBudget();
        return new ExBudget(spent.steps(), spent.memory());
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
