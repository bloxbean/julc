package com.bloxbean.cardano.julc.vm.truffle.node;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.vm.java.cost.MachineCosts.StepKind;
import com.bloxbean.cardano.julc.vm.truffle.UplcContext;
import com.oracle.truffle.api.frame.Frame;

/**
 * Returns a constant value.
 */
public final class ConstNode extends UplcNode {

    private final Constant value;

    public ConstNode(Term sourceTerm, Constant value) {
        super(sourceTerm);
        this.value = value;
    }

    @Override
    public Object execute(Frame frame, UplcContext context) {
        context.getCostTracker().chargeMachineStep(StepKind.CONST);
        return value;
    }
}
