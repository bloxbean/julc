package org.julclang.compiler.frontend.javac.lower;

import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
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

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.IntersectionType;
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
     * {@code tree}'s static type. A type JuLC has no representation for, {@code Object}, {@code java.lang.Record} or an
     * intersection, is read from what the expression can be ({@link #unrepresented}); anything else with no representation
     * stops here: nothing can be decided on it.
     */
    PirType of(ExpressionTree tree) {
        var type = facts.type(tree);
        try {
            return facts.registry().resolveNamed(facts.valueType(type, tree));
        } catch (CompilerException e) {
            var read = hasNoRepresentation(type) ? unrepresented(tree, type) : null;
            if (read != null) return read;
            throw NotPorted.at(facts, tree, "an expression of type " + ArrayIdentityEquality.text(type));
        }
    }

    /**
     * The type of an expression whose static type has no representation, or null:
     * <ul>
     *   <li>an intersection, such as {@code Record & S} for {@code c ? new S.A() : new S.B()}, is the one bound that has a
     *       representation, as Java's value is a value of each bound; with none, or more than one, it is read as below;</li>
     *   <li>a conditional or a {@code switch}, which javac types {@code Object} as an argument of an {@code Object} parameter,
     *       or {@code Record} between two records, is what its arms are: their one type; Data where they differ and are all
     *       Data-shaped, which is their encoding, as the reference types a {@code switch}; and otherwise
     *       {@link PirHelpers#UNKNOWN}, which no encoding takes ({@code JULC0012}), as the reference says of a
     *       {@code switch}. A conditional is held to that rule too, so its else branch is never read as its then branch;</li>
     *   <li>a record component read through a raw type, {@code t.first()} of a raw {@code Tuple2}, is the field's type in
     *       the record's representation, Data, as the reference reads it.</li>
     * </ul>
     */
    private PirType unrepresented(ExpressionTree tree, TypeMirror type) {
        if (type instanceof IntersectionType intersection) {
            var bounds = new LinkedHashSet<PirType>();
            for (var bound : intersection.getBounds()) {
                if (isObject(bound) || isRecord(bound)) continue;
                try {
                    bounds.add(facts.registry().resolveNamed(facts.valueType(bound, tree)));
                } catch (CompilerException noRepresentation) {
                    // Number, Comparable or Serializable says nothing of the value's representation.
                }
            }
            if (bounds.size() == 1) return bounds.iterator().next();
        }
        return switch (tree) {
            case ParenthesizedTree parenthesized -> of(parenthesized.getExpression());
            case ConditionalExpressionTree conditional -> arms(List.of(conditional.getTrueExpression(), conditional.getFalseExpression()));
            case SwitchExpressionTree nested -> arms(SwitchResults.of(nested));
            case MethodInvocationTree call -> rawComponent(call);
            default -> null;
        };
    }

    private PirType arms(List<? extends ExpressionTree> arms) {
        var types = new LinkedHashSet<PirType>();
        for (var arm : arms) types.add(of(arm));
        if (types.size() == 1) return types.iterator().next();
        return types.stream().allMatch(Typing::isDataShaped) ? new PirType.DataType() : PirHelpers.UNKNOWN;
    }

    /** A record's component read through a raw type: the field of the record's representation, or null. */
    private PirType rawComponent(MethodInvocationTree call) {
        if (!call.getArguments().isEmpty() || !(call.getMethodSelect() instanceof MemberSelectTree select)) return null;
        if (!(facts.element(select) instanceof ExecutableElement method)
                || method.getEnclosingElement().getKind() != ElementKind.RECORD) {
            return null;
        }
        var record = (TypeElement) method.getEnclosingElement();
        boolean component = record.getRecordComponents().stream()
                .anyMatch(c -> c.getAccessor() != null && c.getAccessor().equals(method));
        if (!component || !(of(select.getExpression()) instanceof PirType.RecordType fields)) return null;
        String name = method.getSimpleName().toString();
        return fields.fields().stream().filter(f -> f.name().equals(name)).map(PirType.Field::type).findFirst().orElse(null);
    }

    private boolean hasNoRepresentation(TypeMirror type) {
        return isObject(type) || isRecord(type) || type.getKind() == TypeKind.INTERSECTION;
    }

    private boolean isObject(TypeMirror type) {
        return type.getKind() == TypeKind.DECLARED && facts.identity(facts.typeElement(type)).equals(JavacNames.OBJECT);
    }

    private boolean isRecord(TypeMirror type) {
        return type.getKind() == TypeKind.DECLARED && facts.identity(facts.typeElement(type)).equals("java.lang.Record");
    }

    /** {@code lowered}'s type, for the expression {@code tree}: the one kept with it, or see the class's comment. */
    PirType of(ExpressionTree tree, PirTerm lowered) {
        if (kept.contains(lowered)) return ofVariable(lowered);
        PirType type;
        try {
            type = of(tree);
        } catch (NotPorted stop) {
            // A variable of a type with no representation, var x = c ? new R1(a) : new R2(a), holds what its initializer was
            // typed, and its binder says that.
            if (lowered instanceof PirTerm.Var && hasNoRepresentation(facts.type(tree))) return ofVariable(lowered);
            throw stop;
        }
        if (type == PirHelpers.UNKNOWN) {
            // The term alone would say its first arm's type: keep the unknown with it, for what encodes it.
            termTypes.record(lowered, type);
            return type;
        }
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
