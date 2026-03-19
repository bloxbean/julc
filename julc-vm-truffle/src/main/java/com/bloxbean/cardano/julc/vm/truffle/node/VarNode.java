package com.bloxbean.cardano.julc.vm.truffle.node;

import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.vm.java.cost.MachineCosts.StepKind;
import com.bloxbean.cardano.julc.vm.truffle.UplcContext;
import com.bloxbean.cardano.julc.vm.truffle.runtime.UplcRuntimeException;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.MaterializedFrame;

/**
 * De Bruijn variable lookup.
 * <p>
 * Walks up the chain of captured frames (via argument[1]) to find the
 * binding at the specified De Bruijn index. Index 1 = most recent binding.
 * <p>
 * Frame layout: each frame stores its single lambda binding at slot 0,
 * and the parent (captured) frame as argument[1].
 */
public final class VarNode extends UplcNode {

    private final int deBruijnIndex;

    public VarNode(Term sourceTerm, int deBruijnIndex) {
        super(sourceTerm);
        this.deBruijnIndex = deBruijnIndex;
    }

    @Override
    public Object execute(Frame frame, UplcContext context) {
        context.getCostTracker().chargeMachineStep(StepKind.VAR);
        // Walk up the frame chain: index 1 = current frame's slot 0,
        // index 2 = parent frame's slot 0, etc.
        Frame current = frame;
        for (int i = 1; i < deBruijnIndex; i++) {
            Object[] args = current.getArguments();
            if (args.length < 2 || !(args[1] instanceof MaterializedFrame parent)) {
                throw new UplcRuntimeException(
                        "Variable index " + deBruijnIndex + " out of scope",
                        getSourceTerm(), this);
            }
            current = parent;
        }
        try {
            return current.getObject(0);
        } catch (Exception e) {
            throw new UplcRuntimeException(
                    "Variable index " + deBruijnIndex + " out of scope",
                    getSourceTerm(), this);
        }
    }
}
