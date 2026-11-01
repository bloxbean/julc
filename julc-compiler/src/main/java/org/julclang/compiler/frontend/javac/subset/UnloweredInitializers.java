package org.julclang.compiler.frontend.javac.subset;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import org.julclang.compiler.CompilerTypeDiagnostics;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.frontend.javac.JavacNames;
import org.julclang.compiler.frontend.javac.NotPorted;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A subset rule ({@code JULC0095}): what is not lowered holds only a constant. A static field of the compiled class is
 * lowered. Any other field initializer (a {@code @Param} field, an instance field, a field of another type) holds only a
 * literal, a {@code new byte[]{...}} of literals, an API constant or a constant variable, and an initializer block is
 * empty.
 * <p>
 * This list is closed, and it is deliberately not {@link SubsetScan}'s: that list grows as lowering learns a construct,
 * and a construct lowering has learned says nothing here, where lowering never looks. So a kind joins {@code SubsetScan}
 * for the code lowering reaches, and this rule stays as it is. A field Java itself treats as a constant is left alone, so
 * that {@code JULC0088} still reports a {@code @Param} one.
 */
public final class UnloweredInitializers {

    private static final String BOOLEAN_VALUE_OF = "valueOf";

    private final JavacFacts facts;

    private UnloweredInitializers(JavacFacts facts) {
        this.facts = facts;
    }

    /** Checks {@code unit}, where {@code lowered} says which fields the compilation lowers. */
    public static void check(JavacFacts facts, CompilationUnitTree unit, Predicate<VariableTree> lowered) {
        var rule = new UnloweredInitializers(facts);
        for (Tree type : unit.getTypeDecls()) {
            if (type instanceof ClassTree declared) rule.check(declared, lowered);
        }
    }

    private void check(ClassTree type, Predicate<VariableTree> lowered) {
        for (Tree member : type.getMembers()) {
            switch (member) {
                case VariableTree field when field.getInitializer() != null && !lowered.test(field)
                        && !holdsAConstant(field) -> throw CompilerTypeDiagnostics.initializerNotLowered(
                        "The initializer of field '" + field.getName() + "'", facts.location(field.getInitializer()));
                case BlockTree block when !block.getStatements().isEmpty() ->
                        throw CompilerTypeDiagnostics.initializerNotLowered("An initializer block", facts.location(block));
                case ClassTree nested -> check(nested, lowered);
                default -> {
                }
            }
        }
    }

    private boolean holdsAConstant(VariableTree field) {
        return ((VariableElement) facts.element(field)).getConstantValue() != null || isAllowed(field.getInitializer());
    }

    private boolean isAllowed(ExpressionTree expression) {
        return switch (expression) {
            case ParenthesizedTree enclosed -> isAllowed(enclosed.getExpression());
            case LiteralTree literal -> isLiteral(literal);
            case UnaryTree unary -> unary.getKind() == Tree.Kind.UNARY_MINUS && unary.getExpression() instanceof LiteralTree;
            case NewArrayTree array -> SubsetScan.isByteLiteral(array);
            case IdentifierTree name -> isConstantVariable(name);
            case MemberSelectTree select -> isConstantVariable(select) || isApiConstant(select);
            case MethodInvocationTree call -> isBooleanValueOf(call);
            default -> false;
        };
    }

    /** {@code null} has a rule of its own in the subset, which is not ported. */
    private boolean isLiteral(LiteralTree literal) {
        if (literal.getKind() == Tree.Kind.NULL_LITERAL) throw NotPorted.at(facts, literal);
        return true;
    }

    private boolean isConstantVariable(Tree name) {
        return facts.element(name) instanceof VariableElement variable && variable.getConstantValue() != null;
    }

    private boolean isApiConstant(MemberSelectTree select) {
        return select.getExpression() instanceof IdentifierTree qualifier && facts.element(qualifier) instanceof TypeElement
                && facts.element(select).getEnclosingElement() instanceof TypeElement owner
                && JavacNames.API_CONSTANTS.getOrDefault(facts.identity(owner), Set.of())
                        .contains(select.getIdentifier().toString());
    }

    /**
     * {@code Boolean.valueOf(true)}, the one call: how a {@code boolean @Param} is written so that it is no constant
     * variable (D14). By identity: the JDK's {@code valueOf(boolean)}, over a literal.
     */
    private boolean isBooleanValueOf(MethodInvocationTree call) {
        return facts.element(call.getMethodSelect()) instanceof ExecutableElement method
                && method.getSimpleName().contentEquals(BOOLEAN_VALUE_OF)
                && method.getEnclosingElement() instanceof TypeElement owner
                && facts.identity(owner).equals(JavacNames.BOOLEAN)
                && method.getParameters().size() == 1
                && method.getParameters().getFirst().asType().getKind() == TypeKind.BOOLEAN
                && call.getArguments().getFirst().getKind() == Tree.Kind.BOOLEAN_LITERAL;
    }
}
