package org.julclang.compiler.frontend.javac.subset;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BindingPatternTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.BreakTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ConstantCaseLabelTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.InstanceOfTree;
import com.sun.source.tree.LabeledStatementTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.SynchronizedTree;
import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.TryTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreeScanner;
import org.julclang.compiler.CompilerException;
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
import java.util.IdentityHashMap;
import java.util.Collections;
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
            Tree.Kind.CLASS, Tree.Kind.INTERFACE, Tree.Kind.RECORD, Tree.Kind.ANNOTATION_TYPE, Tree.Kind.METHOD, Tree.Kind.VARIABLE,
            Tree.Kind.BLOCK, Tree.Kind.RETURN,
            Tree.Kind.PRIMITIVE_TYPE, Tree.Kind.ARRAY_TYPE, Tree.Kind.PARAMETERIZED_TYPE, Tree.Kind.TYPE_PARAMETER,
            Tree.Kind.UNBOUNDED_WILDCARD, Tree.Kind.EXTENDS_WILDCARD, Tree.Kind.SUPER_WILDCARD,
            Tree.Kind.IDENTIFIER, Tree.Kind.MEMBER_SELECT, Tree.Kind.PARENTHESIZED, Tree.Kind.NEW_ARRAY,
            Tree.Kind.INT_LITERAL, Tree.Kind.LONG_LITERAL, Tree.Kind.BOOLEAN_LITERAL, Tree.Kind.STRING_LITERAL,
            Tree.Kind.MEMBER_REFERENCE, Tree.Kind.FOR_LOOP, Tree.Kind.DO_WHILE_LOOP, Tree.Kind.TRY, Tree.Kind.THROW,
            Tree.Kind.SYNCHRONIZED, Tree.Kind.ARRAY_ACCESS, Tree.Kind.NULL_LITERAL);

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
            Tree.Kind.LOGICAL_COMPLEMENT, Tree.Kind.UNARY_MINUS, Tree.Kind.UNARY_PLUS, Tree.Kind.BITWISE_COMPLEMENT,
            Tree.Kind.AND, Tree.Kind.OR, Tree.Kind.XOR, Tree.Kind.LEFT_SHIFT, Tree.Kind.RIGHT_SHIFT, Tree.Kind.UNSIGNED_RIGHT_SHIFT,
            Tree.Kind.SWITCH, Tree.Kind.SWITCH_EXPRESSION, Tree.Kind.CASE, Tree.Kind.YIELD, Tree.Kind.DEFAULT_CASE_LABEL,
            Tree.Kind.PATTERN_CASE_LABEL, Tree.Kind.CONSTANT_CASE_LABEL,
            Tree.Kind.ENHANCED_FOR_LOOP, Tree.Kind.WHILE_LOOP, Tree.Kind.BREAK, Tree.Kind.CONTINUE,
            Tree.Kind.PREFIX_INCREMENT, Tree.Kind.PREFIX_DECREMENT, Tree.Kind.POSTFIX_INCREMENT, Tree.Kind.POSTFIX_DECREMENT,
            Tree.Kind.TYPE_CAST);

    /** The kinds only a {@code switch} guard holds: each is {@link GuardRules}' to reject, or a constant that needs no term. */
    private static final Set<Tree.Kind> IN_A_GUARD = EnumSet.of(Tree.Kind.TYPE_CAST, Tree.Kind.AND, Tree.Kind.OR, Tree.Kind.XOR,
            Tree.Kind.LEFT_SHIFT, Tree.Kind.RIGHT_SHIFT, Tree.Kind.UNSIGNED_RIGHT_SHIFT, Tree.Kind.UNARY_PLUS,
            Tree.Kind.BITWISE_COMPLEMENT, Tree.Kind.CHAR_LITERAL, Tree.Kind.FLOAT_LITERAL, Tree.Kind.DOUBLE_LITERAL);

    /** The statements a block holds where lowering reaches it; {@code Statements} rejects those the reference rejects. */
    private static final Set<Tree.Kind> STATEMENTS = EnumSet.of(Tree.Kind.RETURN, Tree.Kind.VARIABLE, Tree.Kind.IF,
            Tree.Kind.EXPRESSION_STATEMENT, Tree.Kind.BLOCK, Tree.Kind.EMPTY_STATEMENT, Tree.Kind.LABELED_STATEMENT,
            Tree.Kind.YIELD, Tree.Kind.SWITCH, Tree.Kind.ENHANCED_FOR_LOOP, Tree.Kind.WHILE_LOOP, Tree.Kind.BREAK, Tree.Kind.CONTINUE);

    /** The statements rejected wherever they stand, lowered or not, with a code of their own. */
    private static final Set<Tree.Kind> REJECTED_STATEMENTS = EnumSet.of(Tree.Kind.FOR_LOOP, Tree.Kind.DO_WHILE_LOOP, Tree.Kind.TRY,
            Tree.Kind.THROW, Tree.Kind.SYNCHRONIZED);

    /** The methods of a functional interface, as the reference lists them. */
    private static final Set<String> FUNCTIONAL_METHODS = Set.of("apply", "test", "accept", "get", "run");

    /** The reference's functional interfaces, by the simple name a declaration writes (its {@code FUNCTIONAL_TYPES}). */
    private static final Set<String> FUNCTIONAL_TYPES = Set.of("Function", "BiFunction", "UnaryOperator", "BinaryOperator",
            "Predicate", "BiPredicate", "Consumer", "BiConsumer", "Supplier", "Runnable");

    /** The compound operators the reference lowers as {@code target op value}; the rest have a rule of their own. */
    private static final Map<Tree.Kind, String> UNLOWERED_COMPOUNDS = Map.of(
            Tree.Kind.LEFT_SHIFT_ASSIGNMENT, "<<=", Tree.Kind.RIGHT_SHIFT_ASSIGNMENT, ">>=",
            Tree.Kind.UNSIGNED_RIGHT_SHIFT_ASSIGNMENT, ">>>=", Tree.Kind.AND_ASSIGNMENT, "&=",
            Tree.Kind.XOR_ASSIGNMENT, "^=", Tree.Kind.OR_ASSIGNMENT, "|=");

    private final JavacFacts facts;
    private final EqualityRules equality;
    /** The methods and fields lowering reaches. */
    private final Set<Tree> lowered;
    /** What the scan found, in the order it found it: its warnings, and every rule's error (F946, as the reference collects). */
    private final List<CompilerDiagnostic> diagnostics = new ArrayList<>();
    /** The nodes code nothing lowers has had read, once each though a lambda's block in it is a block of its own. */
    private final Set<Tree> walked = Collections.newSetFromMap(new IdentityHashMap<>());
    /** The blocks warned of, once each: code nothing lowers reaches a block both through a rejected statement and around it. */
    private final Set<Tree> warned = Collections.newSetFromMap(new IdentityHashMap<>());
    /** The methods not read: a library's templates, which only a producer's request lowers. */
    private Set<Tree> skipped = Set.of();
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
     * A rule's rejection does not stop the scan: as the reference's validator does, it collects every error and goes on past
     * the subtree that broke the rule, and the compilation fails with all of them, and the warnings found before, in the
     * order found. The first error is the one a source with one error would give alone. A construct not ported stops the scan
     * at once ({@code JULC0084}, I8), and if an error came before it, the errors are what the compilation fails with.
     *
     * @param lowered the methods and fields of {@code unit} that the compilation lowers, by identity
     * @return what the scan warns of, in source order
     */
    public static List<CompilerDiagnostic> check(JavacFacts facts, CompilationUnitTree unit, Set<Tree> lowered) {
        return check(facts, unit, lowered, Set.of());
    }

    /** {@link #check(JavacFacts, CompilationUnitTree, Set)}, not reading the methods {@code skipped} holds, by identity. */
    public static List<CompilerDiagnostic> check(JavacFacts facts, Tree unit, Set<Tree> lowered, Set<Tree> skipped) {
        var scan = new SubsetScan(facts, lowered);
        scan.skipped = skipped;
        try {
            scan.scan(unit, null);
        } catch (CompilerException stop) {
            // A stop after an error: the errors before it are the rejection.
            if (!scan.hasErrors()) throw stop;
        }
        if (scan.hasErrors()) throw new CompilerException(scan.diagnostics);
        return List.copyOf(scan.diagnostics);
    }

    private boolean hasErrors() {
        return diagnostics.stream().anyMatch(diagnostic -> diagnostic.level() == CompilerDiagnostic.Level.ERROR);
    }

    /** A {@code ;} after a type is no declaration: javac keeps it among a unit's types. */
    @Override
    public Void scan(Tree tree, Void unused) {
        if (tree != null && tree.getKind() == Tree.Kind.EMPTY_STATEMENT && facts.parent(tree) instanceof CompilationUnitTree) return null;
        if (tree != null && !allowed(tree.getKind())) {
            // A char has no on-chain representation, as the reference says of its type (JULC0124); a guard folds it.
            if (tree.getKind() == Tree.Kind.CHAR_LITERAL) throw charLiteral(tree);
            throw outside(tree);
        }
        try {
            return super.scan(tree, unused);
        } catch (NotPorted stop) {
            throw stop;
        } catch (CompilerException rejection) {
            if (rejection.diagnostics().isEmpty()) throw rejection;
            diagnostics.addAll(rejection.diagnostics());
            return null;
        }
    }

    /**
     * A kind no rule reads where it stands, with the reference's words: a statement is {@code JULC0110}, an expression
     * {@code JULC0125}, floating point {@code JULC0020}, and any other tree, such as an enum, {@code JULC0125} too (I2a).
     */
    private CompilerException outside(Tree tree) {
        return switch (tree) {
            case LiteralTree literal when literal.getKind() == Tree.Kind.FLOAT_LITERAL || literal.getKind() == Tree.Kind.DOUBLE_LITERAL ->
                    facts.rejection(DiagnosticCodes.FLOATING_POINT_UNSUPPORTED, tree);
            case StatementTree statement -> facts.rejection(DiagnosticCodes.STATEMENT_UNSUPPORTED, tree, facts.location(tree).fragment());
            default -> facts.rejection(DiagnosticCodes.EXPRESSION_UNSUPPORTED, tree, facts.location(tree).fragment());
        };
    }

    /**
     * A {@code char} literal, in the reference's words: of a {@code char} variable, the type's ({@code JULC0124}, at the type);
     * anywhere else, "Unsupported expression: CharLiteralExpr" ({@code JULC0125}).
     */
    private CompilerException charLiteral(Tree tree) {
        if (facts.parent(tree) instanceof VariableTree variable && variable.getType() != null
                && variable.getType().getKind() == Tree.Kind.PRIMITIVE_TYPE && facts.type(tree).getKind() == TypeKind.CHAR
                && facts.element(variable).asType().getKind() == TypeKind.CHAR) {
            return facts.unsupportedPrimitive("char", variable.getType());
        }
        return facts.rejection(DiagnosticCodes.EXPRESSION_UNSUPPORTED, tree, "CharLiteralExpr");
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
        if (facts.isGenerated(tree)) return null;
        if (skipped.contains(tree)) requireNoFloatingPoint(tree);
        else member(tree, () -> super.visitMethod(tree, unused));
        return null;
    }

    /**
     * A statement of code nothing lowers never runs, and the reference's validator reads it only for what it rejects wherever
     * it stands, which {@link #requireNoRejectedStatement} has read, {@code null} included (ADR-068 M5): so it is passed over,
     * with its strings' surrogates read as a field initializer's are and its blocks' unreachable code warned of, as the
     * reference warns. A rejected statement there the walk has read by its own rule. What javac wrote into a constructor, a
     * {@code super();} or a compact constructor's assignments, is not source, and {@code JULC0099} has read the rest.
     */
    @Override
    public Void visitBlock(BlockTree tree, Void unused) {
        if (facts.parent(tree) instanceof ClassTree) return literalsOf(tree);
        var written = tree.getStatements().stream().filter(statement -> !facts.isGenerated(statement)).toList();
        if (!inLowered) requireNoRejectedStatement(tree);
        warnUnreachable(tree);
        // In source order: a statement this list does not read stops the scan where it stands, after what came before it.
        for (var statement : written) {
            boolean rejected = REJECTED_STATEMENTS.contains(statement.getKind());
            if (inLowered && (rejected || STATEMENTS.contains(statement.getKind()))) {
                scan(statement, null);
            } else if (inLowered) {
                throw facts.rejection(DiagnosticCodes.STATEMENT_UNSUPPORTED, statement, facts.location(statement).fragment());
            } else if (!rejected) {
                // A rejected statement here the walk has read, by its own rule.
                SubsetWalk.all(statement, LiteralTree.class).forEach(this::requirePairedSurrogates);
                SubsetWalk.all(statement, BlockTree.class).forEach(this::warnUnreachable);
            }
        }
        return null;
    }

    /**
     * In code nothing lowers the walk stops at the first statement it does not read. What the reference's validator rejects
     * wherever it stands has its code even so, in one walk in source order, before that stop: a rejected statement, a
     * {@code break} outside a loop, floating point, {@code null}, {@code this} and {@code super}, an array that is no
     * {@code byte[]} literal and its access, a method reference, a call through a functional local, a compound operator
     * with no lowering, a pattern variable named like a field, and two declarators in one statement.
     */
    private void requireNoRejectedStatement(BlockTree tree) {
        var written = new ArrayList<Tree>();
        for (var statement : tree.getStatements()) {
            // What javac wrote, such as a constructor's super(), is no source.
            if (!facts.isGenerated(statement)) written.addAll(SubsetWalk.all(statement, Tree.class));
        }
        for (var node : written) {
            // What javac wrote at any depth, such as the super() of a local class's constructor, is no source either.
            if (facts.isGenerated(node) || !walked.add(node)) continue;
            try {
                requireNothingRejected(node);
            } catch (NotPorted stop) {
                throw stop;
            } catch (CompilerException rejection) {
                if (rejection.diagnostics().isEmpty()) throw rejection;
                diagnostics.addAll(rejection.diagnostics());
            }
        }
    }

    /** The rules {@link #requireNoRejectedStatement} reads at one node, each the reference validator's, which collects them all. */
    private void requireNothingRejected(Tree node) {
        switch (node) {
            case BreakTree loopExit -> visitBreak(loopExit, null);
            case PrimitiveTypeTree type -> visitPrimitiveType(type, null);
            case LiteralTree literal when literal.getKind() == Tree.Kind.NULL_LITERAL -> visitLiteral(literal, null);
            case IdentifierTree name -> visitIdentifier(name, null);
            case MemberSelectTree select when isThisOrSuper(select.getIdentifier()) -> visitMemberSelect(select, null);
            case ArrayAccessTree access -> visitArrayAccess(access, null);
            case MemberReferenceTree reference -> visitMemberReference(reference, null);
            case NewArrayTree array when array.getType() != null -> visitNewArray(array, null);
            case CompoundAssignmentTree compound when UNLOWERED_COMPOUNDS.containsKey(compound.getKind()) ->
                    visitCompoundAssignment(compound, null);
            case MethodInvocationTree call -> requireNoFunctionalCall(call);
            case InstanceOfTree test -> requireNoPatternNamedLikeAField(test);
            case VariableTree local when facts.parent(local) instanceof BlockTree block ->
                    requireSingleDeclarator(local, block.getStatements());
            default -> {
                if (REJECTED_STATEMENTS.contains(node.getKind())) scan(node, null);
            }
        }
    }

    /** A template is not read, but floating point in it is rejected as anywhere else. */
    private void requireNoFloatingPoint(MethodTree tree) {
        SubsetWalk.all(tree, PrimitiveTypeTree.class).forEach(type -> visitPrimitiveType(type, null));
    }

    /** One warning a block: javac has rejected a statement after a {@code return} or a {@code break}, so {@code Builtins.error()} is the rest. */
    private void warnUnreachable(BlockTree tree) {
        if (!warned.add(tree)) return;
        var statements = tree.getStatements();
        for (int i = 0; i + 1 < statements.size(); i++) {
            if (isError(statements.get(i))) {
                var at = facts.location(statements.get(i + 1));
                diagnostics.add(new CompilerDiagnostic(CompilerDiagnostic.Level.WARNING,
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
            if (statement == tree) continue;
            if (statement instanceof VariableTree other && startsAt(other, at)) {
                // The declaration is one rejection, at its first declarator, as the reference reports it.
                if (statements.indexOf(other) < statements.indexOf(tree)) return;
                last = other;
            }
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
                if (literal.getKind() == Tree.Kind.NULL_LITERAL) throw facts.rejection(DiagnosticCodes.NULL_UNSUPPORTED, literal);
                requirePairedSurrogates(literal);
                return null;
            }
        }.scan(tree, null);
        return null;
    }

    /** A label is the lowering's rejection, so what its statement holds comes first: the reference's validator runs before lowering. */
    @Override
    public Void visitLabeledStatement(LabeledStatementTree tree, Void unused) {
        return scan(tree.getStatement(), null);
    }

    /** javac allows a {@code break} of a {@code switch} statement, which has no lowering, and the reference says so of the {@code break}. */
    @Override
    public Void visitBreak(BreakTree tree, Void unused) {
        if (facts.enclosing(tree, Tree.Kind.ENHANCED_FOR_LOOP, Tree.Kind.WHILE_LOOP) == null) {
            if (tree.getLabel() != null) throw facts.rejection(DiagnosticCodes.LABEL_UNSUPPORTED, tree, "break " + tree.getLabel());
            throw facts.rejection(DiagnosticCodes.BREAK_OUTSIDE_LOOP, tree);
        }
        return null;
    }

    /** A C-style {@code for} has no lowering: a for-each or a {@code while} does what it does. */
    @Override
    public Void visitForLoop(ForLoopTree tree, Void unused) {
        throw facts.rejection(DiagnosticCodes.C_STYLE_FOR_UNSUPPORTED, tree);
    }

    /** {@code try} and {@code throw} are outside the subset wherever they stand, with the reference's codes. */
    @Override
    public Void visitTry(TryTree tree, Void unused) {
        throw facts.rejection(DiagnosticCodes.TRY_CATCH_UNSUPPORTED, tree);
    }

    @Override
    public Void visitThrow(ThrowTree tree, Void unused) {
        throw facts.rejection(DiagnosticCodes.THROW_UNSUPPORTED, tree);
    }

    /** On-chain code is single-threaded: {@code synchronized} is outside the subset wherever it stands. */
    @Override
    public Void visitSynchronized(SynchronizedTree tree, Void unused) {
        throw facts.rejection(DiagnosticCodes.SYNCHRONIZED_UNSUPPORTED, tree);
    }

    /** An array is a {@code byte[]} literal or nothing, so there is no element to read. */
    @Override
    public Void visitArrayAccess(ArrayAccessTree tree, Void unused) {
        throw facts.rejection(DiagnosticCodes.ARRAY_ACCESS_UNSUPPORTED, tree);
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
        requireNoPatternNamedLikeAField(tree);
        return super.visitInstanceOf(tree, unused);
    }

    private void requireNoPatternNamedLikeAField(InstanceOfTree tree) {
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
        // An anonymous class extends its type: inheritance (JULC0109).
        if (tree.getClassBody() != null) throw facts.rejection(DiagnosticCodes.INHERITANCE_UNSUPPORTED, tree);
        return super.visitNewClass(tree, unused);
    }

    /** Inheritance is outside the subset. An interface's {@code extends} is javac's implements clause. */
    @Override
    public Void visitClass(ClassTree tree, Void unused) {
        boolean inherits = tree.getKind() == Tree.Kind.INTERFACE ? !tree.getImplementsClause().isEmpty()
                : tree.getExtendsClause() != null && !isObject(tree.getExtendsClause());
        if (tree.getKind() != Tree.Kind.RECORD && inherits) {
            // The reference reads a superclass by its simple name: one written Object is no inheritance there, so a user's own
            // Object stays a stop, which no rule of the reference's answers.
            // A user's own Object is no java.lang.Object: inheritance as any other (JULC0109).
            throw facts.rejection(DiagnosticCodes.INHERITANCE_UNSUPPORTED, tree);
        }
        return super.visitClass(tree, unused);
    }

    private static String simpleName(Tree type) {
        return switch (type) {
            case IdentifierTree name -> name.getName().toString();
            case MemberSelectTree select -> select.getIdentifier().toString();
            case ParameterizedTypeTree parameterized -> simpleName(parameterized.getType());
            default -> "";
        };
    }

    private boolean isObject(Tree type) {
        return facts.element(type) instanceof TypeElement element && facts.identity(element).equals(JavacNames.OBJECT);
    }

    /** Floating point is outside the subset. */
    @Override
    public Void visitPrimitiveType(PrimitiveTypeTree tree, Void unused) {
        if (tree.getPrimitiveTypeKind() == TypeKind.FLOAT || tree.getPrimitiveTypeKind() == TypeKind.DOUBLE) {
            throw facts.rejection(DiagnosticCodes.FLOATING_POINT_UNSUPPORTED, tree);
        }
        return null;
    }

    /** {@code this} and {@code super} are identifiers to javac, and outside the subset. */
    @Override
    public Void visitIdentifier(IdentifierTree tree, Void unused) {
        if (isThisOrSuper(tree.getName())) throw facts.rejection(DiagnosticCodes.THIS_OR_SUPER_UNSUPPORTED, tree, tree.getName());
        return null;
    }

    /** {@code V.this} and {@code V.super} are member selects to javac. */
    @Override
    public Void visitMemberSelect(MemberSelectTree tree, Void unused) {
        if (isThisOrSuper(tree.getIdentifier())) {
            throw facts.rejection(DiagnosticCodes.THIS_OR_SUPER_UNSUPPORTED, tree, tree.getIdentifier());
        }
        return super.visitMemberSelect(tree, unused);
    }

    private static boolean isThisOrSuper(CharSequence name) {
        return name.toString().equals("this") || name.toString().equals("super");
    }

    /** {@code null} is outside the subset wherever it stands; a string literal is Unicode text. */
    @Override
    public Void visitLiteral(LiteralTree tree, Void unused) {
        if (tree.getKind() == Tree.Kind.NULL_LITERAL) throw facts.rejection(DiagnosticCodes.NULL_UNSUPPORTED, tree);
        requirePairedSurrogates(tree);
        return null;
    }

    /** D6: {@code ==}, {@code !=} and string {@code +} are Java's, not their on-chain lowering's. */
    @Override
    public Void visitBinary(BinaryTree tree, Void unused) {
        requireOneArmTypeWithin(tree);
        if (!equalityDeferred) equality.check(tree);
        return super.visitBinary(tree, unused);
    }

    /** Arms of two types are the rule that speaks first in a call or a comparison: what is compared has no type (ArmTypes). */
    private void requireOneArmTypeWithin(Tree tree) {
        for (var choice : SubsetWalk.all(tree, ExpressionTree.class)) {
            if (choice instanceof ConditionalExpressionTree || choice instanceof SwitchExpressionTree) ArmTypes.requireOneType(facts, choice);
        }
    }

    /** A conditional of arms of two types has no type (ArmTypes, ADR-068 M5). */
    @Override
    public Void visitConditionalExpression(ConditionalExpressionTree tree, Void unused) {
        ArmTypes.requireOneType(facts, tree);
        return super.visitConditionalExpression(tree, unused);
    }

    /** A {@code switch} expression of arms of two types has no type (ArmTypes, ADR-068 M5). */
    @Override
    public Void visitSwitchExpression(SwitchExpressionTree tree, Void unused) {
        ArmTypes.requireOneType(facts, tree);
        return super.visitSwitchExpression(tree, unused);
    }

    /** D6: an operation that compares by {@code equals} must not reach an array by identity. */
    @Override
    public Void visitMethodInvocation(MethodInvocationTree tree, Void unused) {
        requireNoFunctionalCall(tree);
        requireOneArmTypeWithin(tree);
        if (!equalityDeferred) equality.check(tree);
        return super.visitMethodInvocation(tree, unused);
    }

    /** A function exists on-chain only as the argument of the list method that applies it, and a local holds none. */
    private void requireNoFunctionalCall(MethodInvocationTree tree) {
        if (tree.getMethodSelect() instanceof MemberSelectTree select && select.getExpression() instanceof IdentifierTree receiver
                && FUNCTIONAL_METHODS.contains(select.getIdentifier().toString())
                && facts.element(receiver).getKind() == ElementKind.LOCAL_VARIABLE
                && (isFunctional(facts.type(receiver)) || spelledFunctional(receiver))) {
            throw facts.rejection(DiagnosticCodes.FUNCTIONAL_INTERFACE_CALL_UNSUPPORTED, tree, select.getIdentifier());
        }
    }

    /**
     * The reference's reading: a local of the enclosing method declared with a written type whose simple name is one of the
     * JDK's functional interfaces, whatever type that name binds to. So a user's {@code record Supplier(BigInteger get)}
     * read through {@code s.get()} is rejected, as there. A {@code var} writes no type, and the reference reads none.
     */
    private boolean spelledFunctional(IdentifierTree receiver) {
        var method = facts.enclosing(receiver, Tree.Kind.METHOD);
        if (method == null) return false;
        for (var local : SubsetWalk.all(method, VariableTree.class)) {
            var declaredIn = facts.parent(local);
            if (!local.getName().contentEquals(receiver.getName()) || declaredIn instanceof MethodTree
                    || declaredIn instanceof LambdaExpressionTree) continue;
            if (local.getType() != null && !facts.isGenerated(local.getType()) && FUNCTIONAL_TYPES.contains(rawSimpleName(local.getType()))) {
                return true;
            }
        }
        return false;
    }

    private static String rawSimpleName(Tree type) {
        return switch (type) {
            case ParameterizedTypeTree parameterized -> rawSimpleName(parameterized.getType());
            case MemberSelectTree select -> select.toString();
            default -> type.toString();
        };
    }

    /** Whether {@code type} is a functional interface of the JDK's, by identity. */
    private boolean isFunctional(TypeMirror type) {
        return type.getKind() == TypeKind.DECLARED && facts.identity(facts.typeElement(type)).startsWith(JavacNames.FUNCTIONS);
    }

    /** Outside the subset, wherever it stands. */
    @Override
    public Void visitMemberReference(MemberReferenceTree tree, Void unused) {
        throw facts.rejection(DiagnosticCodes.METHOD_REFERENCE_UNSUPPORTED, tree);
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

    /**
     * The one array: {@code new byte[]{...}} over integer literals, each negated or not. Any other {@code new} of an array is
     * the reference's {@code JULC0014}. A bare initializer, {@code int[] a = {1}}, has no {@code new}, and the reference has
     * no rule of its own for it, so it stays a stop.
     */
    @Override
    public Void visitNewArray(NewArrayTree tree, Void unused) {
        if (isByteLiteral(tree)) return null;
        throw facts.rejection(DiagnosticCodes.ARRAY_UNSUPPORTED, tree);
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
