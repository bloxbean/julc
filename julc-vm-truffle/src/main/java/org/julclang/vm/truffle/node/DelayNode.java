package org.julclang.vm.truffle.node;

import org.julclang.core.Term;
import org.julclang.vm.java.cost.MachineCosts.StepKind;
import org.julclang.vm.truffle.UplcContext;
import org.julclang.vm.truffle.runtime.UplcDelay;
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
