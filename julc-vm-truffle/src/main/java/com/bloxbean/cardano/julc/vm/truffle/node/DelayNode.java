package com.bloxbean.cardano.julc.vm.truffle.node;

import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.vm.java.cost.MachineCosts.StepKind;
import com.bloxbean.cardano.julc.vm.truffle.UplcContext;
import com.bloxbean.cardano.julc.vm.truffle.runtime.UplcDelay;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.frame.Frame;

/**
 * Creates a delay thunk — captures the body and current frame.
 */
public final class DelayNode extends UplcNode {

    private final CallTarget bodyCallTarget;

    public DelayNode(Term sourceTerm, CallTarget bodyCallTarget) {
        super(sourceTerm);
        this.bodyCallTarget = bodyCallTarget;
    }

    @Override
    public Object execute(Frame frame, UplcContext context) {
        context.getCostTracker().chargeMachineStep(StepKind.DELAY);
        return new UplcDelay(bodyCallTarget, frame.materialize(), (Term.Delay) getSourceTerm());
    }
}
