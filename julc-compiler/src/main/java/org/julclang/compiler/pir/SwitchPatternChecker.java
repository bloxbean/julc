package org.julclang.compiler.pir;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.CharLiteralExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.InstanceOfExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.RecordPatternExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import com.github.javaparser.ast.expr.TypePatternExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.YieldStmt;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.type.Type;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.SwitchDiagnostics;
import org.julclang.compiler.error.DiagnosticCodes;
import org.julclang.compiler.error.DiagnosticInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The compile-time checks JuLC owns for a {@code switch} expression over a sealed interface (ADR-067 D4, I9, I16),
 * run on the source before the switch is lowered, and the label model the lowering uses. JuLC compiles source
 * without javac, so it implements the JLS 21 rules itself: colon-form groups that would fall through, several
 * patterns on one label, duplicate and misplaced {@code default}, applicability, pattern-variable conflicts, guard
 * validity (type, constant {@code false}, effectively final variables, guard-introduced pattern variables),
 * dominance and exhaustiveness. Guarded labels never count towards exhaustiveness, and a constant {@code true}
 * guard makes its label unguarded (JLS 14.11.1).
 * <p>
 * Record patterns are not lowered yet (ADR-067 M2a): their labels take part in the conflict, guard and dominance
 * checks here, but they cover nothing and produce no arm, as at the base.
 */
final class SwitchPatternChecker {

    /**
     * A type-pattern arm. {@code guard} is null when the label is unguarded, including a guard that is the constant
     * {@code true}.
     */
    record Arm(SwitchEntry entry, TypePatternExpr pattern, String variant, String patternVar, Expression guard) {
    }

    /**
     * The checked switch: its type-pattern arms in source order, its {@code default} entry (or null), and whether any
     * label has a guard ({@code when}), which selects the guard-aware lowering (ADR-067 D2 step 6).
     */
    record Model(List<Arm> arms, SwitchEntry defaultEntry, boolean guarded) {
    }

    /** A pattern label of the switch, with its constant-folded guard. */
    private record Label(SwitchEntry entry, Expression pattern, String variant, boolean isRecord,
                         Expression guard, boolean unguarded) {
    }

    private SwitchPatternChecker() {
    }

    static Model check(SwitchExpr se, PirType.SumType sumType) {
        var labels = new ArrayList<Label>();
        SwitchEntry defaultEntry = null;
        boolean guarded = false;
        for (var entry : se.getEntries()) {
            checkGroup(se, entry);
            boolean hasPattern = entry.getLabels().stream().anyMatch(l -> l.isPatternExpr());
            if (entry.isDefault()) {
                if (defaultEntry != null) {
                    throw error(DiagnosticCodes.SWITCH_DUPLICATE_DEFAULT, entry, location(entry));
                }
                defaultEntry = entry;
            }
            if (!hasPattern) continue;
            if (defaultEntry != null) {
                throw error(DiagnosticCodes.SWITCH_LABEL_DOMINATED, entry, labelText(entry), "the default label at " + location(defaultEntry));
            }
            var label = entry.getLabels().get(0);
            var guard = entry.getGuard().orElse(null);
            if (guard != null) guarded = true;
            String variant = patternTypeName(label);
            checkApplicable(entry, label, variant, sumType);
            checkPatternVariables(se, entry, label);
            boolean unguarded = guard == null || checkGuard(entry, guard);
            labels.add(new Label(entry, label, variant, label instanceof RecordPatternExpr, guard, unguarded));
        }
        checkDominance(labels);
        if (defaultEntry == null) checkExhaustive(se, sumType, labels);
        var arms = new ArrayList<Arm>();
        for (var label : labels) {
            if (label.pattern() instanceof TypePatternExpr type) {
                arms.add(new Arm(label.entry(), type, label.variant(), type.getNameAsString(),
                        label.unguarded() ? null : label.guard()));
            }
        }
        return new Model(arms, defaultEntry, guarded);
    }

    // ---- statement groups (D4 colon form, I16)

    private static void checkGroup(SwitchExpr se, SwitchEntry entry) {
        long patterns = entry.getLabels().stream().filter(l -> l.isPatternExpr()).count();
        if (patterns > 0 && entry.getLabels().size() > 1) {
            throw error(DiagnosticCodes.SWITCH_GROUP_UNSUPPORTED, entry, labelText(entry),
                    "a case label with more than one pattern (javac: illegal fall-through from a pattern)");
        }
        if (entry.getType() != SwitchEntry.Type.STATEMENT_GROUP) return;
        if (entry.getStatements().isEmpty()) {
            throw error(DiagnosticCodes.SWITCH_GROUP_UNSUPPORTED, entry, labelText(entry),
                    "a statement group with several labels; give each label its own group that ends in yield");
        }
        if (!cannotCompleteNormally(entry.getStatements())) {
            throw error(DiagnosticCodes.SWITCH_GROUP_UNSUPPORTED, entry, labelText(entry),
                    "the group can complete normally, so Java would fall through into the next group; "
                            + "JuLC does not implement fall-through (#283). End the group with yield");
        }
    }

    /** JLS 14.22 for the statements JuLC compiles in a switch group: does the list always yield or throw? */
    static boolean cannotCompleteNormally(List<Statement> statements) {
        for (var statement : statements) {
            if (cannotCompleteNormally(statement)) return true;
        }
        return false;
    }

    private static boolean cannotCompleteNormally(Statement statement) {
        return switch (statement) {
            case YieldStmt ignored -> true;
            case ThrowStmt ignored -> true;
            case BlockStmt block -> cannotCompleteNormally(block.getStatements());
            case IfStmt branch -> branch.getElseStmt().isPresent()
                    && cannotCompleteNormally(branch.getThenStmt())
                    && cannotCompleteNormally(branch.getElseStmt().get());
            default -> false;
        };
    }

    // ---- applicability (D3, D4)

    private static String patternTypeName(Expression label) {
        if (label instanceof TypePatternExpr type) return type.getType().asClassOrInterfaceType().getNameAsString();
        if (label instanceof RecordPatternExpr record) return record.getType().asClassOrInterfaceType().getNameAsString();
        throw new CompilerException("Unsupported case pattern: " + label);
    }

    private static void checkApplicable(SwitchEntry entry, Expression label, String variant, PirType.SumType sumType) {
        if (sumType.constructors().stream().anyMatch(c -> c.name().equals(variant))) return;
        if (variant.equals(sumType.name()) && label instanceof TypePatternExpr) {
            throw error(DiagnosticCodes.CASE_PATTERN_UNSUPPORTED, entry, label.toString(),
                    "unsupported: total type pattern (valid Java, not supported by JuLC; use default)");
        }
        String available = String.join(", ", sumType.constructors().stream().map(PirType.Constructor::name).toList());
        throw error(DiagnosticCodes.CASE_PATTERN_UNSUPPORTED, entry, label.toString(),
                "not applicable: " + variant + " is not a permitted variant of " + sumType.name()
                        + " (variants: " + available + ")");
    }

    // ---- pattern-variable conflicts (D4, JLS 6.4, 14.30.1)

    private static void checkPatternVariables(SwitchExpr se, SwitchEntry entry, Expression label) {
        var seen = new HashSet<String>();
        for (var variable : label.findAll(TypePatternExpr.class)) {
            String name = variable.getNameAsString();
            if (!seen.add(name)) {
                throw error(DiagnosticCodes.PATTERN_VARIABLE_CONFLICT, variable, name, "in this pattern");
            }
            var declaration = resolveLocal(se, name);
            if (declaration.isPresent()) {
                throw error(DiagnosticCodes.PATTERN_VARIABLE_CONFLICT, variable, name,
                        "as " + describe(declaration.get()) + " in scope");
            }
        }
    }

    private static String describe(Node declaration) {
        if (declaration instanceof Parameter) return "a parameter";
        if (declaration instanceof TypePatternExpr) return "a pattern variable";
        return "a local variable";
    }

    // ---- guards (D1, D4)

    /** Checks a guard; true when it is the constant {@code true}, which makes the label unguarded. */
    private static boolean checkGuard(SwitchEntry entry, Expression guard) {
        String label = labelText(entry);
        for (var instanceOf : guard.findAll(InstanceOfExpr.class)) {
            var pattern = instanceOf.getPattern();
            if (pattern.isEmpty()) continue;
            var variables = pattern.get().findAll(TypePatternExpr.class);
            if (!variables.isEmpty()) {
                throw error(DiagnosticCodes.SWITCH_GUARD_PATTERN_VARIABLE_UNSUPPORTED, instanceOf, label,
                        variables.getFirst().getNameAsString());
            }
        }
        var constant = constantValue(guard, new HashSet<>());
        if (constant.isPresent()) {
            if (!(constant.get() instanceof Boolean value)) {
                throw error(DiagnosticCodes.SWITCH_GUARD_INVALID, guard, label,
                        "a guard must have type boolean or Boolean, but '" + guard + "' is a constant of type "
                                + typeName(constant.get()));
            }
            if (!value) {
                throw error(DiagnosticCodes.SWITCH_GUARD_INVALID, guard, label,
                        "'" + guard + "' is a constant expression with value false, so the case can never apply");
            }
            return true;
        }
        for (var name : guard.findAll(NameExpr.class)) {
            var declaration = resolveLocal(name, name.getNameAsString());
            if (declaration.isEmpty() || isDescendant(declaration.get(), guard)) continue;
            if (!(declaration.get() instanceof Parameter) && !(declaration.get() instanceof VariableDeclarator)) continue;
            if (isReassigned(declaration.get(), name.getNameAsString())) {
                throw error(DiagnosticCodes.SWITCH_GUARD_VARIABLE_NOT_EFFECTIVELY_FINAL, name, label, name.getNameAsString());
            }
        }
        return false;
    }

    private static String typeName(Object constant) {
        return switch (constant) {
            case Integer ignored -> "int";
            case Long ignored -> "long";
            case String ignored -> "String";
            default -> constant.getClass().getSimpleName();
        };
    }

    // ---- dominance (JLS 14.11.1, 14.30.3) and exhaustiveness (14.11.1.1)

    private static void checkDominance(List<Label> labels) {
        for (int j = 0; j < labels.size(); j++) {
            var later = labels.get(j);
            for (int i = 0; i < j; i++) {
                var earlier = labels.get(i);
                // An unguarded type pattern dominates every later pattern of its type, record patterns included.
                // Whether a record pattern dominates another is decided with record patterns (ADR-067 M2a).
                if (!earlier.isRecord() && earlier.unguarded() && earlier.variant().equals(later.variant())) {
                    throw error(DiagnosticCodes.SWITCH_LABEL_DOMINATED, later.entry(), labelText(later.entry()),
                            "'" + labelText(earlier.entry()) + "' at " + location(earlier.entry()));
                }
            }
        }
    }

    private static void checkExhaustive(SwitchExpr se, PirType.SumType sumType, List<Label> labels) {
        var missing = new LinkedHashSet<String>();
        for (var constructor : sumType.constructors()) {
            boolean covered = labels.stream().anyMatch(l -> !l.isRecord() && l.unguarded() && l.variant().equals(constructor.name()));
            if (!covered) missing.add(constructor.name());
        }
        if (!missing.isEmpty()) {
            throw error(DiagnosticCodes.SWITCH_NOT_EXHAUSTIVE, se, "'" + sumType.name() + "'", String.join(", ", missing));
        }
    }

    // ---- name resolution by declaration (D4: identity and scope, not names)

    /**
     * The declaration of a local variable, parameter or pattern variable that {@code name} resolves to at
     * {@code at}, or empty when it resolves to a field (or nothing). Walks the enclosing scopes outwards to the
     * enclosing method or lambda.
     */
    static Optional<Node> resolveLocal(Node at, String name) {
        Node previous = at;
        for (var parent = at.getParentNode(); parent.isPresent(); previous = parent.get(), parent = parent.get().getParentNode()) {
            Node scope = parent.get();
            Node child = previous;
            switch (scope) {
                case BlockStmt block -> {
                    var statements = block.getStatements();
                    int index = indexOf(statements, child);
                    for (int k = index; k >= 0; k--) {
                        var declared = declaredIn(statements.get(k), name);
                        if (declared.isPresent()) return declared;
                    }
                }
                case SwitchEntry entry when indexOf(entry.getLabels(), child) < 0 -> {
                    for (var label : entry.getLabels()) {
                        for (var variable : label.findAll(TypePatternExpr.class)) {
                            if (variable.getNameAsString().equals(name)) return Optional.of(variable);
                        }
                    }
                }
                case ForEachStmt loop when loop.getBody() == child -> {
                    for (var variable : loop.getVariable().getVariables()) {
                        if (variable.getNameAsString().equals(name)) return Optional.of(variable);
                    }
                }
                case ForStmt loop when indexOf(loop.getInitialization(), child) < 0 -> {
                    for (var init : loop.getInitialization()) {
                        if (init instanceof VariableDeclarationExpr declaration) {
                            for (var variable : declaration.getVariables()) {
                                if (variable.getNameAsString().equals(name)) return Optional.of(variable);
                            }
                        }
                    }
                }
                case IfStmt branch when branch.getThenStmt() == child
                        && branch.getCondition() instanceof InstanceOfExpr condition -> {
                    var pattern = condition.getPattern();
                    if (pattern.isPresent() && pattern.get() instanceof TypePatternExpr type
                            && type.getNameAsString().equals(name)) {
                        return Optional.of(type);
                    }
                }
                case LambdaExpr lambda -> {
                    for (var parameter : lambda.getParameters()) {
                        if (parameter.getNameAsString().equals(name)) return Optional.of(parameter);
                    }
                }
                case MethodDeclaration method -> {
                    for (var parameter : method.getParameters()) {
                        if (parameter.getNameAsString().equals(name)) return Optional.of(parameter);
                    }
                    return Optional.empty();
                }
                case ConstructorDeclaration constructor -> {
                    for (var parameter : constructor.getParameters()) {
                        if (parameter.getNameAsString().equals(name)) return Optional.of(parameter);
                    }
                    return Optional.empty();
                }
                case TypeDeclaration<?> ignored -> {
                    return Optional.empty();
                }
                case ObjectCreationExpr ignored -> {
                    return Optional.empty();
                }
                default -> {
                }
            }
        }
        return Optional.empty();
    }

    /** The index of {@code node} in {@code nodes} by identity (JavaParser's {@code equals} is structural). */
    private static int indexOf(List<? extends Node> nodes, Node node) {
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i) == node) return i;
        }
        return -1;
    }

    private static Optional<Node> declaredIn(Statement statement, String name) {
        if (statement instanceof ExpressionStmt expression && expression.getExpression() instanceof VariableDeclarationExpr declaration) {
            for (var variable : declaration.getVariables()) {
                if (variable.getNameAsString().equals(name)) return Optional.of(variable);
            }
        }
        return Optional.empty();
    }

    /** True when the declaration is the target of an assignment or increment anywhere in its scope. */
    private static boolean isReassigned(Node declaration, String name) {
        // Every candidate is matched by the declaration it resolves to, so searching the whole compilation unit
        // cannot confuse sibling blocks that declare the same name.
        Node scope = declaration.findRootNode();
        for (var assignment : scope.findAll(AssignExpr.class)) {
            if (assignment.getTarget() instanceof NameExpr target && target.getNameAsString().equals(name)
                    && resolveLocal(target, name).orElse(null) == declaration) {
                return true;
            }
        }
        for (var unary : scope.findAll(UnaryExpr.class)) {
            if (isIncrement(unary.getOperator()) && unary.getExpression() instanceof NameExpr target
                    && target.getNameAsString().equals(name) && resolveLocal(target, name).orElse(null) == declaration) {
                return true;
            }
        }
        return false;
    }

    private static boolean isIncrement(UnaryExpr.Operator operator) {
        return operator == UnaryExpr.Operator.PREFIX_INCREMENT || operator == UnaryExpr.Operator.PREFIX_DECREMENT
                || operator == UnaryExpr.Operator.POSTFIX_INCREMENT || operator == UnaryExpr.Operator.POSTFIX_DECREMENT;
    }

    private static boolean isDescendant(Node node, Node ancestor) {
        for (Node current = node; current != null; current = current.getParentNode().orElse(null)) {
            if (current == ancestor) return true;
        }
        return false;
    }

    // ---- constant expressions (JLS 15.29, 4.12.4)

    /**
     * The value of a constant expression: a {@code Boolean}, {@code Integer}, {@code Long} or {@code String}; empty
     * when the expression is not constant. Names count when they refer to constant variables: a {@code final} local
     * or field of primitive or {@code String} type initialized with a constant expression.
     */
    static Optional<Object> constantValue(Expression expression, Set<Node> visiting) {
        return Optional.ofNullable(constant(expression, visiting));
    }

    private static Object constant(Expression expression, Set<Node> visiting) {
        return switch (expression) {
            case BooleanLiteralExpr literal -> literal.getValue();
            case IntegerLiteralExpr literal -> {
                try {
                    yield literal.asNumber();
                } catch (RuntimeException e) {
                    yield null;
                }
            }
            case LongLiteralExpr literal -> {
                try {
                    yield literal.asNumber().longValue();
                } catch (RuntimeException e) {
                    yield null;
                }
            }
            case CharLiteralExpr literal -> {
                try {
                    yield (int) literal.asChar();
                } catch (RuntimeException e) {
                    yield null;
                }
            }
            case StringLiteralExpr literal -> {
                try {
                    yield literal.asString();
                } catch (RuntimeException e) {
                    yield null;
                }
            }
            case TextBlockLiteralExpr literal -> literal.asString();
            case EnclosedExpr enclosed -> constant(enclosed.getInner(), visiting);
            case UnaryExpr unary -> unary(unary.getOperator(), constant(unary.getExpression(), visiting));
            case BinaryExpr binary -> {
                Object left = constant(binary.getLeft(), visiting);
                Object right = left == null ? null : constant(binary.getRight(), visiting);
                yield right == null ? null : binary(binary.getOperator(), left, right);
            }
            case ConditionalExpr conditional -> {
                Object condition = constant(conditional.getCondition(), visiting);
                Object then = constant(conditional.getThenExpr(), visiting);
                Object otherwise = constant(conditional.getElseExpr(), visiting);
                yield condition instanceof Boolean b && then != null && otherwise != null ? (b ? then : otherwise) : null;
            }
            case NameExpr name -> constantVariable(name, name.getNameAsString(), visiting);
            case FieldAccessExpr access when access.getScope() instanceof NameExpr type -> constantField(access, type.getNameAsString(),
                    access.getNameAsString(), visiting);
            default -> null;
        };
    }

    private static Object constantVariable(NameExpr at, String name, Set<Node> visiting) {
        var local = resolveLocal(at, name);
        if (local.isPresent()) {
            if (local.get() instanceof VariableDeclarator variable
                    && variable.getParentNode().orElse(null) instanceof VariableDeclarationExpr declaration
                    && declaration.isFinal()) {
                return constantInitializer(variable, visiting);
            }
            return null;
        }
        // A field of an enclosing type, innermost first.
        for (var type = at.findAncestor(TypeDeclaration.class); type.isPresent();
             type = type.get().findAncestor(TypeDeclaration.class)) {
            var field = constantFieldOf(type.get(), name, visiting);
            if (field.found()) return field.value();
        }
        return null;
    }

    private static Object constantField(Node at, String typeName, String fieldName, Set<Node> visiting) {
        var unit = at.findCompilationUnit();
        if (unit.isEmpty()) return null;
        for (var type : unit.get().findAll(TypeDeclaration.class)) {
            if (type.getNameAsString().equals(typeName)) {
                var field = constantFieldOf(type, fieldName, visiting);
                if (field.found()) return field.value();
            }
        }
        return null;
    }

    private record FieldLookup(boolean found, Object value) {
    }

    private static FieldLookup constantFieldOf(TypeDeclaration<?> type, String name, Set<Node> visiting) {
        for (var member : type.getMembers()) {
            if (!(member instanceof FieldDeclaration field)) continue;
            for (var variable : field.getVariables()) {
                if (variable.getNameAsString().equals(name)) {
                    return new FieldLookup(true, field.isFinal() ? constantInitializer(variable, visiting) : null);
                }
            }
        }
        return new FieldLookup(false, null);
    }

    private static Object constantInitializer(VariableDeclarator variable, Set<Node> visiting) {
        if (!isConstantType(variable.getType()) || variable.getInitializer().isEmpty() || !visiting.add(variable)) return null;
        try {
            return constant(variable.getInitializer().get(), visiting);
        } finally {
            visiting.remove(variable);
        }
    }

    private static boolean isConstantType(Type type) {
        return type instanceof PrimitiveType || type.isVarType()
                || (type.isClassOrInterfaceType() && type.asClassOrInterfaceType().getNameAsString().equals("String"));
    }

    private static Object unary(UnaryExpr.Operator operator, Object value) {
        if (value == null) return null;
        return switch (operator) {
            case LOGICAL_COMPLEMENT -> value instanceof Boolean b ? !b : null;
            case MINUS -> value instanceof Integer i ? (Object) (-i) : value instanceof Long l ? (Object) (-l) : null;
            case PLUS -> value instanceof Integer || value instanceof Long ? value : null;
            case BITWISE_COMPLEMENT -> value instanceof Integer i ? (Object) (~i) : value instanceof Long l ? (Object) (~l) : null;
            default -> null;
        };
    }

    private static Object binary(BinaryExpr.Operator operator, Object left, Object right) {
        if (left instanceof Boolean a && right instanceof Boolean b) {
            return switch (operator) {
                case AND, BINARY_AND -> a && b;
                case OR, BINARY_OR -> a || b;
                case XOR -> a ^ b;
                case EQUALS -> a.equals(b);
                case NOT_EQUALS -> !a.equals(b);
                default -> null;
            };
        }
        if (operator == BinaryExpr.Operator.PLUS && (left instanceof String || right instanceof String)) {
            return String.valueOf(left) + right;
        }
        if (left instanceof String a && right instanceof String b) {
            return null; // == on strings is not a constant comparison of values JuLC models
        }
        if (!(left instanceof Integer || left instanceof Long) || !(right instanceof Integer || right instanceof Long)) return null;
        boolean wide = left instanceof Long || right instanceof Long;
        long a = ((Number) left).longValue();
        long b = ((Number) right).longValue();
        return switch (operator) {
            case EQUALS -> a == b;
            case NOT_EQUALS -> a != b;
            case LESS -> a < b;
            case LESS_EQUALS -> a <= b;
            case GREATER -> a > b;
            case GREATER_EQUALS -> a >= b;
            case PLUS -> narrow(a + b, wide);
            case MINUS -> narrow(a - b, wide);
            case MULTIPLY -> narrow(a * b, wide);
            case DIVIDE -> b == 0 ? null : narrow(a / b, wide);
            case REMAINDER -> b == 0 ? null : narrow(a % b, wide);
            case BINARY_AND -> narrow(a & b, wide);
            case BINARY_OR -> narrow(a | b, wide);
            case XOR -> narrow(a ^ b, wide);
            default -> null;
        };
    }

    private static Object narrow(long value, boolean wide) {
        return wide ? (Object) value : (Object) (int) value;
    }

    // ---- diagnostics

    private static String labelText(SwitchEntry entry) {
        if (entry.isDefault() && entry.getLabels().isEmpty()) return "default";
        var text = new StringBuilder("case ");
        for (int i = 0; i < entry.getLabels().size(); i++) {
            if (i > 0) text.append(", ");
            text.append(entry.getLabels().get(i));
        }
        entry.getGuard().ifPresent(guard -> text.append(" when ").append(guard));
        return text.toString();
    }

    private static String location(Node node) {
        return node.getRange().map(r -> "line " + r.begin.line).orElse("an earlier line");
    }

    private static CompilerException error(DiagnosticInfo info, Node node, Object... args) {
        return SwitchDiagnostics.error(info, PirGenerator.sourceLocation(node), args);
    }

}
