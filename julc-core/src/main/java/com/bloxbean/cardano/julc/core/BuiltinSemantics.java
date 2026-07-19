package com.bloxbean.cardano.julc.core;

import java.math.BigInteger;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Static semantic facts about Plutus builtin functions, used by compiler
 * optimization passes to reason about term behavior without evaluating.
 * <p>
 * For each builtin this table records:
 * <ul>
 *   <li><b>type arity</b> — the number of {@code Force} applications the builtin
 *       requires before it accepts value arguments (0, 1 or 2). Forcing a builtin
 *       more times than its type arity is a runtime error; applying a value
 *       argument while forces remain is a runtime error.</li>
 *   <li><b>value arity</b> — the number of value arguments required to execute.
 *       Under CEK semantics an application below value arity is a <i>value</i>
 *       (argument checking is deferred to saturation).</li>
 *   <li><b>totality</b> — whether execution is guaranteed to succeed without
 *       emitting logs when the arguments have the declared {@link ArgType}s.
 *       {@code Trace} is deliberately <i>not</i> total: it always succeeds but
 *       emits an observable log message. Builtins whose runtime has any error
 *       path on arguments of the declared types (division by zero, empty list,
 *       out-of-range index, malformed encodings, ...) are not total. A
 *       polymorphic builtin may be declared total at a <i>monomorphic</i>
 *       signature (e.g. {@code MkCons} at {@code (DATA, LIST_DATA)}) when that
 *       instantiation cannot fail. When in doubt a builtin is classified as not
 *       total — that only costs optimization opportunity, never soundness.</li>
 *   <li><b>result type</b> — for total builtins, the {@link ArgType} of the
 *       result when the arguments have the declared types, or null when the
 *       result type is not expressible (polymorphic results). This lets an
 *       optimizer certify nested applications, e.g. constant Data literals
 *       built from {@code constrData}/{@code mkCons}/{@code mkNilData}.</li>
 * </ul>
 * These facts must stay in sync with the VM's builtin table
 * ({@code julc-vm-java} {@code BuiltinTable}); a cross-check test in that module
 * guards against drift and executes every claimed-total builtin on sample
 * arguments of the declared types.
 */
public final class BuiltinSemantics {

    private BuiltinSemantics() {}

    /**
     * Argument/result type tags for total builtins. A saturated application of
     * a total builtin is guaranteed error-free only when each argument matches
     * its tag. {@code ANY} marks polymorphic positions that the builtin returns
     * or discards without inspecting (branch arguments of {@code ifThenElse}).
     * {@code CONSTR_TAG} is an integer restricted to [0, Integer.MAX_VALUE] —
     * the range where {@code constrData} cannot fail.
     */
    public enum ArgType {
        INTEGER, CONSTR_TAG, BYTESTRING, STRING, BOOL, UNIT, DATA,
        LIST, LIST_DATA, LIST_PAIR_DATA, PAIR, ANY
    }

    /**
     * Semantic signature of a builtin.
     *
     * @param typeArity  number of {@code Force} applications required before value args
     * @param valueArity number of value arguments required to execute
     * @param total      true iff execution cannot fail and cannot emit logs when
     *                   applied to arguments of the declared types
     * @param argTypes   declared argument types; empty when totality is not claimed
     * @param resultType result type when total and expressible, else null
     */
    public record Sig(int typeArity, int valueArity, boolean total,
                      List<ArgType> argTypes, ArgType resultType) {
        public Sig {
            argTypes = List.copyOf(argTypes);
            if (total && argTypes.size() != valueArity) {
                throw new IllegalArgumentException("total builtin must declare all arg types");
            }
        }
    }

    private static final Map<DefaultFun, Sig> TABLE = new EnumMap<>(DefaultFun.class);

    private static void total(DefaultFun fun, int typeArity, ArgType resultType, ArgType... argTypes) {
        TABLE.put(fun, new Sig(typeArity, argTypes.length, true, List.of(argTypes), resultType));
    }

    private static void partial(DefaultFun fun, int typeArity, int valueArity) {
        TABLE.put(fun, new Sig(typeArity, valueArity, false, List.of(), null));
    }

    static {
        var I = ArgType.INTEGER;
        var B = ArgType.BYTESTRING;
        var S = ArgType.STRING;
        var BOOL = ArgType.BOOL;
        var U = ArgType.UNIT;
        var D = ArgType.DATA;
        var L = ArgType.LIST;
        var LD = ArgType.LIST_DATA;
        var LPD = ArgType.LIST_PAIR_DATA;
        var P = ArgType.PAIR;
        var TAG = ArgType.CONSTR_TAG;
        var ANY = ArgType.ANY;

        // === Integer (V1) ===
        total(DefaultFun.AddInteger, 0, I, I, I);
        total(DefaultFun.SubtractInteger, 0, I, I, I);
        total(DefaultFun.MultiplyInteger, 0, I, I, I);
        partial(DefaultFun.DivideInteger, 0, 2);        // divisor 0
        partial(DefaultFun.QuotientInteger, 0, 2);      // divisor 0
        partial(DefaultFun.RemainderInteger, 0, 2);     // divisor 0
        partial(DefaultFun.ModInteger, 0, 2);           // divisor 0
        total(DefaultFun.EqualsInteger, 0, BOOL, I, I);
        total(DefaultFun.LessThanInteger, 0, BOOL, I, I);
        total(DefaultFun.LessThanEqualsInteger, 0, BOOL, I, I);

        // === ByteString (V1) ===
        total(DefaultFun.AppendByteString, 0, B, B, B);
        partial(DefaultFun.ConsByteString, 0, 2);       // byte out of [0,255]
        total(DefaultFun.SliceByteString, 0, B, I, I, B);  // indices clamp
        total(DefaultFun.LengthOfByteString, 0, I, B);
        partial(DefaultFun.IndexByteString, 0, 2);      // index out of bounds
        total(DefaultFun.EqualsByteString, 0, BOOL, B, B);
        total(DefaultFun.LessThanByteString, 0, BOOL, B, B);
        total(DefaultFun.LessThanEqualsByteString, 0, BOOL, B, B);

        // === Crypto (V1) ===
        total(DefaultFun.Sha2_256, 0, B, B);
        total(DefaultFun.Sha3_256, 0, B, B);
        total(DefaultFun.Blake2b_256, 0, B, B);
        partial(DefaultFun.VerifyEd25519Signature, 0, 3);  // malformed key/signature

        // === String (V1) ===
        total(DefaultFun.AppendString, 0, S, S, S);
        total(DefaultFun.EqualsString, 0, BOOL, S, S);
        total(DefaultFun.EncodeUtf8, 0, B, S);
        partial(DefaultFun.DecodeUtf8, 0, 1);           // invalid UTF-8

        // === Control (V1) ===
        total(DefaultFun.IfThenElse, 1, null, BOOL, ANY, ANY);
        total(DefaultFun.ChooseUnit, 1, null, U, ANY);
        partial(DefaultFun.Trace, 1, 2);                // total, but emits a log

        // === Pair (V1) ===
        total(DefaultFun.FstPair, 2, null, P);
        total(DefaultFun.SndPair, 2, null, P);

        // === List (V1) ===
        total(DefaultFun.ChooseList, 2, null, L, ANY, ANY);
        // MkCons is polymorphic; declared total at the monomorphic Data-list
        // signature, where the element type always matches the list type.
        total(DefaultFun.MkCons, 1, LD, D, LD);
        partial(DefaultFun.HeadList, 1, 1);             // empty list
        partial(DefaultFun.TailList, 1, 1);             // empty list
        total(DefaultFun.NullList, 1, BOOL, L);

        // === Data (V1) ===
        total(DefaultFun.ChooseData, 1, null, D, ANY, ANY, ANY, ANY, ANY);
        total(DefaultFun.ConstrData, 0, D, TAG, LD);    // tag must fit in int
        total(DefaultFun.MapData, 0, D, LPD);
        total(DefaultFun.ListData, 0, D, LD);
        total(DefaultFun.IData, 0, D, I);
        total(DefaultFun.BData, 0, D, B);
        partial(DefaultFun.UnConstrData, 0, 1);         // wrong Data variant
        partial(DefaultFun.UnMapData, 0, 1);            // wrong Data variant
        partial(DefaultFun.UnListData, 0, 1);           // wrong Data variant
        partial(DefaultFun.UnIData, 0, 1);              // wrong Data variant
        partial(DefaultFun.UnBData, 0, 1);              // wrong Data variant
        total(DefaultFun.EqualsData, 0, BOOL, D, D);
        total(DefaultFun.MkPairData, 0, P, D, D);
        total(DefaultFun.MkNilData, 0, LD, U);
        total(DefaultFun.MkNilPairData, 0, LPD, U);

        // === V2 ===
        partial(DefaultFun.SerialiseData, 0, 1);        // runtime has a CBOR error path
        partial(DefaultFun.VerifyEcdsaSecp256k1Signature, 0, 3);
        partial(DefaultFun.VerifySchnorrSecp256k1Signature, 0, 3);

        // === V3 BLS12-381 (conservative: no constant literals reach these) ===
        partial(DefaultFun.Bls12_381_G1_add, 0, 2);
        partial(DefaultFun.Bls12_381_G1_neg, 0, 1);
        partial(DefaultFun.Bls12_381_G1_scalarMul, 0, 2);
        partial(DefaultFun.Bls12_381_G1_equal, 0, 2);
        partial(DefaultFun.Bls12_381_G1_compress, 0, 1);
        partial(DefaultFun.Bls12_381_G1_uncompress, 0, 1);
        partial(DefaultFun.Bls12_381_G1_hashToGroup, 0, 2);
        partial(DefaultFun.Bls12_381_G2_add, 0, 2);
        partial(DefaultFun.Bls12_381_G2_neg, 0, 1);
        partial(DefaultFun.Bls12_381_G2_scalarMul, 0, 2);
        partial(DefaultFun.Bls12_381_G2_equal, 0, 2);
        partial(DefaultFun.Bls12_381_G2_compress, 0, 1);
        partial(DefaultFun.Bls12_381_G2_uncompress, 0, 1);
        partial(DefaultFun.Bls12_381_G2_hashToGroup, 0, 2);
        partial(DefaultFun.Bls12_381_millerLoop, 0, 2);
        partial(DefaultFun.Bls12_381_mulMlResult, 0, 2);
        partial(DefaultFun.Bls12_381_finalVerify, 0, 2);

        // === V3 Crypto ===
        total(DefaultFun.Keccak_256, 0, B, B);
        total(DefaultFun.Blake2b_224, 0, B, B);
        total(DefaultFun.Ripemd_160, 0, B, B);

        // === V3 Integer/ByteString conversions (CIP-121) ===
        partial(DefaultFun.IntegerToByteString, 0, 3);  // negative/oversized inputs
        total(DefaultFun.ByteStringToInteger, 0, I, BOOL, B);

        // === V3 Bitwise (CIP-122) ===
        total(DefaultFun.AndByteString, 0, B, BOOL, B, B);
        total(DefaultFun.OrByteString, 0, B, BOOL, B, B);
        total(DefaultFun.XorByteString, 0, B, BOOL, B, B);
        total(DefaultFun.ComplementByteString, 0, B, B);
        partial(DefaultFun.ReadBit, 0, 2);              // index out of range
        partial(DefaultFun.WriteBits, 0, 3);            // index out of range
        partial(DefaultFun.ReplicateByte, 0, 2);        // negative/oversized length

        // === V3 Shift/Rotate (CIP-123) ===
        total(DefaultFun.ShiftByteString, 0, B, B, I);
        total(DefaultFun.RotateByteString, 0, B, B, I);
        total(DefaultFun.CountSetBits, 0, I, B);
        total(DefaultFun.FindFirstSetBit, 0, I, B);

        // === V3 Modular exponentiation (CIP-109) ===
        partial(DefaultFun.ExpModInteger, 0, 3);        // non-invertible cases

        // === PV11 Batch 6 (conservative until semantics are locked in) ===
        partial(DefaultFun.DropList, 1, 2);
        partial(DefaultFun.LengthOfArray, 1, 1);
        partial(DefaultFun.ListToArray, 1, 1);
        partial(DefaultFun.IndexArray, 1, 2);
        partial(DefaultFun.MultiIndexArray, 1, 2);
        partial(DefaultFun.Bls12_381_G1_multiScalarMul, 0, 2);
        partial(DefaultFun.Bls12_381_G2_multiScalarMul, 0, 2);
        partial(DefaultFun.InsertCoin, 0, 4);
        partial(DefaultFun.LookupCoin, 0, 3);
        partial(DefaultFun.UnionValue, 0, 2);
        partial(DefaultFun.ValueContains, 0, 2);
        partial(DefaultFun.ValueData, 0, 1);
        partial(DefaultFun.UnValueData, 0, 1);
        partial(DefaultFun.ScaleValue, 0, 2);
    }

    /**
     * Look up the semantic signature of a builtin.
     *
     * @return the signature, or {@code null} if the builtin is unknown to this
     *         table — callers must treat unknown builtins as unsafe (not values,
     *         not total)
     */
    public static Sig find(DefaultFun fun) {
        return TABLE.get(fun);
    }

    private static final BigInteger MAX_INT = BigInteger.valueOf(Integer.MAX_VALUE);

    /**
     * Check whether a constant matches an {@link ArgType} tag.
     * {@code ANY} is not handled here (it accepts non-constant values too);
     * callers decide how to treat polymorphic positions.
     * <p>
     * {@code LIST_DATA}/{@code LIST_PAIR_DATA} validate the list <i>contents</i>,
     * not just the declared element type: {@link Constant.ListConst} does not
     * enforce that its values match {@code elemType()}, and the builtins that
     * consume these tags ({@code listData}, {@code mapData}, {@code mkCons})
     * reject mismatched elements at runtime — trusting the declared type alone
     * would certify an erroring application as pure. Plain {@code LIST} needs no
     * content check: its consumers ({@code nullList}, {@code chooseList}) never
     * inspect elements.
     */
    public static boolean constantMatches(Constant c, ArgType t) {
        return switch (t) {
            case INTEGER -> c instanceof Constant.IntegerConst;
            case CONSTR_TAG -> c instanceof Constant.IntegerConst(var v)
                    && v.signum() >= 0 && v.compareTo(MAX_INT) <= 0;
            case BYTESTRING -> c instanceof Constant.ByteStringConst;
            case STRING -> c instanceof Constant.StringConst;
            case BOOL -> c instanceof Constant.BoolConst;
            case UNIT -> c instanceof Constant.UnitConst;
            case DATA -> c instanceof Constant.DataConst;
            case LIST -> c instanceof Constant.ListConst;
            case LIST_DATA -> c instanceof Constant.ListConst lc
                    && lc.elemType().equals(DefaultUni.DATA)
                    && lc.values().stream().allMatch(v -> v instanceof Constant.DataConst);
            case LIST_PAIR_DATA -> c instanceof Constant.ListConst lc
                    && lc.elemType().equals(DefaultUni.pairOf(DefaultUni.DATA, DefaultUni.DATA))
                    && lc.values().stream().allMatch(v -> v instanceof Constant.PairConst pc
                            && pc.first() instanceof Constant.DataConst
                            && pc.second() instanceof Constant.DataConst);
            case PAIR -> c instanceof Constant.PairConst;
            case ANY -> true;
        };
    }

    /**
     * The most specific {@link ArgType} tag of a constant, or null for
     * constants with no tag (BLS elements, arrays, values).
     * <p>
     * A list whose contents do not match its declared element type tags as
     * plain {@code LIST}: list-ness is still true (safe for {@code nullList}/
     * {@code chooseList}), but the data-typed claims are not — tagging it
     * {@code LIST_DATA} would let the mismatch certify through paths that
     * bypass {@link #constantMatches} (e.g. lazy-if branch certification).
     */
    public static ArgType constantType(Constant c) {
        return switch (c) {
            case Constant.IntegerConst ignored -> ArgType.INTEGER;
            case Constant.ByteStringConst ignored -> ArgType.BYTESTRING;
            case Constant.StringConst ignored -> ArgType.STRING;
            case Constant.BoolConst ignored -> ArgType.BOOL;
            case Constant.UnitConst ignored -> ArgType.UNIT;
            case Constant.DataConst ignored -> ArgType.DATA;
            case Constant.ListConst lc -> constantMatches(lc, ArgType.LIST_DATA)
                    ? ArgType.LIST_DATA
                    : constantMatches(lc, ArgType.LIST_PAIR_DATA)
                            ? ArgType.LIST_PAIR_DATA
                            : ArgType.LIST;
            case Constant.PairConst ignored -> ArgType.PAIR;
            default -> null;
        };
    }

    /**
     * Check whether a value of certified type {@code actual} satisfies the
     * declared type {@code expected}. Exact match plus the safe widenings:
     * any list satisfies {@code LIST}, and a {@code CONSTR_TAG} is an integer.
     */
    public static boolean typeSatisfies(ArgType actual, ArgType expected) {
        if (actual == null) return false;
        if (actual == expected) return true;
        return switch (expected) {
            case LIST -> actual == ArgType.LIST_DATA || actual == ArgType.LIST_PAIR_DATA;
            case INTEGER -> actual == ArgType.CONSTR_TAG;
            case ANY -> true;
            default -> false;
        };
    }
}
