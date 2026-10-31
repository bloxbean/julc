package org.julclang.compiler.frontend.javac;

import com.sun.source.tree.Tree;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.error.CompilerDiagnostic;
import org.julclang.compiler.error.DiagnosticCodes;

import java.util.List;

/**
 * {@code JULC0084}: the javac frontend reached a construct it does not lower yet (ADR-068 D4). A compilation stops here
 * and never falls back (I8). {@link #construct} is the stable name the I1 tool counts: a tree kind, or a phrase for what
 * is not a tree. The message adds the source text and the location; M5's gate is that this never happens on the corpus.
 */
public final class NotPorted extends CompilerException {

    private final String construct;

    private NotPorted(String construct, String shown, String file, int line, int column) {
        super(List.of(diagnostic(shown, file, line, column)));
        this.construct = construct;
    }

    private static CompilerDiagnostic diagnostic(String shown, String file, int line, int column) {
        var info = DiagnosticCodes.FRONTEND_CONSTRUCT_NOT_PORTED;
        return new CompilerDiagnostic(info.level(), info.format(shown), file, line, column, info.fix(), info.code());
    }

    /** The construct at {@code tree}, named by its kind. */
    public static NotPorted at(JavacFacts facts, Tree tree) {
        return at(facts, tree, tree.getKind().name());
    }

    /** The construct at {@code tree}, under a name of its own: a phrase, where the tree's kind says too little. */
    public static NotPorted at(JavacFacts facts, Tree tree, String construct) {
        var at = facts.location(tree);
        return new NotPorted(construct, at.fragment().isEmpty() ? construct : construct + " `" + at.fragment() + "`",
                at.fileName(), at.line(), at.column());
    }

    /** What has no tree to point at, such as a compilation's option. */
    public static NotPorted of(String construct) {
        return new NotPorted(construct, construct, "<source>", 0, 0);
    }

    /** The name of the construct, without its position: what stopped the compilation. */
    public String construct() {
        return construct;
    }
}
