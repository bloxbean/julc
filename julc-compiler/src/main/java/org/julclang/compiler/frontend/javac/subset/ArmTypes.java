package org.julclang.compiler.frontend.javac.subset;

import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.SwitchExpressionTree;
import org.julclang.compiler.error.DiagnosticCodes;
import org.julclang.compiler.frontend.javac.ArrayIdentityEquality;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.frontend.javac.JavacNames;
import org.julclang.compiler.frontend.javac.SwitchResults;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import java.util.List;

/**
 * ADR-068 M5: a conditional or a {@code switch} expression whose arms do not all have one Java type is {@code JULC0012}, at the
 * expression, wherever it stands. Its value is then of a type javac invents ({@code Object}, {@code Record}, an intersection, a
 * type argument inferred over the arms), whose arms can have different representations on-chain: a term of it either decodes
 * one arm wrongly or compares representations Java keeps distinct. Arms of one type keep it. A boxed and a primitive type are one
 * type, and an expression javac types primitive (a numeric or boolean conditional) promotes its arms to that type, whose one
 * representation they share. Arms of a sealed interface of records and its records share its representation, a constructor of
 * Data; {@code PlutusData} is not such an interface, as its views are held in different forms.
 * <p>
 * One Java type does not make one representation ({@code ListData} from {@code unListData} is a builtin list, from
 * {@code toPlutusData()} Data), so the lowering holds the arms' lowered terms to one representation as well
 * ({@code Typing.requireOneRepresentation}).
 */
public final class ArmTypes {

    private ArmTypes() {
    }

    /** @throws org.julclang.compiler.CompilerException {@code JULC0012} if {@code tree} is such an expression of arms of two types */
    public static void requireOneType(JavacFacts facts, ExpressionTree tree) {
        while (tree instanceof ParenthesizedTree parenthesized) tree = parenthesized.getExpression();
        List<? extends ExpressionTree> arms;
        String what;
        if (tree instanceof ConditionalExpressionTree conditional) {
            arms = List.of(conditional.getTrueExpression(), conditional.getFalseExpression());
            what = "a conditional";
        } else if (tree instanceof SwitchExpressionTree selected) {
            arms = SwitchResults.of(selected);
            what = "a switch expression";
        } else {
            return;
        }
        if (facts.type(tree).getKind().isPrimitive()) return;
        var sum = sumOf(facts, facts.type(tree));
        if (sum != null && arms.stream().allMatch(arm -> isOf(facts, armType(facts, arm), sum))) return;
        TypeMirror first = null;
        for (var arm : arms) {
            var type = boxed(facts, armType(facts, arm));
            if (type.getKind() == TypeKind.NULL) continue;   // null has a rule of its own
            if (first == null) {
                first = type;
            } else if (!facts.types().isSameType(first, type)) {
                throw facts.rejection(DiagnosticCodes.TYPE_RESOLUTION_FAILED, tree, "the type of " + what + " whose arms have different types, "
                        + ArrayIdentityEquality.text(first) + " and " + ArrayIdentityEquality.text(type) + ". Give the arms one type");
            }
        }
    }

    /**
     * The sealed interface of records the expression's type is, or the one such bound of an intersection ({@code Record & S},
     * javac's type of {@code c ? new A() : new B()}): its variants share its one representation, a constructor of Data. Null
     * otherwise.
     */
    private static TypeElement sumOf(JavacFacts facts, TypeMirror type) {
        var bounds = type instanceof IntersectionType intersection ? intersection.getBounds() : List.of(type);
        TypeElement found = null;
        for (var bound : bounds) {
            if (!(bound instanceof DeclaredType declared) || !(declared.asElement() instanceof TypeElement element)) continue;
            if (element.getKind() != ElementKind.INTERFACE || !element.getModifiers().contains(Modifier.SEALED)) continue;
            // PlutusData's views are records, but each is held its own way: a builtin's list is no Data (review 2, F1).
            if (facts.identity(element).equals(JavacNames.PLUTUS_DATA)) continue;
            // The scan runs before the registry holds the program's types: a sum is a sealed interface whose permitted are records.
            if (element.getPermittedSubclasses().isEmpty()
                    || !facts.permitted(element).stream().allMatch(permitted -> permitted.getKind() == ElementKind.RECORD)) {
                continue;
            }
            if (found != null) return null;
            found = element;
        }
        return found;
    }

    /** Whether an arm of {@code type} is a value of {@code sum}'s representation: the sum itself, or one of its records. */
    private static boolean isOf(JavacFacts facts, TypeMirror type, TypeElement sum) {
        if (!(type instanceof DeclaredType declared) || !(declared.asElement() instanceof TypeElement element)) return false;
        if (element.equals(sum)) return true;
        return element.getKind() == ElementKind.RECORD && !facts.hasAnnotation(element, JavacNames.NEW_TYPE)
                && facts.permitted(sum).contains(element);
    }

    /**
     * An arm's type: javac's, but for an arm that is itself a choice, which javac types by its target (an arm of an argument of
     * an {@code Object} parameter is {@code Object}), its own arms' one type when they have one.
     */
    private static TypeMirror armType(JavacFacts facts, ExpressionTree arm) {
        while (arm instanceof ParenthesizedTree parenthesized) arm = parenthesized.getExpression();
        List<? extends ExpressionTree> inner = arm instanceof ConditionalExpressionTree conditional
                ? List.of(conditional.getTrueExpression(), conditional.getFalseExpression())
                : arm instanceof SwitchExpressionTree selected ? SwitchResults.of(selected) : null;
        if (inner == null || facts.type(arm).getKind().isPrimitive()) return facts.type(arm);
        TypeMirror one = null;
        for (var each : inner) {
            var type = boxed(facts, armType(facts, each));
            if (type.getKind() == TypeKind.NULL) continue;
            if (one == null) one = type;
            else if (!facts.types().isSameType(one, type)) return facts.type(arm);
        }
        return one == null ? facts.type(arm) : one;
    }

    private static TypeMirror boxed(JavacFacts facts, TypeMirror type) {
        return type instanceof PrimitiveType primitive ? facts.types().boxedClass(primitive).asType() : type;
    }
}
