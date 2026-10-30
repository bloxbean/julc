package org.julclang.compiler.pir;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.CharLiteralExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.InstanceOfExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
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
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.type.Type;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.SwitchDiagnostics;
import org.julclang.compiler.error.DiagnosticCodes;
import org.julclang.compiler.error.DiagnosticInfo;
import org.julclang.compiler.resolve.TypeResolver;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The compile-time checks JuLC owns for a {@code switch} expression over a sealed interface (ADR-067 D4, I9, I16),
 * run on the source before the switch is lowered, and the label model the lowering uses. JuLC compiles source
 * without javac, so it implements the JLS 21 rules itself: case kinds and colon-form groups that would fall through,
 * several patterns on one label, duplicate and misplaced {@code default}, applicability by nominal type,
 * pattern-variable conflicts, guard validity (Java type, constant {@code false}, effectively final variables,
 * guard-introduced pattern variables), dominance and exhaustiveness. Guarded labels never count towards
 * exhaustiveness, and a constant {@code true} guard makes its label unguarded (JLS 14.11.1).
 * <p>
 * Record patterns are rejected with {@code JULC0076} until ADR-067 M2a implements them, so no label is dropped (I7).
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
    private record Label(SwitchEntry entry, Expression pattern, String variant, Expression guard, boolean unguarded) {
    }

    /**
     * The Java type of a guard expression, as far as JuLC can establish it from the source (ADR-067 D4, M1 decision):
     * {@code BOOLEAN} for {@code boolean} and {@code Boolean}, {@code UNKNOWN} when the source alone does not say.
     */
    enum JType { BOOLEAN, NUMERIC, STRING, OTHER, UNKNOWN }

    private SwitchPatternChecker() {
    }

    static Model check(SwitchExpr se, PirType.SumType sumType, TypeResolver types) {
        checkCaseKinds(se);
        var labels = new ArrayList<Label>();
        SwitchEntry defaultEntry = null;
        boolean guarded = false;
        for (var entry : se.getEntries()) {
            checkGroup(entry);
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
            if (label instanceof RecordPatternExpr) {
                // Interim (ADR-067 F35): until M2a lowers record patterns, reject them rather than drop the label.
                throw error(DiagnosticCodes.CASE_PATTERN_UNSUPPORTED, entry, label.toString(),
                        "not yet supported: record patterns are implemented by ADR-067 M2a; match the type pattern "
                                + "and read the components with their accessors");
            }
            var type = (TypePatternExpr) label;
            var guard = entry.getGuard().orElse(null);
            if (guard != null) guarded = true;
            String variant = checkApplicable(entry, type, sumType, types);
            checkPatternVariables(se, entry, label);
            boolean unguarded = guard == null || checkGuard(entry, guard);
            labels.add(new Label(entry, label, variant, guard, unguarded));
        }
        checkDominance(labels);
        if (defaultEntry == null) checkExhaustive(se, sumType, labels);
        var arms = new ArrayList<Arm>();
        for (var label : labels) {
            var type = (TypePatternExpr) label.pattern();
            arms.add(new Arm(label.entry(), type, label.variant(), type.getNameAsString(), label.unguarded() ? null : label.guard()));
        }
        return new Model(arms, defaultEntry, guarded);
    }

    // ---- case kinds and statement groups (D4 colon form, I16)

    private static void checkCaseKinds(SwitchExpr se) {
        SwitchEntry group = null;
        SwitchEntry rule = null;
        for (var entry : se.getEntries()) {
            if (entry.getType() == SwitchEntry.Type.STATEMENT_GROUP) {
                if (group == null) group = entry;
            } else if (rule == null) {
                rule = entry;
            }
            if (group != null && rule != null) {
                throw error(DiagnosticCodes.SWITCH_GROUP_UNSUPPORTED, entry, labelText(entry),
                        "a switch mixes colon groups and arrow rules (javac: different case kinds used in the switch)");
            }
        }
    }

    private static void checkGroup(SwitchEntry entry) {
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

    // ---- applicability by nominal type (D3, D4, F56)

    /**
     * The constructor name a type pattern names. A qualified pattern type ({@code Other.A}) is resolved to its
     * declaration and must be a permitted variant of exactly the selector's sealed interface; an unqualified one
     * names the variant of that simple name, as in Java's scope (and as at the base, I8).
     */
    private static String checkApplicable(SwitchEntry entry, TypePatternExpr label, PirType.SumType sumType, TypeResolver types) {
        var type = label.getType().asClassOrInterfaceType();
        String variant = type.getNameAsString();
        boolean named = sumType.constructors().stream().anyMatch(c -> c.name().equals(variant));
        if (type.getScope().isPresent()) {
            Optional<PirType.SumType> family;
            try {
                family = types.lookupSumTypeForVariant(types.resolveTypeWithScope(type));
            } catch (CompilerException | IllegalArgumentException e) {
                family = Optional.empty();
            }
            if (named && family.isPresent() && family.get().equals(sumType)) return variant;
            throw error(DiagnosticCodes.CASE_PATTERN_UNSUPPORTED, entry, label.toString(),
                    "not applicable: " + type + " is not a permitted variant of " + sumType.name());
        }
        if (named) return variant;
        if (variant.equals(sumType.name())) {
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
            var declaration = resolveLocal(se, name).or(() -> earlierGroupLocal(se, entry, name));
            if (declaration.isPresent()) {
                throw error(DiagnosticCodes.PATTERN_VARIABLE_CONFLICT, variable, name,
                        "as " + describe(declaration.get()) + " in scope");
            }
        }
    }

    /** A local declared at the top level of an earlier statement group of the same switch, still in scope (JLS 6.3). */
    private static Optional<Node> earlierGroupLocal(SwitchExpr se, SwitchEntry entry, String name) {
        for (var earlier : se.getEntries()) {
            if (earlier == entry) break;
            for (var statement : earlier.getStatements()) {
                var declared = declaredIn(statement, name);
                if (declared.isPresent()) return declared;
            }
        }
        return Optional.empty();
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
        checkGuardPatternVariables(label, guard);
        var type = sourceType(guard, e -> JType.UNKNOWN);
        if (type != JType.BOOLEAN && type != JType.UNKNOWN) {
            throw error(DiagnosticCodes.SWITCH_GUARD_INVALID, guard, label, notBoolean(guard, type));
        }
        var constant = JavaConstants.evaluate(guard);
        if (constant.unclassifiable()) {
            throw error(DiagnosticCodes.SWITCH_GUARD_INVALID, guard, label, "'" + guard
                    + "' is a constant expression JuLC cannot evaluate exactly (floating point or a division by zero); "
                    + "write the condition without it");
        }
        if (constant.constant()) {
            if (constant.value().kind() != JavaConstants.Kind.BOOLEAN) {
                throw error(DiagnosticCodes.SWITCH_GUARD_INVALID, guard, label,
                        "a guard must have type boolean or Boolean, but '" + guard + "' is a constant of type "
                                + constant.value().kind().name().toLowerCase(java.util.Locale.ROOT));
            }
            if (!(Boolean) constant.value().value()) {
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

    static String notBoolean(Expression guard, JType type) {
        return "a guard must have type boolean or Boolean, but the type of '" + guard + "' is "
                + switch (type) {
                    case NUMERIC -> "numeric";
                    case STRING -> "String";
                    case OTHER -> "not boolean";
                    default -> "not established as boolean";
                }
                + ". Assign the condition to a boolean local first, or call a helper declared to return boolean";
    }

    /**
     * JLS 6.3.1: a guard may use instanceof with a pattern, but a pattern variable it introduces when true would be
     * in scope in the arm, which JuLC does not bind (#204), so it is {@code JULC0074}. A pattern variable that is not
     * introduced when true ({@code !(o instanceof A a)}) is accepted as long as the guard does not read it.
     */
    private static void checkGuardPatternVariables(String label, Expression guard) {
        var whenTrue = introducedWhenTrue(guard);
        if (!whenTrue.isEmpty()) {
            throw error(DiagnosticCodes.SWITCH_GUARD_PATTERN_VARIABLE_UNSUPPORTED, guard, label, whenTrue.iterator().next());
        }
        var declared = new LinkedHashSet<String>();
        for (var instanceOf : guard.findAll(InstanceOfExpr.class)) {
            instanceOf.getPattern().ifPresent(p -> p.findAll(TypePatternExpr.class).forEach(v -> declared.add(v.getNameAsString())));
        }
        for (var name : guard.findAll(NameExpr.class)) {
            if (declared.contains(name.getNameAsString())) {
                throw error(DiagnosticCodes.SWITCH_GUARD_PATTERN_VARIABLE_UNSUPPORTED, name, label, name.getNameAsString());
            }
        }
    }

    private static Set<String> introducedWhenTrue(Expression e) {
        return switch (e) {
            case EnclosedExpr enclosed -> introducedWhenTrue(enclosed.getInner());
            case InstanceOfExpr instanceOf -> {
                var names = new LinkedHashSet<String>();
                instanceOf.getPattern().ifPresent(p -> p.findAll(TypePatternExpr.class).forEach(v -> names.add(v.getNameAsString())));
                yield names;
            }
            case UnaryExpr unary when unary.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT -> introducedWhenFalse(unary.getExpression());
            case BinaryExpr binary when binary.getOperator() == BinaryExpr.Operator.AND -> {
                var names = new LinkedHashSet<>(introducedWhenTrue(binary.getLeft()));
                names.addAll(introducedWhenTrue(binary.getRight()));
                yield names;
            }
            default -> Set.of();
        };
    }

    private static Set<String> introducedWhenFalse(Expression e) {
        return switch (e) {
            case EnclosedExpr enclosed -> introducedWhenFalse(enclosed.getInner());
            case UnaryExpr unary when unary.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT -> introducedWhenTrue(unary.getExpression());
            case BinaryExpr binary when binary.getOperator() == BinaryExpr.Operator.OR -> {
                var names = new LinkedHashSet<>(introducedWhenFalse(binary.getLeft()));
                names.addAll(introducedWhenFalse(binary.getRight()));
                yield names;
            }
            default -> Set.of();
        };
    }

    // ---- the Java type of a guard (D4; M1 decision: fail closed)

    /**
     * The Java type of {@code e} from the source: literals, operators (a conditional by both branches), casts,
     * declared types of locals, parameters and fields, and declared return types of methods of the compilation
     * unit. Anything else is asked of {@code leaf}, which the lowering answers from its symbol table.
     */
    static JType sourceType(Expression e, Function<Expression, JType> leaf) {
        return switch (e) {
            case BooleanLiteralExpr ignored -> JType.BOOLEAN;
            case IntegerLiteralExpr ignored -> JType.NUMERIC;
            case LongLiteralExpr ignored -> JType.NUMERIC;
            case CharLiteralExpr ignored -> JType.NUMERIC;
            case DoubleLiteralExpr ignored -> JType.NUMERIC;
            case StringLiteralExpr ignored -> JType.STRING;
            case TextBlockLiteralExpr ignored -> JType.STRING;
            case NullLiteralExpr ignored -> JType.OTHER;
            case EnclosedExpr enclosed -> sourceType(enclosed.getInner(), leaf);
            case InstanceOfExpr ignored -> JType.BOOLEAN;
            case UnaryExpr unary -> switch (unary.getOperator()) {
                case LOGICAL_COMPLEMENT -> booleanOperand(sourceType(unary.getExpression(), leaf));
                default -> JType.NUMERIC;
            };
            case BinaryExpr binary -> switch (binary.getOperator()) {
                case EQUALS, NOT_EQUALS, LESS, LESS_EQUALS, GREATER, GREATER_EQUALS -> JType.BOOLEAN;
                case AND, OR -> both(sourceType(binary.getLeft(), leaf), sourceType(binary.getRight(), leaf));
                case BINARY_AND, BINARY_OR, XOR -> {
                    var a = sourceType(binary.getLeft(), leaf);
                    var b = sourceType(binary.getRight(), leaf);
                    yield a == JType.NUMERIC || b == JType.NUMERIC ? JType.NUMERIC : both(a, b);
                }
                case PLUS -> sourceType(binary.getLeft(), leaf) == JType.STRING
                        || sourceType(binary.getRight(), leaf) == JType.STRING ? JType.STRING : JType.NUMERIC;
                default -> JType.NUMERIC;
            };
            case ConditionalExpr conditional -> {
                var a = sourceType(conditional.getThenExpr(), leaf);
                var b = sourceType(conditional.getElseExpr(), leaf);
                if (a == JType.BOOLEAN && b == JType.BOOLEAN) yield JType.BOOLEAN;
                if (a == JType.UNKNOWN || b == JType.UNKNOWN) yield JType.UNKNOWN;
                yield JType.OTHER; // boolean with another type is a reference type (JLS 15.25)
            }
            case CastExpr cast -> {
                var target = declaredType(cast.getType(), cast);
                if (target != JType.BOOLEAN) yield target;
                // (boolean) and (Boolean) give boolean only when the operand is boolean: a downcast from Object would
                // only fail at run time on the JVM, and on-chain it would not be checked.
                yield booleanOperand(sourceType(cast.getExpression(), leaf));
            }
            case NameExpr name -> nameType(name, leaf);
            case FieldAccessExpr access -> {
                if (access.getScope() instanceof NameExpr qualifier) {
                    var type = typeInScope(qualifier, qualifier.getNameAsString());
                    if (type.isPresent()) {
                        var field = JavaConstants.fieldOf(type.get(), access.getNameAsString());
                        if (field.isPresent()) yield declaredType(field.get().getType(), field.get());
                    }
                }
                yield leaf.apply(access);
            }
            case MethodCallExpr call -> {
                var method = unitMethod(call);
                yield method.isPresent() ? declaredType(method.get().getType(), method.get()) : leaf.apply(call);
            }
            case SwitchExpr nested -> switchType(nested, leaf);
            case ObjectCreationExpr creation -> declaredType(creation.getType(), creation);
            case LambdaExpr ignored -> JType.OTHER;
            default -> leaf.apply(e);
        };
    }

    private static JType booleanOperand(JType operand) {
        return operand == JType.BOOLEAN || operand == JType.UNKNOWN ? operand : JType.OTHER;
    }

    private static JType both(JType a, JType b) {
        if (a == JType.BOOLEAN && b == JType.BOOLEAN) return JType.BOOLEAN;
        if (a == JType.UNKNOWN && (b == JType.BOOLEAN || b == JType.UNKNOWN)) return JType.UNKNOWN;
        if (b == JType.UNKNOWN && a == JType.BOOLEAN) return JType.UNKNOWN;
        return JType.OTHER;
    }

    private static JType nameType(NameExpr name, Function<Expression, JType> leaf) {
        var local = resolveLocal(name, name.getNameAsString());
        if (local.isPresent()) {
            return switch (local.get()) {
                case Parameter parameter -> declaredType(parameter.getType(), parameter);
                case VariableDeclarator variable when variable.getType().isVarType() ->
                        variable.getInitializer().map(init -> sourceType(init, leaf)).orElse(JType.UNKNOWN);
                case VariableDeclarator variable -> declaredType(variable.getType(), variable);
                default -> JType.OTHER; // a pattern variable has a record type
            };
        }
        for (var type = name.findAncestor(TypeDeclaration.class); type.isPresent();
             type = type.get().findAncestor(TypeDeclaration.class)) {
            var field = JavaConstants.fieldOf(type.get(), name.getNameAsString());
            if (field.isPresent()) return declaredType(field.get().getType(), field.get());
        }
        return leaf.apply(name);
    }

    /** The return type of an unqualified (or own-class-qualified) call of a method declared once in the unit. */
    private static Optional<MethodDeclaration> unitMethod(MethodCallExpr call) {
        if (call.getScope().isPresent()) {
            if (!(call.getScope().get() instanceof NameExpr qualifier)) return Optional.empty();
            var owner = typeInScope(qualifier, qualifier.getNameAsString());
            if (owner.isEmpty()) return Optional.empty();
            var methods = owner.get().getMethodsByName(call.getNameAsString());
            return methods.size() == 1 ? Optional.of(methods.getFirst()) : Optional.empty();
        }
        var methods = new ArrayList<MethodDeclaration>();
        for (var type = call.findAncestor(TypeDeclaration.class); type.isPresent();
             type = type.get().findAncestor(TypeDeclaration.class)) {
            methods.addAll(type.get().getMethodsByName(call.getNameAsString()));
            if (!methods.isEmpty()) break;
        }
        return methods.size() == 1 ? Optional.of(methods.getFirst()) : Optional.empty();
    }

    private static JType switchType(SwitchExpr nested, Function<Expression, JType> leaf) {
        var results = new ArrayList<JType>();
        for (var entry : nested.getEntries()) {
            if (entry.getType() == SwitchEntry.Type.EXPRESSION && entry.getStatements().size() == 1
                    && entry.getStatements().get(0) instanceof ExpressionStmt statement) {
                results.add(sourceType(statement.getExpression(), leaf));
                continue;
            }
            for (var yield : entry.findAll(YieldStmt.class)) {
                if (yield.findAncestor(SwitchExpr.class).orElse(null) == nested) {
                    results.add(sourceType(yield.getExpression(), leaf));
                }
            }
        }
        if (results.isEmpty()) return JType.UNKNOWN;
        if (results.stream().allMatch(t -> t == JType.BOOLEAN)) return JType.BOOLEAN;
        if (results.stream().anyMatch(t -> t == JType.UNKNOWN)) return JType.UNKNOWN;
        return JType.OTHER;
    }

    /** The guard type of a declared Java type; a type variable is unknown. */
    private static JType declaredType(Type type, Node at) {
        if (type instanceof PrimitiveType primitive) {
            return primitive.getType() == PrimitiveType.Primitive.BOOLEAN ? JType.BOOLEAN : JType.NUMERIC;
        }
        if (!(type instanceof ClassOrInterfaceType named)) return type.isVarType() ? JType.UNKNOWN : JType.OTHER;
        String name = named.getNameAsString();
        if (isTypeVariable(name, at)) return JType.UNKNOWN;
        return switch (name) {
            case "Boolean" -> JType.BOOLEAN;
            case "String" -> JType.STRING;
            case "Integer", "Long", "Short", "Byte", "Character", "BigInteger" -> JType.NUMERIC;
            default -> JType.OTHER;
        };
    }

    private static boolean isTypeVariable(String name, Node at) {
        for (Node current = at; current != null; current = current.getParentNode().orElse(null)) {
            if (current instanceof CallableDeclaration<?> callable
                    && callable.getTypeParameters().stream().anyMatch(p -> p.getNameAsString().equals(name))) return true;
            if (current instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration declaration
                    && declaration.getTypeParameters().stream().anyMatch(p -> p.getNameAsString().equals(name))) return true;
            if (current instanceof com.github.javaparser.ast.body.RecordDeclaration declaration
                    && declaration.getTypeParameters().stream().anyMatch(p -> p.getNameAsString().equals(name))) return true;
        }
        return false;
    }

    // ---- dominance (JLS 14.11.1, 14.30.3) and exhaustiveness (14.11.1.1)

    private static void checkDominance(List<Label> labels) {
        for (int j = 0; j < labels.size(); j++) {
            var later = labels.get(j);
            for (int i = 0; i < j; i++) {
                var earlier = labels.get(i);
                // An unguarded type pattern dominates every later pattern of its type.
                if (earlier.unguarded() && earlier.variant().equals(later.variant())) {
                    throw error(DiagnosticCodes.SWITCH_LABEL_DOMINATED, later.entry(), labelText(later.entry()),
                            "'" + labelText(earlier.entry()) + "' at " + location(earlier.entry()));
                }
            }
        }
    }

    private static void checkExhaustive(SwitchExpr se, PirType.SumType sumType, List<Label> labels) {
        var missing = new LinkedHashSet<String>();
        for (var constructor : sumType.constructors()) {
            boolean covered = labels.stream().anyMatch(l -> l.unguarded() && l.variant().equals(constructor.name()));
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
     * enclosing method or lambda. A local of a colon-form statement group is in scope in the rest of its switch
     * block, the later groups included (JLS 6.3).
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
                    var statements = entry.getStatements();
                    for (int k = indexOf(statements, child); k >= 0; k--) {
                        var declared = declaredIn(statements.get(k), name);
                        if (declared.isPresent()) return declared;
                    }
                    for (var label : entry.getLabels()) {
                        for (var variable : label.findAll(TypePatternExpr.class)) {
                            if (variable.getNameAsString().equals(name)) return Optional.of(variable);
                        }
                    }
                    if (entry.getType() == SwitchEntry.Type.STATEMENT_GROUP) {
                        var switchBlock = entry.getParentNode().orElse(null);
                        if (switchBlock instanceof SwitchExpr se) {
                            var earlier = earlierGroupLocal(se, entry, name);
                            if (earlier.isPresent()) return earlier;
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

    /**
     * The type that a simple name used as a qualifier denotes at {@code at}: empty when a variable (local, parameter or
     * field) of that name is in scope, which obscures the type (JLS 6.4.2), else an enclosing type, a member type of an
     * enclosing type, or a top-level type of the compilation unit.
     */
    static Optional<TypeDeclaration<?>> typeInScope(Node at, String name) {
        if (resolveLocal(at, name).isPresent()) return Optional.empty();
        for (var type = at.findAncestor(TypeDeclaration.class); type.isPresent();
             type = type.get().findAncestor(TypeDeclaration.class)) {
            if (JavaConstants.fieldOf(type.get(), name).isPresent()) return Optional.empty();
        }
        for (var type = at.findAncestor(TypeDeclaration.class); type.isPresent();
             type = type.get().findAncestor(TypeDeclaration.class)) {
            TypeDeclaration<?> enclosing = type.get();
            if (enclosing.getNameAsString().equals(name)) return Optional.of(enclosing);
            for (var member : enclosing.getMembers()) {
                if (member instanceof TypeDeclaration<?> nested && nested.getNameAsString().equals(name)) {
                    return Optional.of(nested);
                }
            }
        }
        var unit = at.findCompilationUnit();
        if (unit.isPresent()) {
            for (var top : unit.get().getTypes()) {
                if (top.getNameAsString().equals(name)) return Optional.of(top);
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

    // ---- diagnostics

    static String labelText(SwitchEntry entry) {
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
