package org.julclang.vm.truffle.node;

import org.julclang.core.Term;
import org.julclang.vm.truffle.UplcContext;
import org.julclang.vm.truffle.runtime.UplcRuntimeException;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.instrumentation.StandardTags.StatementTag;
import com.oracle.truffle.api.instrumentation.Tag;

/**
 * Throws a runtime error — halts evaluation.
 */
public final class ErrorNode extends UplcNode {

    public ErrorNode(Term sourceTerm) {
        super(sourceTerm);
    }

    @Override
    public boolean hasTag(Class<? extends Tag> tag) {
        return tag == StatementTag.class;
    }

    @Override
    public Object execute(Frame frame, UplcContext context) {
        context.recordTrace(this, "Error");
        throw throwError();
    }

    @TruffleBoundary
    private UplcRuntimeException throwError() {
        throw new UplcRuntimeException("Error term encountered", getSourceTerm(), this);
    }
}
