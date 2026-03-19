package com.bloxbean.cardano.julc.vm.truffle.node;

import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.vm.java.cost.MachineCosts.StepKind;
import com.bloxbean.cardano.julc.vm.truffle.UplcContext;
import com.bloxbean.cardano.julc.vm.truffle.builtin.BuiltinDispatcher;
import com.bloxbean.cardano.julc.vm.truffle.runtime.UplcBuiltinDescriptor;
import com.bloxbean.cardano.julc.vm.truffle.runtime.UplcClosure;
import com.bloxbean.cardano.julc.vm.truffle.runtime.UplcRuntimeException;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.instrumentation.StandardTags.StatementTag;
import com.oracle.truffle.api.instrumentation.Tag;

/**
 * Function application: evaluates function and argument, then applies.
 */
public final class ApplyNode extends UplcNode {

    @Child private UplcNode functionNode;
    @Child private UplcNode argumentNode;

    public ApplyNode(Term sourceTerm, UplcNode functionNode, UplcNode argumentNode) {
        super(sourceTerm);
        this.functionNode = functionNode;
        this.argumentNode = argumentNode;
    }

    @Override
    public boolean hasTag(Class<? extends Tag> tag) {
        return tag == StatementTag.class;
    }

    @Override
    public Object execute(Frame frame, UplcContext context) {
        context.getCostTracker().chargeMachineStep(StepKind.APPLY);
        context.recordTrace(this, "Apply");

        Object function = functionNode.execute(frame, context);
        Object argument = argumentNode.execute(frame, context);

        return applyValue(function, argument, context);
    }

    /**
     * Apply a function value to an argument. Shared by ApplyNode and CaseNode.
     */
    static Object applyValue(Object function, Object argument, UplcContext context) {
        if (function instanceof UplcClosure closure) {
            return closure.getCallTarget().call(
                    context, closure.getCapturedFrame(), argument);
        } else if (function instanceof UplcBuiltinDescriptor builtin) {
            var applied = builtin.applyArg(argument);
            if (applied.isReady()) {
                return BuiltinDispatcher.execute(applied, context);
            }
            return applied;
        } else {
            throw throwCannotApply(function);
        }
    }

    @TruffleBoundary
    private static UplcRuntimeException throwCannotApply(Object function) {
        throw new UplcRuntimeException(
                "Cannot apply non-function value: " + describeValue(function));
    }

    static String describeValue(Object v) {
        if (v instanceof com.bloxbean.cardano.julc.core.Constant c) return "Constant(" + c.type() + ")";
        if (v instanceof UplcClosure) return "Closure";
        if (v instanceof com.bloxbean.cardano.julc.vm.truffle.runtime.UplcDelay) return "Delay";
        if (v instanceof UplcBuiltinDescriptor b) return "Builtin(" + b.getFun() + ")";
        if (v instanceof com.bloxbean.cardano.julc.vm.truffle.runtime.UplcConstrValue c) return "Constr(" + c.getTag() + ")";
        return v.getClass().getSimpleName();
    }
}
