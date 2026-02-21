package com.bloxbean.cardano.julc.decompiler.typing;

import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.decompiler.hir.HirType;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Type signatures for all Plutus builtins.
 * Used by the type inference engine to propagate type information.
 */
public final class BuiltinTypeRules {

    private BuiltinTypeRules() {}

    /**
     * A builtin type signature: parameter types and return type.
     */
    public record BuiltinSig(List<HirType> paramTypes, HirType returnType) {
        public BuiltinSig { paramTypes = List.copyOf(paramTypes); }
    }

    private static final Map<DefaultFun, BuiltinSig> SIGNATURES = new EnumMap<>(DefaultFun.class);

    static {
        var INT = HirType.INTEGER;
        var BS = HirType.BYTE_STRING;
        var STR = HirType.STRING;
        var BOOL = HirType.BOOL;
        var UNIT = HirType.UNIT;
        var DATA = HirType.DATA;
        var UNK = HirType.UNKNOWN;

        // Integer operations
        sig(DefaultFun.AddInteger, List.of(INT, INT), INT);
        sig(DefaultFun.SubtractInteger, List.of(INT, INT), INT);
        sig(DefaultFun.MultiplyInteger, List.of(INT, INT), INT);
        sig(DefaultFun.DivideInteger, List.of(INT, INT), INT);
        sig(DefaultFun.QuotientInteger, List.of(INT, INT), INT);
        sig(DefaultFun.RemainderInteger, List.of(INT, INT), INT);
        sig(DefaultFun.ModInteger, List.of(INT, INT), INT);
        sig(DefaultFun.EqualsInteger, List.of(INT, INT), BOOL);
        sig(DefaultFun.LessThanInteger, List.of(INT, INT), BOOL);
        sig(DefaultFun.LessThanEqualsInteger, List.of(INT, INT), BOOL);

        // ByteString operations
        sig(DefaultFun.AppendByteString, List.of(BS, BS), BS);
        sig(DefaultFun.ConsByteString, List.of(INT, BS), BS);
        sig(DefaultFun.SliceByteString, List.of(INT, INT, BS), BS);
        sig(DefaultFun.LengthOfByteString, List.of(BS), INT);
        sig(DefaultFun.IndexByteString, List.of(BS, INT), INT);
        sig(DefaultFun.EqualsByteString, List.of(BS, BS), BOOL);
        sig(DefaultFun.LessThanByteString, List.of(BS, BS), BOOL);
        sig(DefaultFun.LessThanEqualsByteString, List.of(BS, BS), BOOL);

        // Crypto
        sig(DefaultFun.Sha2_256, List.of(BS), BS);
        sig(DefaultFun.Sha3_256, List.of(BS), BS);
        sig(DefaultFun.Blake2b_256, List.of(BS), BS);
        sig(DefaultFun.VerifyEd25519Signature, List.of(BS, BS, BS), BOOL);
        sig(DefaultFun.Keccak_256, List.of(BS), BS);
        sig(DefaultFun.Blake2b_224, List.of(BS), BS);
        sig(DefaultFun.Ripemd_160, List.of(BS), BS);

        // String operations
        sig(DefaultFun.AppendString, List.of(STR, STR), STR);
        sig(DefaultFun.EqualsString, List.of(STR, STR), BOOL);
        sig(DefaultFun.EncodeUtf8, List.of(STR), BS);
        sig(DefaultFun.DecodeUtf8, List.of(BS), STR);

        // Control flow
        sig(DefaultFun.IfThenElse, List.of(BOOL, UNK, UNK), UNK);
        sig(DefaultFun.ChooseUnit, List.of(UNIT, UNK), UNK);
        sig(DefaultFun.Trace, List.of(STR, UNK), UNK);

        // Pair operations
        sig(DefaultFun.FstPair, List.of(UNK), UNK);
        sig(DefaultFun.SndPair, List.of(UNK), UNK);
        sig(DefaultFun.MkPairData, List.of(DATA, DATA), UNK);

        // List operations
        sig(DefaultFun.ChooseList, List.of(UNK, UNK, UNK), UNK);
        sig(DefaultFun.MkCons, List.of(UNK, UNK), UNK);
        sig(DefaultFun.HeadList, List.of(UNK), UNK);
        sig(DefaultFun.TailList, List.of(UNK), UNK);
        sig(DefaultFun.NullList, List.of(UNK), BOOL);
        sig(DefaultFun.MkNilData, List.of(UNIT), UNK);
        sig(DefaultFun.MkNilPairData, List.of(UNIT), UNK);

        // Data operations
        sig(DefaultFun.ConstrData, List.of(INT, UNK), DATA);
        sig(DefaultFun.MapData, List.of(UNK), DATA);
        sig(DefaultFun.ListData, List.of(UNK), DATA);
        sig(DefaultFun.IData, List.of(INT), DATA);
        sig(DefaultFun.BData, List.of(BS), DATA);
        sig(DefaultFun.UnConstrData, List.of(DATA), UNK);
        sig(DefaultFun.UnMapData, List.of(DATA), UNK);
        sig(DefaultFun.UnListData, List.of(DATA), UNK);
        sig(DefaultFun.UnIData, List.of(DATA), INT);
        sig(DefaultFun.UnBData, List.of(DATA), BS);
        sig(DefaultFun.EqualsData, List.of(DATA, DATA), BOOL);
        sig(DefaultFun.SerialiseData, List.of(DATA), BS);
        sig(DefaultFun.ChooseData, List.of(DATA, UNK, UNK, UNK, UNK, UNK), UNK);

        // SECP256k1
        sig(DefaultFun.VerifyEcdsaSecp256k1Signature, List.of(BS, BS, BS), BOOL);
        sig(DefaultFun.VerifySchnorrSecp256k1Signature, List.of(BS, BS, BS), BOOL);

        // Integer/ByteString conversion
        sig(DefaultFun.IntegerToByteString, List.of(BOOL, INT, INT), BS);
        sig(DefaultFun.ByteStringToInteger, List.of(BOOL, BS), INT);

        // Bitwise operations
        sig(DefaultFun.AndByteString, List.of(BOOL, BS, BS), BS);
        sig(DefaultFun.OrByteString, List.of(BOOL, BS, BS), BS);
        sig(DefaultFun.XorByteString, List.of(BOOL, BS, BS), BS);
        sig(DefaultFun.ComplementByteString, List.of(BS), BS);
        sig(DefaultFun.ReadBit, List.of(BS, INT), BOOL);
        sig(DefaultFun.WriteBits, List.of(BS, UNK, UNK), BS);
        sig(DefaultFun.ReplicateByte, List.of(INT, INT), BS);
        sig(DefaultFun.ShiftByteString, List.of(BS, INT), BS);
        sig(DefaultFun.RotateByteString, List.of(BS, INT), BS);
        sig(DefaultFun.CountSetBits, List.of(BS), INT);
        sig(DefaultFun.FindFirstSetBit, List.of(BS), INT);

        // Modular exponentiation
        sig(DefaultFun.ExpModInteger, List.of(INT, INT, INT), INT);
    }

    private static void sig(DefaultFun fun, List<HirType> params, HirType ret) {
        SIGNATURES.put(fun, new BuiltinSig(params, ret));
    }

    /**
     * Get the type signature for a builtin. Returns null for unknown builtins.
     */
    public static BuiltinSig getSignature(DefaultFun fun) {
        return SIGNATURES.get(fun);
    }

    /**
     * Get the return type for a builtin.
     */
    public static HirType returnType(DefaultFun fun) {
        var sig = SIGNATURES.get(fun);
        return sig != null ? sig.returnType() : HirType.UNKNOWN;
    }
}
