package org.julclang.compiler.frontend.javac.lower;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.Tree;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerTypeDiagnostics;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.frontend.javac.NotPorted;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.pir.TermTypes;

import java.util.List;
import java.util.Optional;

/**
 * The PIR types of expressions, in the two steps the reference reads them: javac's static type, and where that says only
 * Data, what the lowered term says of itself ({@link TermTypes}). The second step is not a fallback to a guess: a Data
 * source type does not say what a builtin leaves on the stack, and {@code Builtins.tailList} is declared
 * {@code PlutusData.ListData} and leaves a list.
 */
final class Typing {

    private final JavacFacts facts;
    private final TermTypes termTypes = new TermTypes();

    Typing(JavacFacts facts) {
        this.facts = facts;
    }

    /** {@code tree}'s static type. A type JuLC has no representation for stops here: nothing can be decided on it. */
    PirType of(ExpressionTree tree) {
        var type = facts.type(tree);
        try {
            return facts.registry().resolveNamed(facts.valueType(type, tree));
        } catch (CompilerException e) {
            throw NotPorted.at(facts, tree, "an expression of type " + type);
        }
    }

    /** {@code lowered}'s type, for the expression {@code tree}. */
    PirType of(ExpressionTree tree, PirTerm lowered) {
        var type = of(tree);
        return facts.registry().resolveNamed(type instanceof PirType.DataType ? termTypes.infer(lowered) : type);
    }

    /** What {@code term} says of itself. */
    PirType ofTerm(PirTerm term) {
        return termTypes.infer(term);
    }

    /** The type {@code read}, a read of a variable, was bound with. */
    PirType ofVariable(PirTerm read) {
        return facts.registry().resolveNamed(termTypes.infer(read));
    }

    /** Keeps the source types of a lowered {@code JulcArray.of(...)}'s elements with its term (identity). */
    void recordArrayLiteral(PirTerm term, List<PirType> elementTypes) {
        termTypes.recordArrayLiteral(term, elementTypes);
    }

    /** The element types kept with {@code term}, if it is an array literal. */
    Optional<List<PirType>> arrayLiteralElementTypes(PirTerm term) {
        return termTypes.arrayLiteralElementTypes(term);
    }

    /**
     * ADR-047, the one check of every boundary: a native value never crosses a differently typed boundary, and a byte
     * string, Data or a Data list never crosses a natively typed one. javac admits either only through a raw type.
     */
    void requireNativeBoundary(String what, PirType actual, PirType expected, Tree source) {
        var registry = facts.registry();
        if ((registry.containsNativeOpaque(expected) || registry.containsNativeOpaque(actual)) && !expected.equals(actual)) {
            throw CompilerTypeDiagnostics.nativeTypeMismatch(what, actual, expected, facts.location(source));
        }
    }
}
