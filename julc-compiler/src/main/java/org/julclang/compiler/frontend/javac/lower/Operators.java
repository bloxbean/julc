package org.julclang.compiler.frontend.javac.lower;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.UnaryTree;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.frontend.javac.JavacNames;
import org.julclang.compiler.frontend.javac.NotPorted;
import org.julclang.compiler.frontend.javac.subset.EqualityRules;
import org.julclang.compiler.pir.PirHelpers;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.resolve.LibraryMethodRegistry;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import java.math.BigInteger;

/**
 * Operators, D6's comparison rules among them (ADR-068).
 * <p>
 * <b>Evaluation order.</b> Java evaluates left then right. So does UPLC for a builtin applied to the operands in that
 * order: {@code + - * / %}, {@code == !=}, {@code <} and {@code <=}. {@code >} and {@code >=} have no builtin of their own
 * and apply {@code <} or {@code <=} to the right operand first, so {@code PirHelpers.swappedInOrder} holds D6's
 * minimal binding: one {@code let} of the left operand, and only when both are non-trivial. {@code a.compareTo(b) OP 0}
 * evaluates {@code a} then {@code b}, each once, by the same rule.
 * <p>
 * {@code a && b} is {@code if a then b else false} and {@code a || b} is {@code if a then true else b}, so the right
 * operand runs only when Java's would. {@code c ? t : e} runs {@code c} and one branch. {@code !a} and {@code -a} evaluate
 * their operand once.
 * <p>
 * The subset's rules on these operators are {@code SubsetScan}'s, and run over more than is lowered. Each case here still
 * refuses a type it has no meaning for, so a term is never built on what a rule was meant to have stopped.
 */
final class Operators {

    private static final String COMPARE_TO = "compareTo";

    private final JavacFacts facts;
    private final Typing typing;
    private final CallTargets targets;
    private final Lowering lowering;
    private final EqualityRules equalities;

    Operators(JavacFacts facts, Typing typing, CallTargets targets, Lowering lowering) {
        this.facts = facts;
        this.equalities = new EqualityRules(facts);
        this.typing = typing;
        this.targets = targets;
        this.lowering = lowering;
    }

    /**
     * What an assignment stores (ADR-060): the right-hand side, or {@code target op value}, in Java's order: the target is
     * read first. Integers take every lowered operator and strings take {@code +=}. {@code SubsetScan} has rejected the other
     * operators, and javac every other operand and operator on these types.
     */
    PirTerm assigned(ExpressionTree assignment, Scope.Binder target, Frame frame) {
        String name = target.element().getSimpleName().toString();
        if (assignment instanceof AssignmentTree plain) {
            var value = lowering.expr(plain.getExpression(), frame);
            typing.requireNativeBoundary("Assignment to '" + name + "'", typing.of(plain.getExpression(), value), target.type(), plain.getExpression());
            return value;
        }
        var compound = (CompoundAssignmentTree) assignment;
        String operator = compoundOperator(compound);
        boolean string = target.type() instanceof PirType.StringType;
        if (!(target.type() instanceof PirType.IntegerType) && !string) {
            throw facts.locatedRejection("Compound assignment " + operator + " to '" + name + "' of type "
                            + LibraryMethodRegistry.pirTypeName(target.type()) + " is not supported",
                    "Compound assignment is supported on integer variables, and += on String variables. "
                            + "Write the update explicitly, e.g. x = x.add(y).", assignment);
        }
        var current = lowering.expr(compound.getVariable(), frame);
        var operand = lowering.expr(compound.getExpression(), frame);
        var operandType = typing.of(compound.getExpression(), operand);
        if (string && !(operandType instanceof PirType.StringType)) {
            throw facts.locatedRejection("Compound assignment " + operator + " to '" + name + "' needs a "
                            + LibraryMethodRegistry.pirTypeName(target.type()) + " operand, found " + LibraryMethodRegistry.pirTypeName(operandType),
                    "Convert the right-hand side to the variable's type first.", compound.getExpression());
        }
        if (string) return PirHelpers.builtinApp2(DefaultFun.AppendString, current, operand);
        return PirHelpers.builtinApp2(switch (compound.getKind()) {
            case PLUS_ASSIGNMENT -> DefaultFun.AddInteger;
            case MINUS_ASSIGNMENT -> DefaultFun.SubtractInteger;
            case MULTIPLY_ASSIGNMENT -> DefaultFun.MultiplyInteger;
            case DIVIDE_ASSIGNMENT -> DefaultFun.QuotientInteger;
            case REMAINDER_ASSIGNMENT -> DefaultFun.RemainderInteger;
            default -> throw NotPorted.at(facts, compound);
        }, current, operand);
    }

    private static String compoundOperator(CompoundAssignmentTree tree) {
        return switch (tree.getKind()) {
            case PLUS_ASSIGNMENT -> "+=";
            case MINUS_ASSIGNMENT -> "-=";
            case MULTIPLY_ASSIGNMENT -> "*=";
            case DIVIDE_ASSIGNMENT -> "/=";
            default -> "%=";
        };
    }

    PirTerm binary(BinaryTree tree, Frame frame) {
        var compareTo = compareToZero(tree);
        if (compareTo != null) return compareTo.lower(frame);
        var left = lowering.expr(tree.getLeftOperand(), frame);
        var right = lowering.expr(tree.getRightOperand(), frame);
        return switch (tree.getKind()) {
            case PLUS -> plus(tree, left, right);
            case MINUS -> integers(tree, DefaultFun.SubtractInteger, left, right);
            case MULTIPLY -> integers(tree, DefaultFun.MultiplyInteger, left, right);
            case DIVIDE -> integers(tree, DefaultFun.QuotientInteger, left, right);
            case REMAINDER -> integers(tree, DefaultFun.RemainderInteger, left, right);
            case LESS_THAN, LESS_THAN_EQUAL, GREATER_THAN, GREATER_THAN_EQUAL -> {
                requireIntegers(tree);
                yield comparison(tree.getKind(), left, right);
            }
            case EQUAL_TO -> equality(tree, left, right, false);
            case NOT_EQUAL_TO -> equality(tree, left, right, true);
            case CONDITIONAL_AND -> new PirTerm.IfThenElse(left, right, bool(false));
            case CONDITIONAL_OR -> new PirTerm.IfThenElse(left, bool(true), right);
            // Only a guard reaches these, on booleans: the rest of a guard's int and long arithmetic is a rule's.
            case AND -> throw unsupported("BINARY_AND", tree);
            case OR -> throw unsupported("BINARY_OR", tree);
            case XOR -> throw unsupported("XOR", tree);
            default -> throw NotPorted.at(facts, tree);
        };
    }

    private CompilerException unsupported(String operator, BinaryTree tree) {
        return facts.rejection("Unsupported operator: " + operator, "Supported operators: +, -, *, /, %, ==, !=, <, <=, >, >=, &&, ||", tree);
    }

    private static PirTerm bool(boolean value) {
        return new PirTerm.Const(Constant.bool(value));
    }

    private PirTerm plus(BinaryTree tree, PirTerm left, PirTerm right) {
        if (typing.of(tree) instanceof PirType.StringType) {
            if (!(typing.of(tree.getLeftOperand()) instanceof PirType.StringType
                    && typing.of(tree.getRightOperand()) instanceof PirType.StringType)) {
                throw NotPorted.at(facts, tree, "a string + with another type");
            }
            return PirHelpers.builtinApp2(DefaultFun.AppendString, left, right);
        }
        return integers(tree, DefaultFun.AddInteger, left, right);
    }

    private PirTerm integers(BinaryTree tree, DefaultFun fun, PirTerm left, PirTerm right) {
        requireIntegers(tree);
        return PirHelpers.builtinApp2(fun, left, right);
    }

    private void requireIntegers(BinaryTree tree) {
        if (!(typing.of(tree.getLeftOperand()) instanceof PirType.IntegerType
                && typing.of(tree.getRightOperand()) instanceof PirType.IntegerType)) {
            throw NotPorted.at(facts, tree);
        }
    }

    private static PirTerm comparison(Tree.Kind operator, PirTerm x, PirTerm y) {
        return switch (operator) {
            case LESS_THAN -> PirHelpers.builtinApp2(DefaultFun.LessThanInteger, x, y);
            case LESS_THAN_EQUAL -> PirHelpers.builtinApp2(DefaultFun.LessThanEqualsInteger, x, y);
            case GREATER_THAN -> PirHelpers.swappedInOrder(DefaultFun.LessThanInteger, x, y);
            case GREATER_THAN_EQUAL -> PirHelpers.swappedInOrder(DefaultFun.LessThanEqualsInteger, x, y);
            case EQUAL_TO -> PirHelpers.equality(x, y, new PirType.IntegerType(), false);
            case NOT_EQUAL_TO -> PirHelpers.equality(x, y, new PirType.IntegerType(), true);
            default -> throw new IllegalArgumentException("Not a comparison: " + operator);
        };
    }

    /**
     * D6: {@code ==} on references is identity in Java, so every one that reaches a term stops here, wherever it stands: no
     * reading of a guard's positions could promise that. What it lets through is numeric or boolean equality, or two
     * constant strings of D6's plain form. A guard constant as a whole has Java's value and reaches no term, and a constant
     * of any other form in a guard stops, as a term of it can read otherwise than Java.
     */
    private PirTerm equality(BinaryTree tree, PirTerm left, PirTerm right, boolean negate) {
        equalities.requireValueEquality(tree);
        return PirHelpers.equality(left, right, typing.of(tree.getLeftOperand(), left), negate);
    }

    PirTerm unary(UnaryTree tree, Frame frame) {
        if (tree.getKind() == Tree.Kind.UNARY_MINUS && isMinimumValue(tree.getExpression())) {
            throw NotPorted.at(facts, tree, "the negation of a type's minimum value");
        }
        var operand = lowering.expr(tree.getExpression(), frame);
        return switch (tree.getKind()) {
            case LOGICAL_COMPLEMENT -> new PirTerm.IfThenElse(operand, bool(false), bool(true));
            case UNARY_MINUS -> PirHelpers.builtinApp2(DefaultFun.SubtractInteger,
                    new PirTerm.Const(Constant.integer(BigInteger.ZERO)), operand);
            case PREFIX_INCREMENT, PREFIX_DECREMENT, POSTFIX_INCREMENT, POSTFIX_DECREMENT ->
                    throw facts.rejection("Unsupported unary operator: " + tree.getKind(), "Supported unary operators: ! (logical NOT) and - (negation)", tree);
            default -> throw NotPorted.at(facts, tree);
        };
    }

    /**
     * {@code -0x80000000} wraps to itself in Java, and {@code 0 - n} is positive here. The JVM and the reference disagree on
     * this literal, which javac's value shows, so it stops until someone decides which is right. The same operand at run
     * time is T6, which no tree shows.
     */
    private static boolean isMinimumValue(ExpressionTree operand) {
        var inner = operand;
        while (inner instanceof ParenthesizedTree parenthesized) inner = parenthesized.getExpression();
        return inner instanceof LiteralTree literal && switch (literal.getKind()) {
            case INT_LITERAL -> ((Number) literal.getValue()).intValue() == Integer.MIN_VALUE;
            case LONG_LITERAL -> ((Number) literal.getValue()).longValue() == Long.MIN_VALUE;
            default -> false;
        };
    }

    /** ADR-047: the term is typed by its then branch, so where a native type is in play the else branch must match. */
    PirTerm conditional(ConditionalExpressionTree tree, Frame frame) {
        var condition = lowering.expr(tree.getCondition(), frame);
        var thenTerm = lowering.expr(tree.getTrueExpression(), frame);
        var elseTerm = lowering.expr(tree.getFalseExpression(), frame);
        typing.requireNativeBoundary("Conditional else branch", typing.of(tree.getFalseExpression(), elseTerm),
                typing.of(tree.getTrueExpression(), thenTerm), tree.getFalseExpression());
        return new PirTerm.IfThenElse(condition, thenTerm, elseTerm);
    }

    // --- a.compareTo(b) OP 0 (D6) ---

    /** {@code a.compareTo(b) OP 0}, with {@code OP} normalized so that the call is on the left. */
    private final class CompareToZero {
        private final MethodInvocationTree call;
        private final Tree.Kind operator;

        private CompareToZero(MethodInvocationTree call, Tree.Kind operator) {
            this.call = call;
            this.operator = operator;
        }

        /** A receiver the reference reads as anything but an integer has no {@code compareTo} there (I2). */
        PirTerm lower(Frame frame) {
            var receiver = ((MemberSelectTree) call.getMethodSelect()).getExpression();
            var x = lowering.expr(receiver, frame);
            var y = lowering.expr(call.getArguments().getFirst(), frame);
            targets.requireSeen(TypeKeys.of(JavacNames.BIG_INTEGER), COMPARE_TO, receiver, x, call);
            return comparison(operator, x, y);
        }
    }

    /**
     * D6's match. Its call must resolve to {@code BigInteger.compareTo(BigInteger)}, and its zero must be the {@code int}
     * literal, so {@code 0L} is another form. Parentheses are not looked through: they keep today's lowering, as in the
     * reference. A sign and a zero stay a unary tree to javac, so {@code -0} is another form too.
     */
    private CompareToZero compareToZero(BinaryTree tree) {
        var operator = tree.getKind();
        if (!isComparison(operator)) return null;
        if (isCompareTo(tree.getLeftOperand()) && isIntZero(tree.getRightOperand())) {
            return new CompareToZero((MethodInvocationTree) tree.getLeftOperand(), operator);
        }
        if (isIntZero(tree.getLeftOperand()) && isCompareTo(tree.getRightOperand())) {
            return new CompareToZero((MethodInvocationTree) tree.getRightOperand(), reversed(operator));
        }
        return null;
    }

    private static boolean isComparison(Tree.Kind operator) {
        return switch (operator) {
            case LESS_THAN, LESS_THAN_EQUAL, GREATER_THAN, GREATER_THAN_EQUAL, EQUAL_TO, NOT_EQUAL_TO -> true;
            default -> false;
        };
    }

    private static Tree.Kind reversed(Tree.Kind operator) {
        return switch (operator) {
            case LESS_THAN -> Tree.Kind.GREATER_THAN;
            case LESS_THAN_EQUAL -> Tree.Kind.GREATER_THAN_EQUAL;
            case GREATER_THAN -> Tree.Kind.LESS_THAN;
            case GREATER_THAN_EQUAL -> Tree.Kind.LESS_THAN_EQUAL;
            default -> operator;
        };
    }

    private boolean isCompareTo(ExpressionTree tree) {
        if (!(tree instanceof MethodInvocationTree call && call.getMethodSelect() instanceof MemberSelectTree select
                && select.getIdentifier().contentEquals(COMPARE_TO))) {
            return false;
        }
        var method = (ExecutableElement) facts.element(select);
        var parameters = method.getParameters();
        return !method.getModifiers().contains(Modifier.STATIC)
                && isBigInteger((TypeElement) method.getEnclosingElement())
                && parameters.size() == 1
                && parameters.getFirst().asType().getKind() == TypeKind.DECLARED
                && isBigInteger(facts.typeElement(parameters.getFirst().asType()));
    }

    private boolean isBigInteger(TypeElement type) {
        return facts.identity(type).equals(JavacNames.BIG_INTEGER);
    }

    private static boolean isIntZero(ExpressionTree tree) {
        return tree.getKind() == Tree.Kind.INT_LITERAL && ((Number) ((LiteralTree) tree).getValue()).intValue() == 0;
    }
}
