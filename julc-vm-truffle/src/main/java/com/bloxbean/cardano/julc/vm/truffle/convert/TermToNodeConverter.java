package com.bloxbean.cardano.julc.vm.truffle.convert;

import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.core.source.SourceLocation;
import com.bloxbean.cardano.julc.core.source.SourceMap;
import com.bloxbean.cardano.julc.vm.java.builtins.BuiltinTable;
import com.bloxbean.cardano.julc.vm.truffle.UplcTruffleLanguage;
import com.bloxbean.cardano.julc.vm.truffle.node.*;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.source.Source;

import java.util.HashMap;
import java.util.Map;

/**
 * Converts a {@link Term} (UPLC AST) into a Truffle {@link UplcNode} tree.
 * <p>
 * Each Term variant maps to a corresponding UplcNode subclass.
 * Lambda and Delay bodies are wrapped in their own RootNodes with
 * fresh FrameDescriptors, enabling proper closure capture.
 * <p>
 * When a {@link SourceMap} is provided, each converted node gets a
 * {@link com.oracle.truffle.api.source.SourceSection} attached, enabling
 * Truffle instrumentation (breakpoints, stepping, etc.).
 */
public final class TermToNodeConverter {

    private final SourceMap sourceMap;
    private final TruffleLanguage<?> language;
    private final Map<String, Source> sourceCache;

    private TermToNodeConverter(SourceMap sourceMap, TruffleLanguage<?> language) {
        this.sourceMap = sourceMap;
        this.language = language;
        this.sourceCache = sourceMap != null ? new HashMap<>() : null;
    }

    /**
     * Convert a UPLC term to a Truffle node tree (no source map, no language).
     */
    public static UplcNode convert(Term term) {
        return convert(term, null, null);
    }

    /**
     * Convert a UPLC term to a Truffle node tree with optional source map.
     *
     * @param term      the UPLC term to convert
     * @param sourceMap source map for instrumentation (nullable)
     * @return the root UplcNode of the Truffle AST
     */
    public static UplcNode convert(Term term, SourceMap sourceMap) {
        return convert(term, sourceMap, null);
    }

    /**
     * Convert a UPLC term to a Truffle node tree with optional source map and language.
     *
     * @param term      the UPLC term to convert
     * @param sourceMap source map for instrumentation (nullable)
     * @param language  the TruffleLanguage instance (nullable)
     * @return the root UplcNode of the Truffle AST
     */
    public static UplcNode convert(Term term, SourceMap sourceMap, TruffleLanguage<?> language) {
        return new TermToNodeConverter(sourceMap, language).convertTerm(term);
    }

    private UplcNode convertTerm(Term term) {
        UplcNode node = switch (term) {
            case Term.Var v -> new VarNode(term, v.name().index());

            case Term.Const c -> new ConstNode(term, c.value());

            case Term.Lam lam -> {
                // Create a separate RootNode for the lambda body with one slot (for the arg)
                UplcNode bodyNode = convertTerm(lam.body());
                var builder = FrameDescriptor.newBuilder();
                builder.addSlot(com.oracle.truffle.api.frame.FrameSlotKind.Object, "arg", null);
                var fd = builder.build();
                var rootNode = new LamBodyRootNode(language, fd, bodyNode);
                CallTarget callTarget = rootNode.getCallTarget();
                yield new LamNode(term, callTarget);
            }

            case Term.Apply app -> {
                UplcNode functionNode = convertTerm(app.function());
                UplcNode argumentNode = convertTerm(app.argument());
                yield new ApplyNode(term, functionNode, argumentNode);
            }

            case Term.Force f -> {
                UplcNode innerNode = convertTerm(f.term());
                yield new ForceNode(term, innerNode);
            }

            case Term.Delay d -> {
                // Create a separate RootNode for the delay body (no slots needed)
                UplcNode bodyNode = convertTerm(d.term());
                var fd = FrameDescriptor.newBuilder().build();
                var rootNode = new DelayBodyRootNode(language, fd, bodyNode);
                CallTarget callTarget = rootNode.getCallTarget();
                yield new DelayNode(term, callTarget);
            }

            case Term.Builtin b -> {
                var sig = BuiltinTable.getSignature(b.fun());
                yield new BuiltinRefNode(term, b.fun(), sig.forceCount(), sig.arity());
            }

            case Term.Error _ -> new ErrorNode(term);

            case Term.Constr constr -> {
                UplcNode[] fieldNodes = new UplcNode[constr.fields().size()];
                for (int i = 0; i < fieldNodes.length; i++) {
                    fieldNodes[i] = convertTerm(constr.fields().get(i));
                }
                yield new ConstrNode(term, constr.tag(), fieldNodes);
            }

            case Term.Case cs -> {
                UplcNode scrutineeNode = convertTerm(cs.scrutinee());
                UplcNode[] branchNodes = new UplcNode[cs.branches().size()];
                for (int i = 0; i < branchNodes.length; i++) {
                    branchNodes[i] = convertTerm(cs.branches().get(i));
                }
                yield new CaseNode(term, scrutineeNode, branchNodes);
            }
        };
        attachSourceSection(node, term);
        return node;
    }

    private void attachSourceSection(UplcNode node, Term term) {
        if (sourceMap == null) return;
        SourceLocation loc = sourceMap.lookup(term);
        if (loc == null) return;
        Source src = sourceCache.computeIfAbsent(loc.fileName(),
                name -> Source.newBuilder(UplcTruffleLanguage.ID, "", name)
                        .content(Source.CONTENT_NONE).build());
        node.setSourceSection(src.createSection(loc.line()));
        node.setSourceLocation(loc);
    }
}
