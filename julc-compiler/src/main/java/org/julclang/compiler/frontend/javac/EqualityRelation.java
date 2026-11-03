package org.julclang.compiler.frontend.javac;

import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.SwitchExpressionTree;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Whether Java's {@code equals} can hold between a value of one static type and a value of another (ADR-068 D6). On-chain
 * a comparison reaches {@code EqualsData}, or a builtin on the representation, so {@code 5} and {@code 5L} are equal there,
 * as are a {@code String} and its bytes, and two record classes with the same fields. Java keeps each pair distinct: its
 * {@code equals} starts from the classes.
 * <p>
 * The decision is on javac's types, without erasure. A primitive is its box. Two declared types are related when one is a
 * subtype of the other by class, and every type argument of the wider one is related to the same argument of the narrower
 * one seen as that class: {@code Optional}, {@code Tuple2}, {@code Tuple3} and the collections compare by their arguments.
 * So the recursion follows the arguments, which are finite, and needs no component: two values of one class have one
 * representation.
 * <p>
 * Two arrays are related at one type. {@link ArrayIdentityEquality} says what an array compares by. An array and anything
 * else are not related.
 * <p>
 * What the check cannot read is {@link Kind#UNRESOLVED}: {@code Object}, a raw type, a type variable, and a wildcard with no
 * upper bound. D6 rejects those, since Java's answer then depends on a class the type does not say. A type argument
 * {@code Object} is the exception: only javac's inference gives one (a declared {@code Optional<Object>} has no
 * representation), as in {@code x.equals(Optional.empty())}, where nothing was left to infer it from.
 * <p>
 * An empty {@code Optional} or collection equals an empty one of any argument. The rule stays on the types: such a
 * comparison is {@link Kind#UNRELATED} although Java can answer true there, and the program fails closed.
 */
public final class EqualityRelation {

    /** What the two static types allow. */
    public enum Kind {
        /** Values of the two types can be equal in Java. */
        RELATED,
        /** Java keeps every value of one distinct from every value of the other. */
        UNRELATED,
        /** A type the check cannot decide, which is rejected conservatively. */
        UNRESOLVED
    }

    /** The verdict, the pair of types that decided it, and whether that pair is a type argument of the operands'. */
    public record Result(Kind kind, TypeMirror left, TypeMirror right, boolean inArgument) {
    }

    private final Types types;
    private final Elements elements;
    private final ArrayIdentityEquality views;

    public EqualityRelation(Types types, Elements elements) {
        this.types = types;
        this.elements = elements;
        this.views = new ArrayIdentityEquality(types, elements);
    }

    /**
     * What {@code tree} can be, by its own type: javac types a conditional in an argument by the parameter, so its branches,
     * and a {@code switch} so too, so what its arms give.
     */
    public static List<ExpressionTree> operands(ExpressionTree tree) {
        return switch (tree) {
            case ParenthesizedTree parenthesized -> operands(parenthesized.getExpression());
            case SwitchExpressionTree nested -> {
                var results = new ArrayList<ExpressionTree>();
                SwitchResults.of(nested).forEach(result -> results.addAll(operands(result)));
                yield results;
            }
            case ConditionalExpressionTree conditional -> {
                var both = new ArrayList<>(operands(conditional.getTrueExpression()));
                both.addAll(operands(conditional.getFalseExpression()));
                yield both;
            }
            default -> List.of(tree);
        };
    }

    /** What Java's {@code equals} can do between values of {@code left} and of {@code right}. */
    public Result between(TypeMirror left, TypeMirror right) {
        return decide(boxed(left), boxed(right), false);
    }

    private TypeMirror boxed(TypeMirror type) {
        return type instanceof PrimitiveType primitive ? types.boxedClass(primitive).asType() : type;
    }

    private Result decide(TypeMirror left, TypeMirror right, boolean argument) {
        if (left.getKind() == TypeKind.WILDCARD) return decideBound((WildcardType) left, right, argument, true);
        if (right.getKind() == TypeKind.WILDCARD) return decideBound((WildcardType) right, left, argument, false);
        if (left instanceof IntersectionType intersection) return decideBounds(intersection, right, argument, true);
        if (right instanceof IntersectionType intersection) return decideBounds(intersection, left, argument, false);
        if (isObject(left) || isObject(right)) return new Result(argument ? Kind.RELATED : Kind.UNRESOLVED, left, right, argument);
        if (left.getKind() == TypeKind.ARRAY || right.getKind() == TypeKind.ARRAY) return arrays(left, right, argument);
        if (left instanceof DeclaredType a && right instanceof DeclaredType b) return declared(a, b, argument);
        return new Result(Kind.UNRESOLVED, left, right, argument);
    }

    private Result decideBound(WildcardType wildcard, TypeMirror other, boolean argument, boolean first) {
        var bound = wildcard.getExtendsBound();
        if (bound == null) return new Result(Kind.UNRESOLVED, wildcard, other, argument);
        return first ? decide(bound, other, argument) : decide(other, bound, argument);
    }

    /**
     * An intersection, {@code Record & S} of a conditional's arms, is a type javac invents; whether its values meet another's is
     * not read from its bounds one by one, and it cannot be decided (ADR-068 M5, F3).
     */
    private Result decideBounds(IntersectionType intersection, TypeMirror other, boolean argument, boolean first) {
        return new Result(Kind.UNRESOLVED, first ? intersection : other, first ? other : intersection, argument);
    }

    private static Result worse(Result a, Result b) {
        if (a.kind() == Kind.UNRELATED) return a;
        if (b.kind() == Kind.UNRELATED) return b;
        return a.kind() == Kind.UNRESOLVED ? a : b;
    }

    private Result arrays(TypeMirror left, TypeMirror right, boolean argument) {
        boolean same = left instanceof ArrayType a && right instanceof ArrayType b
                && types.isSameType(a.getComponentType(), b.getComponentType());
        return new Result(same ? Kind.RELATED : Kind.UNRELATED, left, right, argument);
    }

    private Result declared(DeclaredType left, DeclaredType right, boolean argument) {
        var erasedLeft = types.erasure(left);
        var erasedRight = types.erasure(right);
        if (types.isSubtype(erasedLeft, erasedRight)) return arguments(left, right, argument);
        if (types.isSubtype(erasedRight, erasedLeft)) return arguments(right, left, argument);
        // Neither is a subtype of the other. They are disjoint, so Java's answer is false, only if no class can be both: two
        // classes, a final class and an interface it does not implement, or sealed types whose permitted classes cannot meet.
        // Otherwise (Record against a sealed interface of records, two open interfaces) it cannot be decided.
        boolean disjoint = !mayShareAValue((TypeElement) left.asElement(), (TypeElement) right.asElement(), new HashSet<>());
        return new Result(disjoint ? Kind.UNRELATED : Kind.UNRESOLVED, left, right, argument);
    }

    /** Whether a class can be a subtype of both {@code a} and {@code b}, neither a subtype of the other's erasure. */
    private boolean mayShareAValue(TypeElement a, TypeElement b, Set<TypeElement> seen) {
        if (types.isSubtype(types.erasure(a.asType()), types.erasure(b.asType()))
                || types.isSubtype(types.erasure(b.asType()), types.erasure(a.asType()))) {
            return true;
        }
        boolean aInterface = a.getKind().isInterface(), bInterface = b.getKind().isInterface();
        if (!aInterface && !bInterface) return false;   // one superclass each
        if (isFinal(a) || isFinal(b)) return false;      // a final class that is not the other's subtype
        for (var sealed : List.of(a, b)) {
            if (sealed.getModifiers().contains(Modifier.SEALED) && seen.add(sealed)) {
                var other = sealed == a ? b : a;
                for (var permitted : sealed.getPermittedSubclasses()) {
                    if (mayShareAValue((TypeElement) types.asElement(permitted), other, seen)) return true;
                }
                return false;
            }
        }
        return true;
    }

    private static boolean isFinal(TypeElement type) {
        return type.getModifiers().contains(Modifier.FINAL) || type.getKind() == ElementKind.RECORD
                || type.getKind() == ElementKind.ENUM;
    }

    /** {@code narrow} seen as {@code wide}'s class, argument by argument. */
    private Result arguments(DeclaredType narrow, DeclaredType wide, boolean argument) {
        var view = views.asSuper(narrow, (TypeElement) wide.asElement());
        var parameters = ((TypeElement) wide.asElement()).getTypeParameters();
        var given = wide.getTypeArguments();
        if (view == null || given.size() != parameters.size() || view.getTypeArguments().size() != parameters.size()) {
            return new Result(Kind.UNRESOLVED, narrow, wide, argument);
        }
        Result found = new Result(Kind.RELATED, narrow, wide, argument);
        for (int i = 0; i < given.size(); i++) {
            found = worse(found, decide(view.getTypeArguments().get(i), given.get(i), true));
        }
        return found;
    }

    private boolean isObject(TypeMirror type) {
        return type instanceof DeclaredType declared
                && declared.asElement().equals(elements.getTypeElement(JavacNames.OBJECT));
    }
}
