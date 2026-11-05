package org.julclang.compiler.frontend.javac.subset;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.util.TreeScanner;
import org.julclang.compiler.CompilerTypeDiagnostics;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.frontend.javac.JavacNames;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;

/**
 * A subset rule ({@code JULC0098}): a value reaches {@code boolean} or {@code Boolean} through a cast only from a
 * {@code boolean} or {@code Boolean}. What the operand is, through parentheses and other casts, is its type as javac
 * resolved it. A cast has two spellings beside {@code (boolean) x}: the calls JuLC lowers to their argument, which are
 * {@code PlutusData.cast}, {@code X.fromPlutusData} and {@code BigInteger.valueOf}. The target is read from the call's
 * resolved type, so a class literal, a {@code Class<Boolean>} variable and {@code boolean.class} are one case. A target
 * holds a boolean if it is one, or has one as a bound, or is a {@code @NewType} record of one.
 */
public final class BooleanCasts {

    private BooleanCasts() {
    }

    public static void check(JavacFacts facts, CompilationUnitTree unit) {
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitTypeCast(TypeCastTree tree, Void unused) {
                require(facts, tree, tree.getExpression());
                return super.visitTypeCast(tree, unused);
            }

            @Override
            public Void visitMethodInvocation(MethodInvocationTree tree, Void unused) {
                var argument = passedOn(facts, tree);
                if (argument != null) require(facts, tree, argument);
                return super.visitMethodInvocation(tree, unused);
            }
        }.scan(unit, null);
    }

    private static void require(JavacFacts facts, ExpressionTree cast, ExpressionTree expression) {
        var target = facts.type(cast);
        if (!holdsABoolean(facts, target)) return;
        var operand = facts.type(operand(facts, expression));
        if (!holdsABoolean(facts, operand)) {
            throw CompilerTypeDiagnostics.booleanCastOperand(target.toString(), operand.toString(), facts.location(cast));
        }
    }

    private static ExpressionTree operand(JavacFacts facts, ExpressionTree expression) {
        ExpressionTree operand = expression;
        while (true) {
            if (operand instanceof ParenthesizedTree enclosed) operand = enclosed.getExpression();
            else if (operand instanceof TypeCastTree cast) operand = cast.getExpression();
            else if (operand instanceof MethodInvocationTree call && passedOn(facts, call) != null) operand = passedOn(facts, call);
            else return operand;
        }
    }

    /** The argument of a call JuLC lowers to that argument, or null for any other call. */
    private static ExpressionTree passedOn(JavacFacts facts, MethodInvocationTree call) {
        var select = call.getMethodSelect();
        var arguments = call.getArguments();
        String name = select instanceof MemberSelectTree member ? member.getIdentifier().toString()
                : select instanceof IdentifierTree identifier ? identifier.getName().toString() : "";
        return switch (name) {
            case "cast" -> arguments.size() == 2 && (isPlutusDataCast(facts, select) || isSpelled(select, "PlutusData"))
                    ? arguments.getFirst() : null;
            case "fromPlutusData" -> arguments.size() == 1 && select instanceof MemberSelectTree member
                    && member.getExpression() instanceof IdentifierTree ? arguments.getFirst() : null;
            case "valueOf" -> arguments.size() == 1 && isSpelled(select, "BigInteger") ? arguments.getFirst() : null;
            default -> null;
        };
    }

    private static boolean isPlutusDataCast(JavacFacts facts, Tree select) {
        return facts.element(select) instanceof ExecutableElement method
                && method.getEnclosingElement() instanceof TypeElement owner
                && facts.identity(owner).equals(JavacNames.PLUTUS_DATA);
    }

    /** The lowering reads these two qualifiers by spelling, so whatever else the name is, the call is the same to it. */
    private static boolean isSpelled(Tree select, String qualifier) {
        return select instanceof MemberSelectTree member && member.getExpression() instanceof IdentifierTree name
                && name.getName().contentEquals(qualifier);
    }

    private static boolean holdsABoolean(JavacFacts facts, TypeMirror type) {
        if (facts.isBoolean(type)) return true;
        return switch (type) {
            case IntersectionType intersection -> intersection.getBounds().stream().anyMatch(bound -> holdsABoolean(facts, bound));
            case TypeVariable variable -> holdsABoolean(facts, variable.getUpperBound());
            case DeclaredType declared when isNewTypeOfABoolean(facts, declared) -> true;
            default -> false;
        };
    }

    private static boolean isNewTypeOfABoolean(JavacFacts facts, DeclaredType type) {
        var element = type.asElement();
        return element.getKind() == ElementKind.RECORD
                && SpelledMethods.isNewType(element)
                && facts.components((TypeElement) element).stream().anyMatch(component -> facts.isBoolean(component.asType()));
    }
}
