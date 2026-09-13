package org.julclang.vm.truffle.node;

import org.julclang.core.Constant;
import org.julclang.core.Term;
import org.julclang.vm.java.cost.MachineCosts.StepKind;
import org.julclang.vm.truffle.UplcContext;
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
