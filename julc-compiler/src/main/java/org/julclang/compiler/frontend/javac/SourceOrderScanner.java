package org.julclang.compiler.frontend.javac;

import com.sun.source.tree.CaseTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePathScanner;

import java.util.ArrayList;

/**
 * A {@link TreePathScanner} in the order JuLC's tools have always visited a source (JavaParser's): a {@code case}'s body
 * before its guard, and an enum's other members before its constants. javac's own scanner visits the guard first and the
 * constants first. The order is the order of what the tools list: the first call a lint rule reports, the order in
 * which library references are resolved (ADR-068 M6).
 * <p><b>Internal</b> (ADR-068 M6): for JuLC's own tools while JavaParser and javac readings coexist; not an API,
 * and it may change or go without notice.
 */
public class SourceOrderScanner<R, P> extends TreePathScanner<R, P> {

    @Override
    public R visitClass(ClassTree node, P p) {
        if (node.getKind() != Tree.Kind.ENUM) return super.visitClass(node, p);
        R r = scan(node.getModifiers(), p);
        r = reduce(scan(node.getTypeParameters(), p), r);
        r = reduce(scan(node.getExtendsClause(), p), r);
        r = reduce(scan(node.getImplementsClause(), p), r);
        r = reduce(scan(node.getPermitsClause(), p), r);
        var constants = new ArrayList<Tree>();
        for (Tree member : node.getMembers()) {
            if (isConstant(node, member)) constants.add(member);
            else r = reduce(scan(member, p), r);
        }
        return reduce(scan(constants, p), r);
    }

    /** An enum constant: a variable of the enum's type that a {@code new} of it initialises, which no other field is. */
    private static boolean isConstant(ClassTree owner, Tree member) {
        return member instanceof VariableTree variable
                && variable.getInitializer() instanceof NewClassTree creation
                && creation.getIdentifier() instanceof IdentifierTree type
                && type.getName().contentEquals(owner.getSimpleName());
    }

    @Override
    public R visitCase(CaseTree node, P p) {
        R r = scan(node.getLabels(), p);
        r = reduce(node.getCaseKind() == CaseTree.CaseKind.RULE ? scan(node.getBody(), p) : scan(node.getStatements(), p), r);
        r = reduce(scan(node.getGuard(), p), r);
        return r;
    }
}
