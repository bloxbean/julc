package org.julclang.compiler.frontend.javac.subset;

import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BindingPatternTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.DeconstructionPatternTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.InstanceOfTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.util.TreeScanner;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.error.DiagnosticCodes;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.frontend.javac.subset.GuardTypes.JType;

import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * The rules on a {@code switch} guard that javac does not make (ADR-067 D4, ADR-068 D11), ported from
 * {@code SwitchPatternChecker}. A guard's type, {@code pattern.dominated}, a guard variable that is not effectively final,
 * and a conflict with a variable in scope are javac's, so they are not here. What is left is where JuLC's lowering or its
 * evaluation would differ from Java's, and each fails closed.
 * <p>
 * {@link #check} runs where the reference checks a label, {@link #typeOf} and {@link #requireStringEquals} where it lowers
 * the guard, which is the order of its rejections (I2a). {@link #requireEquality} is D6's, which the reference does not
 * have: a guard's {@code equals} and string {@code +} are held to it there, after a guard's own rules. Reads no term, so it
 * has no evaluation order to state.
 */
final class GuardRules {

    /** Binary operators that, on int or long operands, compute a fixed-width result (JLS 15.17-15.19, 15.22). */
    private static final Set<Tree.Kind> FIXED_WIDTH_OPERATORS = Set.of(Tree.Kind.PLUS, Tree.Kind.MINUS, Tree.Kind.MULTIPLY,
            Tree.Kind.DIVIDE, Tree.Kind.REMAINDER, Tree.Kind.LEFT_SHIFT, Tree.Kind.RIGHT_SHIFT, Tree.Kind.UNSIGNED_RIGHT_SHIFT,
            Tree.Kind.AND, Tree.Kind.OR, Tree.Kind.XOR);

    /** Methods JuLC lowers to the identity where Java narrows the value (#294). By name, on any receiver, as the reference reads them. */
    private static final Set<String> NARROWING_IDENTITY_METHODS = Set.of("intValue", "longValue");

    private final JavacFacts facts;
    private final GuardConstants constants;
    private final GuardTypes types;
    private final EqualityRules equality;

    GuardRules(JavacFacts facts) {
        this.facts = facts;
        this.constants = new GuardConstants(facts);
        this.types = new GuardTypes(facts, constants);
        this.equality = new EqualityRules(facts);
    }

    private static JType unknown(ExpressionTree tree) {
        return JType.UNKNOWN;
    }

    /** {@code JULC0072} at {@code at}, for the case {@code entry}. */
    private CompilerException invalid(Tree at, CaseTree entry, String reason) {
        return facts.rejection(DiagnosticCodes.SWITCH_GUARD_INVALID, at, SwitchRules.labelText(facts, entry), reason);
    }

    /** @return whether {@code guard} is the constant {@code true}, which makes its label unguarded */
    boolean check(CaseTree entry, ExpressionTree guard) {
        requireNoBinding(entry, guard);
        requireSubset(entry, guard);
        var type = types.of(guard, GuardRules::unknown);
        if (!GuardTypes.isBoolean(type) && type != JType.UNKNOWN) throw invalid(guard, entry, notBoolean(guard, type));
        // A constant-looking part outside the verified subset is rejected, never lowered ordinarily: the whole guard, or
        // any subexpression of a guard that is not constant as a whole.
        for (var part : SubsetWalk.all(guard, ExpressionTree.class)) {
            if (constants.evaluate(part).unclassifiable()) {
                throw invalid(part, entry, "'" + facts.text(part) + "' is not a constant expression JuLC can evaluate exactly "
                        + "(floating point, a division by zero, an out-of-range literal, or a declaration javac rejects); "
                        + "write the condition without it");
            }
        }
        var constant = constants.evaluate(guard);
        if (!constant.constant()) return false;
        // javac rejects a constant false first. This stays so that such a guard stops if one ever gets past it.
        if (!(Boolean) constant.value().value()) {
            throw invalid(guard, entry, "'" + facts.text(guard) + "' is a constant expression with value false, so the case can never apply");
        }
        // The reference reads no local through a new, so it keeps such a guard as a read at run time, and reads a field of the
        // local's name where one shadows it (follow-ups): this is Java's constant, in other bytes (ADR-068 M5).
        return true;
    }

    private String notBoolean(ExpressionTree guard, JType type) {
        return "a guard must have type boolean or Boolean, but the type of '" + facts.text(guard) + "' is "
                + switch (type) {
                    case NUMERIC, NUMERIC_BOX, BIGINTEGER -> "numeric";
                    case STRING -> "String";
                    case SMALL -> "char, byte or short, which a guard does not support";
                    case OTHER -> "not boolean, or not established as legal Java (a comparison of references, "
                            + "an operand of the wrong type)";
                    default -> "not established as boolean";
                }
                + ". Assign the condition to a boolean local first, or call a helper declared to return boolean";
    }

    /**
     * JLS 6.3.1: a pattern variable a guard introduces when true would be in scope in the arm, which JuLC does not bind
     * (#204), so it is {@code JULC0074}, and a read of one that is not introduced is too. Both go by name, as the reference
     * reads them, so a field of that name is held to the rule: lifting that would widen the subset.
     */
    private void requireNoBinding(CaseTree entry, ExpressionTree guard) {
        var declared = new LinkedHashSet<String>();
        for (var instanceOf : SubsetWalk.all(guard, InstanceOfTree.class)) {
            if (instanceOf.getPattern() == null) continue;
            for (var binding : SubsetWalk.all(instanceOf.getPattern(), BindingPatternTree.class)) {
                String name = binding.getVariable().getName().toString();
                if (!declared.add(name)) throw facts.rejection(DiagnosticCodes.PATTERN_VARIABLE_CONFLICT, binding, name, "in this guard");
            }
        }
        var whenTrue = introducedWhenTrue(guard);
        if (!whenTrue.isEmpty()) {
            throw facts.rejection(DiagnosticCodes.SWITCH_GUARD_PATTERN_VARIABLE_UNSUPPORTED, guard,
                    SwitchRules.labelText(facts, entry), whenTrue.iterator().next());
        }
        for (var name : SubsetWalk.all(guard, IdentifierTree.class)) {
            if (declared.contains(name.getName().toString()) && facts.elementOrNull(name) instanceof VariableElement) {
                throw facts.rejection(DiagnosticCodes.SWITCH_GUARD_PATTERN_VARIABLE_UNSUPPORTED, name,
                        SwitchRules.labelText(facts, entry), name.getName().toString());
            }
        }
    }

    private Set<String> introducedWhenTrue(ExpressionTree tree) {
        return switch (tree) {
            case ParenthesizedTree parenthesized -> introducedWhenTrue(parenthesized.getExpression());
            case InstanceOfTree instanceOf when instanceOf.getPattern() != null -> names(instanceOf);
            case UnaryTree unary when unary.getKind() == Tree.Kind.LOGICAL_COMPLEMENT -> introducedWhenFalse(unary.getExpression());
            case BinaryTree binary when binary.getKind() == Tree.Kind.CONDITIONAL_AND -> {
                var names = new LinkedHashSet<>(introducedWhenTrue(binary.getLeftOperand()));
                names.addAll(introducedWhenTrue(binary.getRightOperand()));
                yield names;
            }
            default -> Set.of();
        };
    }

    private Set<String> introducedWhenFalse(ExpressionTree tree) {
        return switch (tree) {
            case ParenthesizedTree parenthesized -> introducedWhenFalse(parenthesized.getExpression());
            case UnaryTree unary when unary.getKind() == Tree.Kind.LOGICAL_COMPLEMENT -> introducedWhenTrue(unary.getExpression());
            case BinaryTree binary when binary.getKind() == Tree.Kind.CONDITIONAL_OR -> {
                var names = new LinkedHashSet<>(introducedWhenFalse(binary.getLeftOperand()));
                names.addAll(introducedWhenFalse(binary.getRightOperand()));
                yield names;
            }
            default -> Set.of();
        };
    }

    private static Set<String> names(InstanceOfTree instanceOf) {
        var names = new LinkedHashSet<String>();
        for (var binding : SubsetWalk.all(instanceOf.getPattern(), BindingPatternTree.class)) {
            names.add(binding.getVariable().getName().toString());
        }
        return names;
    }

    /** Restrictions on what a guard may contain, each where JuLC's lowering or its constant evaluation differs from Java's. */
    private void requireSubset(CaseTree entry, ExpressionTree guard) {
        var casts = SubsetWalk.all(guard, TypeCastTree.class);
        for (var cast : casts) {
            var target = facts.type(cast.getType());
            if (!target.getKind().isPrimitive() && types.declared(target) != JType.BOOLEAN_BOX) {
                throw invalid(cast, entry, "'" + facts.text(cast) + "' casts to a reference type, and JuLC erases the cast's "
                        + "run-time check; a guard may cast only to a primitive type or to Boolean");
            }
        }
        for (var cast : casts) {
            // A narrowing cast is erased by the lowering (T6); a constant one is lowered as its Java value.
            if (cast.getType() instanceof PrimitiveTypeTree primitive && primitive.getPrimitiveTypeKind() == TypeKind.INT
                    && !constants.evaluate(cast.getExpression()).constant()) {
                throw invalid(cast, entry, "'" + facts.text(cast) + "' may narrow a long, and JuLC erases the narrowing; "
                        + "compare the long value instead");
            }
        }
        // int and long arithmetic that is not a constant expression is evaluated without Java's fixed width (T6).
        for (var binary : SubsetWalk.all(guard, BinaryTree.class)) {
            if (!FIXED_WIDTH_OPERATORS.contains(binary.getKind()) || constants.evaluate(binary).constant()) continue;
            var left = types.of(binary.getLeftOperand(), GuardRules::unknown);
            var right = types.of(binary.getRightOperand(), GuardRules::unknown);
            if (binary.getKind() == Tree.Kind.PLUS && (left == JType.STRING || right == JType.STRING)) continue;
            if (GuardTypes.isBoolean(left) && GuardTypes.isBoolean(right)) continue; // boolean &, |, ^
            throw invalid(binary, entry, arithmetic(binary));
        }
        for (var unary : SubsetWalk.all(guard, UnaryTree.class)) {
            if ((unary.getKind() == Tree.Kind.UNARY_MINUS || unary.getKind() == Tree.Kind.BITWISE_COMPLEMENT)
                    && !constants.evaluate(unary).constant()) {
                throw invalid(unary, entry, arithmetic(unary));
            }
        }
        var declaration = constants.invalidDeclaration(guard);
        if (declaration != null) throw invalid(guard, entry, declaration);
        for (var call : SubsetWalk.all(guard, MethodInvocationTree.class)) {
            // intValue() and longValue() lower to the identity, where Java narrows to 32 or 64 bits (#294).
            if (call.getArguments().isEmpty() && call.getMethodSelect() instanceof MemberSelectTree select
                    && NARROWING_IDENTITY_METHODS.contains(select.getIdentifier().toString())) {
                throw invalid(call, entry, "'" + facts.text(call) + "' narrows to "
                        + (select.getIdentifier().contentEquals("intValue") ? "32" : "64") + " bits in Java, but JuLC "
                        + "lowers it to the identity; compare the BigInteger value instead");
            }
        }
        boolean small = SubsetWalk.all(guard, LiteralTree.class).stream().anyMatch(l -> l.getKind() == Tree.Kind.CHAR_LITERAL)
                || casts.stream().anyMatch(cast -> types.declared(facts.type(cast.getType())) == JType.SMALL);
        if (small) {
            throw invalid(guard, entry, "char, byte and short values are not supported in a guard (their constant narrowing, "
                    + "JLS 15.25.2, is not modelled); use int or long");
        }
        for (var instanceOf : SubsetWalk.all(guard, InstanceOfTree.class)) {
            var reason = incompatibility(instanceOf);
            if (reason != null) throw invalid(instanceOf, entry, "'" + facts.text(instanceOf) + "': " + reason);
        }
    }

    private String arithmetic(Tree tree) {
        return "'" + facts.text(tree) + "' is int or long arithmetic, which JuLC evaluates without Java's overflow; "
                + "use BigInteger arithmetic, or compute the value in a local before the switch "
                + "and compare that";
    }

    /**
     * Null when {@code o instanceof V} can be dispatched on V's constructor tag: the operand's declared type is V itself or
     * V's sealed interface. An operand no declaration types is a reason to reject, though javac knows its type.
     */
    private String incompatibility(InstanceOfTree instanceOf) {
        var pattern = instanceOf.getPattern();
        if (pattern instanceof DeconstructionPatternTree) return "only a type test or type pattern naming a sealed-interface variant is supported";
        Tree target = pattern instanceof BindingPatternTree binding ? binding.getVariable().getType() : instanceOf.getType();
        if (!(facts.types().asElement(facts.type(target)) instanceof TypeElement variant)) {
            return "only a type test or type pattern naming a sealed-interface variant is supported";
        }
        var parents = facts.sealedParents(variant);
        if (parents.isEmpty()) return facts.text(target) + " is not a variant of a sealed interface";
        var family = parents.getFirst();
        var operand = types.declaredTypeTree(instanceOf.getExpression());
        if (operand == null || !GuardTypes.isClassType(facts.type(operand))) {
            return "the operand's type is not established by its declaration as " + family.getSimpleName()
                    + " or " + facts.text(target) + " (constructor tags are only meaningful within one sealed interface); "
                    + "bind it to a local or parameter of that type";
        }
        var declared = facts.types().asElement(facts.type(operand));
        if (variant.equals(declared) || parents.contains(declared)) return null;
        return "incompatible types: " + facts.text(operand) + " can never be a " + facts.text(target);
    }

    // --- Where the guard is lowered ---

    /** The guard's type, which {@code leaf} can answer now that the arm's binders are known. */
    JType typeOf(ExpressionTree guard, Function<ExpressionTree, JType> leaf) {
        return types.of(guard, leaf);
    }

    /** {@code String.equals} lowers to {@code EqualsString}, which fails on a non-{@code String} where Java gives false (#292). */
    void requireStringEquals(CaseTree entry, ExpressionTree guard, Function<ExpressionTree, JType> leaf) {
        for (var call : SubsetWalk.all(guard, MethodInvocationTree.class)) {
            if (call.getMethodSelect() instanceof MemberSelectTree select && select.getIdentifier().contentEquals("equals")
                    && call.getArguments().size() == 1 && types.of(select.getExpression(), leaf) == JType.STRING
                    && types.of(call.getArguments().getFirst(), leaf) != JType.STRING) {
                throw invalid(call, entry, "'" + facts.text(call) + "' compares a String with a value that is not established "
                        + "as a String, which fails on-chain where Java gives false");
            }
        }
    }

    /** {@code JULC0072} for a guard not established as boolean. */
    void requireBoolean(CaseTree entry, ExpressionTree guard, JType type) {
        if (!GuardTypes.isBoolean(type)) throw invalid(guard, entry, notBoolean(guard, type));
    }

    /**
     * D6 in a guard that is not constant: an operation that compares, and a string {@code +}, as anywhere. {@code ==} is
     * this class's: a guard reads it through {@link GuardTypes}, as ADR-067 decided. An arm of a {@code switch} in the guard
     * is ordinary code, which {@code SubsetScan} read.
     */
    void requireEquality(ExpressionTree guard) {
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitBinary(BinaryTree tree, Void unused) {
                if (tree.getKind() == Tree.Kind.PLUS) equality.check(tree);
                return super.visitBinary(tree, unused);
            }

            @Override
            public Void visitMethodInvocation(MethodInvocationTree tree, Void unused) {
                equality.check(tree);
                return super.visitMethodInvocation(tree, unused);
            }

            @Override
            public Void visitSwitchExpression(SwitchExpressionTree tree, Void unused) {
                return scan(tree.getExpression(), null);
            }
        }.scan(guard, null);
    }

    /** Whether {@code guard} is a name or a call, which a term may type by itself. */
    static boolean isLeaf(ExpressionTree guard) {
        var inner = guard;
        while (inner instanceof ParenthesizedTree parenthesized) inner = parenthesized.getExpression();
        return inner instanceof MethodInvocationTree || inner instanceof IdentifierTree || inner instanceof MemberSelectTree;
    }

    /** The constant a guard's part is, to the width Java gives it: what the lowering reads in place of the expression. */
    GuardConstants constants() {
        return constants;
    }
}
