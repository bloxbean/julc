package com.bloxbean.cardano.julc.vm.truffle.node;

import com.bloxbean.cardano.julc.vm.truffle.UplcContext;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.ProbeNode;
import com.oracle.truffle.api.instrumentation.InstrumentableNode.WrapperNode;

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
                probeNode.onReturnValue(vf, returnValue);
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
}
