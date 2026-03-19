package com.bloxbean.cardano.julc.vm.truffle.node;

import com.bloxbean.cardano.julc.vm.truffle.UplcContext;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.ProbeNode;
import com.oracle.truffle.api.instrumentation.InstrumentableNode.WrapperNode;
import com.oracle.truffle.api.interop.TruffleObject;

/**
 * Manual wrapper node for Truffle instrumentation.
 * <p>
 * Intercepts {@link UplcNode#execute(Frame, UplcContext)} calls to fire
 * instrumentation events (enter, return, exception). This is a manual
 * implementation because {@code @GenerateWrapper} requires a standard
 * {@code execute(VirtualFrame)} signature.
 */
public final class UplcNodeWrapper extends UplcNode implements WrapperNode {

    @Child private UplcNode delegateNode;
    @Child private ProbeNode probeNode;

    public UplcNodeWrapper(UplcNode delegateNode, ProbeNode probeNode) {
        super(delegateNode.getSourceTerm());
        this.delegateNode = delegateNode;
        this.probeNode = probeNode;
    }

    @Override
    public UplcNode getDelegateNode() {
        return delegateNode;
    }

    @Override
    public ProbeNode getProbeNode() {
        return probeNode;
    }

    @Override
    public Object execute(Frame frame, UplcContext context) {
        // ProbeNode methods require VirtualFrame; at runtime the Frame is always a VirtualFrame
        VirtualFrame vf = (VirtualFrame) frame;
        for (;;) {
            boolean wasOnReturnExecuted = false;
            try {
                probeNode.onEnter(vf);
                Object returnValue = delegateNode.execute(frame, context);
                wasOnReturnExecuted = true;
                probeNode.onReturnValue(vf, toInterop(returnValue));
                return returnValue;
            } catch (Throwable t) {
                Object result = probeNode.onReturnExceptionalOrUnwind(vf, t, wasOnReturnExecuted);
                if (result == ProbeNode.UNWIND_ACTION_REENTER) {
                    continue;
                } else if (result != null) {
                    return result;
                }
                throw t;
            }
        }
    }

    /**
     * Wrap non-interop values for ProbeNode events.
     * Truffle's debugger requires all values passed to onReturnValue to be
     * valid interop types. Our Constant records aren't TruffleObjects, so
     * we wrap them in a minimal TruffleObject shell. The real return value
     * is still returned directly to the caller.
     */
    private static Object toInterop(Object value) {
        if (value instanceof TruffleObject || value instanceof Number
                || value instanceof String || value instanceof Boolean
                || value instanceof Character) {
            return value; // Already interop-compatible
        }
        return new UplcValueWrapper(value);
    }

    /**
     * Minimal TruffleObject wrapper for non-interop UPLC values (Constants).
     * Only used for instrumentation events — never returned as execution result.
     */
    static final class UplcValueWrapper implements TruffleObject {
        final Object wrapped;

        UplcValueWrapper(Object wrapped) {
            this.wrapped = wrapped;
        }

        @Override
        public String toString() {
            return wrapped != null ? wrapped.toString() : "null";
        }
    }
}
