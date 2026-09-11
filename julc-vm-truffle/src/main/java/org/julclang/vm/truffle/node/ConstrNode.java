package org.julclang.vm.truffle.node;

import org.julclang.core.Term;
import org.julclang.vm.UplcVersion;
import org.julclang.vm.java.cost.MachineCosts.StepKind;
import org.julclang.vm.truffle.UplcContext;
import org.julclang.vm.truffle.runtime.UplcConstrValue;
import org.julclang.vm.truffle.runtime.UplcRuntimeException;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.nodes.ExplodeLoop;

/**
 * UPLC 1.1 constructor — evaluates fields and returns a UplcConstrValue.
 */
public final class ConstrNode extends UplcNode {

    private final long tag;
    @Children private final UplcNode[] fieldNodes;

    public ConstrNode(Term sourceTerm, long tag, UplcNode[] fieldNodes) {
        super(sourceTerm);
        this.tag = tag;
        this.fieldNodes = fieldNodes;
    }

    @Override
    @ExplodeLoop
    public Object execute(Frame frame, UplcContext context) {
        if (!context.getProfile().availableUplcVersions().contains(UplcVersion.V1_1_0)) {
            throw new UplcRuntimeException(
                    "Constr term is not available for " + context.getProfile().target(),
                    getSourceTerm(), this);
        }
        context.getCostTracker().chargeMachineStep(StepKind.CONSTR);

        Object[] fields = new Object[fieldNodes.length];
        for (int i = 0; i < fieldNodes.length; i++) {
            fields[i] = fieldNodes[i].execute(frame, context);
        }
        return new UplcConstrValue(tag, fields);
    }
}
