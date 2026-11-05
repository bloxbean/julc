package org.julclang.compiler.frontend.javac.lower;

import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerTypeDiagnostics;
import org.julclang.compiler.error.DiagnosticCodes;
import org.julclang.compiler.frontend.javac.ArrayIdentityEquality;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.frontend.javac.JavacNames;
import org.julclang.compiler.frontend.javac.SwitchResults;
import org.julclang.compiler.frontend.javac.subset.ArmTypes;
import org.julclang.compiler.pir.PirHelpers;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.pir.TermTypes;
import org.julclang.core.DefaultFun;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The PIR types of expressions, in the two steps the reference reads them: javac's static type, and where that says only
 * Data, what the lowered term says of itself ({@link TermTypes}). The second step is not a fallback to a guess: a Data
 * source type does not say what a builtin leaves on the stack, and {@code Builtins.tailList} is declared
 * {@code PlutusData.ListData} and leaves a list.
 */
final class Typing {

    /**
     * {@code JULC0012}: an expression of a type with no representation. A distinct class, as a caller that can read a value
     * another way (a variable by its binder, a component as Data) takes this one rejection and no other.
     */
    static final class Unrepresented extends CompilerException {
        Unrepresented(CompilerException rejection) {
            super(rejection.diagnostics());
        }
    }

    private final JavacFacts facts;
    private final TermTypes termTypes = new TermTypes();
    /** What each expression was lowered to, by identity: a choice reads its values' forms here. */
    private final Map<Tree, PirTerm> lowered = new IdentityHashMap<>();
    /** Terms whose type no tree says: a diamond's, worked out here. */
    private final Set<PirTerm> kept = Collections.newSetFromMap(new IdentityHashMap<>());

    Typing(JavacFacts facts) {
        this.facts = facts;
    }

    /**
     * {@code tree}'s static type. javac types a conditional or a {@code switch} that is an argument of an {@code Object}
     * parameter {@code Object}; its arms have one type (ArmTypes rejects any other, ADR-068 M5), and it is that type. A record
     * component read through a raw type, {@code t.first()} of a raw {@code Tuple2}, is the field of the record's
     * representation, Data, as the reference reads it. Anything else with no representation is {@code JULC0012}.
     */
    PirType of(ExpressionTree tree) {
        var type = facts.type(tree);
        try {
            return facts.registry().resolveNamed(facts.valueType(type, tree));
        } catch (CompilerException e) {
            // byte, short and char have no representation (JULC0124): a narrowing of a literal folds, and is held where it widens.
            if (type.getKind().isPrimitive()) {
                var bare = tree;
                while (bare instanceof ParenthesizedTree parenthesized) bare = parenthesized.getExpression();
                if (bare instanceof TypeCastTree cast && Casts.narrowedLiteral(type.getKind(), cast.getExpression()) != null) {
                    return new PirType.IntegerType();
                }
                throw facts.unsupportedPrimitive(type, tree);
            }
            var read = isObject(type) ? unrepresented(tree) : null;
            if (read != null) return read;
            // The reference's code for a value of a type it cannot resolve (I2a).
            throw new Unrepresented(facts.rejection(DiagnosticCodes.TYPE_RESOLUTION_FAILED, tree,
                    "an expression of type " + ArrayIdentityEquality.text(type)));
        }
    }

    private PirType unrepresented(ExpressionTree tree) {
        return switch (tree) {
            case ParenthesizedTree parenthesized -> of(parenthesized.getExpression());
            case ConditionalExpressionTree conditional -> arms(conditional, List.of(conditional.getTrueExpression(), conditional.getFalseExpression()));
            case SwitchExpressionTree nested -> arms(nested, SwitchResults.of(nested));
            case MethodInvocationTree call -> rawComponent(call);
            default -> null;
        };
    }

    /** The arms' one type. A method lowered on its own has not met the scan, so the rule is held here too. */
    private PirType arms(ExpressionTree tree, List<? extends ExpressionTree> arms) {
        ArmTypes.requireOneType(facts, tree);
        var types = new LinkedHashSet<PirType>();
        for (var arm : arms) types.add(of(arm));
        return types.size() == 1 ? types.iterator().next() : null;
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

    /**
     * ADR-068 M5a (review 2, F1 and F2): the values of one choice, the arms of a conditional or a {@code switch} or the returns
     * of a lambda's block, are held one way on-chain, by their lowered terms' types. Java's type does not tell it: a
     * {@code ListData} is a builtin's list from {@code unListData} and Data from {@code toPlutusData()}, and nothing encodes an
     * arm by itself, so a reader of the other form fails where the JVM returns. {@code JULC0012} at the choice otherwise.
     */
    void requireOneRepresentation(Tree where, String what, List<PirType> values) {
        String first = null;
        for (var value : values) {
            if (value == null) continue;
            String held = representation(facts.registry().resolveNamed(value));
            if (first == null) {
                first = held;
            } else if (!first.equals(held)) {
                throw facts.rejection(DiagnosticCodes.TYPE_RESOLUTION_FAILED, where, "the type of " + what + " whose values are held as "
                        + first + " and as " + held + " on-chain. Give them one representation");
            }
        }
    }

    /**
     * What an arm {@code tree}, lowered to {@code term}, is held as: {@link #of(ExpressionTree, PirTerm)}, or the term's own
     * type. Null where that is not known: a {@code PlutusData} that no Data-making builtin or record gives, such as a
     * parameter or a method's result, can hold a builtin's list as well (r14), so it differs from neither.
     */
    PirType held(ExpressionTree tree, PirTerm term) {
        PirType type;
        try {
            type = of(tree, term);
        } catch (Unrepresented e) {
            type = ofTerm(term);
        }
        if (type instanceof PirType.DataType && isView(facts.type(tree)) && !makesData(term)) return null;
        return type;
    }

    private static final Set<DefaultFun> DATA_MAKERS = Set.of(DefaultFun.IData, DefaultFun.BData, DefaultFun.ConstrData,
            DefaultFun.ListData, DefaultFun.MapData);

    /** Whether {@code term} is a Data-making builtin's application, whose value is Data whatever its arguments. */
    private static boolean makesData(PirTerm term) {
        while (term instanceof PirTerm.App app) term = app.function();
        return term instanceof PirTerm.Builtin builtin && DATA_MAKERS.contains(builtin.fun());
    }

    /** Whether {@code type} is {@code PlutusData} or one of its views, which say nothing of what a builtin left. */
    private boolean isView(TypeMirror type) {
        if (type.getKind() != TypeKind.DECLARED) return false;
        String identity = facts.identity(facts.typeElement(type));
        return identity.equals(JavacNames.PLUTUS_DATA) || identity.startsWith(JavacNames.PLUTUS_DATA + ".");
    }

    /** Keeps {@code term} as what {@code tree} lowered to, so a choice can read its values' forms; returns it. */
    PirTerm lowered(ExpressionTree tree, PirTerm term) {
        lowered.put(tree, term);
        return term;
    }

    /** What the values {@code trees} were lowered to are held as; a tree not lowered (a throw's arm) is not a value. */
    List<PirType> held(List<? extends ExpressionTree> trees) {
        var types = new ArrayList<PirType>();
        for (var tree : trees) {
            var term = lowered.get(tree);
            if (term != null) types.add(held(tree, term));
        }
        return types;
    }

    /** The expressions of {@code body}'s {@code return}s, not those of a lambda or a class inside it. */
    static List<ExpressionTree> returns(Tree body) {
        return JavacFacts.returnValues(body);
    }

    /** The on-chain form of a value of {@code type}. Records, sums, {@code Optional} and Data are all a Data value. */
    static String representation(PirType type) {
        return switch (type) {
            case PirType.IntegerType ignored -> "an integer";
            case PirType.ByteStringType ignored -> "a byte string";
            case PirType.StringType ignored -> "a string";
            case PirType.BoolType ignored -> "a boolean";
            case PirType.UnitType ignored -> "unit";
            case PirType.ListType ignored -> "a builtin list";
            case PirType.MapType ignored -> "a builtin list of pairs";
            case PirType.PairType ignored -> "a builtin pair";
            default -> isDataShaped(type) ? "Data" : type.toString();
        };
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
