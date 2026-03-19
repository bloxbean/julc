package com.bloxbean.cardano.julc.vm.truffle.node;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.vm.java.cost.MachineCosts.StepKind;
import com.bloxbean.cardano.julc.vm.truffle.UplcContext;
import com.bloxbean.cardano.julc.vm.truffle.runtime.UplcConstrValue;
import com.bloxbean.cardano.julc.vm.truffle.runtime.UplcRuntimeException;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.instrumentation.StandardTags.StatementTag;
import com.oracle.truffle.api.instrumentation.Tag;

import java.util.List;

/**
 * V3 case dispatch — evaluates scrutinee, then selects and applies branch.
 */
public final class CaseNode extends UplcNode {

    @Child private UplcNode scrutineeNode;
    @Children private final UplcNode[] branchNodes;

    public CaseNode(Term sourceTerm, UplcNode scrutineeNode, UplcNode[] branchNodes) {
        super(sourceTerm);
        this.scrutineeNode = scrutineeNode;
        this.branchNodes = branchNodes;
    }

    @Override
    public boolean hasTag(Class<? extends Tag> tag) {
        return tag == StatementTag.class;
    }

    @Override
    public Object execute(Frame frame, UplcContext context) {
        if (context.getLanguage() != com.bloxbean.cardano.julc.vm.PlutusLanguage.PLUTUS_V3) {
            throw new UplcRuntimeException(
                    "Case term is not available in " + context.getLanguage() +
                    " (requires PLUTUS_V3)", getSourceTerm(), this);
        }
        context.getCostTracker().chargeMachineStep(StepKind.CASE);
        context.recordTrace(this, "Case");

        Object scrutinee = scrutineeNode.execute(frame, context);

        int tag;
        Object[] fields;

        if (scrutinee instanceof UplcConstrValue constr) {
            tag = (int) constr.getTag();
            fields = constr.getFields();
        } else if (scrutinee instanceof Constant c) {
            var decomposed = decomposeConstantForCase(c, branchNodes.length);
            tag = decomposed.tag;
            fields = decomposed.fields;
        } else {
            throw new UplcRuntimeException(
                    "Case scrutinee must be a constructor or built-in value, got: " +
                    ApplyNode.describeValue(scrutinee), getSourceTerm(), this);
        }

        if (tag < 0 || tag >= branchNodes.length) {
            throw new UplcRuntimeException(
                    "Case: tag " + tag + " out of range for " + branchNodes.length + " branches",
                    getSourceTerm(), this);
        }

        // Evaluate the selected branch
        Object branch = branchNodes[tag].execute(frame, context);

        // Apply fields as arguments to the branch
        for (Object field : fields) {
            branch = ApplyNode.applyValue(branch, field, context);
        }
        return branch;
    }

    // --- Constant decomposition (mirrors CekMachine.decomposeConstantForCase) ---

    private record CaseDecomposition(int tag, Object[] fields) {}

    private CaseDecomposition decomposeConstantForCase(Constant c, int branchCount) {
        return switch (c) {
            case Constant.BoolConst bc -> {
                validateMaxBranches("Bool", branchCount, 2);
                yield new CaseDecomposition(bc.value() ? 1 : 0, new Object[0]);
            }
            case Constant.UnitConst _ -> {
                validateMaxBranches("Unit", branchCount, 1);
                yield new CaseDecomposition(0, new Object[0]);
            }
            case Constant.IntegerConst ic ->
                    new CaseDecomposition(ic.value().intValueExact(), new Object[0]);
            case Constant.ListConst lc -> {
                validateMaxBranches("List", branchCount, 2);
                if (lc.values().isEmpty()) {
                    yield new CaseDecomposition(1, new Object[0]);
                } else {
                    yield new CaseDecomposition(0, new Object[]{
                            lc.values().getFirst(),
                            new Constant.ListConst(lc.elemType(),
                                    lc.values().subList(1, lc.values().size()))
                    });
                }
            }
            case Constant.PairConst pc -> {
                validateMaxBranches("Pair", branchCount, 1);
                yield new CaseDecomposition(0, new Object[]{pc.first(), pc.second()});
            }
            default -> throw new UplcRuntimeException(
                    "Cannot use case on constant type: " + c.type(), getSourceTerm(), this);
        };
    }

    private void validateMaxBranches(String typeName, int actual, int max) {
        if (actual > max) {
            throw new UplcRuntimeException(
                    "Case on " + typeName + " allows at most " + max +
                    " branch(es), got " + actual, getSourceTerm(), this);
        }
    }
}
