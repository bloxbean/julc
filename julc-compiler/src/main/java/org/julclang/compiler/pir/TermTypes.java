package org.julclang.compiler.pir;

import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.julclang.core.DefaultUni;

import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * What a lowered term says of its own type, whichever frontend built it: a typed dispatch records the type it declares,
 * and otherwise the term's structure is read. Both frontends use this where a source type says only Data, because a Data
 * source type does not say what a builtin left on the stack ({@code Builtins.tailList} is declared {@code ListData} and
 * leaves a list). Moved unchanged from {@code TypeInferenceHelper} (ADR-068).
 */
public final class TermTypes {

    /** Lowered {@code JulcArray.of(...)} terms and the source types of their elements (ADR-046). */
    private final IdentityHashMap<PirTerm, List<PirType>> arrayLiterals = new IdentityHashMap<>();
    /**
     * Terms the generator produced by a typed dispatch, with the type the registry declares for
     * that dispatch: what a chained access on an expression scope is typed by, so that
     * {@code JulcArray.of(JulcList.of(1)).get(0).get(0)} keeps the nested element type through
     * the intermediate access (the structural fallback would see {@code UnListData} and say
     * {@code JulcList<PlutusData>}).
     */
    private final IdentityHashMap<PirTerm, PirType> termTypes = new IdentityHashMap<>();

    /** Record the type the registry declares for a term produced by a typed dispatch (identity). */
    public void record(PirTerm term, PirType type) {
        termTypes.put(term, type);
    }

    /** Record the source element types of a lowered {@code JulcArray.of(...)} term (identity). */
    /** {@code copy} stands for {@code original}, a node placed again (ADR-069 D2): it says of itself what the original says. */
    public void copied(PirTerm original, PirTerm copy) {
        var type = termTypes.get(original);
        if (type != null) termTypes.put(copy, type);
        var elements = arrayLiterals.get(original);
        if (elements != null) arrayLiterals.put(copy, elements);
    }

    public void recordArrayLiteral(PirTerm term, List<PirType> elementTypes) {
        arrayLiterals.put(term, List.copyOf(elementTypes));
    }

    /** The element types recorded for an array literal, if {@code term} is one. */
    public Optional<List<PirType>> arrayLiteralElementTypes(PirTerm term) {
        return Optional.ofNullable(arrayLiterals.get(term));
    }

    /**
     * Infer the PirType of a PIR term by structural analysis.
     * <p>
     * A loop, not recursion (issue #327): most forms are typed by one sub-term (a {@code let}'s body, a conditional's
     * branch), and a call of an unknown function by its function's type, which is unwound once that type is known.
     */
    public PirType infer(PirTerm term) {
        int calls = 0;
        PirType type;
        while (true) {
            var step = inferStep(term);
            if (step.done()) {
                type = step.type();
                break;
            }
            if (step.call()) calls++;
            term = step.next();
        }
        // Each pending call of a function of unknown type is typed by its function's return type, else Data.
        for (; calls > 0; calls--) {
            type = type instanceof PirType.FunType ft ? ft.returnType() : new PirType.DataType();
        }
        return type;
    }

    /**
     * One step of {@link #infer}: a type, or the sub-term {@code next} whose type is the answer, or, when {@code call},
     * the function of a call, whose type's return type is the answer.
     */
    private record Step(boolean done, PirType type, PirTerm next, boolean call) {
        static Step of(PirType type) {
            return new Step(true, type, null, false);
        }

        static Step typeOf(PirTerm next) {
            return new Step(false, null, next, false);
        }

        static Step returnTypeOf(PirTerm function) {
            return new Step(false, null, function, true);
        }
    }

    private Step inferStep(PirTerm term) {
        // A term produced by a typed dispatch carries the type the registry declared for it.
        var recorded = termTypes.get(term);
        if (recorded != null) return Step.of(recorded);
        if (term instanceof PirTerm.Const c) {
            return Step.of(switch (c.value()) {
                case Constant.IntegerConst _ -> new PirType.IntegerType();
                case Constant.BoolConst _ -> new PirType.BoolType();
                case Constant.StringConst _ -> new PirType.StringType();
                case Constant.ByteStringConst _ -> new PirType.ByteStringType();
                case Constant.UnitConst _ -> new PirType.UnitType();
                case Constant.ValueConst _ -> new PirType.NativeValueType();
                case Constant.Bls12_381_G1Element _ -> new PirType.NativeG1Type();
                case Constant.Bls12_381_G2Element _ -> new PirType.NativeG2Type();
                case Constant.Bls12_381_MlResult _ -> new PirType.NativeMlResultType();
                case Constant.ListConst list when list.elemType() instanceof DefaultUni.Integer ->
                        new PirType.NativeListType(new PirType.IntegerType());
                case Constant.ListConst list when list.elemType() instanceof DefaultUni.Bls12_381_G1_Element ->
                        new PirType.NativeListType(new PirType.NativeG1Type());
                case Constant.ListConst list when list.elemType() instanceof DefaultUni.Bls12_381_G2_Element ->
                        new PirType.NativeListType(new PirType.NativeG2Type());
                default -> new PirType.DataType();
            });
        }
        if (term instanceof PirTerm.App app) {
            // An array literal is typed by the source types of its elements (ADR-046).
            var elementTypes = arrayLiteralElementTypes(term);
            if (elementTypes.isPresent()) {
                var distinct = new LinkedHashSet<>(elementTypes.get());
                return Step.of(new PirType.ArrayType(distinct.size() == 1 ? distinct.iterator().next() : new PirType.DataType()));
            }
            PirTerm fn = app.function();
            if (fn instanceof PirTerm.Builtin b) {
                if (b.fun() == DefaultFun.FstPair
                        && app.argument() instanceof PirTerm.App argApp
                        && argApp.function() instanceof PirTerm.Builtin argB
                        && argB.fun() == DefaultFun.UnConstrData) {
                    return Step.of(new PirType.IntegerType());
                }
                return Step.of(builtinReturnType(b.fun()));
            }
            var root = fn;
            while (root instanceof PirTerm.App nested) {
                root = nested.function();
            }
            if (root instanceof PirTerm.Builtin builtin) {
                return Step.of(builtinReturnType(builtin.fun()));
            }
            return Step.returnTypeOf(fn);
        }
        if (term instanceof PirTerm.Var v) {
            return Step.of(v.type());
        }
        if (term instanceof PirTerm.PairMatch m) {
            return m.body() instanceof PirTerm.Error error ? Step.of(error.type()) : Step.typeOf(m.body());
        }
        if (term instanceof PirTerm.IntegerCase c) {
            // Every branch produces the same result type; prefer a non-Error branch.
            for (var branch : c.branches()) {
                if (!(branch instanceof PirTerm.Error)) return Step.typeOf(branch);
            }
            return Step.of(((PirTerm.Error) c.branches().getFirst()).type());
        }
        if (term instanceof PirTerm.ListMatch m) {
            // The guarded for-each producer uses a typed Error for unreachable nil.
            return m.nilBranch() instanceof PirTerm.Error error
                    ? Step.of(error.type()) : Step.typeOf(m.nilBranch());
        }
        if (term instanceof PirTerm.IfThenElse ite) {
            return Step.typeOf(ite.thenBranch());
        }
        if (term instanceof PirTerm.Let let) {
            return Step.typeOf(let.body());
        }
        if (term instanceof PirTerm.LetRec letRec) {
            return Step.typeOf(letRec.body());
        }
        return Step.of(new PirType.DataType());
    }

    /**
     * Infer the return type of a UPLC builtin function.
     */
    public static PirType builtinReturnType(DefaultFun fun) {
        return switch (fun) {
            case AddInteger, SubtractInteger, MultiplyInteger, DivideInteger,
                 QuotientInteger, RemainderInteger, ModInteger,
                 LengthOfByteString, ByteStringToInteger, UnIData,
                 LengthOfArray, LookupCoin -> new PirType.IntegerType();
            case EqualsInteger, LessThanInteger, LessThanEqualsInteger,
                 EqualsByteString, LessThanByteString, LessThanEqualsByteString,
                 EqualsString, EqualsData, NullList,
                 Bls12_381_G1_equal, Bls12_381_G2_equal, Bls12_381_finalVerify,
                 ValueContains -> new PirType.BoolType();
            case AppendByteString, SliceByteString, ConsByteString,
                 Sha2_256, Sha3_256, Blake2b_256, EncodeUtf8, UnBData,
                 IntegerToByteString,
                 Bls12_381_G1_compress, Bls12_381_G2_compress -> new PirType.ByteStringType();
            case Bls12_381_G1_add, Bls12_381_G1_neg, Bls12_381_G1_scalarMul,
                 Bls12_381_G1_hashToGroup, Bls12_381_G1_uncompress,
                 Bls12_381_G1_multiScalarMul -> new PirType.NativeG1Type();
            case Bls12_381_G2_add, Bls12_381_G2_neg, Bls12_381_G2_scalarMul,
                 Bls12_381_G2_hashToGroup, Bls12_381_G2_uncompress,
                 Bls12_381_G2_multiScalarMul -> new PirType.NativeG2Type();
            case Bls12_381_millerLoop, Bls12_381_mulMlResult -> new PirType.NativeMlResultType();
            case AppendString, DecodeUtf8 -> new PirType.StringType();
            case InsertCoin, UnionValue, UnValueData, ScaleValue ->
                    new PirType.NativeValueType();
            case UnListData, TailList, MkCons, MkNilData,
                 DropList, MultiIndexArray -> new PirType.ListType(new PirType.DataType());
            case UnMapData, MkNilPairData -> new PirType.MapType(new PirType.DataType(), new PirType.DataType());
            case ListToArray -> new PirType.ArrayType(new PirType.DataType());
            default -> new PirType.DataType();
        };
    }
}
