package com.bloxbean.cardano.julc.vm.truffle.node;

import com.bloxbean.cardano.julc.vm.truffle.UplcContext;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;

/**
 * Root node wrapping a UPLC expression for Truffle execution.
 * <p>
 * The root node provides the entry point for evaluation. The context is
 * passed as argument[0] and the optional captured frame as argument[1].
 */
public final class UplcRootNode extends RootNode {

    @Child private UplcNode bodyNode;

    public UplcRootNode(FrameDescriptor frameDescriptor, UplcNode bodyNode) {
        this(null, frameDescriptor, bodyNode);
    }

    public UplcRootNode(TruffleLanguage<?> language, FrameDescriptor frameDescriptor, UplcNode bodyNode) {
        super(language, frameDescriptor);
        this.bodyNode = bodyNode;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        // Arguments: [0] = UplcContext, [1] = optional captured frame (for closures)
        Object[] args = frame.getArguments();
        UplcContext context = (UplcContext) args[0];
        return bodyNode.execute(frame, context);
    }

    @Override
    public String getName() {
        return "uplc-root";
    }

    @Override
    public String toString() {
        return "UplcRootNode";
    }
}
