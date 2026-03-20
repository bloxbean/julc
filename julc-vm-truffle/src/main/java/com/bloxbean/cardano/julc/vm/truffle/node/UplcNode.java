package com.bloxbean.cardano.julc.vm.truffle.node;

import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.core.source.SourceLocation;
import com.bloxbean.cardano.julc.vm.truffle.UplcContext;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.instrumentation.InstrumentableNode;
import com.oracle.truffle.api.instrumentation.ProbeNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.source.SourceSection;

/**
 * Abstract base class for all UPLC AST nodes in the Truffle interpreter.
 * <p>
 * Each node represents a UPLC term and implements {@link #execute(Frame, UplcContext)}
 * which evaluates the term and returns a Truffle runtime value.
 * <p>
 * Implements {@link InstrumentableNode} to support Truffle debugging/instrumentation.
 * A node is instrumentable only when a {@link SourceSection} has been attached
 * (i.e., when a source map is available). Without a source map, instrumentation
 * adds zero overhead.
 */
public abstract class UplcNode extends Node implements InstrumentableNode {

    /** The original UPLC term this node was created from (for source map / error reporting). */
    private final Term sourceTerm;

    /** Source section for instrumentation — null when no source map is provided. */
    @CompilationFinal private SourceSection sourceSection;

    /** Original source location for execution tracing — null when no source map is provided. */
    @CompilationFinal private SourceLocation sourceLocation;

    protected UplcNode(Term sourceTerm) {
        this.sourceTerm = sourceTerm;
    }

    /**
     * Execute this node, returning a Truffle runtime value.
     * <p>
     * Return types:
     * <ul>
     *   <li>{@link com.bloxbean.cardano.julc.core.Constant} — constant values</li>
     *   <li>{@link com.bloxbean.cardano.julc.vm.truffle.runtime.UplcClosure} — lambda closures</li>
     *   <li>{@link com.bloxbean.cardano.julc.vm.truffle.runtime.UplcDelay} — delayed thunks</li>
     *   <li>{@link com.bloxbean.cardano.julc.vm.truffle.runtime.UplcBuiltinDescriptor} — partial builtins</li>
     *   <li>{@link com.bloxbean.cardano.julc.vm.truffle.runtime.UplcConstrValue} — constructor values</li>
     * </ul>
     */
    public abstract Object execute(Frame frame, UplcContext context);

    /** The original UPLC term (for error reporting). */
    public Term getSourceTerm() {
        return sourceTerm;
    }

    // --- SourceSection (for instrumentation) ---

    /**
     * Attach a source section to this node. Called during AST construction
     * when a source map is available.
     */
    public void setSourceSection(SourceSection sourceSection) {
        this.sourceSection = sourceSection;
    }

    /**
     * Attach a source location to this node for execution tracing.
     * Called during AST construction alongside {@link #setSourceSection}.
     */
    public void setSourceLocation(SourceLocation sourceLocation) {
        this.sourceLocation = sourceLocation;
    }

    /** The original Java source location (for execution tracing). */
    public SourceLocation getSourceLocation() {
        return sourceLocation;
    }

    @Override
    public SourceSection getSourceSection() {
        return sourceSection;
    }

    // --- InstrumentableNode ---

    @Override
    public boolean isInstrumentable() {
        return sourceSection != null;
    }

    @Override
    public boolean hasTag(Class<? extends com.oracle.truffle.api.instrumentation.Tag> tag) {
        return false;
    }

    @Override
    public WrapperNode createWrapper(ProbeNode probeNode) {
        return new UplcNodeWrapper(this, probeNode);
    }
}
