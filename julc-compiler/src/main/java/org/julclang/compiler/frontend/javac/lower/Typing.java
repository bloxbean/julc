package org.julclang.compiler.frontend.javac.lower;

import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.Tree;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerTypeDiagnostics;
import org.julclang.compiler.frontend.javac.ArrayIdentityEquality;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.frontend.javac.JavacNames;
import org.julclang.compiler.frontend.javac.NotPorted;
import org.julclang.compiler.frontend.javac.SwitchResults;
import org.julclang.compiler.pir.PirHelpers;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.pir.TermTypes;

import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The PIR types of expressions, in the two steps the reference reads them: javac's static type, and where that says only
 * Data, what the lowered term says of itself ({@link TermTypes}). The second step is not a fallback to a guess: a Data
 * source type does not say what a builtin leaves on the stack, and {@code Builtins.tailList} is declared
 * {@code PlutusData.ListData} and leaves a list.
 */
final class Typing {

    private final JavacFacts facts;
    private final TermTypes termTypes = new TermTypes();
    /** Terms whose type no tree says: a diamond's, worked out here. */
    private final Set<PirTerm> kept = Collections.newSetFromMap(new IdentityHashMap<>());

    Typing(JavacFacts facts) {
        this.facts = facts;
    }

    /**
     * {@code tree}'s static type. A type JuLC has no representation for stops here: nothing can be decided on it. javac types
     * a conditional in an argument by its parameter, so one that reaches {@code Object} is typed by its operands, and a
     * {@code switch} by its results.
     */
    PirType of(ExpressionTree tree) {
        var type = facts.type(tree);
        if (isObject(type)) {
            if (tree instanceof ParenthesizedTree parenthesized) return of(parenthesized.getExpression());
            if (tree instanceof ConditionalExpressionTree conditional) {
                var operand = of(conditional.getTrueExpression());
                if (operand.equals(of(conditional.getFalseExpression()))) return operand;
            }
            if (tree instanceof SwitchExpressionTree nested) {
                var results = SwitchResults.of(nested);
                var result = of(results.getFirst());
                if (results.stream().allMatch(other -> of(other).equals(result))) return result;
            }
        }
        try {
            return facts.registry().resolveNamed(facts.valueType(type, tree));
        } catch (CompilerException e) {
            throw NotPorted.at(facts, tree, "an expression of type " + ArrayIdentityEquality.text(type));
        }
    }

    private boolean isObject(TypeMirror type) {
        return type.getKind() == TypeKind.DECLARED && facts.identity(facts.typeElement(type)).equals(JavacNames.OBJECT);
    }

    /** {@code lowered}'s type, for the expression {@code tree}: the one kept with it, or see the class's comment. */
    PirType of(ExpressionTree tree, PirTerm lowered) {
        if (kept.contains(lowered)) return ofVariable(lowered);
        var type = of(tree);
        return facts.registry().resolveNamed(type instanceof PirType.DataType ? termTypes.infer(lowered) : type);
    }

    /**
     * A variable's one type, given what javac says it holds and what the reference sees. They part where the reference takes
     * a decoded value for something that is not, and the type is then what it holds. Otherwise they are one representation,
     * and the reference's reading stands, so its term does.
     */
    static PirType holding(PirType held, PirType seen) {
        return isDecoded(held) && !isDecoded(seen) ? held : seen;
    }

    /** Whether a value of this type is not Data. A record, a sum and {@code Optional} are Data already. */
    static boolean isDecoded(PirType type) {
        return type instanceof PirType.IntegerType || type instanceof PirType.ByteStringType || type instanceof PirType.BoolType
                || type instanceof PirType.StringType || type instanceof PirType.ListType || type instanceof PirType.MapType;
    }

    /** A pair list is not encoded to Data. */
    void requireNoPairListEncoded(ExpressionTree tree, PirTerm lowered) {
        if (of(tree) instanceof PirType.DataType) requireNoPairListEncoded(facts.type(tree), of(tree, lowered), tree);
    }

    /** {@link #requireNoPairListEncoded}, of a value that holds {@code held} and that Java has as {@code java}: a {@code MapData} is a map. */
    void requireNoPairListEncoded(TypeMirror java, PirType held, Tree at) {
        boolean map = java.getKind() == TypeKind.DECLARED && facts.identity(facts.typeElement(java)).equals(JavacNames.PLUTUS_DATA + ".MapData");
        if (held instanceof PirType.MapType && !map) throw NotPorted.at(facts, at, "a pair list encoded to Data");
    }

    /** What {@code term} says of itself. */
    PirType ofTerm(PirTerm term) {
        return termTypes.infer(term);
    }

    /** The type {@code read}, a read of a variable, was bound with. */
    PirType ofVariable(PirTerm read) {
        return facts.registry().resolveNamed(termTypes.infer(read));
    }

    /** Keeps {@code type} with {@code term} (identity): a diamond's type is worked out here, and no tree says it. */
    PirTerm keep(PirTerm term, PirType type) {
        kept.add(term);
        termTypes.record(term, type);
        return term;
    }

    /**
     * What {@code read}, the result of a registry method, says of itself: the type the registry declares on the receiver
     * the reference sees, as the reference records it. A read's value is still decoded by javac's type, which is
     * {@link #of(ExpressionTree, PirTerm)}'s.
     */
    void recordRead(PirTerm read, PirType declared) {
        termTypes.record(read, declared);
    }

    /**
     * Keeps with a lowered {@code switch} the type its arms agree on, which the match alone does not say: read through what
     * the lowering wraps them in. Arms that differ but are all Data-shaped are Data, which is their encoding; arms that
     * otherwise differ have no type, and that is what encoding one rejects.
     */
    void recordSwitch(PirTerm term) {
        var leaves = new LinkedHashSet<PirType>();
        collectArmTypes(term, leaves);
        if (leaves.isEmpty()) return;
        if (leaves.size() == 1) {
            var type = leaves.iterator().next();
            if (!(type instanceof PirType.DataType)) termTypes.record(term, type);
        } else if (!leaves.stream().allMatch(Typing::isDataShaped)) {
            termTypes.record(term, PirHelpers.UNKNOWN);
        }
    }

    private void collectArmTypes(PirTerm term, LinkedHashSet<PirType> leaves) {
        switch (term) {
            case PirTerm.Error ignored -> {
            }
            case PirTerm.DataMatch match -> match.branches().forEach(branch -> collectArmTypes(branch.body(), leaves));
            case PirTerm.IntegerCase select -> select.branches().forEach(branch -> collectArmTypes(branch, leaves));
            case PirTerm.IfThenElse branch -> {
                collectArmTypes(branch.thenBranch(), leaves);
                collectArmTypes(branch.elseBranch(), leaves);
            }
            case PirTerm.Let let -> collectArmTypes(let.body(), leaves);
            case PirTerm.LetRec letRec -> collectArmTypes(letRec.body(), leaves);
            case PirTerm.PairMatch match -> collectArmTypes(match.body(), leaves);
            case PirTerm.ListMatch match -> {
                collectArmTypes(match.nilBranch(), leaves);
                collectArmTypes(match.consBranch(), leaves);
            }
            default -> leaves.add(facts.registry().resolveNamed(termTypes.infer(term)));
        }
    }

    private static boolean isDataShaped(PirType type) {
        return type instanceof PirType.DataType || type instanceof PirType.RecordType || type instanceof PirType.SumType
                || type instanceof PirType.OptionalType || type instanceof PirType.NamedTypeRef;
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
