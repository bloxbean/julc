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
import org.julclang.compiler.frontend.javac.SourceOrderScanner;
import org.julclang.compiler.frontend.javac.ExpressionRoles;
import org.julclang.compiler.frontend.javac.JavacSyntax;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Reading javac's syntax trees as the lint rules read JavaParser's (ADR-068 M6). JavaParser and javac group a few
 * things differently, so the rules need these answers: what JavaParser calls a method, a field, a local variable
 * declarator and a name expression, and where a declarator's name starts. Every search is in pre-order, as
 * JavaParser's {@code findAll} is.
 * <p><b>Internal</b> (ADR-068 M6): for JuLC's own tools; not an API,
 * and it may change or go without notice.
 */
public final class LintTrees {

    private LintTrees() {
    }

    /** Every tree of {@code type} under {@code root}, {@code root} included, with its path, in pre-order. */
    public static <T extends Tree> List<TreePath> findAll(TreePath root, Class<T> type) {
        var found = new ArrayList<TreePath>();
        if (type.isInstance(root.getLeaf())) found.add(root);
        new SourceOrderScanner<Void, Void>() {
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
                && !(owner.getKind() == Tree.Kind.RECORD && !variable.getModifiers().getFlags().contains(Modifier.STATIC))
                && !isEnumConstant(owner, variable);
    }

    /**
     * javac's parser holds an enum constant as a variable of the enum's type initialised by a {@code new} of it, which no
     * field of an enum can be otherwise; JavaParser holds it apart from fields.
     */
    public static boolean isEnumConstant(ClassTree owner, VariableTree variable) {
        return owner.getKind() == Tree.Kind.ENUM
                && variable.getInitializer() instanceof com.sun.source.tree.NewClassTree creation
                && creation.getIdentifier() instanceof IdentifierTree type
                && type.getName().contentEquals(owner.getSimpleName());
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
     * Where a declarator's name starts. javac gives a declaration's start, its modifiers' or type's, and keeps one element
     * type tree for every declarator of {@code int a, b[];}, so the first name is found after the element type and any
     * {@code []}, and each later one after the previous declarator and its comma. {@code var x;} has no type tree.
     */
    public static long declaratorNameStart(JavacSyntax syntax, TreePath path) {
        List<VariableTree> group = declarationGroup(path);
        var first = group.getFirst();
        long name = firstNameStart(syntax, first);
        for (int i = 1; i < group.size(); i++) {
            var before = group.get(i - 1);
            long after = before.getInitializer() != null ? syntax.end(before.getInitializer())
                    : afterNameAndDimensions(syntax, name);
            long comma = syntax.nextToken(after);
            if (comma < 0 || syntax.text().charAt((int) comma) != ',') return -1;
            name = syntax.nextToken(comma + 1);
        }
        return name;
    }

    private static long firstNameStart(JavacSyntax syntax, VariableTree variable) {
        Tree type = variable.getType();
        String text = syntax.text();
        if (type == null) {
            // `var x;` or `final var x;`: the name follows `var`.
            long at = variable.getModifiers().getFlags().isEmpty() && variable.getModifiers().getAnnotations().isEmpty()
                    ? syntax.start(variable) : syntax.end(variable.getModifiers());
            at = syntax.nextToken(at);
            if (at < 0 || !text.startsWith("var", (int) at)) return -1;
            return syntax.nextToken(at + 3);
        }
        long at = syntax.nextToken(syntax.end(innermost(type)));
        while (at >= 0 && text.charAt((int) at) == '[') {
            long close = syntax.nextToken(at + 1);
            if (close < 0 || text.charAt((int) close) != ']') return -1;
            at = syntax.nextToken(close + 1);
        }
        return at;
    }

    /** The declarators of the declaration {@code path}'s variable is in, up to it: those sharing its element type tree. */
    private static List<VariableTree> declarationGroup(TreePath path) {
        var variable = (VariableTree) path.getLeaf();
        List<? extends Tree> siblings = siblings(path);
        int at = -1;
        for (int i = 0; i < siblings.size(); i++) {
            if (siblings.get(i) == variable) at = i;
        }
        var group = new ArrayList<VariableTree>();
        group.add(variable);
        Tree element = variable.getType() == null ? null : innermost(variable.getType());
        for (int i = at - 1; i >= 0 && element != null; i--) {
            if (!(siblings.get(i) instanceof VariableTree before) || before.getType() == null
                    || innermost(before.getType()) != element) {
                break;
            }
            group.addFirst(before);
        }
        return group;
    }

    /** The offset after an identifier at {@code name} (Unicode escapes included) and any {@code []} after it. */
    private static long afterNameAndDimensions(JavacSyntax syntax, long name) {
        if (name < 0) return -1;
        String text = syntax.text();
        int i = (int) name;
        while (i < text.length()) {
            if (text.startsWith("\\u", i)) {
                int k = i + 1;
                while (k < text.length() && text.charAt(k) == 'u') k++;
                i = k + 4;
            } else if (Character.isJavaIdentifierPart(text.charAt(i))) {
                i++;
            } else {
                break;
            }
        }
        long at = i;
        while (true) {
            long open = syntax.nextToken(at);
            if (open < 0 || text.charAt((int) open) != '[') return at;
            long close = syntax.nextToken(open + 1);
            if (close < 0 || text.charAt((int) close) != ']') return at;
            at = close + 1;
        }
    }

    /** An array type's element type, at any depth; the type itself otherwise. */
    private static Tree innermost(Tree type) {
        while (type instanceof ArrayTypeTree array) type = array.getType();
        return type;
    }

    private static List<? extends Tree> siblings(TreePath path) {
        return switch (path.getParentPath().getLeaf()) {
            case com.sun.source.tree.BlockTree block -> block.getStatements();
            case ClassTree type -> type.getMembers();
            case CaseTree c -> c.getStatements() == null ? List.of() : c.getStatements();
            case com.sun.source.tree.ForLoopTree loop -> loop.getInitializer();
            case com.sun.source.tree.TryTree attempt -> attempt.getResources();
            default -> List.of();
        };
    }

    /** What JavaParser calls a name expression ({@link ExpressionRoles#isNameExpression}). */
    public static boolean isNameExpression(TreePath path) {
        return ExpressionRoles.isNameExpression(path);
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
