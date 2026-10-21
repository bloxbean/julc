package org.julclang.compiler.backend;

import org.julclang.compiler.pir.PirType;
import org.julclang.core.DefaultFun;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Representation-level builtin signatures for the {@link PirVerifier} (ADR-059). Every
 * {@link DefaultFun} is either monomorphic (a fixed PIR signature), polymorphic (typed by a
 * dedicated rule in the verifier, which must see it fully applied) or unsupported in
 * producer PIR. A test anchors value and type arity to {@code BuiltinSemantics}.
 */
final class BuiltinTyping {
    private BuiltinTyping() {}

    /** A monomorphic builtin signature over canonical PIR representations. */
    record Signature(List<PirType> parameters, PirType result) {
        Signature {
            parameters = List.copyOf(parameters);
        }
    }

    static final PirType INT = new PirType.IntegerType();
    static final PirType BYTES = new PirType.ByteStringType();
    static final PirType STRING = new PirType.StringType();
    static final PirType BOOL = new PirType.BoolType();
    static final PirType UNIT = new PirType.UnitType();
    static final PirType DATA = new PirType.DataType();
    static final PirType G1 = new PirType.NativeG1Type();
    static final PirType G2 = new PirType.NativeG2Type();
    static final PirType ML = new PirType.NativeMlResultType();
    static final PirType VALUE = new PirType.NativeValueType();
    static final PirType DATA_LIST = new PirType.ListType(DATA);
    static final PirType DATA_PAIR = new PirType.PairType(DATA, DATA);
    static final PirType PAIR_LIST = new PirType.ListType(DATA_PAIR);
    static final PirType CONSTR_PAIR = new PirType.PairType(INT, DATA_LIST);
    static final PirType SCALARS = new PirType.NativeListType(INT);
    static final PirType G1_POINTS = new PirType.NativeListType(G1);
    static final PirType G2_POINTS = new PirType.NativeListType(G2);

    private static final Map<DefaultFun, Signature> MONOMORPHIC = new EnumMap<>(DefaultFun.class);
    private static final Map<DefaultFun, Integer> POLYMORPHIC_ARITY = new EnumMap<>(DefaultFun.class);
    /** Unreleased builtins that producer PIR must not use. */
    static final Set<DefaultFun> UNSUPPORTED = EnumSet.of(DefaultFun.MultiIndexArray);

    private static void sig(DefaultFun fun, PirType result, PirType... parameters) {
        if (MONOMORPHIC.put(fun, new Signature(List.of(parameters), result)) != null)
            throw new IllegalStateException("Duplicate builtin signature: " + fun);
    }

    private static void polymorphic(DefaultFun fun, int arity) {
        POLYMORPHIC_ARITY.put(fun, arity);
    }

    static {
        for (var fun : List.of(DefaultFun.AddInteger, DefaultFun.SubtractInteger,
                DefaultFun.MultiplyInteger, DefaultFun.DivideInteger, DefaultFun.QuotientInteger,
                DefaultFun.RemainderInteger, DefaultFun.ModInteger))
            sig(fun, INT, INT, INT);
        for (var fun : List.of(DefaultFun.EqualsInteger, DefaultFun.LessThanInteger,
                DefaultFun.LessThanEqualsInteger))
            sig(fun, BOOL, INT, INT);

        sig(DefaultFun.AppendByteString, BYTES, BYTES, BYTES);
        sig(DefaultFun.ConsByteString, BYTES, INT, BYTES);
        sig(DefaultFun.SliceByteString, BYTES, INT, INT, BYTES);
        sig(DefaultFun.LengthOfByteString, INT, BYTES);
        sig(DefaultFun.IndexByteString, INT, BYTES, INT);
        for (var fun : List.of(DefaultFun.EqualsByteString, DefaultFun.LessThanByteString,
                DefaultFun.LessThanEqualsByteString))
            sig(fun, BOOL, BYTES, BYTES);

        for (var fun : List.of(DefaultFun.Sha2_256, DefaultFun.Sha3_256, DefaultFun.Blake2b_256,
                DefaultFun.Keccak_256, DefaultFun.Blake2b_224, DefaultFun.Ripemd_160))
            sig(fun, BYTES, BYTES);
        for (var fun : List.of(DefaultFun.VerifyEd25519Signature,
                DefaultFun.VerifyEcdsaSecp256k1Signature, DefaultFun.VerifySchnorrSecp256k1Signature))
            sig(fun, BOOL, BYTES, BYTES, BYTES);

        sig(DefaultFun.AppendString, STRING, STRING, STRING);
        sig(DefaultFun.EqualsString, BOOL, STRING, STRING);
        sig(DefaultFun.EncodeUtf8, BYTES, STRING);
        sig(DefaultFun.DecodeUtf8, STRING, BYTES);

        sig(DefaultFun.ConstrData, DATA, INT, DATA_LIST);
        sig(DefaultFun.MapData, DATA, PAIR_LIST);
        sig(DefaultFun.ListData, DATA, DATA_LIST);
        sig(DefaultFun.IData, DATA, INT);
        sig(DefaultFun.BData, DATA, BYTES);
        sig(DefaultFun.UnConstrData, CONSTR_PAIR, DATA);
        sig(DefaultFun.UnMapData, PAIR_LIST, DATA);
        sig(DefaultFun.UnListData, DATA_LIST, DATA);
        sig(DefaultFun.UnIData, INT, DATA);
        sig(DefaultFun.UnBData, BYTES, DATA);
        sig(DefaultFun.EqualsData, BOOL, DATA, DATA);
        sig(DefaultFun.MkPairData, DATA_PAIR, DATA, DATA);
        sig(DefaultFun.MkNilData, DATA_LIST, UNIT);
        sig(DefaultFun.MkNilPairData, PAIR_LIST, UNIT);
        sig(DefaultFun.SerialiseData, BYTES, DATA);

        sig(DefaultFun.Bls12_381_G1_add, G1, G1, G1);
        sig(DefaultFun.Bls12_381_G1_neg, G1, G1);
        sig(DefaultFun.Bls12_381_G1_scalarMul, G1, INT, G1);
        sig(DefaultFun.Bls12_381_G1_equal, BOOL, G1, G1);
        sig(DefaultFun.Bls12_381_G1_compress, BYTES, G1);
        sig(DefaultFun.Bls12_381_G1_uncompress, G1, BYTES);
        sig(DefaultFun.Bls12_381_G1_hashToGroup, G1, BYTES, BYTES);
        sig(DefaultFun.Bls12_381_G2_add, G2, G2, G2);
        sig(DefaultFun.Bls12_381_G2_neg, G2, G2);
        sig(DefaultFun.Bls12_381_G2_scalarMul, G2, INT, G2);
        sig(DefaultFun.Bls12_381_G2_equal, BOOL, G2, G2);
        sig(DefaultFun.Bls12_381_G2_compress, BYTES, G2);
        sig(DefaultFun.Bls12_381_G2_uncompress, G2, BYTES);
        sig(DefaultFun.Bls12_381_G2_hashToGroup, G2, BYTES, BYTES);
        sig(DefaultFun.Bls12_381_millerLoop, ML, G1, G2);
        sig(DefaultFun.Bls12_381_mulMlResult, ML, ML, ML);
        sig(DefaultFun.Bls12_381_finalVerify, BOOL, ML, ML);
        sig(DefaultFun.Bls12_381_G1_multiScalarMul, G1, SCALARS, G1_POINTS);
        sig(DefaultFun.Bls12_381_G2_multiScalarMul, G2, SCALARS, G2_POINTS);

        sig(DefaultFun.IntegerToByteString, BYTES, BOOL, INT, INT);
        sig(DefaultFun.ByteStringToInteger, INT, BOOL, BYTES);
        for (var fun : List.of(DefaultFun.AndByteString, DefaultFun.OrByteString,
                DefaultFun.XorByteString))
            sig(fun, BYTES, BOOL, BYTES, BYTES);
        sig(DefaultFun.ComplementByteString, BYTES, BYTES);
        sig(DefaultFun.ReadBit, BOOL, BYTES, INT);
        sig(DefaultFun.WriteBits, BYTES, BYTES, SCALARS, BOOL);
        sig(DefaultFun.ReplicateByte, BYTES, INT, INT);
        sig(DefaultFun.ShiftByteString, BYTES, BYTES, INT);
        sig(DefaultFun.RotateByteString, BYTES, BYTES, INT);
        sig(DefaultFun.CountSetBits, INT, BYTES);
        sig(DefaultFun.FindFirstSetBit, INT, BYTES);
        sig(DefaultFun.ExpModInteger, INT, INT, INT, INT);

        sig(DefaultFun.InsertCoin, VALUE, BYTES, BYTES, INT, VALUE);
        sig(DefaultFun.LookupCoin, INT, BYTES, BYTES, VALUE);
        sig(DefaultFun.UnionValue, VALUE, VALUE, VALUE);
        sig(DefaultFun.ValueContains, BOOL, VALUE, VALUE);
        sig(DefaultFun.ValueData, DATA, VALUE);
        sig(DefaultFun.UnValueData, VALUE, DATA);
        sig(DefaultFun.ScaleValue, VALUE, INT, VALUE);

        polymorphic(DefaultFun.IfThenElse, 3);
        polymorphic(DefaultFun.ChooseUnit, 2);
        polymorphic(DefaultFun.Trace, 2);
        polymorphic(DefaultFun.FstPair, 1);
        polymorphic(DefaultFun.SndPair, 1);
        polymorphic(DefaultFun.ChooseList, 3);
        polymorphic(DefaultFun.MkCons, 2);
        polymorphic(DefaultFun.HeadList, 1);
        polymorphic(DefaultFun.TailList, 1);
        polymorphic(DefaultFun.NullList, 1);
        polymorphic(DefaultFun.ChooseData, 6);
        polymorphic(DefaultFun.DropList, 2);
        polymorphic(DefaultFun.LengthOfArray, 1);
        polymorphic(DefaultFun.ListToArray, 1);
        polymorphic(DefaultFun.IndexArray, 2);
        polymorphic(DefaultFun.MultiIndexArray, 2);
    }

    /** The fixed signature, or null for polymorphic and unsupported builtins. */
    static Signature monomorphic(DefaultFun fun) {
        return MONOMORPHIC.get(fun);
    }

    /** The value arity of a polymorphic builtin, or null when it is monomorphic. */
    static Integer polymorphicArity(DefaultFun fun) {
        return POLYMORPHIC_ARITY.get(fun);
    }
}
