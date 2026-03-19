package com.bloxbean.cardano.julc.vm.truffle.node;

import com.bloxbean.cardano.julc.vm.truffle.UplcContext;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;

/**
 * Root node for a lambda body.
 * <p>
 * Arguments layout:
 * <ul>
 *   <li>[0] = UplcContext</li>
 *   <li>[1] = captured (parent) MaterializedFrame</li>
 *   <li>[2] = argument value being applied</li>
 * </ul>
 * <p>
 * On entry, stores the argument value in slot 0 of the new frame,
 * then executes the body. The captured frame is available via argument[1]
 * for VarNode lookups.
 */
public final class LamBodyRootNode extends RootNode {

    @Child private UplcNode bodyNode;

    public LamBodyRootNode(FrameDescriptor frameDescriptor, UplcNode bodyNode) {
        this(null, frameDescriptor, bodyNode);
    }

    public LamBodyRootNode(TruffleLanguage<?> language, FrameDescriptor frameDescriptor, UplcNode bodyNode) {
        super(language, frameDescriptor);
        this.bodyNode = bodyNode;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object[] args = frame.getArguments();
        UplcContext context = (UplcContext) args[0];
        // args[1] = captured parent frame (available for VarNode chain walking)
        Object argValue = args[2];
        // Store argument in slot 0 for De Bruijn index 1 access
        frame.setObject(0, argValue);
        return bodyNode.execute(frame, context);
    }

    @Override
    public String getName() {
        return "uplc-lambda";
    }
}
