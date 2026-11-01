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
import com.sun.source.util.TreeScanner;
import org.julclang.compiler.CompilerTypeDiagnostics;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.frontend.javac.JavacNames;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A subset rule ({@code JULC0095}): what is not lowered holds only a constant. Any field initializer a compilation does not
 * lower, and a library's static field, which binds at script start whether or not Java initializes the class, holds only a literal, a {@code new byte[]{...}} of literals, an API constant or a constant variable, and an
 * initializer block is empty. Only javac can tell these: {@link VariableElement#getConstantValue()} is the language's own
 * answer, and a name is what it resolved to.
 * <p>
 * This list is closed, and it is deliberately not {@link SubsetScan}'s: that list grows as lowering learns a construct,
 * and a construct lowering has learned says nothing here, where lowering never looks. A field Java itself treats as a
 * constant is left alone, so that the javac frontend's {@code JULC0088} still reports a {@code @Param} one.
 */
public final class UnloweredInitializers {

    private static final String BOOLEAN_VALUE_OF = "valueOf";

    private final JavacFacts facts;

    private UnloweredInitializers(JavacFacts facts) {
        this.facts = facts;
    }

    /**
     * Checks {@code unit}, where {@code lowered} says which fields the compilation lowers. Every type of it counts, however
     * deep, a local one included, so this stays true as lowering learns statements.
     */
    public static void check(JavacFacts facts, CompilationUnitTree unit, Predicate<VariableTree> lowered) {
        var rule = new UnloweredInitializers(facts);
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitVariable(VariableTree tree, Void unused) {
                if (facts.parent(tree) instanceof ClassTree && tree.getInitializer() != null && !lowered.test(tree)
                        && !rule.holdsAConstant(tree)) {
                    throw CompilerTypeDiagnostics.initializerNotLowered("The initializer of field '" + tree.getName() + "'",
                            "it holds only a literal, a byte[] literal, an API constant or a constant",
                            facts.location(tree.getInitializer()));
                }
                return super.visitVariable(tree, unused);
            }

            @Override
            public Void visitBlock(BlockTree tree, Void unused) {
                if (facts.parent(tree) instanceof ClassTree && !tree.getStatements().isEmpty()) {
                    throw CompilerTypeDiagnostics.initializerNotLowered("An initializer block", "it is empty", facts.location(tree));
                }
                return super.visitBlock(tree, unused);
            }
        }.scan(unit, null);
    }

    private boolean holdsAConstant(VariableTree field) {
        return ((VariableElement) facts.element(field)).getConstantValue() != null || isAllowed(field.getInitializer());
    }

    private boolean isAllowed(ExpressionTree expression) {
        return switch (expression) {
            case ParenthesizedTree enclosed -> isAllowed(enclosed.getExpression());
            case LiteralTree literal -> literal.getKind() != Tree.Kind.NULL_LITERAL;
            case UnaryTree unary -> unary.getKind() == Tree.Kind.UNARY_MINUS && unary.getExpression() instanceof LiteralTree;
            case NewArrayTree array -> SubsetScan.isByteLiteral(array);
            case IdentifierTree name -> isConstantVariable(name);
            case MemberSelectTree select -> onAType(select) && (isConstantVariable(select) || isApiConstant(select));
            case MethodInvocationTree call -> isBooleanValueOf(call);
            default -> false;
        };
    }

    /** What stands before the dot is a type. Any other expression there would run, whatever the dot reaches. */
    private boolean onAType(MemberSelectTree select) {
        var qualifier = select.getExpression();
        return (qualifier instanceof IdentifierTree || qualifier instanceof MemberSelectTree)
                && facts.element(qualifier) instanceof TypeElement;
    }

    private boolean isConstantVariable(Tree name) {
        return facts.element(name) instanceof VariableElement variable && variable.getConstantValue() != null;
    }

    private boolean isApiConstant(MemberSelectTree select) {
        return facts.element(select).getEnclosingElement() instanceof TypeElement owner
                && JavacNames.API_CONSTANTS.getOrDefault(facts.identity(owner), Set.of())
                        .contains(select.getIdentifier().toString());
    }

    /**
     * {@code Boolean.valueOf(true)}, the one call: how a {@code boolean @Param} is written so that it is no constant
     * variable (D14). By identity: the JDK's {@code valueOf}, of a boolean literal, on a type or a static import.
     */
    private boolean isBooleanValueOf(MethodInvocationTree call) {
        var select = call.getMethodSelect();
        return (select instanceof IdentifierTree || select instanceof MemberSelectTree member && onAType(member))
                && facts.element(select) instanceof ExecutableElement method
                && method.getSimpleName().contentEquals(BOOLEAN_VALUE_OF)
                && method.getEnclosingElement() instanceof TypeElement owner
                && facts.identity(owner).equals(JavacNames.BOOLEAN)
                && call.getArguments().getFirst().getKind() == Tree.Kind.BOOLEAN_LITERAL;
    }
}
