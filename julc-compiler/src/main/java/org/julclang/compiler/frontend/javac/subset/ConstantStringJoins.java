package org.julclang.compiler.frontend.javac.subset;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreeScanner;
import org.julclang.compiler.CompilerTypeDiagnostics;
import org.julclang.compiler.error.CompilerDiagnostic;
import org.julclang.compiler.frontend.javac.JavacFacts;

import javax.lang.model.element.VariableElement;
import java.util.ArrayList;
import java.util.List;

/**
 * A warning ({@code JULC0102}): a {@code +} of string constants, such as {@code "a" + "b"}. Java joins these when it
 * compiles, and JuLC keeps a {@code +} as written, so the script joins them each time it runs. A constant is a string
 * literal, a {@code String} variable javac gives a constant value, or a {@code +} of constants, through parentheses. One
 * warning covers a whole expression of constants, at its outermost {@code +}.
 */
public final class ConstantStringJoins {

    private ConstantStringJoins() {
    }

    /** The warnings of {@code unit}, in source order. */
    public static List<CompilerDiagnostic> in(JavacFacts facts, CompilationUnitTree unit) {
        var warnings = new ArrayList<CompilerDiagnostic>();
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitBinary(BinaryTree tree, Void unused) {
                if (!isConstantJoin(facts, tree)) return super.visitBinary(tree, unused);
                warnings.add(CompilerTypeDiagnostics.constantStringJoin(facts.location(tree)));
                return null;
            }

            /** An annotation's argument is a constant Java keeps: nothing joins it at run time. */
            @Override
            public Void visitAnnotation(AnnotationTree tree, Void unused) {
                return null;
            }
        }.scan(unit, null);
        return warnings;
    }

    private static boolean isConstantJoin(JavacFacts facts, BinaryTree tree) {
        return tree.getKind() == Tree.Kind.PLUS && isConstant(facts, tree.getLeftOperand()) && isConstant(facts, tree.getRightOperand());
    }

    private static boolean isConstant(JavacFacts facts, Tree tree) {
        return switch (tree) {
            case LiteralTree literal -> literal.getValue() instanceof String;
            case ParenthesizedTree parenthesized -> isConstant(facts, parenthesized.getExpression());
            case BinaryTree binary -> isConstantJoin(facts, binary);
            default -> facts.elementOrNull(tree) instanceof VariableElement variable && variable.getConstantValue() instanceof String;
        };
    }
}
