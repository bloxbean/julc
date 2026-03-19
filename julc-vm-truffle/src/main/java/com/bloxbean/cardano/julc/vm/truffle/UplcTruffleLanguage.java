package com.bloxbean.cardano.julc.vm.truffle;

import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.core.source.SourceMap;
import com.bloxbean.cardano.julc.vm.truffle.convert.TermToNodeConverter;
import com.bloxbean.cardano.julc.vm.truffle.node.UplcNode;
import com.bloxbean.cardano.julc.vm.truffle.node.UplcRootNode;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.instrumentation.ProvidedTags;
import com.oracle.truffle.api.instrumentation.StandardTags;

/**
 * TruffleLanguage registration for Untyped Plutus Core.
 * <p>
 * Required for Truffle's instrumentation and debugger framework to discover
 * and attach to UPLC nodes. The {@code @ProvidedTags} annotation declares that
 * our language supports {@link StandardTags.StatementTag}, enabling the Truffle
 * debugger to set breakpoints and step through statement-tagged nodes.
 * <p>
 * For normal evaluation (no debugger), use {@link TruffleVmProvider} directly.
 * For debug sessions, the caller sets the pending term/context via {@link #setPending}
 * before calling {@code context.eval("uplc", "")}.
 */
@TruffleLanguage.Registration(id = UplcTruffleLanguage.ID, name = "Untyped Plutus Core", version = "1.1.0")
@ProvidedTags(StandardTags.StatementTag.class)
public final class UplcTruffleLanguage extends TruffleLanguage<UplcTruffleLanguage.Ctx> {

    public static final String ID = "uplc";

    private record PendingRequest(Term term, SourceMap sourceMap, UplcContext context) {}

    private static final ThreadLocal<PendingRequest> PENDING = new ThreadLocal<>();

    record Ctx(Env env) {}

    @Override
    protected Ctx createContext(Env env) {
        return new Ctx(env);
    }

    @Override
    protected CallTarget parse(ParsingRequest request) {
        PendingRequest pending = PENDING.get();
        if (pending == null) {
            throw new UnsupportedOperationException(
                    "No pending request. Use UplcTruffleLanguage.setPending() before eval, " +
                    "or use TruffleVmProvider for non-debug evaluation.");
        }
        // Convert Term → Truffle AST inside parse() so nodes belong to this language's sharing layer
        UplcNode bodyNode = TermToNodeConverter.convert(pending.term, pending.sourceMap, this);
        var fd = FrameDescriptor.newBuilder().build();
        var rootNode = new UplcRootNode(this, fd, bodyNode);
        return rootNode.getCallTarget();
    }

    /**
     * Set the pending UPLC term, source map, and context for the next {@link #parse} call.
     * Must be called in a try/finally block with {@link #clearPending()}.
     */
    public static void setPending(Term term, SourceMap sourceMap, UplcContext context) {
        PENDING.set(new PendingRequest(term, sourceMap, context));
    }

    /**
     * Retrieve the pending execution context.
     */
    public static UplcContext getPendingContext() {
        PendingRequest pending = PENDING.get();
        return pending != null ? pending.context : null;
    }

    /**
     * Clear the thread-local pending state to prevent leaks.
     */
    public static void clearPending() {
        PENDING.remove();
    }
}
