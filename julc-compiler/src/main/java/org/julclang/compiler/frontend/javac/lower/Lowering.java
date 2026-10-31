package org.julclang.compiler.frontend.javac.lower;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.ParenthesizedTree;
import org.julclang.compiler.CompilationContext;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.frontend.javac.NotPorted;
import org.julclang.compiler.pir.PirTerm;

/**
 * The javac trees of a program to PIR (ADR-068 D2): the one dispatch over expressions and the construct families around
 * it. A family lowers its construct and comes back here for its parts, so this page is the map of what is covered. Reads
 * javac only through {@link JavacFacts}; assumes validated input (I12), so a tree kind not listed here is
 * {@code JULC0084}, naming the construct, and never falls back (I8).
 */
public final class Lowering {

    private final JavacFacts facts;
    private final Terms terms;
    private final Literals literals;
    private final Names names;
    private final Statements statements;

    public Lowering(JavacFacts facts, CompilationContext context) {
        this.facts = facts;
        this.terms = new Terms(facts, context);
        this.literals = new Literals(facts, terms);
        this.names = new Names(facts, terms);
        this.statements = new Statements(facts, terms, this);
    }

    public Terms terms() {
        return terms;
    }

    /** A method: a lambda over its parameters, as the reference's {@code generateMethod} builds it. */
    public PirTerm method(MethodTree tree, Scope scope, boolean guardBoolean) {
        return statements.method(tree, scope, guardBoolean);
    }

    /** A field initializer. */
    public PirTerm initializer(ExpressionTree tree, Scope scope) {
        return expr(tree, Frame.initializer(scope));
    }

    /** The one dispatch over expressions. */
    PirTerm expr(ExpressionTree tree, Frame frame) {
        return switch (tree) {
            case LiteralTree t -> literals.literal(t);
            case NewArrayTree t -> literals.byteArray(t);
            case IdentifierTree t -> names.identifier(t, frame);
            case MemberSelectTree t -> names.constant(t);
            case ParenthesizedTree t -> expr(t.getExpression(), frame);
            default -> throw NotPorted.at(facts, tree);
        };
    }
}
