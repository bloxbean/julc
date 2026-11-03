package org.julclang.compiler.frontend.javac;

import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.DeconstructionPatternTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.InstanceOfTree;
import com.sun.source.tree.IntersectionTypeTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.PackageTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.tree.UnionTypeTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WildcardTree;
import com.sun.source.util.TreePath;

/**
 * Where a name or a member select stands in javac's syntax tree, for JuLC's tools that read a source's shape (ADR-068
 * M6). javac uses one tree for a name whether it is a value, a type, a package or a method's name; these tell the
 * value uses apart, as the tools have always told them apart: a name expression and a field access are values, or the
 * qualifier of one.
 * <p><b>Internal</b> (ADR-068 M6): for JuLC's own tools while JavaParser and javac readings coexist; not an API,
 * and it may change or go without notice.
 */
public final class ExpressionRoles {

    private ExpressionRoles() {
    }

    /**
     * Whether an identifier is what JavaParser calls a name expression: a simple name used as a value, or as the
     * qualifier of a member access. Not a method's name, a type, {@code this} or {@code super}, an annotation's
     * element, or a part of a type or class literal.
     */
    public static boolean isNameExpression(TreePath path) {
        if (!(path.getLeaf() instanceof IdentifierTree identifier)) return false;
        String name = identifier.getName().toString();
        if (name.equals("this") || name.equals("super")) return false;
        Tree parent = path.getParentPath().getLeaf();
        if (parent instanceof MethodInvocationTree call && call.getMethodSelect() == identifier) return false;
        if (parent instanceof AssignmentTree assignment && assignment.getVariable() == identifier
                && path.getParentPath().getParentPath().getLeaf() instanceof AnnotationTree) {
            return false;
        }
        // Up a qualified name to its outermost select: the name is a type's, a class literal's or this's if that is.
        TreePath top = path;
        while (top.getParentPath().getLeaf() instanceof MemberSelectTree select && select.getExpression() == top.getLeaf()) {
            top = top.getParentPath();
            String member = select.getIdentifier().toString();
            if (member.equals("class") || member.equals("this") || member.equals("super")) return false;
        }
        if (top.getParentPath().getLeaf() instanceof MethodInvocationTree call && call.getMethodSelect() == top.getLeaf()
                && top != path) {
            return true;
        }
        return !isTypePosition(top);
    }

    static boolean isTypePosition(TreePath path) {
        Tree leaf = path.getLeaf();
        Tree parent = path.getParentPath().getLeaf();
        return switch (parent) {
            case VariableTree v -> v.getType() == leaf;
            case MethodTree m -> m.getReturnType() == leaf || m.getThrows().contains(leaf);
            case NewClassTree n -> n.getIdentifier() == leaf || n.getTypeArguments().contains(leaf);
            case TypeCastTree c -> c.getType() == leaf;
            case InstanceOfTree i -> i.getType() == leaf;
            case DeconstructionPatternTree d -> d.getDeconstructor() == leaf;
            case ParameterizedTypeTree p -> true;
            case ArrayTypeTree a -> true;
            case WildcardTree w -> true;
            case UnionTypeTree u -> true;
            case IntersectionTypeTree i -> true;
            case TypeParameterTree t -> true;
            case AnnotatedTypeTree a -> true;
            case AnnotationTree a -> a.getAnnotationType() == leaf;
            case ClassTree c -> true;
            case NewArrayTree n -> n.getType() == leaf;
            // JavaParser reads every name or dotted name before `::` as a type, `foo::bar` too, and its type arguments.
            case MemberReferenceTree r -> r.getQualifierExpression() == leaf || r.getTypeArguments() != null
                    && r.getTypeArguments().contains(leaf);
            case MethodInvocationTree call -> call.getTypeArguments().contains(leaf);
            case ImportTree i -> true;
            case PackageTree p -> true;
            default -> false;
        };
    }


    /**
     * Whether a member select is a field access: a value, or the qualifier of a value or of a call. Not a call's
     * method, a type, a package or an import, and not a class literal, a qualified {@code this} or {@code super}.
     */
    public static boolean isFieldAccess(TreePath path) {
        if (!(path.getLeaf() instanceof MemberSelectTree select)) return false;
        if (isSpecial(select)) return false;
        Tree parent = path.getParentPath().getLeaf();
        if (parent instanceof MethodInvocationTree call && call.getMethodSelect() == select) return false;
        TreePath top = path;
        while (top.getParentPath().getLeaf() instanceof MemberSelectTree outer && outer.getExpression() == top.getLeaf()) {
            top = top.getParentPath();
            if (isSpecial(outer)) return false;
        }
        if (top != path && top.getParentPath().getLeaf() instanceof MethodInvocationTree call
                && call.getMethodSelect() == top.getLeaf()) {
            return true;
        }
        return !isTypePosition(top);
    }

    private static boolean isSpecial(MemberSelectTree select) {
        String member = select.getIdentifier().toString();
        return member.equals("class") || member.equals("this") || member.equals("super");
    }
}
