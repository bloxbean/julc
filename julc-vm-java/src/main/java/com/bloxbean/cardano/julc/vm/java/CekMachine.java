package com.bloxbean.cardano.julc.vm.java;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;
import com.bloxbean.cardano.julc.vm.java.builtins.BuiltinHelper;
import com.bloxbean.cardano.julc.vm.java.builtins.BuiltinRuntime;
import com.bloxbean.cardano.julc.vm.java.builtins.BuiltinTable;
import com.bloxbean.cardano.julc.vm.java.cost.CostTracker;
import com.bloxbean.cardano.julc.vm.java.cost.MachineCosts.StepKind;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Iterative CEK machine for UPLC evaluation.
 * <p>
 * Uses an explicit stack ({@link ArrayDeque}) instead of recursion to avoid
 * stack overflow on deeply nested programs.
 * <p>
 * The machine alternates between two phases:
 * <ul>
 *   <li><b>COMPUTE</b> — evaluates a term in an environment, pushing frames onto the stack</li>
 *   <li><b>RETURN</b> — pops a frame from the stack and processes a value</li>
 * </ul>
 */
public final class CekMachine {

    private final ArrayDeque<CekFrame> stack = new ArrayDeque<>();
    private final List<String> traces = new ArrayList<>();
    private final CostTracker costTracker;
    private final PlutusLanguage language;
    private final BuiltinTable.VersionedBuiltinTable builtinTable;

    // Machine state
    private Term currentTerm;
    private CekEnvironment currentEnv;
    private CekValue currentValue;
    private boolean inComputePhase;

    /** Create a CekMachine without cost tracking (for backward compatibility). Defaults to V3. */
    public CekMachine() {
        this(null, PlutusLanguage.PLUTUS_V3);
    }

    /** Create a CekMachine with optional cost tracking. Defaults to V3. */
    public CekMachine(CostTracker costTracker) {
        this(costTracker, PlutusLanguage.PLUTUS_V3);
    }

    /** Create a CekMachine with optional cost tracking and language version. */
    public CekMachine(CostTracker costTracker, PlutusLanguage language) {
        this.costTracker = costTracker;
        this.language = language;
        this.builtinTable = BuiltinTable.forLanguage(language);
    }

    /**
     * Evaluate a UPLC term.
     *
     * @param term the term to evaluate
     * @return the evaluated value
     * @throws CekEvaluationException on evaluation error
     */
    public CekValue evaluate(Term term) {
        this.currentTerm = term;
        this.currentEnv = CekEnvironment.EMPTY;
        this.currentValue = null;
        this.inComputePhase = true;
        this.stack.clear();
        this.traces.clear();

        if (costTracker != null) {
            costTracker.chargeMachineStep(StepKind.STARTUP);
        }

        try {
            while (true) {
                if (inComputePhase) {
                    compute();
                } else {
                    if (stack.isEmpty()) {
                        return currentValue;
                    }
                    returnValue();
                }
            }
        } catch (com.bloxbean.cardano.julc.vm.java.cost.BudgetExhaustedException e) {
            // Re-throw with currentTerm attached for source map resolution
            if (e.failedTerm() == null && currentTerm != null) {
                throw new com.bloxbean.cardano.julc.vm.java.cost.BudgetExhaustedException(
                        e.getMessage(), currentTerm);
            }
            throw e;
        }
    }

    /** Get trace messages collected during evaluation. */
    public List<String> getTraces() {
        return List.copyOf(traces);
    }

    // === COMPUTE phase ===

    private void compute() {
        switch (currentTerm) {
            case Term.Var v -> {
                chargeStep(StepKind.VAR);
                currentValue = currentEnv.lookup(v.name().index());
                inComputePhase = false;
            }
            case Term.Const c -> {
                chargeStep(StepKind.CONST);
                currentValue = new CekValue.VCon(c.value());
                inComputePhase = false;
            }
            case Term.Lam lam -> {
                chargeStep(StepKind.LAM);
                currentValue = new CekValue.VLam(lam.paramName(), lam.body(), currentEnv);
                inComputePhase = false;
            }
            case Term.Delay delay -> {
                chargeStep(StepKind.DELAY);
                currentValue = new CekValue.VDelay(delay.term(), currentEnv);
                inComputePhase = false;
            }
            case Term.Builtin b -> {
                chargeStep(StepKind.BUILTIN);
                var sig = builtinTable.getSignature(b.fun());
                currentValue = CekValue.VBuiltin.initial(b.fun(), sig.forceCount(), sig.arity());
                inComputePhase = false;
            }
            case Term.Error _ -> {
                throw new CekEvaluationException("Error term encountered", currentTerm);
            }
            case Term.Force f -> {
                chargeStep(StepKind.FORCE);
                stack.push(new CekFrame.ForceFrame());
                currentTerm = f.term();
            }
            case Term.Apply app -> {
                chargeStep(StepKind.APPLY);
                // Push frame: "after function evaluates, evaluate argument in current env"
                stack.push(new CekFrame.ComputeArgFrame(app.argument(), currentEnv));
                currentTerm = app.function();
                // currentEnv stays the same
            }
            case Term.Constr constr -> {
                if (language != PlutusLanguage.PLUTUS_V3) {
                    throw new CekEvaluationException(
                            "Constr term is not available in " + language +
                            " (requires PLUTUS_V3)", currentTerm);
                }
                chargeStep(StepKind.CONSTR);
                if (constr.fields().isEmpty()) {
                    currentValue = new CekValue.VConstr(constr.tag(), List.of());
                    inComputePhase = false;
                } else {
                    var remaining = constr.fields().subList(1, constr.fields().size());
                    stack.push(new CekFrame.ConstrFrame(constr.tag(), new ArrayList<>(),
                            remaining, currentEnv));
                    currentTerm = constr.fields().getFirst();
                }
            }
            case Term.Case cs -> {
                if (language != PlutusLanguage.PLUTUS_V3) {
                    throw new CekEvaluationException(
                            "Case term is not available in " + language +
                            " (requires PLUTUS_V3)", currentTerm);
                }
                chargeStep(StepKind.CASE);
                stack.push(new CekFrame.CaseFrame(cs.branches(), currentEnv));
                currentTerm = cs.scrutinee();
            }
        }
    }

    // === RETURN phase ===

    private void returnValue() {
        var frame = stack.pop();
        switch (frame) {
            case CekFrame.ForceFrame _ -> handleForce(currentValue);

            case CekFrame.ComputeArgFrame caf -> {
                // Function has been evaluated to currentValue. Now evaluate the argument.
                var function = currentValue;
                stack.push(new CekFrame.ApplyArgFrame(function));
                currentTerm = caf.term();
                currentEnv = caf.env();
                inComputePhase = true;
            }

            case CekFrame.ApplyArgFrame af -> {
                // Argument has been evaluated. Apply function to argument.
                applyFunction(af.function(), currentValue);
            }

            case CekFrame.ValueArgFrame vaf -> {
                // Apply the current value (a function) to the pre-evaluated argument.
                applyFunction(currentValue, vaf.value());
            }

            case CekFrame.ConstrFrame cf -> {
                var evaluated = new ArrayList<>(cf.evaluatedFields());
                evaluated.add(currentValue);
                if (cf.remainingTerms().isEmpty()) {
                    currentValue = new CekValue.VConstr(cf.tag(), evaluated);
                    // stay in return phase
                } else {
                    stack.push(new CekFrame.ConstrFrame(cf.tag(), evaluated,
                            cf.remainingTerms().subList(1, cf.remainingTerms().size()),
                            cf.env()));
                    currentTerm = cf.remainingTerms().getFirst();
                    currentEnv = cf.env();
                    inComputePhase = true;
                }
            }

            case CekFrame.CaseFrame cf -> {
                // Decompose the scrutinee into (tag, fields) for case dispatch.
                // Supports both VConstr and built-in constant types.
                int tag;
                List<CekValue> fields;

                if (currentValue instanceof CekValue.VConstr vc) {
                    tag = (int) vc.tag();
                    fields = vc.fields();
                } else if (currentValue instanceof CekValue.VCon vcon) {
                    var decomposed = decomposeConstantForCase(vcon.constant(), cf.branches().size());
                    tag = decomposed.tag;
                    fields = decomposed.fields;
                } else {
                    throw new CekEvaluationException(
                            "Case scrutinee must be a constructor or built-in value, got: " +
                            describeValue(currentValue), currentTerm);
                }

                if (tag < 0 || tag >= cf.branches().size()) {
                    throw new CekEvaluationException(
                            "Case: tag " + tag + " out of range for " +
                            cf.branches().size() + " branches", currentTerm);
                }
                Term branch = cf.branches().get(tag);

                if (fields.isEmpty()) {
                    currentTerm = branch;
                    currentEnv = cf.env();
                    inComputePhase = true;
                } else {
                    for (int i = fields.size() - 1; i >= 1; i--) {
                        stack.push(new CekFrame.ValueArgFrame(fields.get(i)));
                    }
                    stack.push(new CekFrame.ValueArgFrame(fields.getFirst()));
                    currentTerm = branch;
                    currentEnv = cf.env();
                    inComputePhase = true;
                }
            }
        }
    }

    private void handleForce(CekValue value) {
        switch (value) {
            case CekValue.VDelay vd -> {
                currentTerm = vd.body();
                currentEnv = vd.env();
                inComputePhase = true;
            }
            case CekValue.VBuiltin vb -> {
                var forced = vb.applyForce();
                if (forced.isReady()) {
                    currentValue = executeBuiltin(forced);
                } else {
                    currentValue = forced;
                }
                // stay in return phase
            }
            default -> throw new CekEvaluationException(
                    "Cannot force value: " + describeValue(value), currentTerm);
        }
    }

    private void applyFunction(CekValue function, CekValue argument) {
        switch (function) {
            case CekValue.VLam vlam -> {
                currentTerm = vlam.body();
                currentEnv = vlam.env().extend(argument);
                inComputePhase = true;
            }
            case CekValue.VBuiltin vb -> {
                var applied = vb.applyArg(argument);
                if (applied.isReady()) {
                    currentValue = executeBuiltin(applied);
                } else {
                    currentValue = applied;
                }
                // stay in return phase
            }
            default -> throw new CekEvaluationException(
                    "Cannot apply non-function value: " + describeValue(function), currentTerm);
        }
    }

    private CekValue executeBuiltin(CekValue.VBuiltin vb) {
        // Special handling for Trace: extract and record the message
        if (vb.fun() == DefaultFun.Trace) {
            var msg = BuiltinHelper.asString(vb.collectedArgs().getFirst(), "Trace");
            traces.add(msg);
        }

        // Charge builtin execution cost
        if (costTracker != null) {
            costTracker.chargeBuiltin(vb.fun(), vb.collectedArgs());
        }

        BuiltinRuntime runtime = builtinTable.getRuntime(vb.fun());
        return runtime.execute(vb.collectedArgs());
    }

    private void chargeStep(StepKind kind) {
        if (costTracker != null) {
            costTracker.chargeMachineStep(kind);
        }
    }

    private static String describeValue(CekValue v) {
        return switch (v) {
            case CekValue.VCon vc -> "VCon(" + vc.constant().type() + ")";
            case CekValue.VDelay _ -> "VDelay";
            case CekValue.VLam _ -> "VLam";
            case CekValue.VConstr vc -> "VConstr(" + vc.tag() + ")";
            case CekValue.VBuiltin vb -> "VBuiltin(" + vb.fun() + ")";
        };
    }

    /**
     * Decompose a constant value for case expression dispatch.
     * Built-in types are treated as constructors:
     * - Bool: False=tag0, True=tag1 (no fields)
     * - Unit: tag0 (no fields)
     * - Integer: tag=value (no fields)
     * - List: Nil=tag0, Cons=tag1(head, tail)
     * - Pair: tag0(fst, snd)
     */
    private record CaseDecomposition(int tag, List<CekValue> fields) {}

    /**
     * Decompose a constant for case dispatch, also validating branch count.
     *
     * @param c the constant to decompose
     * @param branchCount the number of case branches (for validation)
     */
    private static CaseDecomposition decomposeConstantForCase(Constant c, int branchCount) {
        return switch (c) {
            case Constant.BoolConst bc -> {
                validateMaxBranches("Bool", branchCount, 2);
                yield new CaseDecomposition(bc.value() ? 1 : 0, List.of());
            }
            case Constant.UnitConst _ -> {
                validateMaxBranches("Unit", branchCount, 1);
                yield new CaseDecomposition(0, List.of());
            }
            case Constant.IntegerConst ic ->
                    new CaseDecomposition(ic.value().intValueExact(), List.of());
            case Constant.ListConst lc -> {
                validateMaxBranches("List", branchCount, 2);
                if (lc.values().isEmpty()) {
                    yield new CaseDecomposition(1, List.of());
                } else {
                    yield new CaseDecomposition(0, List.of(
                            new CekValue.VCon(lc.values().getFirst()),
                            new CekValue.VCon(new Constant.ListConst(lc.elemType(),
                                    lc.values().subList(1, lc.values().size())))));
                }
            }
            case Constant.PairConst pc -> {
                validateMaxBranches("Pair", branchCount, 1);
                yield new CaseDecomposition(0, List.of(
                        new CekValue.VCon(pc.first()),
                        new CekValue.VCon(pc.second())));
            }
            default -> throw new CekEvaluationException(
                    "Cannot use case on constant type: " + c.type());
        };
    }

    private static void validateMaxBranches(String typeName, int actual, int max) {
        if (actual > max) {
            throw new CekEvaluationException(
                    "Case on " + typeName + " allows at most " + max +
                    " branch(es), got " + actual);
        }
    }
}
