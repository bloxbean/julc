package org.julclang.compiler.pir;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.CharLiteralExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.type.Type;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Constant expressions of the Java language (JLS 21 §15.29), for the verified subset a switch guard may use (ADR-067
 * D1, D4, M1 review round 3): constants of type {@code boolean}, {@code int}, {@code long} and {@code String} with the
 * JLS's fixed-width semantics; casts to those types;
 * the unary, multiplicative, additive, shift, relational, equality, bitwise and conditional operators; and simple or
 * type-qualified names of constant variables (JLS §4.12.4). Operands follow Java's numeric promotion, so
 * {@code ((true ? 2147483647 : 0L) + 1) > 0} is {@code true}. A form that is a constant expression in Java but that
 * this class does not model exactly (floating point, a division by zero) is {@link Result#UNCLASSIFIABLE}, never a
 * guess: a guard must not be classified wrongly (I9).
 */
final class JavaConstants {

    /** The Java type of a constant value: byte and short values are held as {@code INT} after promotion. */
    enum Kind { BOOLEAN, INT, LONG, CHAR, STRING }

    /** A constant value and its type: Boolean, Integer, Long, Character or String. */
    record Value(Kind kind, Object value) {
        boolean isNumeric() {
            return kind == Kind.INT || kind == Kind.LONG || kind == Kind.CHAR;
        }

        long asLong() {
            return value instanceof Character c ? c : ((Number) value).longValue();
        }
    }

    /** Whether the expression is a constant, and its value. */
    record Result(Value value, boolean constant, boolean unclassifiable) {
        static final Result NOT_CONSTANT = new Result(null, false, false);
        static final Result UNCLASSIFIABLE = new Result(null, true, true);

        static Result of(Kind kind, Object value) {
            return new Result(new Value(kind, value), true, false);
        }
    }

    private JavaConstants() {
    }

    static Result evaluate(Expression expression) {
        return evaluate(expression, new HashSet<>());
    }

    private static Result evaluate(Expression expression, Set<Node> visiting) {
        return switch (expression) {
            case BooleanLiteralExpr literal -> Result.of(Kind.BOOLEAN, literal.getValue());
            case IntegerLiteralExpr literal when minimumValueOperand(literal) != null -> minimumValueOperand(literal);
            case LongLiteralExpr literal when minimumValueOperand(literal) != null -> minimumValueOperand(literal);
            case IntegerLiteralExpr literal -> number(() -> Result.of(Kind.INT, literal.asNumber().intValue()));
            case LongLiteralExpr literal -> number(() -> Result.of(Kind.LONG, literal.asNumber().longValue()));
            case CharLiteralExpr ignored -> Result.UNCLASSIFIABLE; // outside the verified subset (F63)
            case StringLiteralExpr literal -> number(() -> Result.of(Kind.STRING, literal.asString()));
            case TextBlockLiteralExpr literal -> number(() -> Result.of(Kind.STRING, literal.asString()));
            case DoubleLiteralExpr ignored -> Result.UNCLASSIFIABLE;
            case EnclosedExpr enclosed -> evaluate(enclosed.getInner(), visiting);
            case UnaryExpr unary -> unary(unary.getOperator(), evaluate(unary.getExpression(), visiting));
            case BinaryExpr binary -> binary(binary.getOperator(), evaluate(binary.getLeft(), visiting),
                    evaluate(binary.getRight(), visiting));
            case ConditionalExpr conditional -> conditional(evaluate(conditional.getCondition(), visiting),
                    evaluate(conditional.getThenExpr(), visiting), evaluate(conditional.getElseExpr(), visiting));
            case CastExpr cast -> cast(cast.getType(), evaluate(cast.getExpression(), visiting));
            case NameExpr name -> variable(name, visiting);
            case FieldAccessExpr access -> qualified(access, visiting);
            default -> Result.NOT_CONSTANT;
        };
    }

    private static Result number(java.util.function.Supplier<Result> literal) {
        try {
            return literal.get();
        } catch (RuntimeException e) {
            return Result.UNCLASSIFIABLE;
        }
    }

    /** Combines operand results: a non-constant operand makes the whole non-constant. */
    private static Result guard(Result... operands) {
        for (var operand : operands) if (!operand.constant()) return Result.NOT_CONSTANT;
        for (var operand : operands) if (operand.unclassifiable()) return Result.UNCLASSIFIABLE;
        return null;
    }

    /**
     * F82, F85: {@code -2147483648} and {@code -9223372036854775808L}, where the decimal literal is legal only as the
     * direct operand of unary minus (JLS 3.10.1), with underscores and either suffix case ({@code -2_147_483_648},
     * {@code -9223372036854775808l}); anywhere else it is out of range and unclassifiable. Null otherwise.
     */
    private static Result minimumValue(UnaryExpr unary) {
        if (unary.getOperator() != UnaryExpr.Operator.MINUS) return null;
        return switch (unary.getExpression()) {
            case IntegerLiteralExpr literal when digits(literal.getValue()).equals("2147483648") -> Result.of(Kind.INT, Integer.MIN_VALUE);
            case LongLiteralExpr literal when digits(literal.getValue()).equals("9223372036854775808") -> Result.of(Kind.LONG, Long.MIN_VALUE);
            default -> null;
        };
    }

    /**
     * The literal operand of {@link #minimumValue}: legal there, with the minimum as its two's-complement value, which
     * the negation keeps ({@code -Integer.MIN_VALUE == Integer.MIN_VALUE}), so both the operand on its own (as the
     * checker evaluates every part of a guard) and the negation are exact. Null otherwise.
     */
    private static Result minimumValueOperand(Expression literal) {
        return literal.getParentNode().orElse(null) instanceof UnaryExpr unary && unary.getExpression() == literal
                ? minimumValue(unary) : null;
    }

    /** A decimal literal's digits, without underscores or a long suffix. */
    private static String digits(String literal) {
        return literal.replace("_", "").replaceAll("[lL]$", "");
    }

    private static Result unary(UnaryExpr.Operator operator, Result operand) {
        var early = guard(operand);
        if (early != null) return early;
        var v = operand.value();
        return switch (operator) {
            case LOGICAL_COMPLEMENT -> v.kind() == Kind.BOOLEAN ? Result.of(Kind.BOOLEAN, !(Boolean) v.value()) : Result.NOT_CONSTANT;
            case PLUS -> v.isNumeric() ? integral(promoted(v.kind()), v.asLong()) : Result.NOT_CONSTANT;
            case MINUS -> v.isNumeric() ? integral(promoted(v.kind()), -v.asLong()) : Result.NOT_CONSTANT;
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

    private static Result binary(BinaryExpr.Operator operator, Result left, Result right) {
        var early = guard(left, right);
        if (early != null) return early;
        var a = left.value();
        var b = right.value();
        if (operator == BinaryExpr.Operator.PLUS && (a.kind() == Kind.STRING || b.kind() == Kind.STRING)) {
            return Result.of(Kind.STRING, text(a) + text(b));
        }
        if (a.kind() == Kind.BOOLEAN && b.kind() == Kind.BOOLEAN) {
            boolean x = (Boolean) a.value();
            boolean y = (Boolean) b.value();
            return switch (operator) {
                case AND, BINARY_AND -> Result.of(Kind.BOOLEAN, x && y);
                case OR, BINARY_OR -> Result.of(Kind.BOOLEAN, x || y);
                case XOR -> Result.of(Kind.BOOLEAN, x ^ y);
                case EQUALS -> Result.of(Kind.BOOLEAN, x == y);
                case NOT_EQUALS -> Result.of(Kind.BOOLEAN, x != y);
                default -> Result.NOT_CONSTANT;
            };
        }
        if (a.kind() == Kind.STRING && b.kind() == Kind.STRING) {
            // Constant strings are interned (JLS 3.10.5), so == compares their values.
            return switch (operator) {
                case EQUALS -> Result.of(Kind.BOOLEAN, a.value().equals(b.value()));
                case NOT_EQUALS -> Result.of(Kind.BOOLEAN, !a.value().equals(b.value()));
                default -> Result.NOT_CONSTANT;
            };
        }
        if (!a.isNumeric() || !b.isNumeric()) return Result.NOT_CONSTANT;
        long x = a.asLong();
        long y = b.asLong();
        Kind kind = binaryPromoted(a, b);
        boolean wide = kind == Kind.LONG;
        return switch (operator) {
            case EQUALS -> Result.of(Kind.BOOLEAN, x == y);
            case NOT_EQUALS -> Result.of(Kind.BOOLEAN, x != y);
            case LESS -> Result.of(Kind.BOOLEAN, x < y);
            case LESS_EQUALS -> Result.of(Kind.BOOLEAN, x <= y);
            case GREATER -> Result.of(Kind.BOOLEAN, x > y);
            case GREATER_EQUALS -> Result.of(Kind.BOOLEAN, x >= y);
            case PLUS -> integral(kind, wide ? x + y : (int) x + (int) y);
            case MINUS -> integral(kind, wide ? x - y : (int) x - (int) y);
            case MULTIPLY -> integral(kind, wide ? x * y : (int) x * (int) y);
            case DIVIDE -> y == 0 ? Result.UNCLASSIFIABLE : integral(kind, wide ? x / y : (int) x / (int) y);
            case REMAINDER -> y == 0 ? Result.UNCLASSIFIABLE : integral(kind, wide ? x % y : (int) x % (int) y);
            case BINARY_AND -> integral(kind, x & y);
            case BINARY_OR -> integral(kind, x | y);
            case XOR -> integral(kind, x ^ y);
            // Shifts take the promoted type of the left operand only (JLS 15.19).
            case LEFT_SHIFT -> promoted(a.kind()) == Kind.LONG ? Result.of(Kind.LONG, x << y) : Result.of(Kind.INT, (int) x << (int) y);
            case SIGNED_RIGHT_SHIFT -> promoted(a.kind()) == Kind.LONG ? Result.of(Kind.LONG, x >> y) : Result.of(Kind.INT, (int) x >> (int) y);
            case UNSIGNED_RIGHT_SHIFT -> promoted(a.kind()) == Kind.LONG ? Result.of(Kind.LONG, x >>> y) : Result.of(Kind.INT, (int) x >>> (int) y);
            default -> Result.NOT_CONSTANT;
        };
    }

    private static String text(Value v) {
        return String.valueOf(v.value());
    }

    private static Result conditional(Result condition, Result then, Result otherwise) {
        var early = guard(condition, then, otherwise);
        if (early != null) return early;
        if (condition.value().kind() != Kind.BOOLEAN) return Result.NOT_CONSTANT;
        var chosen = (Boolean) condition.value().value() ? then.value() : otherwise.value();
        var a = then.value();
        var b = otherwise.value();
        if (a.kind() == Kind.BOOLEAN && b.kind() == Kind.BOOLEAN) return Result.of(Kind.BOOLEAN, chosen.value());
        if (a.kind() == Kind.STRING && b.kind() == Kind.STRING) return Result.of(Kind.STRING, chosen.value());
        if (a.isNumeric() && b.isNumeric()) {
            // Binary numeric promotion of the operands (JLS 15.25.2); the narrower special cases keep the value.
            if (a.kind() == Kind.CHAR && b.kind() == Kind.CHAR) return Result.of(Kind.CHAR, chosen.value());
            return integral(binaryPromoted(a, b), chosen.asLong());
        }
        // Mixed operands (boolean and int, say) give a reference type: not a constant expression (JLS 15.29).
        return Result.NOT_CONSTANT;
    }

    private static Result cast(Type type, Result operand) {
        if (type instanceof PrimitiveType primitive) {
            var early = guard(operand);
            if (early != null) return early;
            var v = operand.value();
            return switch (primitive.getType()) {
                case BOOLEAN -> v.kind() == Kind.BOOLEAN ? operand : Result.NOT_CONSTANT;
                case INT -> v.isNumeric() ? Result.of(Kind.INT, (int) v.asLong()) : Result.NOT_CONSTANT;
                case LONG -> v.isNumeric() ? Result.of(Kind.LONG, v.asLong()) : Result.NOT_CONSTANT;
                // Outside the verified subset (boolean, int, long, String): never classified (F63).
                case SHORT, BYTE, CHAR, FLOAT, DOUBLE -> Result.UNCLASSIFIABLE;
            };
        }
        if (type.isClassOrInterfaceType() && type.asClassOrInterfaceType().getNameAsString().equals("String")) {
            var early = guard(operand);
            if (early != null) return early;
            return operand.value().kind() == Kind.STRING ? operand : Result.NOT_CONSTANT;
        }
        return Result.NOT_CONSTANT; // a cast to any other reference type is not a constant expression
    }

    // ---- constant variables (JLS 4.12.4)

    private static Result variable(NameExpr name, Set<Node> visiting) {
        var local = SwitchPatternChecker.resolveLocal(name, name.getNameAsString());
        if (local.isPresent()) {
            if (local.get() instanceof VariableDeclarator variable
                    && variable.getParentNode().orElse(null) instanceof VariableDeclarationExpr declaration
                    && declaration.isFinal()) {
                return initializer(variable, visiting);
            }
            return Result.NOT_CONSTANT;
        }
        for (var type = name.findAncestor(TypeDeclaration.class); type.isPresent();
             type = type.get().findAncestor(TypeDeclaration.class)) {
            var field = fieldOf(type.get(), name.getNameAsString());
            if (field.isPresent()) return constantField(field.get(), visiting);
        }
        return Result.NOT_CONSTANT;
    }

    /**
     * {@code T.F}: a constant only when {@code T} is a type in scope, not a variable of that name (a variable obscures
     * a type, JLS 6.4.2), and {@code F} is a constant field of exactly that type.
     */
    private static Result qualified(FieldAccessExpr access, Set<Node> visiting) {
        if (!(access.getScope() instanceof NameExpr qualifier)) return Result.NOT_CONSTANT;
        var type = SwitchPatternChecker.typeInScope(qualifier, qualifier.getNameAsString());
        if (type.isEmpty()) return Result.NOT_CONSTANT;
        var field = fieldOf(type.get(), access.getNameAsString());
        return field.isPresent() ? constantField(field.get(), visiting) : Result.NOT_CONSTANT;
    }

    /**
     * F71: a constant declaration that a guard references, simply, qualified ({@code Probe.N}) or through another
     * constant's initializer, whose initializer is a constant javac rejects for its declared type (a narrowing such as
     * {@code int N = 4294967296L}) or that JuLC cannot evaluate exactly ({@code int N = 2147483648}, F82), whether or
     * not the guard itself is constant; the reason, or null if none.
     */
    static String invalidDeclaration(Expression guard) {
        // The declarations the guard names, simply or qualified, and those their initializers name in turn.
        var reachable = new ArrayList<VariableDeclarator>();
        var pending = new ArrayDeque<VariableDeclarator>(referencedDeclarations(guard));
        var seen = new HashSet<VariableDeclarator>();
        while (!pending.isEmpty()) {
            var declaration = pending.pop();
            if (!seen.add(declaration)) continue;
            if (!isConstantType(declaration.getType()) || declaration.getInitializer().isEmpty()) continue;
            reachable.add(declaration);
            pending.addAll(referencedDeclarations(declaration.getInitializer().get()));
        }
        for (var declaration : reachable) {
            var value = evaluate(declaration.getInitializer().get(), new HashSet<>());
            if (!declaration.getType().isVarType() && value.constant() && !value.unclassifiable()
                    && !assignable(declaration.getType(), value.value())) {
                return isSubsetType(declaration.getType())
                        ? "the constant declaration '" + declaration + "' narrows its initializer, which javac rejects (JLS 5.2)"
                        : "the constant declaration '" + declaration + "' has a type outside boolean, int, long and String";
            }
        }
        // F82: an initializer the checker cannot evaluate exactly (an out-of-range literal such as 2147483648, floating
        // point) is rejected, never left to the ordinary lowering. The deepest one is named: it is the cause.
        for (int i = reachable.size() - 1; i >= 0; i--) {
            var declaration = reachable.get(i);
            if (evaluate(declaration.getInitializer().get(), new HashSet<>()).unclassifiable()) {
                return "the constant declaration '" + declaration + "' cannot be evaluated exactly by JuLC (an "
                        + "out-of-range literal, or a type outside boolean, int, long and String)";
            }
        }
        return null;
    }

    private static List<VariableDeclarator> referencedDeclarations(Expression expression) {
        var result = new ArrayList<VariableDeclarator>();
        for (var name : expression.findAll(NameExpr.class)) {
            var declaration = declarationOf(name);
            if (declaration != null) result.add(declaration);
        }
        for (var access : expression.findAll(FieldAccessExpr.class)) {
            if (!(access.getScope() instanceof NameExpr qualifier)) continue;
            var type = SwitchPatternChecker.typeInScope(qualifier, qualifier.getNameAsString());
            var field = type.flatMap(t -> fieldOf(t, access.getNameAsString()));
            if (field.isPresent() && field.get().getParentNode().orElse(null) instanceof FieldDeclaration declaration
                    && declaration.isFinal() && declaration.isStatic()) {
                result.add(field.get());
            }
        }
        return result;
    }

    /** The final local or static final field a name denotes, or null. */
    private static VariableDeclarator declarationOf(NameExpr name) {
        var local = SwitchPatternChecker.resolveLocal(name, name.getNameAsString());
        if (local.isPresent()) {
            return local.get() instanceof VariableDeclarator variable
                    && variable.getParentNode().orElse(null) instanceof VariableDeclarationExpr declaration
                    && declaration.isFinal() ? variable : null;
        }
        for (var type = name.findAncestor(TypeDeclaration.class); type.isPresent();
             type = type.get().findAncestor(TypeDeclaration.class)) {
            var field = fieldOf(type.get(), name.getNameAsString());
            if (field.isPresent()) {
                return field.get().getParentNode().orElse(null) instanceof FieldDeclaration declaration
                        && declaration.isFinal() && declaration.isStatic() ? field.get() : null;
            }
        }
        return null;
    }

    static Optional<VariableDeclarator> fieldOf(TypeDeclaration<?> type, String name) {
        for (var member : type.getMembers()) {
            if (!(member instanceof FieldDeclaration field)) continue;
            for (var variable : field.getVariables()) {
                if (variable.getNameAsString().equals(name)) return Optional.of(variable);
            }
        }
        return Optional.empty();
    }

    private static Result constantField(VariableDeclarator variable, Set<Node> visiting) {
        var field = variable.getParentNode().orElse(null);
        // Only a static final field: an instance field cannot be read from the static method (F66).
        if (!(field instanceof FieldDeclaration declaration) || !declaration.isFinal() || !declaration.isStatic()) {
            return Result.NOT_CONSTANT;
        }
        // F41/F80: a @Param field is read as the applied parameter on-chain, never as its initializer, so it is not a
        // constant JuLC folds (whole guards, R3a subexpressions, other constants' initializers). javac does inline it
        // (JLS 13.1), which is #293.
        if (isParam(declaration)) return Result.NOT_CONSTANT;
        return initializer(variable, visiting);
    }

    /** Whether the field is annotated {@code @Param} (simple or qualified name). */
    static boolean isParam(FieldDeclaration field) {
        return field.getAnnotations().stream().anyMatch(a -> a.getName().getIdentifier().equals("Param"));
    }

    private static Result initializer(VariableDeclarator variable, Set<Node> visiting) {
        if (!isConstantType(variable.getType()) || variable.getInitializer().isEmpty() || !visiting.add(variable)) {
            return Result.NOT_CONSTANT;
        }
        try {
            var value = evaluate(variable.getInitializer().get(), visiting);
            if (!value.constant() || value.unclassifiable() || variable.getType().isVarType()) return value;
            // The initializer must be assignable without narrowing (JLS 5.2); a declaration javac rejects
            // (int N = 4294967296L) is not a constant JuLC classifies (F66). char, byte and short are not modelled.
            if (!assignable(variable.getType(), value.value())) return Result.UNCLASSIFIABLE;
            // Then the assignment conversion to the declared type, widening included: a long N = 1 is a long (F70).
            return variable.getType() instanceof PrimitiveType ? cast(variable.getType(), value) : value;
        } finally {
            visiting.remove(variable);
        }
    }

    private static boolean assignable(Type type, Value value) {
        if (type instanceof PrimitiveType primitive) {
            return switch (primitive.getType()) {
                case BOOLEAN -> value.kind() == Kind.BOOLEAN;
                case INT -> value.kind() == Kind.INT;
                case LONG -> value.kind() == Kind.INT || value.kind() == Kind.LONG;
                default -> false;
            };
        }
        return value.kind() == Kind.STRING;
    }

    private static boolean isSubsetType(Type type) {
        if (type instanceof PrimitiveType primitive) {
            return switch (primitive.getType()) {
                case BOOLEAN, INT, LONG -> true;
                default -> false;
            };
        }
        return type.isClassOrInterfaceType() && type.asClassOrInterfaceType().getNameAsString().equals("String");
    }

    private static boolean isConstantType(Type type) {
        return type instanceof PrimitiveType || type.isVarType()
                || (type.isClassOrInterfaceType() && type.asClassOrInterfaceType().getNameAsString().equals("String"));
    }
}
