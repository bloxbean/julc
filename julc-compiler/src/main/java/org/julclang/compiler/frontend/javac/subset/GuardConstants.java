package org.julclang.compiler.frontend.javac.subset;

import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.frontend.javac.JavacNames;

import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The constant expressions a {@code switch} guard may use (ADR-068 I9), ported from {@code JavaConstants}: constants of type
 * {@code boolean}, {@code int}, {@code long} and {@code String}, with Java's fixed widths. No exported javac API gives the
 * value of an expression, so the operators stay this class's. A leaf comes from {@link LiteralTree#getValue()}, or from the
 * initializer of what javac bound a name to, so a variable is the one the name means, and a {@code @Param} never is one
 * (D14). What the reference reads no initializer of, a constant of a class file among them, is not a constant here, or
 * the subset would widen (I2).
 * <p>
 * A form that is a constant in Java and that this class does not model exactly (floating point, {@code char},
 * {@code byte}, {@code short}, a division by zero) is {@link Result#UNCLASSIFIABLE}, never a guess. Reads no term, so it
 * has no evaluation order of its own: a constant has no effect, and a division by zero is not evaluated.
 */
public final class GuardConstants {

    /** The Java type of a constant: byte and short values are held as {@code INT} after promotion. */
    public enum Kind { BOOLEAN, INT, LONG, STRING }

    /** A constant value and its type: Boolean, Integer, Long or String. */
    public record Value(Kind kind, Object value) {
        boolean isNumeric() {
            return kind == Kind.INT || kind == Kind.LONG;
        }

        public long asLong() {
            return ((Number) value).longValue();
        }
    }

    /** Whether the expression is a constant, and its value. */
    public record Result(Value value, boolean constant, boolean unclassifiable) {
        static final Result NOT_CONSTANT = new Result(null, false, false);
        static final Result UNCLASSIFIABLE = new Result(null, true, true);

        static Result of(Kind kind, Object value) {
            return new Result(new Value(kind, value), true, false);
        }
    }

    private final JavacFacts facts;

    public GuardConstants(JavacFacts facts) {
        this.facts = facts;
    }

    public Result evaluate(ExpressionTree tree) {
        return evaluate(tree, new HashSet<>());
    }

    private Result evaluate(ExpressionTree tree, Set<Element> visiting) {
        return switch (tree) {
            case LiteralTree literal -> literal(literal);
            case ParenthesizedTree parenthesized -> evaluate(parenthesized.getExpression(), visiting);
            case UnaryTree unary -> unary(unary.getKind(), evaluate(unary.getExpression(), visiting));
            case BinaryTree binary -> binary(binary.getKind(), evaluate(binary.getLeftOperand(), visiting),
                    evaluate(binary.getRightOperand(), visiting));
            case ConditionalExpressionTree conditional -> conditional(evaluate(conditional.getCondition(), visiting),
                    evaluate(conditional.getTrueExpression(), visiting), evaluate(conditional.getFalseExpression(), visiting));
            case TypeCastTree cast -> cast(cast.getType(), evaluate(cast.getExpression(), visiting));
            case IdentifierTree name -> variable(name, visiting);
            case MemberSelectTree select -> qualified(select, visiting);
            default -> Result.NOT_CONSTANT;
        };
    }

    private static Result literal(LiteralTree literal) {
        return switch (literal.getKind()) {
            case BOOLEAN_LITERAL -> Result.of(Kind.BOOLEAN, literal.getValue());
            case INT_LITERAL -> Result.of(Kind.INT, literal.getValue());
            case LONG_LITERAL -> Result.of(Kind.LONG, literal.getValue());
            case STRING_LITERAL -> Result.of(Kind.STRING, literal.getValue());
            case CHAR_LITERAL, FLOAT_LITERAL, DOUBLE_LITERAL -> Result.UNCLASSIFIABLE;
            default -> Result.NOT_CONSTANT;
        };
    }

    /** Combines operand results: a non-constant operand makes the whole non-constant. */
    private static Result early(Result... operands) {
        for (var operand : operands) if (!operand.constant()) return Result.NOT_CONSTANT;
        for (var operand : operands) if (operand.unclassifiable()) return Result.UNCLASSIFIABLE;
        return null;
    }

    private static Result unary(Tree.Kind operator, Result operand) {
        var early = early(operand);
        if (early != null) return early;
        var v = operand.value();
        return switch (operator) {
            case LOGICAL_COMPLEMENT -> v.kind() == Kind.BOOLEAN ? Result.of(Kind.BOOLEAN, !(Boolean) v.value()) : Result.NOT_CONSTANT;
            case UNARY_PLUS -> v.isNumeric() ? integral(promoted(v.kind()), v.asLong()) : Result.NOT_CONSTANT;
            case UNARY_MINUS -> v.isNumeric() ? integral(promoted(v.kind()), -v.asLong()) : Result.NOT_CONSTANT;
            case BITWISE_COMPLEMENT -> v.isNumeric() ? integral(promoted(v.kind()), ~v.asLong()) : Result.NOT_CONSTANT;
            default -> Result.NOT_CONSTANT; // ++ and -- are never constant
        };
    }

    private static Kind promoted(Kind kind) {
        return kind == Kind.LONG ? Kind.LONG : Kind.INT;
    }

    private static Kind binaryPromoted(Value a, Value b) {
        return a.kind() == Kind.LONG || b.kind() == Kind.LONG ? Kind.LONG : Kind.INT;
    }

    private static Result integral(Kind kind, long value) {
        return kind == Kind.LONG ? Result.of(Kind.LONG, value) : Result.of(Kind.INT, (int) value);
    }

    private static Result binary(Tree.Kind operator, Result left, Result right) {
        var early = early(left, right);
        if (early != null) return early;
        var a = left.value();
        var b = right.value();
        if (operator == Tree.Kind.PLUS && (a.kind() == Kind.STRING || b.kind() == Kind.STRING)) {
            return Result.of(Kind.STRING, String.valueOf(a.value()) + b.value());
        }
        if (a.kind() == Kind.BOOLEAN && b.kind() == Kind.BOOLEAN) {
            boolean x = (Boolean) a.value();
            boolean y = (Boolean) b.value();
            return switch (operator) {
                case CONDITIONAL_AND, AND -> Result.of(Kind.BOOLEAN, x && y);
                case CONDITIONAL_OR, OR -> Result.of(Kind.BOOLEAN, x || y);
                case XOR -> Result.of(Kind.BOOLEAN, x ^ y);
                case EQUAL_TO -> Result.of(Kind.BOOLEAN, x == y);
                case NOT_EQUAL_TO -> Result.of(Kind.BOOLEAN, x != y);
                default -> Result.NOT_CONSTANT;
            };
        }
        if (a.kind() == Kind.STRING && b.kind() == Kind.STRING) {
            // Constant strings are interned (JLS 3.10.5), so == compares their values.
            return switch (operator) {
                case EQUAL_TO -> Result.of(Kind.BOOLEAN, a.value().equals(b.value()));
                case NOT_EQUAL_TO -> Result.of(Kind.BOOLEAN, !a.value().equals(b.value()));
                default -> Result.NOT_CONSTANT;
            };
        }
        if (!a.isNumeric() || !b.isNumeric()) return Result.NOT_CONSTANT;
        return numeric(operator, a, b);
    }

    private static Result numeric(Tree.Kind operator, Value a, Value b) {
        long x = a.asLong();
        long y = b.asLong();
        Kind kind = binaryPromoted(a, b);
        boolean wide = kind == Kind.LONG;
        // Shifts take the promoted type of the left operand only (JLS 15.19).
        boolean wideLeft = promoted(a.kind()) == Kind.LONG;
        return switch (operator) {
            case EQUAL_TO -> Result.of(Kind.BOOLEAN, x == y);
            case NOT_EQUAL_TO -> Result.of(Kind.BOOLEAN, x != y);
            case LESS_THAN -> Result.of(Kind.BOOLEAN, x < y);
            case LESS_THAN_EQUAL -> Result.of(Kind.BOOLEAN, x <= y);
            case GREATER_THAN -> Result.of(Kind.BOOLEAN, x > y);
            case GREATER_THAN_EQUAL -> Result.of(Kind.BOOLEAN, x >= y);
            case PLUS -> integral(kind, wide ? x + y : (int) x + (int) y);
            case MINUS -> integral(kind, wide ? x - y : (int) x - (int) y);
            case MULTIPLY -> integral(kind, wide ? x * y : (int) x * (int) y);
            case DIVIDE -> y == 0 ? Result.UNCLASSIFIABLE : integral(kind, wide ? x / y : (int) x / (int) y);
            case REMAINDER -> y == 0 ? Result.UNCLASSIFIABLE : integral(kind, wide ? x % y : (int) x % (int) y);
            case AND -> integral(kind, x & y);
            case OR -> integral(kind, x | y);
            case XOR -> integral(kind, x ^ y);
            case LEFT_SHIFT -> wideLeft ? Result.of(Kind.LONG, x << y) : Result.of(Kind.INT, (int) x << (int) y);
            case RIGHT_SHIFT -> wideLeft ? Result.of(Kind.LONG, x >> y) : Result.of(Kind.INT, (int) x >> (int) y);
            case UNSIGNED_RIGHT_SHIFT -> wideLeft ? Result.of(Kind.LONG, x >>> y) : Result.of(Kind.INT, (int) x >>> (int) y);
            default -> Result.NOT_CONSTANT;
        };
    }

    private static Result conditional(Result condition, Result then, Result otherwise) {
        var early = early(condition, then, otherwise);
        if (early != null) return early;
        if (condition.value().kind() != Kind.BOOLEAN) return Result.NOT_CONSTANT;
        var chosen = (Boolean) condition.value().value() ? then.value() : otherwise.value();
        var a = then.value();
        var b = otherwise.value();
        if (a.kind() == Kind.BOOLEAN && b.kind() == Kind.BOOLEAN) return Result.of(Kind.BOOLEAN, chosen.value());
        if (a.kind() == Kind.STRING && b.kind() == Kind.STRING) return Result.of(Kind.STRING, chosen.value());
        // Binary numeric promotion of the operands (JLS 15.25.2).
        if (a.isNumeric() && b.isNumeric()) return integral(binaryPromoted(a, b), chosen.asLong());
        // Mixed operands (boolean and int, say) give a reference type: not a constant expression (JLS 15.29).
        return Result.NOT_CONSTANT;
    }

    private Result cast(Tree type, Result operand) {
        if (type instanceof PrimitiveTypeTree primitive) return toPrimitive(primitive.getPrimitiveTypeKind(), operand);
        if (isString(facts.type(type))) {
            var early = early(operand);
            if (early != null) return early;
            return operand.value().kind() == Kind.STRING ? operand : Result.NOT_CONSTANT;
        }
        return Result.NOT_CONSTANT; // a cast to any other reference type is not a constant expression
    }

    private static Result toPrimitive(TypeKind target, Result operand) {
        var early = early(operand);
        if (early != null) return early;
        var v = operand.value();
        return switch (target) {
            case BOOLEAN -> v.kind() == Kind.BOOLEAN ? operand : Result.NOT_CONSTANT;
            case INT -> v.isNumeric() ? Result.of(Kind.INT, (int) v.asLong()) : Result.NOT_CONSTANT;
            case LONG -> v.isNumeric() ? Result.of(Kind.LONG, v.asLong()) : Result.NOT_CONSTANT;
            // Outside the verified subset (boolean, int, long, String): never classified.
            default -> Result.UNCLASSIFIABLE;
        };
    }

    private boolean isString(TypeMirror type) {
        return type.getKind() == TypeKind.DECLARED && facts.identity(facts.typeElement(type)).equals(JavacNames.STRING);
    }

    // --- Constant variables (JLS 4.12.4) ---

    /** A {@code final} local, or a {@code static final} field of a class around {@code name}. */
    private Result variable(IdentifierTree name, Set<Element> visiting) {
        return facts.elementOrNull(name) instanceof VariableElement variable && isConstantDeclaration(variable, name)
                ? initializer(variable, visiting) : Result.NOT_CONSTANT;
    }

    /**
     * {@code T.F}: a constant only where {@code T} is a type of this unit, and {@code F} a constant field of exactly that
     * type. A variable of the name obscures the type, which javac has decided.
     */
    private Result qualified(MemberSelectTree select, Set<Element> visiting) {
        return constantFieldOf(select) instanceof VariableElement field ? initializer(field, visiting) : Result.NOT_CONSTANT;
    }

    private VariableElement constantFieldOf(MemberSelectTree select) {
        if (!(select.getExpression() instanceof IdentifierTree qualifier
                && facts.elementOrNull(qualifier) instanceof TypeElement type && inUnit(type, select))) {
            return null;
        }
        return facts.elementOrNull(select) instanceof VariableElement field && field.getEnclosingElement().equals(type)
                && isStaticFinal(field) ? field : null;
    }

    private boolean inUnit(TypeElement type, Tree at) {
        return facts.unitOf(type) != null && facts.unitOf(type) == facts.unitOfTree(at);
    }

    private boolean isConstantDeclaration(VariableElement variable, Tree at) {
        return switch (variable.getKind()) {
            case LOCAL_VARIABLE -> {
                var declaration = facts.declaration(variable);
                yield declaration != null && declaration.getModifiers().getFlags().contains(Modifier.FINAL);
            }
            case FIELD -> facts.encloses(variable.getEnclosingElement(), at) && isStaticFinal(variable);
            default -> false;
        };
    }

    /** With an interface's implicit modifiers, which javac has added. A {@code @Param} is the applied parameter. */
    private boolean isStaticFinal(VariableElement field) {
        if (facts.declaration(field) == null || facts.hasAnnotation(field, JavacNames.PARAM)) return false;
        return field.getModifiers().containsAll(Set.of(Modifier.STATIC, Modifier.FINAL));
    }

    private Result initializer(VariableElement variable, Set<Element> visiting) {
        var declaration = facts.declaration(variable);
        if (!isConstantType(declaration) || declaration.getInitializer() == null || !visiting.add(variable)) {
            return Result.NOT_CONSTANT;
        }
        try {
            var value = evaluate(declaration.getInitializer(), visiting);
            if (!value.constant() || value.unclassifiable() || isVar(declaration)) return value;
            // The assignment conversion to the declared type, widening included: a long N = 1 is a long. javac has rejected
            // a narrowing, and char, byte and short are not modelled.
            var type = variable.asType();
            return type.getKind().isPrimitive() ? toPrimitive(type.getKind(), value) : value;
        } finally {
            visiting.remove(variable);
        }
    }

    private boolean isVar(VariableTree declaration) {
        return declaration.getType() == null || facts.isGenerated(declaration.getType());
    }

    private boolean isConstantType(VariableTree declaration) {
        if (isVar(declaration)) return true;
        var type = facts.element(declaration).asType();
        return type.getKind().isPrimitive() || isString(type);
    }

    private static boolean assignable(TypeMirror type, Value value) {
        return switch (type.getKind()) {
            case BOOLEAN -> value.kind() == Kind.BOOLEAN;
            case INT -> value.kind() == Kind.INT;
            case LONG -> value.kind() == Kind.INT || value.kind() == Kind.LONG;
            case DECLARED -> value.kind() == Kind.STRING;
            default -> false;
        };
    }

    private boolean isSubsetType(TypeMirror type) {
        return switch (type.getKind()) {
            case BOOLEAN, INT, LONG -> true;
            default -> isString(type);
        };
    }

    /**
     * A constant declaration a guard reads, simply, qualified or through another constant's initializer, whether or not the
     * guard itself is constant, that JuLC cannot evaluate exactly: the reason, or null if none. What javac rejects for its
     * declared type does not reach this.
     */
    String invalidDeclaration(ExpressionTree guard) {
        var reachable = new ArrayList<VariableElement>();
        var pending = new ArrayDeque<>(referenced(guard));
        var seen = new HashSet<VariableElement>();
        while (!pending.isEmpty()) {
            var variable = pending.pop();
            if (!seen.add(variable)) continue;
            var declaration = facts.declaration(variable);
            if (!isConstantType(declaration) || declaration.getInitializer() == null) continue;
            reachable.add(variable);
            pending.addAll(referenced(declaration.getInitializer()));
        }
        for (var variable : reachable) {
            var declaration = facts.declaration(variable);
            var value = evaluate(declaration.getInitializer());
            if (!isVar(declaration) && value.constant() && !value.unclassifiable() && !assignable(variable.asType(), value.value())) {
                return isSubsetType(variable.asType())
                        ? "the constant declaration '" + text(declaration) + "' narrows its initializer, which javac rejects (JLS 5.2)"
                        : "the constant declaration '" + text(declaration) + "' has a type outside boolean, int, long and String";
            }
        }
        // An initializer the checker cannot evaluate exactly is rejected, never left to the ordinary lowering. The deepest
        // one is named: it is the cause.
        for (int i = reachable.size() - 1; i >= 0; i--) {
            var declaration = facts.declaration(reachable.get(i));
            if (evaluate(declaration.getInitializer()).unclassifiable()) {
                return "the constant declaration '" + text(declaration) + "' cannot be evaluated exactly by JuLC (an "
                        + "out-of-range literal, or a type outside boolean, int, long and String)";
            }
        }
        return null;
    }

    private String text(VariableTree declaration) {
        return declaration.getName() + " = " + facts.text(declaration.getInitializer());
    }

    /** The constant declarations {@code expression} names. */
    private List<VariableElement> referenced(ExpressionTree expression) {
        var result = new ArrayList<VariableElement>();
        for (var name : SubsetWalk.all(expression, IdentifierTree.class)) {
            if (facts.elementOrNull(name) instanceof VariableElement variable && isConstantDeclaration(variable, name)) {
                result.add(variable);
            }
        }
        for (var select : SubsetWalk.all(expression, MemberSelectTree.class)) {
            var field = constantFieldOf(select);
            if (field != null) result.add(field);
        }
        return result;
    }
}
