package org.julclang.vm.truffle.node;

import org.julclang.core.DefaultFun;
import org.julclang.core.Term;
import org.julclang.vm.java.cost.MachineCosts.StepKind;
import org.julclang.vm.truffle.UplcContext;
import org.julclang.vm.truffle.runtime.UplcBuiltinDescriptor;
import com.oracle.truffle.api.frame.Frame;

/**
 * Returns an initial (unsaturated) builtin descriptor.
 */
public final class BuiltinRefNode extends UplcNode {

    private final DefaultFun fun;
    private final int forceCount;
    private final int arity;

    public BuiltinRefNode(Term sourceTerm, DefaultFun fun, int forceCount, int arity) {
        super(sourceTerm);
        this.fun = fun;
        this.forceCount = forceCount;
        this.arity = arity;
    }

    @Override
    public Object execute(Frame frame, UplcContext context) {
        context.getCostTracker().chargeMachineStep(StepKind.BUILTIN);
        return UplcBuiltinDescriptor.initial(fun, forceCount, arity);
    }
}
