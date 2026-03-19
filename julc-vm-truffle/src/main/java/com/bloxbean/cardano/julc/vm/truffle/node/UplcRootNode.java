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
        // Direct path: args[0] = UplcContext
        // Polyglot path (debugger): no UplcContext in args — use thread-local
        Object[] args = frame.getArguments();
        boolean directPath = args.length > 0 && args[0] instanceof UplcContext;
        UplcContext context;
        if (directPath) {
            context = (UplcContext) args[0];
        } else {
            context = com.bloxbean.cardano.julc.vm.truffle.UplcTruffleLanguage.getPendingContext();
            if (context == null) {
                throw new IllegalStateException("No UplcContext available");
            }
        }
        Object result = bodyNode.execute(frame, context);
        if (!directPath) {
            // Polyglot path: capture result for debugger retrieval, return interop-safe value
            context.setCapturedResult(result);
            return 0;
        }
        return result;
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
