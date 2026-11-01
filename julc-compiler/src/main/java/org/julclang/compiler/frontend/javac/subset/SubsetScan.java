package org.julclang.compiler.frontend.javac.subset;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreeScanner;
import org.julclang.compiler.CompilerTypeDiagnostics;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.frontend.javac.JavacNames;
import org.julclang.compiler.frontend.javac.NotPorted;

import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import java.util.EnumSet;
import java.util.Set;

/**
 * ADR-068 I2c at its first size: an allow-list of tree kinds, with the contextual rules each needs, over declarations and
 * method bodies. A method nothing lowers does not run, but the JavaParser frontend's {@code SubsetValidator} still reads
 * it, so what that frontend rejects there is {@code JULC0084} here until its rule is ported. That is what the list decides,
 * and {@code JULC0084} fails closed.
 * <p>
 * <b>A kind goes on this list when lowering reaches it, and it is allowed where lowering reaches it, not everywhere.</b> A
 * field initializer and an initializer block run when a class is loaded, whether or not anything lowers them, so this scan
 * reads only their literals: a lowered one is checked by the lowering, and any other by {@link UnloweredInitializers},
 * whose list is closed. An annotation's arguments are constants, read the same way.
 * <p>
 * The list grows with each ported construct, and takes on {@code SubsetValidator}'s rules and codes as it does; it is not
 * that validator yet.
 */
public final class SubsetScan extends TreeScanner<Void, Void> {

    private static final Set<Tree.Kind> ALLOWED = EnumSet.of(
            Tree.Kind.COMPILATION_UNIT, Tree.Kind.PACKAGE, Tree.Kind.IMPORT, Tree.Kind.MODIFIERS, Tree.Kind.ANNOTATION,
            Tree.Kind.CLASS, Tree.Kind.INTERFACE, Tree.Kind.RECORD, Tree.Kind.METHOD, Tree.Kind.VARIABLE,
            Tree.Kind.BLOCK, Tree.Kind.RETURN,
            Tree.Kind.PRIMITIVE_TYPE, Tree.Kind.ARRAY_TYPE, Tree.Kind.PARAMETERIZED_TYPE, Tree.Kind.TYPE_PARAMETER,
            Tree.Kind.UNBOUNDED_WILDCARD, Tree.Kind.EXTENDS_WILDCARD, Tree.Kind.SUPER_WILDCARD,
            Tree.Kind.IDENTIFIER, Tree.Kind.MEMBER_SELECT, Tree.Kind.PARENTHESIZED, Tree.Kind.NEW_ARRAY,
            Tree.Kind.INT_LITERAL, Tree.Kind.LONG_LITERAL, Tree.Kind.BOOLEAN_LITERAL, Tree.Kind.STRING_LITERAL);

    private final JavacFacts facts;

    private SubsetScan(JavacFacts facts) {
        this.facts = facts;
    }

    public static void check(JavacFacts facts, CompilationUnitTree unit) {
        new SubsetScan(facts).scan(unit, null);
    }

    @Override
    public Void scan(Tree tree, Void unused) {
        if (tree != null && !ALLOWED.contains(tree.getKind())) throw NotPorted.at(facts, tree);
        return super.scan(tree, unused);
    }

    /** A constructor javac generated is not source: its {@code super()} call is no construct of the program. */
    @Override
    public Void visitMethod(MethodTree tree, Void unused) {
        return facts.isGenerated(tree) ? null : super.visitMethod(tree, unused);
    }

    /** {@code return} alone is ported, and in code that is not lowered a statement can still be a rejected one. */
    @Override
    public Void visitBlock(BlockTree tree, Void unused) {
        if (facts.parent(tree) instanceof ClassTree) return literalsOf(tree);
        for (var statement : tree.getStatements()) {
            if (statement.getKind() != Tree.Kind.RETURN) throw NotPorted.at(facts, statement);
        }
        return super.visitBlock(tree, unused);
    }

    /** A field's initializer is not this list's to judge: see the class comment. */
    @Override
    public Void visitVariable(VariableTree tree, Void unused) {
        if (facts.parent(tree) instanceof ClassTree) {
            scan(tree.getModifiers(), null);
            scan(tree.getType(), null);
            return literalsOf(tree.getInitializer());
        }
        return super.visitVariable(tree, unused);
    }

    /** An annotation's arguments are constants, and {@code name = value} is how one is written, and nowhere else. */
    @Override
    public Void visitAnnotation(AnnotationTree tree, Void unused) {
        return literalsOf(tree);
    }

    /** The literals of a subtree no kind check applies to. */
    private Void literalsOf(Tree tree) {
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitLiteral(LiteralTree literal, Void ignored) {
                requirePairedSurrogates(literal);
                return null;
            }
        }.scan(tree, null);
        return null;
    }

    /** Inheritance is outside the subset. An interface's {@code extends} is javac's implements clause. */
    @Override
    public Void visitClass(ClassTree tree, Void unused) {
        boolean inherits = tree.getKind() == Tree.Kind.INTERFACE ? !tree.getImplementsClause().isEmpty()
                : tree.getExtendsClause() != null && !isObject(tree.getExtendsClause());
        if (tree.getKind() != Tree.Kind.RECORD && inherits) throw NotPorted.at(facts, tree, "inheritance");
        return super.visitClass(tree, unused);
    }

    private boolean isObject(Tree type) {
        return facts.element(type) instanceof TypeElement element && facts.identity(element).equals(JavacNames.OBJECT);
    }

    /** Floating point is outside the subset. */
    @Override
    public Void visitPrimitiveType(PrimitiveTypeTree tree, Void unused) {
        if (tree.getPrimitiveTypeKind() == TypeKind.FLOAT || tree.getPrimitiveTypeKind() == TypeKind.DOUBLE) {
            throw NotPorted.at(facts, tree);
        }
        return null;
    }

    /** {@code this} and {@code super} are identifiers to javac, and outside the subset. */
    @Override
    public Void visitIdentifier(IdentifierTree tree, Void unused) {
        if (isThisOrSuper(tree.getName())) throw NotPorted.at(facts, tree);
        return null;
    }

    /** {@code V.this} and {@code V.super} are member selects to javac. */
    @Override
    public Void visitMemberSelect(MemberSelectTree tree, Void unused) {
        if (isThisOrSuper(tree.getIdentifier())) throw NotPorted.at(facts, tree);
        return super.visitMemberSelect(tree, unused);
    }

    private static boolean isThisOrSuper(CharSequence name) {
        return name.toString().equals("this") || name.toString().equals("super");
    }

    @Override
    public Void visitLiteral(LiteralTree tree, Void unused) {
        requirePairedSurrogates(tree);
        return null;
    }

    /** A UPLC string is Unicode text: a surrogate must be the high half of a pair followed by its low half. */
    private void requirePairedSurrogates(LiteralTree tree) {
        if (!(tree.getValue() instanceof String value)) return;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
                i++;
            } else if (Character.isSurrogate(c)) {
                throw CompilerTypeDiagnostics.unpairedSurrogate(c, facts.location(tree));
            }
        }
    }

    /** The one array: {@code new byte[]{...}} over integer literals, each negated or not. */
    @Override
    public Void visitNewArray(NewArrayTree tree, Void unused) {
        if (!isByteLiteral(tree)) throw NotPorted.at(facts, tree);
        return null;
    }

    /** Whether {@code tree} is the one array of the subset. */
    static boolean isByteLiteral(NewArrayTree tree) {
        if (tree.getType() == null || tree.getInitializers() == null || !isByte(tree.getType())) return false;
        for (ExpressionTree element : tree.getInitializers()) {
            var literal = element instanceof UnaryTree unary && element.getKind() == Tree.Kind.UNARY_MINUS
                    ? unary.getExpression() : element;
            if (literal.getKind() != Tree.Kind.INT_LITERAL) return false;
        }
        return true;
    }

    private static boolean isByte(Tree type) {
        return type instanceof PrimitiveTypeTree primitive && primitive.getPrimitiveTypeKind() == TypeKind.BYTE;
    }
}
