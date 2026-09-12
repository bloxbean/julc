package org.julclang.vm.truffle.node;

import org.julclang.core.Term;
import org.julclang.vm.java.cost.MachineCosts.StepKind;
import org.julclang.vm.truffle.UplcContext;
import org.julclang.vm.truffle.builtin.BuiltinDispatcher;
import org.julclang.vm.truffle.runtime.UplcBuiltinDescriptor;
import org.julclang.vm.truffle.runtime.UplcDelay;
import org.julclang.vm.truffle.runtime.UplcRuntimeException;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.instrumentation.StandardTags.StatementTag;
import com.oracle.truffle.api.instrumentation.Tag;

/**
 * Force: unwraps a delayed computation or forces a builtin.
 */
public final class ForceNode extends UplcNode {

    @Child private UplcNode innerNode;

    public ForceNode(Term sourceTerm, UplcNode innerNode) {
        super(sourceTerm);
        this.innerNode = innerNode;
    }

    @Override
    public boolean hasTag(Class<? extends Tag> tag) {
        return tag == StatementTag.class;
    }

    @Override
    public Object execute(Frame frame, UplcContext context) {
        context.getCostTracker().chargeMachineStep(StepKind.FORCE);
        context.recordTrace(this, "Force");

        Object value = innerNode.execute(frame, context);

        if (value instanceof UplcDelay delay) {
            // Force the delayed computation
            return delay.getCallTarget().call(context, delay.getCapturedFrame());
        } else if (value instanceof UplcBuiltinDescriptor builtin) {
            var forced = builtin.applyForce();
            if (forced.isReady()) {
                return BuiltinDispatcher.execute(forced, context);
            }
            return forced;
        } else {
            throw throwCannotForce(value);
        }
    }

    @TruffleBoundary
    private UplcRuntimeException throwCannotForce(Object value) {
        throw new UplcRuntimeException(
                "Cannot force value: " + ApplyNode.describeValue(value),
                getSourceTerm(), this);
    }
}
