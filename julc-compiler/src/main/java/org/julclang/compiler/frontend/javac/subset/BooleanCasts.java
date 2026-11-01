package org.julclang.compiler.frontend.javac.subset;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.util.TreeScanner;
import org.julclang.compiler.CompilerTypeDiagnostics;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.frontend.javac.JavacNames;

import javax.lang.model.type.TypeMirror;

/**
 * A subset rule ({@code JULC0098}): a cast to {@code boolean} or {@code Boolean} takes an operand whose type, through
 * parentheses and other casts, is {@code boolean}, {@code Boolean}, or {@code PlutusData} and its subtypes.
 */
public final class BooleanCasts {

    private BooleanCasts() {
    }

    public static void check(JavacFacts facts, CompilationUnitTree unit) {
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitTypeCast(TypeCastTree tree, Void unused) {
                var target = facts.type(tree);
                if (facts.isBoolean(target)) {
                    var operand = facts.type(operand(tree.getExpression()));
                    if (!facts.isBoolean(operand) && !isData(facts, operand)) {
                        throw CompilerTypeDiagnostics.booleanCastOperand(target.toString(), operand.toString(), facts.location(tree));
                    }
                }
                return super.visitTypeCast(tree, unused);
            }
        }.scan(unit, null);
    }

    private static ExpressionTree operand(ExpressionTree expression) {
        ExpressionTree operand = expression;
        while (true) {
            if (operand instanceof ParenthesizedTree enclosed) operand = enclosed.getExpression();
            else if (operand instanceof TypeCastTree cast) operand = cast.getExpression();
            else return operand;
        }
    }

    private static boolean isData(JavacFacts facts, TypeMirror type) {
        var types = facts.types();
        return types.isSubtype(types.erasure(type), types.erasure(facts.elements().getTypeElement(JavacNames.PLUTUS_DATA).asType()));
    }
}
