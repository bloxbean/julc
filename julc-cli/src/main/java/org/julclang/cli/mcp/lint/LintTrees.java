package org.julclang.cli.mcp.lint;

import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BindingPatternTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.CatchTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.DeconstructionPatternTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.InstanceOfTree;
import com.sun.source.tree.IntersectionTypeTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.tree.UnionTypeTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WildcardTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import org.julclang.compiler.frontend.javac.JavacSyntax;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Reading javac's syntax trees as the lint rules read JavaParser's (ADR-068 M6). JavaParser and javac group a few
 * things differently, so the rules need these answers: what JavaParser calls a method, a field, a local variable
 * declarator and a name expression, and where a declarator's name starts. Every search is in pre-order, as
 * JavaParser's {@code findAll} is.
 */
public final class LintTrees {

    private LintTrees() {
    }

    /** Every tree of {@code type} under {@code root}, {@code root} included, with its path, in pre-order. */
    public static <T extends Tree> List<TreePath> findAll(TreePath root, Class<T> type) {
        var found = new ArrayList<TreePath>();
        if (type.isInstance(root.getLeaf())) found.add(root);
        new TreePathScanner<Void, Void>() {
            @Override
            public Void scan(Tree tree, Void unused) {
                if (tree != null && type.isInstance(tree)) found.add(new TreePath(getCurrentPath(), tree));
                return super.scan(tree, unused);
            }
        }.scan(root, null);
        // TreePathScanner.scan(TreePath) visits the root itself through scan(Tree) only for its children.
        return found;
    }

    /** {@link #findAll(TreePath, Class)} over the whole unit. */
    public static <T extends Tree> List<TreePath> findAll(JavacSyntax syntax, Class<T> type) {
        return findAll(new TreePath(syntax.unit()), type);
    }

    /** JavaParser's method declarations: every method with a name, not a constructor, not an annotation member. */
    public static List<TreePath> methods(JavacSyntax syntax) {
        return findAll(syntax, MethodTree.class).stream()
                .filter(path -> !((MethodTree) path.getLeaf()).getName().contentEquals("<init>"))
                .filter(path -> !(path.getParentPath().getLeaf() instanceof ClassTree owner
                        && owner.getKind() == Tree.Kind.ANNOTATION_TYPE))
                .toList();
    }

    /** What JavaParser calls a field's variable: a member variable of a class, not a record's component. */
    public static boolean isField(TreePath path) {
        return path.getLeaf() instanceof VariableTree variable
                && path.getParentPath().getLeaf() instanceof ClassTree owner
                && !(owner.getKind() == Tree.Kind.RECORD && !variable.getModifiers().getFlags().contains(Modifier.STATIC));
    }

    /**
     * What JavaParser calls a variable declarator: a field ({@link #isField}) or a local variable, of a statement, a
     * {@code for} header, a resource or an enhanced {@code for}; not a parameter, a catch parameter or a pattern's
     * binding.
     */
    public static boolean isDeclarator(TreePath path) {
        if (!(path.getLeaf() instanceof VariableTree)) return false;
        Tree parent = path.getParentPath().getLeaf();
        if (parent instanceof ClassTree) return isField(path);
        return !(parent instanceof MethodTree || parent instanceof LambdaExpressionTree || parent instanceof CatchTree
                || parent instanceof BindingPatternTree);
    }

    /**
     * Where a declarator's name starts. javac gives a declaration's start, its modifiers' or type's, and keeps one type
     * tree for every declarator of {@code int a, b;}, so the name is found after the type, or after the previous
     * declarator and its comma.
     */
    public static long declaratorNameStart(JavacSyntax syntax, TreePath path) {
        var variable = (VariableTree) path.getLeaf();
        Tree type = variable.getType();
        VariableTree previous = previousSibling(path);
        if (previous != null && previous.getType() == type) {
            long comma = syntax.nextToken(syntax.end(previous));
            if (comma < 0 || syntax.text().charAt((int) comma) != ',') return -1;
            return syntax.nextToken(comma + 1);
        }
        if (type == null) return syntax.start(variable);
        return syntax.nextToken(syntax.end(type));
    }

    private static VariableTree previousSibling(TreePath path) {
        Tree parent = path.getParentPath().getLeaf();
        List<? extends Tree> siblings = switch (parent) {
            case com.sun.source.tree.BlockTree block -> block.getStatements();
            case ClassTree type -> type.getMembers();
            case CaseTree c -> c.getStatements() == null ? List.of() : c.getStatements();
            case com.sun.source.tree.ForLoopTree loop -> loop.getInitializer();
            case com.sun.source.tree.TryTree attempt -> attempt.getResources();
            default -> List.of();
        };
        int at = -1;
        for (int i = 0; i < siblings.size(); i++) {
            if (siblings.get(i) == path.getLeaf()) at = i;
        }
        return at > 0 && siblings.get(at - 1) instanceof VariableTree v ? v : null;
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

    private static boolean isTypePosition(TreePath path) {
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
            case MemberReferenceTree r -> r.getQualifierExpression() == leaf && isTypeLike(leaf);
            case MethodInvocationTree call -> call.getTypeArguments().contains(leaf);
            case com.sun.source.tree.ImportTree i -> true;
            case com.sun.source.tree.PackageTree p -> true;
            default -> false;
        };
    }

    /** A member reference's qualifier: JavaParser reads a capitalised simple name as a type. */
    private static boolean isTypeLike(Tree tree) {
        return tree instanceof IdentifierTree id && !id.getName().isEmpty()
                && Character.isUpperCase(id.getName().charAt(0));
    }

    /** {@code a.b.c} for a simple or qualified name, else null. */
    public static String dottedName(Tree tree) {
        return switch (tree) {
            case IdentifierTree id -> id.getName().toString();
            case MemberSelectTree select -> {
                String qualifier = dottedName(select.getExpression());
                yield qualifier == null ? null : qualifier + "." + select.getIdentifier();
            }
            case null, default -> null;
        };
    }

    /** A call's method name, whether it is qualified or not. */
    public static String methodName(MethodInvocationTree call) {
        return switch (call.getMethodSelect()) {
            case IdentifierTree id -> id.getName().toString();
            case MemberSelectTree select -> select.getIdentifier().toString();
            default -> "";
        };
    }

    /** A call's receiver, or null for an unqualified call. */
    public static Tree receiver(MethodInvocationTree call) {
        return call.getMethodSelect() instanceof MemberSelectTree select ? select.getExpression() : null;
    }

    /** An annotation's name as written, {@code a.b.C} or {@code C}. */
    public static String annotationName(AnnotationTree annotation) {
        String name = dottedName(annotation.getAnnotationType());
        return name == null ? annotation.getAnnotationType().toString() : name;
    }

    /** The finding's position: {@code tree}'s start. */
    public static int line(JavacSyntax syntax, Tree tree) {
        return syntax.line(tree);
    }

    /** The finding's column: {@code tree}'s start (ADR-068 I7). */
    public static int column(JavacSyntax syntax, Tree tree) {
        return syntax.column(tree);
    }
}
