package org.julclang.compiler.frontend.javac.subset;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BindingPatternTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.BreakTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ConstantCaseLabelTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.InstanceOfTree;
import com.sun.source.tree.LabeledStatementTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberReferenceTree;
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

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
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
            Tree.Kind.INT_LITERAL, Tree.Kind.LONG_LITERAL, Tree.Kind.BOOLEAN_LITERAL, Tree.Kind.STRING_LITERAL,
            Tree.Kind.MEMBER_REFERENCE, Tree.Kind.FOR_LOOP, Tree.Kind.DO_WHILE_LOOP);

    /** The kinds that are subset only where lowering reaches them. */
    private static final Set<Tree.Kind> WHERE_LOWERED = EnumSet.of(
            Tree.Kind.METHOD_INVOCATION, Tree.Kind.CONDITIONAL_EXPRESSION, Tree.Kind.NEW_CLASS, Tree.Kind.LAMBDA_EXPRESSION,
            Tree.Kind.IF, Tree.Kind.EXPRESSION_STATEMENT, Tree.Kind.LABELED_STATEMENT, Tree.Kind.EMPTY_STATEMENT,
            Tree.Kind.INSTANCE_OF, Tree.Kind.BINDING_PATTERN, Tree.Kind.DECONSTRUCTION_PATTERN,
            Tree.Kind.ASSIGNMENT, Tree.Kind.PLUS_ASSIGNMENT, Tree.Kind.MINUS_ASSIGNMENT, Tree.Kind.MULTIPLY_ASSIGNMENT,
            Tree.Kind.DIVIDE_ASSIGNMENT, Tree.Kind.REMAINDER_ASSIGNMENT, Tree.Kind.LEFT_SHIFT_ASSIGNMENT,
            Tree.Kind.RIGHT_SHIFT_ASSIGNMENT, Tree.Kind.UNSIGNED_RIGHT_SHIFT_ASSIGNMENT, Tree.Kind.AND_ASSIGNMENT,
            Tree.Kind.XOR_ASSIGNMENT, Tree.Kind.OR_ASSIGNMENT,
            Tree.Kind.PLUS, Tree.Kind.MINUS, Tree.Kind.MULTIPLY, Tree.Kind.DIVIDE, Tree.Kind.REMAINDER,
            Tree.Kind.EQUAL_TO, Tree.Kind.NOT_EQUAL_TO, Tree.Kind.LESS_THAN, Tree.Kind.LESS_THAN_EQUAL,
            Tree.Kind.GREATER_THAN, Tree.Kind.GREATER_THAN_EQUAL, Tree.Kind.CONDITIONAL_AND, Tree.Kind.CONDITIONAL_OR,
            Tree.Kind.LOGICAL_COMPLEMENT, Tree.Kind.UNARY_MINUS,
            Tree.Kind.SWITCH, Tree.Kind.SWITCH_EXPRESSION, Tree.Kind.CASE, Tree.Kind.YIELD, Tree.Kind.DEFAULT_CASE_LABEL,
            Tree.Kind.PATTERN_CASE_LABEL, Tree.Kind.CONSTANT_CASE_LABEL,
            Tree.Kind.ENHANCED_FOR_LOOP, Tree.Kind.WHILE_LOOP, Tree.Kind.BREAK, Tree.Kind.CONTINUE,
            Tree.Kind.PREFIX_INCREMENT, Tree.Kind.PREFIX_DECREMENT, Tree.Kind.POSTFIX_INCREMENT, Tree.Kind.POSTFIX_DECREMENT);

    /** The kinds only a {@code switch} guard holds: each is {@link GuardRules}' to reject, or a constant that needs no term. */
    private static final Set<Tree.Kind> IN_A_GUARD = EnumSet.of(Tree.Kind.TYPE_CAST, Tree.Kind.AND, Tree.Kind.OR, Tree.Kind.XOR,
            Tree.Kind.LEFT_SHIFT, Tree.Kind.RIGHT_SHIFT, Tree.Kind.UNSIGNED_RIGHT_SHIFT, Tree.Kind.UNARY_PLUS,
            Tree.Kind.BITWISE_COMPLEMENT, Tree.Kind.CHAR_LITERAL, Tree.Kind.FLOAT_LITERAL, Tree.Kind.DOUBLE_LITERAL);

    /** The statements a block holds where lowering reaches it; {@code Statements} rejects those the reference rejects. */
    private static final Set<Tree.Kind> STATEMENTS = EnumSet.of(Tree.Kind.RETURN, Tree.Kind.VARIABLE, Tree.Kind.IF,
            Tree.Kind.EXPRESSION_STATEMENT, Tree.Kind.BLOCK, Tree.Kind.EMPTY_STATEMENT, Tree.Kind.LABELED_STATEMENT,
            Tree.Kind.YIELD, Tree.Kind.SWITCH, Tree.Kind.ENHANCED_FOR_LOOP, Tree.Kind.WHILE_LOOP, Tree.Kind.BREAK, Tree.Kind.CONTINUE);

    /** The statements rejected wherever they stand, lowered or not, with a code of their own. */
    private static final Set<Tree.Kind> REJECTED_STATEMENTS = EnumSet.of(Tree.Kind.FOR_LOOP, Tree.Kind.DO_WHILE_LOOP);

    /** The methods of a functional interface, as the reference lists them. */
    private static final Set<String> FUNCTIONAL_METHODS = Set.of("apply", "test", "accept", "get", "run");

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
    /** Above zero in a {@code switch} guard, an arm of a {@code switch} in one included, as a guard's lowering is. */
    private int guardDepth;
    /** In a guard, and not in an arm of a {@code switch} in it: {@link GuardRules} reads D6's rules there, in the reference's order. */
    private boolean equalityDeferred;

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

    /** A {@code ;} after a type is no declaration: javac keeps it among a unit's types. */
    @Override
    public Void scan(Tree tree, Void unused) {
        if (tree != null && tree.getKind() == Tree.Kind.EMPTY_STATEMENT && facts.parent(tree) instanceof CompilationUnitTree) return null;
        if (tree != null && !allowed(tree.getKind())) throw NotPorted.at(facts, tree);
        return super.scan(tree, unused);
    }

    private boolean allowed(Tree.Kind kind) {
        return ALLOWED.contains(kind) || inLowered && (WHERE_LOWERED.contains(kind) || guardDepth > 0 && IN_A_GUARD.contains(kind));
    }

    /** {@code case null}: JuLC has no {@code null}, and the rest of what stands for one is not ported. */
    @Override
    public Void visitConstantCaseLabel(ConstantCaseLabelTree tree, Void unused) {
        if (tree.getConstantExpression().getKind() == Tree.Kind.NULL_LITERAL) {
            throw facts.rejection(DiagnosticCodes.NULL_UNSUPPORTED, tree.getConstantExpression());
        }
        return super.visitConstantCaseLabel(tree, unused);
    }

    /** A guard is a guard's to judge, whichever rule stops it; an arm in it is ordinary code, as far as D6 goes. */
    @Override
    public Void visitCase(CaseTree tree, Void unused) {
        scan(tree.getLabels(), null);
        if (tree.getGuard() != null) {
            boolean deferred = equalityDeferred;
            guardDepth++;
            equalityDeferred = true;
            try {
                scan(tree.getGuard(), null);
            } finally {
                guardDepth--;
                equalityDeferred = deferred;
            }
        }
        boolean deferred = equalityDeferred;
        equalityDeferred = false;
        try {
            if (tree.getCaseKind() == CaseTree.CaseKind.RULE) scan(tree.getBody(), null);
            else scan(tree.getStatements(), null);
        } finally {
            equalityDeferred = deferred;
        }
        return null;
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
            boolean read = REJECTED_STATEMENTS.contains(statement.getKind())
                    || (inLowered ? STATEMENTS.contains(statement.getKind()) : statement.getKind() == Tree.Kind.RETURN);
            if (!read) throw NotPorted.at(facts, statement);
        }
        warnUnreachable(tree);
        written.forEach(statement -> scan(statement, null));
        return null;
    }

    /** One warning a block: javac has rejected a statement after a {@code return} or a {@code break}, so {@code Builtins.error()} is the rest. */
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
    private void requireSingleDeclarator(VariableTree tree, List<? extends StatementTree> statements) {
        var at = facts.location(tree);
        VariableTree last = tree;
        for (var statement : statements) {
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
            if (facts.parent(tree) instanceof BlockTree block) requireSingleDeclarator(tree, block.getStatements());
            if (facts.parent(tree) instanceof CaseTree group) requireSingleDeclarator(tree, group.getStatements());
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

    /** javac allows a {@code break} of a {@code switch} statement, which has no lowering, and the reference says so of the {@code break}. */
    @Override
    public Void visitBreak(BreakTree tree, Void unused) {
        if (facts.enclosing(tree, Tree.Kind.ENHANCED_FOR_LOOP, Tree.Kind.WHILE_LOOP) == null) {
            throw facts.rejection("break is only supported inside for-each or while loops on-chain",
                    "Use for-each or while with an accumulator and break to exit early", tree);
        }
        return null;
    }

    /** A C-style {@code for} has no lowering: a for-each or a {@code while} does what it does. */
    @Override
    public Void visitForLoop(ForLoopTree tree, Void unused) {
        throw facts.rejection(DiagnosticCodes.C_STYLE_FOR_UNSUPPORTED, tree);
    }

    /** A {@code do}-{@code while} has no lowering: a {@code while} does what it does. */
    @Override
    public Void visitDoWhileLoop(DoWhileLoopTree tree, Void unused) {
        throw facts.rejection(DiagnosticCodes.DO_WHILE_UNSUPPORTED, tree);
    }

    /** {@code JULC0052}. The rest of the compound operators are assignments, which are the lowering's to reject. */
    @Override
    public Void visitCompoundAssignment(CompoundAssignmentTree tree, Void unused) {
        var operator = UNLOWERED_COMPOUNDS.get(tree.getKind());
        if (operator != null) throw CompilerTypeDiagnostics.compoundAssignmentUnsupported(operator, facts.location(tree));
        return super.visitCompoundAssignment(tree, unused);
    }

    /**
     * {@code JULC0059}: the reference binds a pattern variable only where it is a whole condition, so one does not take a
     * field's name, at any depth of a pattern. It is anchored at the type, as a modifier is no part of what the reference reads.
     */
    @Override
    public Void visitInstanceOf(InstanceOfTree tree, Void unused) {
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitBindingPattern(BindingPatternTree pattern, Void ignored) {
                var variable = pattern.getVariable();
                var owner = enclosingWithField(tree, variable.getName());
                if (owner != null) {
                    throw facts.rejection(DiagnosticCodes.PATTERN_VARIABLE_SHADOWS_FIELD,
                            facts.isGenerated(variable.getType()) ? pattern : variable.getType(), variable.getName(), owner);
                }
                return null;
            }
        }.scan(tree.getPattern(), null);
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

    /** D6: {@code ==}, {@code !=} and string {@code +} are Java's, not their on-chain lowering's. */
    @Override
    public Void visitBinary(BinaryTree tree, Void unused) {
        if (!equalityDeferred) equality.check(tree);
        return super.visitBinary(tree, unused);
    }

    /** D6: an operation that compares by {@code equals} must not reach an array by identity. */
    @Override
    public Void visitMethodInvocation(MethodInvocationTree tree, Void unused) {
        requireNoFunctionalCall(tree);
        if (!equalityDeferred) equality.check(tree);
        return super.visitMethodInvocation(tree, unused);
    }

    /** A function exists on-chain only as the argument of the list method that applies it, and a local holds none. */
    private void requireNoFunctionalCall(MethodInvocationTree tree) {
        if (tree.getMethodSelect() instanceof MemberSelectTree select && select.getExpression() instanceof IdentifierTree receiver
                && FUNCTIONAL_METHODS.contains(select.getIdentifier().toString())
                && facts.element(receiver).getKind() == ElementKind.LOCAL_VARIABLE && isFunctional(facts.type(receiver))) {
            throw facts.rejection("Functional interface ." + select.getIdentifier() + "() is not supported on-chain",
                    "Call the method directly instead of through a function reference", tree);
        }
    }

    /** Whether {@code type} is a functional interface of the JDK's, by identity. */
    private boolean isFunctional(TypeMirror type) {
        return type.getKind() == TypeKind.DECLARED && facts.identity(facts.typeElement(type)).startsWith(JavacNames.FUNCTIONS);
    }

    /** Outside the subset, wherever it stands. */
    @Override
    public Void visitMemberReference(MemberReferenceTree tree, Void unused) {
        throw facts.rejection("Method references (::) are not supported on-chain", "Use a regular static method call instead", tree);
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
