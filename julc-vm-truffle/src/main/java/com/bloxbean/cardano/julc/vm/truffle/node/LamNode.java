package com.bloxbean.cardano.julc.vm.truffle.node;

import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.vm.java.cost.MachineCosts.StepKind;
import com.bloxbean.cardano.julc.vm.truffle.UplcContext;
import com.bloxbean.cardano.julc.vm.truffle.runtime.UplcClosure;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.frame.Frame;

/**
 * Creates a lambda closure capturing the current frame and a pre-built call target.
 * <p>
 * The call target wraps a LamBodyRootNode that, when called, creates a new frame
 * with the argument in slot 0 and the captured frame as parent.
 */
public final class LamNode extends UplcNode {

    private final CallTarget bodyCallTarget;

    public LamNode(Term sourceTerm, CallTarget bodyCallTarget) {
        super(sourceTerm);
        this.bodyCallTarget = bodyCallTarget;
    }

    @Override
    public Object execute(Frame frame, UplcContext context) {
        context.getCostTracker().chargeMachineStep(StepKind.LAM);
        return new UplcClosure(bodyCallTarget, frame.materialize(), (Term.Lam) getSourceTerm());
    }
}
