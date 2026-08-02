package com.bloxbean.cardano.julc.vm.truffle;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.vm.*;
import com.bloxbean.cardano.julc.vm.java.cost.*;
import com.bloxbean.cardano.julc.vm.truffle.convert.NodeToTermConverter;
import com.bloxbean.cardano.julc.vm.truffle.convert.TermToNodeConverter;
import com.bloxbean.cardano.julc.vm.truffle.node.UplcNode;
import com.bloxbean.cardano.julc.vm.truffle.node.UplcRootNode;
import com.bloxbean.cardano.julc.vm.truffle.runtime.UplcRuntimeException;
import com.oracle.truffle.api.frame.FrameDescriptor;

import java.util.List;

/**
 * GraalVM Truffle JIT-based {@link JulcVmProvider} implementation.
 * <p>
 * Converts UPLC terms to a Truffle AST, evaluates them using the Truffle
 * framework (enabling JIT compilation for hot paths), and produces results
 * compatible with the standard {@link EvalResult} interface.
 * <p>
 * Priority 200 — preferred over both Scalus (50) and Java (100) backends
 * when on the classpath.
 * <p>
 * Cost model infrastructure is shared with julc-vm-java, guaranteeing
 * exact budget parity.
 * <p>
 */
public class TruffleVmProvider implements JulcVmProvider {

    private static final System.Logger LOGGER =
            System.getLogger(TruffleVmProvider.class.getName());

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
            if (configured != null
                    && !configured.target().hasSamePlutusSemantics(target)) {
                throw new UnsupportedLedgerTargetException(
                        "Configured cost model targets " + configured.target()
                                + " but evaluation requested " + target);
            }
        } catch (RuntimeException e) {
            return new EvalResult.Failure(messageOf(e), ExBudget.ZERO, List.of());
        }

        // Set up cost tracking (shared with julc-vm-java)
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
        var context = new UplcContext(costTracker, profile,
                options.tracingEnabled(), options.builtinTraceEnabled());

        try {
            // Charge startup cost (inside try for budget exhaustion handling)
            costTracker.chargeMachineStep(MachineCosts.StepKind.STARTUP);
            // Convert Term → Truffle AST
            UplcNode bodyNode = TermToNodeConverter.convert(program.term(), options.sourceMap());
            var fd = FrameDescriptor.newBuilder().build();
            var rootNode = new UplcRootNode(fd, bodyNode);

            // Execute
            Object result = rootNode.getCallTarget().call(context);

            // Convert result → Term
            Term resultTerm = NodeToTermConverter.toTerm(result);
            return new EvalResult.Success(resultTerm, costTracker.consumed(), context.getTraces(),
                    context.getExecutionTrace(), context.getBuiltinTrace());

        } catch (BudgetExhaustedException e) {
            return new EvalResult.BudgetExhausted(costTracker.consumed(), context.getTraces(),
                    e.failedTerm(), context.getExecutionTrace(), context.getBuiltinTrace());
        } catch (UplcRuntimeException e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            // Enhance with source location if available
            if (e.getLocation() != null && e.getLocation().getSourceSection() != null) {
                var ss = e.getLocation().getSourceSection();
                errorMsg = errorMsg + " at " + ss.getSource().getName() + ":" + ss.getStartLine();
            }
            return new EvalResult.Failure(errorMsg, costTracker.consumed(), context.getTraces(),
                    e.getFailedTerm(), context.getExecutionTrace(), context.getBuiltinTrace());
        } catch (com.bloxbean.cardano.julc.vm.java.CekEvaluationException e) {
            // Builtin errors from julc-vm-java implementations
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new EvalResult.Failure(errorMsg, costTracker.consumed(), context.getTraces(),
                    e.failedTerm(), context.getExecutionTrace(), context.getBuiltinTrace());
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new EvalResult.Failure(errorMsg, costTracker.consumed(), context.getTraces(),
                    null, context.getExecutionTrace(), context.getBuiltinTrace());
        }
    }

    /** Target used by the legacy language-only API; PV10 when unconfigured. */
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
        return "Truffle";
    }

    @Override
    public int priority() {
        return 200;
    }
}
