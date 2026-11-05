package org.julclang.compiler.frontend.javac.lower;

import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.UnaryTree;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.error.DiagnosticCodes;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.frontend.javac.JavacNames;
import org.julclang.compiler.frontend.javac.SwitchResults;
import org.julclang.compiler.pir.NarrowingElisionPass;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;

import javax.lang.model.element.Element;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Casts: {@code (T) x} and {@code PlutusData.cast(x, T.class)}.
 * <ul>
 *   <li>A cast to a reference type is a view of the value, as the reference has it: the term is the operand's, except that a
 *       view as a map decodes what is not a map yet, since a map variable holds a pair list. Java checks such a cast at run
 *       time and the script does not (#288), as with {@code PlutusData.cast}, which D6 names an unchecked view.</li>
 *   <li>A cast between {@code int} and {@code long} has Java's value: {@code (int)} of a {@code long} keeps its low 32 bits,
 *       as two's complement; widening and a cast to the same type are the operand. The reference erases the narrowing, which
 *       differs from Java only out of range (D4's cap, the follow-ups' "Closed by M5"). Where the operand's value is shown to
 *       be in range, {@link NarrowingElisionPass} removes the wrap on the assembled term, when every method body is known.</li>
 *   <li>A cast to {@code byte}, {@code short} or {@code char} has no representation, as its type: {@code JULC0124}.</li>
 * </ul>
 * The operand is evaluated once, before what the cast does to it. A guard's casts are {@link Lowering}'s, under ADR-067.
 */
final class Casts {

    private final JavacFacts facts;
    private final Typing typing;
    private final CallTargets targets;
    private final Lowering lowering;

    Casts(JavacFacts facts, Typing typing, CallTargets targets, Lowering lowering) {
        this.facts = facts;
        this.typing = typing;
        this.lowering = lowering;
        this.targets = targets;
    }

    /** {@code (T) x}. */
    PirTerm cast(TypeCastTree tree, Frame frame) {
        var operand = tree.getExpression();
        var target = facts.type(tree);
        if (target.getKind().isPrimitive()) return primitive(tree, target, operand, frame);
        var inner = lowering.expr(operand, frame);
        return toMap(tree) && !isMap(operand, inner) ? new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnMapData), inner) : inner;
    }

    private PirTerm primitive(TypeCastTree tree, TypeMirror target, ExpressionTree operand, Frame frame) {
        var kind = target.getKind();
        // A narrowing of a literal is its Java value, as the reference's for one in range ((char) 97 is 97): review 2, F3.
        var folded = narrowedLiteral(kind, operand);
        if (folded != null) return integer(BigInteger.valueOf(folded));
        // byte, short and char have no representation, as their types (JULC0124); floating point is the scan's.
        if (kind != TypeKind.INT && kind != TypeKind.LONG && kind != TypeKind.BOOLEAN) throw facts.unsupportedPrimitive(target, tree);
        var inner = lowering.expr(operand, frame);
        // Java has no narrowing from a boxed Long: (int) of one is javac's error.
        return kind == TypeKind.INT && facts.type(operand).getKind() == TypeKind.LONG ? narrowToInt(inner) : inner;
    }

    /** {@code (char)}, {@code (short)} or {@code (byte)} of an integer literal, perhaps negated: Java's value, else null. */
    static Long narrowedLiteral(TypeKind kind, ExpressionTree operand) {
        if (kind != TypeKind.CHAR && kind != TypeKind.SHORT && kind != TypeKind.BYTE) return null;
        while (operand instanceof ParenthesizedTree parenthesized) operand = parenthesized.getExpression();
        boolean negated = operand instanceof UnaryTree unary && unary.getKind() == Tree.Kind.UNARY_MINUS;
        if (negated) operand = ((UnaryTree) operand).getExpression();
        if (!(operand instanceof LiteralTree literal) || !(literal.getValue() instanceof Number number)) return null;
        // javac holds -2147483648's literal as Integer.MIN_VALUE: negate in the literal's own width, as Java does.
        long value = literal.getKind() == Tree.Kind.INT_LITERAL ? (negated ? -number.intValue() : number.intValue())
                : (negated ? -number.longValue() : number.longValue());
        return switch (kind) {
            case CHAR -> (long) (char) value;
            case SHORT -> (long) (short) value;
            default -> (long) (byte) value;
        };
    }

    /** {@code (int)} of a {@code long}: {@link NarrowingElisionPass#narrowToInt}, folded where the operand is a constant. */
    private static PirTerm narrowToInt(PirTerm value) {
        if (value instanceof PirTerm.Const(Constant.IntegerConst(var constant))) {
            return new PirTerm.Const(Constant.integer(BigInteger.valueOf(constant.intValue())));
        }
        return NarrowingElisionPass.narrowToInt(value);
    }

    private static PirTerm integer(BigInteger value) {
        return new PirTerm.Const(Constant.integer(value));
    }

    /** {@code PlutusData.cast(d, T.class)} views {@code d} as {@code T}; a map variable holds a pair list, so it decodes. */
    PirTerm plutusDataCast(MethodInvocationTree tree, Frame frame) {
        if (!(tree.getArguments().get(1) instanceof MemberSelectTree literal && literal.getIdentifier().contentEquals("class"))) {
            throw facts.rejection(DiagnosticCodes.PLUTUS_DATA_CAST_TARGET_NOT_LITERAL, tree);
        }
        var argument = tree.getArguments().getFirst();
        var inner = lowering.expr(argument, frame);
        return toMap(tree) && !isMap(argument, inner) ? new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnMapData), inner) : inner;
    }

    /** Whether {@code cast}, a cast of either form, views its value as a map. A type with no representation is not one. */
    private boolean toMap(ExpressionTree cast) {
        try {
            return typing.of(cast) instanceof PirType.MapType;
        } catch (CompilerException e) {
            return false;
        }
    }

    /** Whether {@code tree} is a cast, of either form, that leaves its value as it is: not a view as a map. */
    private boolean isPlainView(ExpressionTree tree) {
        return (tree instanceof TypeCastTree cast && !(cast.getType() instanceof PrimitiveTypeTree)
                || tree instanceof MethodInvocationTree call && targets.classify(call) == CallTarget.Intrinsic.CAST)
                && !toMap(tree);
    }

    private boolean isVar(VariableElement variable) {
        var declaration = facts.declaration(variable);
        return declaration.getType() == null || facts.isGenerated(declaration.getType());
    }

    private static ExpressionTree viewed(ExpressionTree cast) {
        return cast instanceof TypeCastTree typeCast ? typeCast.getExpression()
                : ((MethodInvocationTree) cast).getArguments().getFirst();
    }

    /**
     * Whether {@code inner} holds a decoded map, so a cast to a map decodes a value once, or not at all. javac's type goes
     * first, since a binder can say Data of a decoded value ({@link Lambdas}, a {@code var}), and it is the type of what
     * {@link #valuesOf} reaches: a cast that does not decode says nothing of the value. One term cannot decode one value it
     * may hold and leave another, so a cast of both stops.
     */
    private boolean isMap(ExpressionTree argument, PirTerm inner) {
        var values = new ArrayList<ExpressionTree>();
        valuesOf(argument, new HashSet<>(), values);
        if (values.size() == 1) {
            try {
                return typing.of(values.getFirst(), inner) instanceof PirType.MapType;
            } catch (Typing.Unrepresented e) {
                return typing.ofTerm(inner) instanceof PirType.MapType;
            }
        }
        boolean map = typing.of(values.getFirst()) instanceof PirType.MapType;
        for (var value : values) {
            if (typing.of(value) instanceof PirType.MapType != map) {
                // One term cannot decode a map on one path and leave another type on the other: the value has no one type.
                throw facts.rejection(DiagnosticCodes.TYPE_RESOLUTION_FAILED, argument,
                        "a value cast to a map that holds a map on one path and another type on another");
            }
        }
        return map;
    }

    /**
     * What {@code tree} may evaluate to, through parentheses, a choice of terms, a cast that does not decode and an
     * effectively final {@code var}, each variable once. A variable with a written type is what that type says, as the
     * reference binds it, and so is a {@code var} assigned again whose type is a map; a {@code var} of another type assigned
     * again stops (I8). A {@code @Param}'s value is what is applied, not its initializer, so the read ends at it.
     */
    private void valuesOf(ExpressionTree tree, Set<Element> read, List<ExpressionTree> values) {
        switch (tree) {
            case ParenthesizedTree parenthesized -> valuesOf(parenthesized.getExpression(), read, values);
            case ConditionalExpressionTree conditional -> {
                valuesOf(conditional.getTrueExpression(), read, values);
                valuesOf(conditional.getFalseExpression(), read, values);
            }
            case SwitchExpressionTree selected -> SwitchResults.of(selected).forEach(result -> valuesOf(result, read, values));
            case ExpressionTree cast when isPlainView(cast) -> valuesOf(viewed(cast), read, values);
            case IdentifierTree name when facts.elementOrNull(name) instanceof VariableElement variable
                    && facts.initializer(variable) != null && !facts.hasAnnotation(variable, JavacNames.PARAM) -> {
                // The reference binds a variable with a written type as that type, and a var as its initializer is seen.
                if (!isVar(variable)) {
                    values.add(name);
                } else if (!facts.effectivelyFinal(variable)) {
                    // A var assigned again may hold another value than its initializer's: a map by its type holds a map
                    // whatever was assigned, and Data may hold either, so it stops.
                    if (!(typing.of(name) instanceof PirType.MapType)) {
                        throw facts.rejection(DiagnosticCodes.TYPE_RESOLUTION_FAILED, name,
                                "a variable cast to a map that is assigned after its declaration, so it may hold a map or not");
                    }
                    values.add(name);
                } else if (read.add(variable)) {
                    valuesOf(facts.initializer(variable), read, values);
                }
            }
            default -> values.add(tree);
        }
    }
}
