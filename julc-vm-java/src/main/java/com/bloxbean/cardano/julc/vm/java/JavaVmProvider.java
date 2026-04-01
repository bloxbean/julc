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

    private volatile CostModelParser.ParsedCostModel customV1CostModel;
    private volatile CostModelParser.ParsedCostModel customV2CostModel;
    private volatile CostModelParser.ParsedCostModel customV3CostModel;

    @Override
    public void setCostModelParams(long[] costModelValues, PlutusLanguage language,
                                   int protocolMajorVersion, int protocolMinorVersion) {
        var parsed = CostModelParser.parse(costModelValues, language, protocolMajorVersion, protocolMinorVersion);
        switch (language) {
            case PLUTUS_V1 -> this.customV1CostModel = parsed;
            case PLUTUS_V2 -> this.customV2CostModel = parsed;
            case PLUTUS_V3 -> this.customV3CostModel = parsed;
        }
    }

    @Override
    public EvalResult evaluate(Program program, PlutusLanguage language, ExBudget budget,
                               EvalOptions options) {
        return evaluateInternal(program.term(), language, budget, options);
    }

    @Override
    public EvalResult evaluateWithArgs(Program program, PlutusLanguage language,
                                       List<PlutusData> args, ExBudget budget,
                                       EvalOptions options) {
        // Apply each argument as a Data constant
        Term term = program.term();
        for (var arg : args) {
            term = new Term.Apply(term, new Term.Const(Constant.data(arg)));
        }
        return evaluateInternal(term, language, budget, options);
    }

    private EvalResult evaluateInternal(Term term, PlutusLanguage language, ExBudget budget,
                                        EvalOptions options) {
        MachineCosts mc;
        BuiltinCostModel bcm;
        var parsed = getCustomCostModel(language);
        if (parsed != null) {
            mc = parsed.machineCosts();
            bcm = parsed.builtinCostModel();
        } else {
            mc = DefaultCostModel.defaultMachineCosts(language);
            bcm = DefaultCostModel.defaultBuiltinCostModel(language);
        }
        var costTracker = new CostTracker(mc, bcm, budget);
        var machine = new CekMachine(costTracker, language,
                options.sourceMap(), options.tracingEnabled(), options.builtinTraceEnabled());
        try {
            CekValue result = machine.evaluate(term);
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
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new EvalResult.Failure(errorMsg, costTracker.consumed(), machine.getTraces(),
                    null, machine.getExecutionTrace(), machine.getBuiltinTrace());
        }
    }

    private CostModelParser.ParsedCostModel getCustomCostModel(PlutusLanguage language) {
        return switch (language) {
            case PLUTUS_V1 -> customV1CostModel;
            case PLUTUS_V2 -> customV2CostModel;
            case PLUTUS_V3 -> customV3CostModel;
        };
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
