package com.bloxbean.cardano.julc.vm.truffle.node;

import com.bloxbean.cardano.julc.vm.truffle.UplcContext;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;

/**
 * Root node for a delay body.
 * <p>
 * Arguments layout:
 * <ul>
 *   <li>[0] = UplcContext</li>
 *   <li>[1] = captured (parent) MaterializedFrame</li>
 * </ul>
 */
public final class DelayBodyRootNode extends RootNode {

    @Child private UplcNode bodyNode;

    public DelayBodyRootNode(FrameDescriptor frameDescriptor, UplcNode bodyNode) {
        this(null, frameDescriptor, bodyNode);
    }

    public DelayBodyRootNode(TruffleLanguage<?> language, FrameDescriptor frameDescriptor, UplcNode bodyNode) {
        super(language, frameDescriptor);
        this.bodyNode = bodyNode;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object[] args = frame.getArguments();
        UplcContext context = (UplcContext) args[0];
        MaterializedFrame capturedFrame = (MaterializedFrame) args[1];
        return bodyNode.execute(capturedFrame, context);
    }

    @Override
    public String getName() {
        return "uplc-delay";
    }
}
