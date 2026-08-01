package com.bloxbean.cardano.julc.vm.java;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.core.source.SourceMap;
import com.bloxbean.cardano.julc.vm.*;
import com.bloxbean.cardano.julc.vm.java.cost.*;

import java.util.List;

/**
 * Pure Java {@link JulcVmProvider} implementation — a direct CEK machine interpreter.
 * <p>
 * Evaluates UPLC terms directly without any external dependency (no Scalus, no FLAT bridge).
 * Uses an iterative CEK machine with an explicit stack for deep recursion safety.
 * <p>
 * Priority 100 — preferred over the Scalus backend (priority 50) when both are on the classpath.
 */
public class JavaVmProvider implements JulcVmProvider {

    private static final System.Logger LOGGER =
            System.getLogger(JavaVmProvider.class.getName());

    private volatile ConfiguredCostModel customV1CostModel;
    private volatile ConfiguredCostModel customV2CostModel;
    private volatile ConfiguredCostModel customV3CostModel;

    @Override
    public void setCostModelParams(long[] costModelValues, PlutusLanguage language,
                                   int protocolMajorVersion, int protocolMinorVersion) {
        var target = new LedgerEvaluationTarget(
                language, new ProtocolVersion(protocolMajorVersion, protocolMinorVersion));
        var profile = ProtocolFeatureRegistry.resolve(target);
        var parsed = CostModelParser.parse(costModelValues, language, protocolMajorVersion, protocolMinorVersion);
        parsed.warnings().forEach(warning ->
                LOGGER.log(System.Logger.Level.WARNING, warning.message()));
        var configured = new ConfiguredCostModel(parsed, target, profile);
        switch (language) {
            case PLUTUS_V1 -> this.customV1CostModel = configured;
            case PLUTUS_V2 -> this.customV2CostModel = configured;
            case PLUTUS_V3 -> this.customV3CostModel = configured;
        }
    }

    @Override
    public EvalResult evaluate(Program program, PlutusLanguage language, ExBudget budget,
                               EvalOptions options) {
        return evaluateInternal(program, compatibilityTargetFor(language), budget, options);
    }

    @Override
    public EvalResult evaluateWithArgs(Program program, PlutusLanguage language,
                                       List<PlutusData> args, ExBudget budget,
                                       EvalOptions options) {
        return evaluateInternal(applyArgs(program, args), compatibilityTargetFor(language),
                budget, options);
    }

    @Override
    public EvalResult evaluate(Program program, LedgerEvaluationTarget target, ExBudget budget,
                               EvalOptions options) {
        return evaluateInternal(program, target, budget, options);
    }

    @Override
    public EvalResult evaluateWithArgs(Program program, LedgerEvaluationTarget target,
                                       List<PlutusData> args, ExBudget budget,
                                       EvalOptions options) {
        return evaluateInternal(applyArgs(program, args), target, budget, options);
    }

    private EvalResult evaluateInternal(Program program, LedgerEvaluationTarget target,
                                        ExBudget budget,
                                        EvalOptions options) {
        ProtocolFeatureProfile profile;
        ConfiguredCostModel configured;
        try {
            profile = ProtocolFeatureRegistry.resolve(target);
            ProgramValidator.validate(program, profile);
            configured = getCustomCostModel(target.ledgerLanguage());
            if (configured != null && !configured.target().equals(target)) {
                throw new UnsupportedLedgerTargetException(
                        "Configured cost model targets " + configured.target()
                                + " but evaluation requested " + target);
            }
        } catch (RuntimeException e) {
            return new EvalResult.Failure(messageOf(e), ExBudget.ZERO, List.of());
        }

        MachineCosts mc;
        BuiltinCostModel bcm;
        if (configured != null) {
            mc = configured.costModel().machineCosts();
            bcm = configured.costModel().builtinCostModel();
        } else {
            mc = DefaultCostModel.defaultMachineCosts(profile);
            bcm = DefaultCostModel.defaultBuiltinCostModel(profile);
        }
        var costTracker = new CostTracker(mc, bcm, profile, budget);
        var machine = new CekMachine(costTracker, profile,
                options.sourceMap(), options.tracingEnabled(), options.builtinTraceEnabled());
        try {
            CekValue result = machine.evaluate(program.term());
            Term resultTerm = ValueConverter.toTerm(result);
            return new EvalResult.Success(resultTerm, costTracker.consumed(), machine.getTraces(),
                    machine.getExecutionTrace(), machine.getBuiltinTrace());
        } catch (BudgetExhaustedException e) {
            return new EvalResult.BudgetExhausted(costTracker.consumed(), machine.getTraces(),
                    e.failedTerm(), machine.getExecutionTrace(), machine.getBuiltinTrace());
        } catch (CekEvaluationException e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new EvalResult.Failure(errorMsg, costTracker.consumed(), machine.getTraces(),
                    e.failedTerm(), machine.getExecutionTrace(), machine.getBuiltinTrace());
        } catch (Exception e) {
            return new EvalResult.Failure(messageOf(e), costTracker.consumed(), machine.getTraces(),
                    null, machine.getExecutionTrace(), machine.getBuiltinTrace());
        }
    }

    /**
     * Return the target used by the legacy language-only API. A configured
     * model supplies its retained target; otherwise the documented default is PV10.
     */
    public LedgerEvaluationTarget compatibilityTargetFor(PlutusLanguage language) {
        var configured = getCustomCostModel(language);
        return configured != null ? configured.target() : LedgerEvaluationTarget.pv10(language);
    }

    private ConfiguredCostModel getCustomCostModel(PlutusLanguage language) {
        return switch (language) {
            case PLUTUS_V1 -> customV1CostModel;
            case PLUTUS_V2 -> customV2CostModel;
            case PLUTUS_V3 -> customV3CostModel;
        };
    }

    private static Program applyArgs(Program program, List<PlutusData> args) {
        Term term = program.term();
        for (var arg : args) {
            term = new Term.Apply(term, new Term.Const(Constant.data(arg)));
        }
        return new Program(program.major(), program.minor(), program.patch(), term);
    }

    private static String messageOf(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    @Override
    public String name() {
        return "Java";
    }

    @Override
    public int priority() {
        return 100;
    }
}
