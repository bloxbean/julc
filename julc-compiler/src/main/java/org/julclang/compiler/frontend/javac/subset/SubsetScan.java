package org.julclang.compiler.frontend.javac.subset;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BindingPatternTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.InstanceOfTree;
import com.sun.source.tree.LabeledStatementTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreeScanner;
import org.julclang.compiler.CompilerTypeDiagnostics;
import org.julclang.compiler.error.CompilerDiagnostic;
import org.julclang.compiler.error.DiagnosticCodes;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.frontend.javac.JavacNames;
import org.julclang.compiler.frontend.javac.NotPorted;
import org.julclang.core.source.SourceLocation;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ADR-068 I2c at its first size: an allow-list of tree kinds, with the contextual rules each needs, over declarations and
 * method bodies. A method nothing lowers does not run, but the JavaParser frontend's {@code SubsetValidator} still reads
 * it, so what that frontend rejects there is {@code JULC0084} here until its rule is ported. That is what the list decides,
 * and {@code JULC0084} fails closed.
 * <p>
 * <b>A kind goes on this list when lowering reaches it, and it is allowed where lowering reaches it, not everywhere.</b> A
 * field initializer and an initializer block run when a class is loaded, whether or not anything lowers them, so this scan
 * reads only their literals, and {@link UnloweredInitializers}, whose list is closed, checks them. A field the compilation
 * lowers is read as a method is. An annotation's arguments are constants, read for their literals too.
 * <p>
 * The list grows with each ported construct, and takes on {@code SubsetValidator}'s rules and codes as it does; it is not
 * that validator yet. A construct on it here is one whose rejections, if any, are its own rules below.
 * <p>
 * {@link #WHERE_LOWERED} holds in the methods and static fields the compilation lowers, and nowhere else.
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

    /** The kinds that are subset only where lowering reaches them. */
    private static final Set<Tree.Kind> WHERE_LOWERED = EnumSet.of(
            Tree.Kind.METHOD_INVOCATION, Tree.Kind.CONDITIONAL_EXPRESSION, Tree.Kind.NEW_CLASS,
            Tree.Kind.IF, Tree.Kind.EXPRESSION_STATEMENT, Tree.Kind.LABELED_STATEMENT, Tree.Kind.EMPTY_STATEMENT,
            Tree.Kind.INSTANCE_OF, Tree.Kind.BINDING_PATTERN, Tree.Kind.DECONSTRUCTION_PATTERN,
            Tree.Kind.ASSIGNMENT, Tree.Kind.PLUS_ASSIGNMENT, Tree.Kind.MINUS_ASSIGNMENT, Tree.Kind.MULTIPLY_ASSIGNMENT,
            Tree.Kind.DIVIDE_ASSIGNMENT, Tree.Kind.REMAINDER_ASSIGNMENT, Tree.Kind.LEFT_SHIFT_ASSIGNMENT,
            Tree.Kind.RIGHT_SHIFT_ASSIGNMENT, Tree.Kind.UNSIGNED_RIGHT_SHIFT_ASSIGNMENT, Tree.Kind.AND_ASSIGNMENT,
            Tree.Kind.XOR_ASSIGNMENT, Tree.Kind.OR_ASSIGNMENT,
            Tree.Kind.PLUS, Tree.Kind.MINUS, Tree.Kind.MULTIPLY, Tree.Kind.DIVIDE, Tree.Kind.REMAINDER,
            Tree.Kind.EQUAL_TO, Tree.Kind.NOT_EQUAL_TO, Tree.Kind.LESS_THAN, Tree.Kind.LESS_THAN_EQUAL,
            Tree.Kind.GREATER_THAN, Tree.Kind.GREATER_THAN_EQUAL, Tree.Kind.CONDITIONAL_AND, Tree.Kind.CONDITIONAL_OR,
            Tree.Kind.LOGICAL_COMPLEMENT, Tree.Kind.UNARY_MINUS);

    /** The statements a block holds where lowering reaches it; {@code Statements} rejects those the reference rejects. */
    private static final Set<Tree.Kind> STATEMENTS = EnumSet.of(Tree.Kind.RETURN, Tree.Kind.VARIABLE, Tree.Kind.IF,
            Tree.Kind.EXPRESSION_STATEMENT, Tree.Kind.BLOCK, Tree.Kind.EMPTY_STATEMENT, Tree.Kind.LABELED_STATEMENT);

    /** The compound operators the reference lowers as {@code target op value}; the rest have a rule of their own. */
    private static final Map<Tree.Kind, String> UNLOWERED_COMPOUNDS = Map.of(
            Tree.Kind.LEFT_SHIFT_ASSIGNMENT, "<<=", Tree.Kind.RIGHT_SHIFT_ASSIGNMENT, ">>=",
            Tree.Kind.UNSIGNED_RIGHT_SHIFT_ASSIGNMENT, ">>>=", Tree.Kind.AND_ASSIGNMENT, "&=",
            Tree.Kind.XOR_ASSIGNMENT, "^=", Tree.Kind.OR_ASSIGNMENT, "|=");

    private final JavacFacts facts;
    private final EqualityRules equality;
    /** The methods and fields lowering reaches. */
    private final Set<Tree> lowered;
    private final List<CompilerDiagnostic> warnings = new ArrayList<>();
    private boolean inLowered;

    private SubsetScan(JavacFacts facts, Set<Tree> lowered) {
        this.facts = facts;
        this.equality = new EqualityRules(facts);
        this.lowered = lowered;
    }

    /**
     * @param lowered the methods and fields of {@code unit} that the compilation lowers, by identity
     * @return what the scan warns of, in source order
     */
    public static List<CompilerDiagnostic> check(JavacFacts facts, CompilationUnitTree unit, Set<Tree> lowered) {
        var scan = new SubsetScan(facts, lowered);
        scan.scan(unit, null);
        return scan.warnings;
    }

    @Override
    public Void scan(Tree tree, Void unused) {
        if (tree != null && !allowed(tree.getKind())) throw NotPorted.at(facts, tree);
        return super.scan(tree, unused);
    }

    private boolean allowed(Tree.Kind kind) {
        return ALLOWED.contains(kind) || inLowered && WHERE_LOWERED.contains(kind);
    }

    /** A member sets the context of what it holds, so a nested type's members are never in an outer member's. */
    private void member(Tree tree, Runnable scan) {
        boolean outer = inLowered;
        inLowered = lowered.contains(tree);
        try {
            scan.run();
        } finally {
            inLowered = outer;
        }
    }

    /** A constructor javac generated is not source: its {@code super()} call is no construct of the program. */
    @Override
    public Void visitMethod(MethodTree tree, Void unused) {
        if (!facts.isGenerated(tree)) member(tree, () -> super.visitMethod(tree, unused));
        return null;
    }

    /**
     * In code that is not lowered only {@code return} is read: a statement there can still be a rejected one. What javac
     * wrote into a constructor, a {@code super();} or a compact constructor's assignments, is not source, and {@code JULC0099}
     * has read the rest.
     */
    @Override
    public Void visitBlock(BlockTree tree, Void unused) {
        if (facts.parent(tree) instanceof ClassTree) return literalsOf(tree);
        var written = tree.getStatements().stream().filter(statement -> !facts.isGenerated(statement)).toList();
        for (var statement : written) {
            boolean read = inLowered ? STATEMENTS.contains(statement.getKind()) : statement.getKind() == Tree.Kind.RETURN;
            if (!read) throw NotPorted.at(facts, statement);
        }
        warnUnreachable(tree);
        written.forEach(statement -> scan(statement, null));
        return null;
    }

    /** One warning a block: javac has rejected a statement after a {@code return}, so {@code Builtins.error()} is the rest. */
    private void warnUnreachable(BlockTree tree) {
        var statements = tree.getStatements();
        for (int i = 0; i + 1 < statements.size(); i++) {
            if (isError(statements.get(i))) {
                var at = facts.location(statements.get(i + 1));
                warnings.add(new CompilerDiagnostic(CompilerDiagnostic.Level.WARNING,
                        "Unreachable code after Builtins.error(). This code will never execute.", at.fileName(), at.line(), at.column()));
                return;
            }
        }
    }

    private boolean isError(StatementTree statement) {
        return statement instanceof ExpressionStatementTree expression
                && expression.getExpression() instanceof MethodInvocationTree call
                && facts.element(call.getMethodSelect()) instanceof ExecutableElement method
                && method.getSimpleName().contentEquals("error")
                && method.getEnclosingElement() instanceof TypeElement owner && facts.identity(owner).equals(JavacNames.BUILTINS);
    }

    /** {@code JULC0053}: {@code int a = 1, b = 2;} is two trees at one start, and the last one's text is the whole declaration. */
    private void requireSingleDeclarator(VariableTree tree, BlockTree block) {
        var at = facts.location(tree);
        VariableTree last = tree;
        for (var statement : block.getStatements()) {
            if (statement instanceof VariableTree other && other != tree && startsAt(other, at)) last = other;
        }
        if (last != tree) {
            String text = facts.location(last).fragment();
            throw CompilerTypeDiagnostics.multipleDeclarators(text.endsWith(";") ? text.substring(0, text.length() - 1) : text, at);
        }
    }

    private boolean startsAt(VariableTree tree, SourceLocation at) {
        var other = facts.location(tree);
        return other.line() == at.line() && other.column() == at.column();
    }

    /** A lowered field is a member, as a method is. Any other field's initializer is not this list's to judge. */
    @Override
    public Void visitVariable(VariableTree tree, Void unused) {
        if (!(facts.parent(tree) instanceof ClassTree)) {
            if (facts.parent(tree) instanceof BlockTree block) requireSingleDeclarator(tree, block);
            // A var's type tree is javac's, and says what the initializer's type is, whatever that is.
            if (tree.getType() == null || facts.isGenerated(tree.getType())) {
                scan(tree.getModifiers(), null);
                return scan(tree.getInitializer(), null);
            }
            return super.visitVariable(tree, unused);
        }
        if (lowered.contains(tree)) {
            member(tree, () -> super.visitVariable(tree, unused));
            return null;
        }
        scan(tree.getModifiers(), null);
        scan(tree.getType(), null);
        return literalsOf(tree.getInitializer());
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
                if (literal.getKind() == Tree.Kind.NULL_LITERAL) throw NotPorted.at(facts, literal);
                requirePairedSurrogates(literal);
                return null;
            }
        }.scan(tree, null);
        return null;
    }

    /** A label is the lowering's rejection, and it comes first of all that its statement holds. */
    @Override
    public Void visitLabeledStatement(LabeledStatementTree tree, Void unused) {
        return null;
    }

    /** {@code JULC0052}. The rest of the compound operators are assignments, which are the lowering's to reject. */
    @Override
    public Void visitCompoundAssignment(CompoundAssignmentTree tree, Void unused) {
        var operator = UNLOWERED_COMPOUNDS.get(tree.getKind());
        if (operator != null) throw CompilerTypeDiagnostics.compoundAssignmentUnsupported(operator, facts.location(tree));
        return super.visitCompoundAssignment(tree, unused);
    }

    /** {@code JULC0059}: the reference binds a pattern variable only where it is a whole condition, so one does not take a field's name. */
    @Override
    public Void visitInstanceOf(InstanceOfTree tree, Void unused) {
        if (tree.getPattern() instanceof BindingPatternTree pattern) {
            var owner = enclosingWithField(tree, pattern.getVariable().getName());
            if (owner != null) {
                throw facts.rejection(DiagnosticCodes.PATTERN_VARIABLE_SHADOWS_FIELD, pattern, pattern.getVariable().getName(), owner);
            }
        }
        return super.visitInstanceOf(tree, unused);
    }

    /** The nearest class around {@code tree} that declares a field {@code name}. A record's components are not fields here. */
    private String enclosingWithField(Tree tree, Name name) {
        for (var at = facts.parent(tree); at != null; at = facts.parent(at)) {
            if (at instanceof ClassTree type) {
                for (var member : type.getMembers()) {
                    if (member instanceof VariableTree field && field.getName().equals(name)
                            && (type.getKind() != Tree.Kind.RECORD || field.getModifiers().getFlags().contains(Modifier.STATIC))) {
                        return type.getSimpleName().toString();
                    }
                }
            }
        }
        return null;
    }

    /** A class body would run code no record has: the reference has no meaning for it, and the gate rejects it. */
    @Override
    public Void visitNewClass(NewClassTree tree, Void unused) {
        if (tree.getClassBody() != null) throw NotPorted.at(facts, tree, "a new with a class body");
        return super.visitNewClass(tree, unused);
    }

    /**
     * Inheritance is outside the subset. An interface's {@code extends} is javac's implements clause. A {@code ;} between
     * members is no member: javac keeps it as a statement of the class.
     */
    @Override
    public Void visitClass(ClassTree tree, Void unused) {
        boolean inherits = tree.getKind() == Tree.Kind.INTERFACE ? !tree.getImplementsClause().isEmpty()
                : tree.getExtendsClause() != null && !isObject(tree.getExtendsClause());
        if (tree.getKind() != Tree.Kind.RECORD && inherits) throw NotPorted.at(facts, tree, "inheritance");
        scan(tree.getModifiers(), null);
        scan(tree.getTypeParameters(), null);
        scan(tree.getExtendsClause(), null);
        scan(tree.getImplementsClause(), null);
        scan(tree.getPermitsClause(), null);
        for (var member : tree.getMembers()) {
            if (member.getKind() != Tree.Kind.EMPTY_STATEMENT) scan(member, null);
        }
        return null;
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

    /** D6: {@code ==}, {@code !=} and string {@code +} are Java's, not their on-chain lowering's. */
    @Override
    public Void visitBinary(BinaryTree tree, Void unused) {
        equality.check(tree);
        return super.visitBinary(tree, unused);
    }

    /** D6: an operation that compares by {@code equals} must not reach an array by identity. */
    @Override
    public Void visitMethodInvocation(MethodInvocationTree tree, Void unused) {
        equality.check(tree);
        return super.visitMethodInvocation(tree, unused);
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
