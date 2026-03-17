package com.bloxbean.cardano.julc.vm.java;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.Term;
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

    private volatile CostModelParser.ParsedCostModel customCostModel;

    @Override
    public void setCostModelParams(long[] costModelValues, int protocolMajorVersion) {
        this.customCostModel = CostModelParser.parse(costModelValues);
    }

    @Override
    public EvalResult evaluate(Program program, PlutusLanguage language, ExBudget budget) {
        return evaluateInternal(program.term(), budget);
    }

    @Override
    public EvalResult evaluateWithArgs(Program program, PlutusLanguage language,
                                       List<PlutusData> args, ExBudget budget) {
        // Apply each argument as a Data constant
        Term term = program.term();
        for (var arg : args) {
            term = new Term.Apply(term, new Term.Const(Constant.data(arg)));
        }
        return evaluateInternal(term, budget);
    }

    private EvalResult evaluateInternal(Term term, ExBudget budget) {
        MachineCosts mc;
        BuiltinCostModel bcm;
        var parsed = customCostModel;
        if (parsed != null) {
            mc = parsed.machineCosts();
            bcm = parsed.builtinCostModel();
        } else {
            mc = DefaultCostModel.defaultMachineCosts();
            bcm = DefaultCostModel.defaultBuiltinCostModel();
        }
        var costTracker = new CostTracker(mc, bcm, budget);
        var machine = new CekMachine(costTracker);
        try {
            CekValue result = machine.evaluate(term);
            Term resultTerm = ValueConverter.toTerm(result);
            return new EvalResult.Success(resultTerm, costTracker.consumed(), machine.getTraces());
        } catch (BudgetExhaustedException e) {
            return new EvalResult.BudgetExhausted(costTracker.consumed(), machine.getTraces());
        } catch (CekEvaluationException e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new EvalResult.Failure(errorMsg, costTracker.consumed(), machine.getTraces());
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new EvalResult.Failure(errorMsg, costTracker.consumed(), machine.getTraces());
        }
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
