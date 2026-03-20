package com.bloxbean.cardano.julc.vm.truffle.node;

import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.vm.java.cost.MachineCosts.StepKind;
import com.bloxbean.cardano.julc.vm.truffle.UplcContext;
import com.bloxbean.cardano.julc.vm.truffle.runtime.UplcConstrValue;
import com.bloxbean.cardano.julc.vm.truffle.runtime.UplcRuntimeException;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.nodes.ExplodeLoop;

/**
 * V3 constructor — evaluates fields and returns a UplcConstrValue.
 */
public final class ConstrNode extends UplcNode {

    private final long tag;
    @Children private final UplcNode[] fieldNodes;

    public ConstrNode(Term sourceTerm, long tag, UplcNode[] fieldNodes) {
        super(sourceTerm);
        this.tag = tag;
        this.fieldNodes = fieldNodes;
    }

    @Override
    @ExplodeLoop
    public Object execute(Frame frame, UplcContext context) {
        if (context.getLanguage() != com.bloxbean.cardano.julc.vm.PlutusLanguage.PLUTUS_V3) {
            throw new UplcRuntimeException(
                    "Constr term is not available in " + context.getLanguage() +
                    " (requires PLUTUS_V3)", getSourceTerm(), this);
        }
        context.getCostTracker().chargeMachineStep(StepKind.CONSTR);

        Object[] fields = new Object[fieldNodes.length];
        for (int i = 0; i < fieldNodes.length; i++) {
            fields[i] = fieldNodes[i].execute(frame, context);
        }
        return new UplcConstrValue(tag, fields);
    }
}
