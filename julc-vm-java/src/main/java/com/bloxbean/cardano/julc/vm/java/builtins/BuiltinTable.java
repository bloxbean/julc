package com.bloxbean.cardano.julc.vm.java.builtins;

import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;

import java.util.EnumMap;
import java.util.Map;

/**
 * Master registry mapping {@link DefaultFun} to its signature and runtime implementation.
 * <p>
 * Covers all V1/V2/V3 builtins. V4 builtins throw UnsupportedBuiltinException.
 * <p>
 * Use {@link #forLanguage(PlutusLanguage)} to get a version-gated view that only
 * includes builtins available in the specified Plutus language version.
 */
public final class BuiltinTable {

    private BuiltinTable() {}

    private record Entry(BuiltinSignature signature, BuiltinRuntime runtime) {}

    private static final Map<DefaultFun, Entry> TABLE = new EnumMap<>(DefaultFun.class);

    static {
        // === Integer (V1) ===
        reg(DefaultFun.AddInteger,              0, 2, IntegerBuiltins::addInteger);
        reg(DefaultFun.SubtractInteger,         0, 2, IntegerBuiltins::subtractInteger);
        reg(DefaultFun.MultiplyInteger,         0, 2, IntegerBuiltins::multiplyInteger);
        reg(DefaultFun.DivideInteger,           0, 2, IntegerBuiltins::divideInteger);
        reg(DefaultFun.QuotientInteger,         0, 2, IntegerBuiltins::quotientInteger);
        reg(DefaultFun.RemainderInteger,        0, 2, IntegerBuiltins::remainderInteger);
        reg(DefaultFun.ModInteger,              0, 2, IntegerBuiltins::modInteger);
        reg(DefaultFun.EqualsInteger,           0, 2, IntegerBuiltins::equalsInteger);
        reg(DefaultFun.LessThanInteger,         0, 2, IntegerBuiltins::lessThanInteger);
        reg(DefaultFun.LessThanEqualsInteger,   0, 2, IntegerBuiltins::lessThanEqualsInteger);

        // === ByteString (V1) ===
        reg(DefaultFun.AppendByteString,        0, 2, ByteStringBuiltins::appendByteString);
        reg(DefaultFun.ConsByteString,          0, 2, ByteStringBuiltins::consByteString);
        reg(DefaultFun.SliceByteString,         0, 3, ByteStringBuiltins::sliceByteString);
        reg(DefaultFun.LengthOfByteString,      0, 1, ByteStringBuiltins::lengthOfByteString);
        reg(DefaultFun.IndexByteString,         0, 2, ByteStringBuiltins::indexByteString);
        reg(DefaultFun.EqualsByteString,        0, 2, ByteStringBuiltins::equalsByteString);
        reg(DefaultFun.LessThanByteString,      0, 2, ByteStringBuiltins::lessThanByteString);
        reg(DefaultFun.LessThanEqualsByteString,0, 2, ByteStringBuiltins::lessThanEqualsByteString);

        // === Crypto (V1) ===
        reg(DefaultFun.Sha2_256,                0, 1, CryptoBuiltins::sha2_256);
        reg(DefaultFun.Sha3_256,                0, 1, CryptoBuiltins::sha3_256);
        reg(DefaultFun.Blake2b_256,             0, 1, CryptoBuiltins::blake2b_256);
        reg(DefaultFun.VerifyEd25519Signature,  0, 3, CryptoBuiltins::verifyEd25519Signature);

        // === String (V1) ===
        reg(DefaultFun.AppendString,            0, 2, StringBuiltins::appendString);
        reg(DefaultFun.EqualsString,            0, 2, StringBuiltins::equalsString);
        reg(DefaultFun.EncodeUtf8,              0, 1, StringBuiltins::encodeUtf8);
        reg(DefaultFun.DecodeUtf8,              0, 1, StringBuiltins::decodeUtf8);

        // === Control (V1) ===
        reg(DefaultFun.IfThenElse,              1, 3, ControlBuiltins::ifThenElse);
        reg(DefaultFun.ChooseUnit,              1, 2, ControlBuiltins::chooseUnit);
        reg(DefaultFun.Trace,                   1, 2, ControlBuiltins::trace);

        // === Pair (V1) ===
        reg(DefaultFun.FstPair,                 2, 1, PairBuiltins::fstPair);
        reg(DefaultFun.SndPair,                 2, 1, PairBuiltins::sndPair);

        // === List (V1) ===
        reg(DefaultFun.ChooseList,              2, 3, ListBuiltins::chooseList);
        reg(DefaultFun.MkCons,                  1, 2, ListBuiltins::mkCons);
        reg(DefaultFun.HeadList,                1, 1, ListBuiltins::headList);
        reg(DefaultFun.TailList,                1, 1, ListBuiltins::tailList);
        reg(DefaultFun.NullList,                1, 1, ListBuiltins::nullList);

        // === Data (V1) ===
        reg(DefaultFun.ChooseData,              1, 6, DataBuiltins::chooseData);
        reg(DefaultFun.ConstrData,              0, 2, DataBuiltins::constrData);
        reg(DefaultFun.MapData,                 0, 1, DataBuiltins::mapData);
        reg(DefaultFun.ListData,                0, 1, DataBuiltins::listData);
        reg(DefaultFun.IData,                   0, 1, DataBuiltins::iData);
        reg(DefaultFun.BData,                   0, 1, DataBuiltins::bData);
        reg(DefaultFun.UnConstrData,            0, 1, DataBuiltins::unConstrData);
        reg(DefaultFun.UnMapData,               0, 1, DataBuiltins::unMapData);
        reg(DefaultFun.UnListData,              0, 1, DataBuiltins::unListData);
        reg(DefaultFun.UnIData,                 0, 1, DataBuiltins::unIData);
        reg(DefaultFun.UnBData,                 0, 1, DataBuiltins::unBData);
        reg(DefaultFun.EqualsData,              0, 2, DataBuiltins::equalsData);
        reg(DefaultFun.MkPairData,              0, 2, DataBuiltins::mkPairData);
        reg(DefaultFun.MkNilData,               0, 1, DataBuiltins::mkNilData);
        reg(DefaultFun.MkNilPairData,           0, 1, DataBuiltins::mkNilPairData);

        // === V2 ===
        reg(DefaultFun.SerialiseData,           0, 1, DataBuiltins::serialiseData);
        reg(DefaultFun.VerifyEcdsaSecp256k1Signature,   0, 3, CryptoBuiltins::verifyEcdsaSecp256k1Signature);
        reg(DefaultFun.VerifySchnorrSecp256k1Signature,  0, 3, CryptoBuiltins::verifySchnorrSecp256k1Signature);

        // === V3 BLS12-381 ===
        reg(DefaultFun.Bls12_381_G1_add,        0, 2, Bls12381Stubs::g1Add);
        reg(DefaultFun.Bls12_381_G1_neg,        0, 1, Bls12381Stubs::g1Neg);
        reg(DefaultFun.Bls12_381_G1_scalarMul,  0, 2, Bls12381Stubs::g1ScalarMul);
        reg(DefaultFun.Bls12_381_G1_equal,      0, 2, Bls12381Stubs::g1Equal);
        reg(DefaultFun.Bls12_381_G1_compress,   0, 1, Bls12381Stubs::g1Compress);
        reg(DefaultFun.Bls12_381_G1_uncompress, 0, 1, Bls12381Stubs::g1Uncompress);
        reg(DefaultFun.Bls12_381_G1_hashToGroup,0, 2, Bls12381Stubs::g1HashToGroup);
        reg(DefaultFun.Bls12_381_G2_add,        0, 2, Bls12381Stubs::g2Add);
        reg(DefaultFun.Bls12_381_G2_neg,        0, 1, Bls12381Stubs::g2Neg);
        reg(DefaultFun.Bls12_381_G2_scalarMul,  0, 2, Bls12381Stubs::g2ScalarMul);
        reg(DefaultFun.Bls12_381_G2_equal,      0, 2, Bls12381Stubs::g2Equal);
        reg(DefaultFun.Bls12_381_G2_compress,   0, 1, Bls12381Stubs::g2Compress);
        reg(DefaultFun.Bls12_381_G2_uncompress, 0, 1, Bls12381Stubs::g2Uncompress);
        reg(DefaultFun.Bls12_381_G2_hashToGroup,0, 2, Bls12381Stubs::g2HashToGroup);
        reg(DefaultFun.Bls12_381_millerLoop,    0, 2, Bls12381Stubs::millerLoop);
        reg(DefaultFun.Bls12_381_mulMlResult,   0, 2, Bls12381Stubs::mulMlResult);
        reg(DefaultFun.Bls12_381_finalVerify,   0, 2, Bls12381Stubs::finalVerify);

        // === V3 Crypto ===
        reg(DefaultFun.Keccak_256,              0, 1, CryptoBuiltins::keccak_256);
        reg(DefaultFun.Blake2b_224,             0, 1, CryptoBuiltins::blake2b_224);

        // === V3 Integer/ByteString conversions (CIP-121) ===
        reg(DefaultFun.IntegerToByteString,     0, 3, BitwiseBuiltins::integerToByteString);
        reg(DefaultFun.ByteStringToInteger,     0, 2, BitwiseBuiltins::byteStringToInteger);

        // === V3 Bitwise (CIP-122) ===
        reg(DefaultFun.AndByteString,           0, 3, BitwiseBuiltins::andByteString);
        reg(DefaultFun.OrByteString,            0, 3, BitwiseBuiltins::orByteString);
        reg(DefaultFun.XorByteString,           0, 3, BitwiseBuiltins::xorByteString);
        reg(DefaultFun.ComplementByteString,    0, 1, BitwiseBuiltins::complementByteString);
        reg(DefaultFun.ReadBit,                 0, 2, BitwiseBuiltins::readBit);
        reg(DefaultFun.WriteBits,               0, 3, BitwiseBuiltins::writeBits);
        reg(DefaultFun.ReplicateByte,           0, 2, BitwiseBuiltins::replicateByte);

        // === V3 Shift/Rotate (CIP-123) ===
        reg(DefaultFun.ShiftByteString,         0, 2, BitwiseBuiltins::shiftByteString);
        reg(DefaultFun.RotateByteString,        0, 2, BitwiseBuiltins::rotateByteString);
        reg(DefaultFun.CountSetBits,            0, 1, BitwiseBuiltins::countSetBits);
        reg(DefaultFun.FindFirstSetBit,         0, 1, BitwiseBuiltins::findFirstSetBit);

        // === V3 RIPEMD-160 (CIP-127) ===
        reg(DefaultFun.Ripemd_160,              0, 1, CryptoBuiltins::ripemd_160);

        // === V3 Modular exponentiation (CIP-109) ===
        reg(DefaultFun.ExpModInteger,           0, 3, IntegerBuiltins::expModInteger);

        // === V4 stubs — not implemented ===
        // These are not registered; looking them up will produce an error.
    }

    private static void reg(DefaultFun fun, int forces, int arity, BuiltinRuntime runtime) {
        TABLE.put(fun, new Entry(new BuiltinSignature(forces, arity), runtime));
    }

    /**
     * Get the signature for a builtin function.
     *
     * @throws BuiltinException if the builtin is not registered
     */
    public static BuiltinSignature getSignature(DefaultFun fun) {
        var entry = TABLE.get(fun);
        if (entry == null) {
            throw new UnsupportedBuiltinException("Builtin not supported: " + fun);
        }
        return entry.signature();
    }

    /**
     * Get the runtime for a builtin function.
     *
     * @throws BuiltinException if the builtin is not registered
     */
    public static BuiltinRuntime getRuntime(DefaultFun fun) {
        var entry = TABLE.get(fun);
        if (entry == null) {
            throw new UnsupportedBuiltinException("Builtin not supported: " + fun);
        }
        return entry.runtime();
    }

    /**
     * Check if a builtin is registered.
     */
    public static boolean isSupported(DefaultFun fun) {
        return TABLE.containsKey(fun);
    }

    /**
     * Create a version-gated builtin table for the specified Plutus language version.
     * <p>
     * Only builtins available in the given language version are included.
     * Looking up a builtin from a newer version throws {@link UnsupportedBuiltinException}.
     *
     * @param language the Plutus language version
     * @return a version-gated view of the builtin table
     */
    public static VersionedBuiltinTable forLanguage(PlutusLanguage language) {
        int langVersion = languageToVersion(language);
        Map<DefaultFun, Entry> filtered = new EnumMap<>(DefaultFun.class);
        for (var entry : TABLE.entrySet()) {
            if (entry.getKey().isAvailableIn(langVersion)) {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }
        return new VersionedBuiltinTable(filtered, language);
    }

    private static int languageToVersion(PlutusLanguage language) {
        return switch (language) {
            case PLUTUS_V1 -> 1;
            case PLUTUS_V2 -> 2;
            case PLUTUS_V3 -> 3;
        };
    }

    /**
     * A version-gated view of the builtin table that only includes builtins
     * available in a specific Plutus language version.
     */
    public static final class VersionedBuiltinTable {
        private final Map<DefaultFun, Entry> table;
        private final PlutusLanguage language;

        private VersionedBuiltinTable(Map<DefaultFun, Entry> table, PlutusLanguage language) {
            this.table = table;
            this.language = language;
        }

        public BuiltinSignature getSignature(DefaultFun fun) {
            var entry = table.get(fun);
            if (entry == null) {
                if (TABLE.containsKey(fun)) {
                    throw new UnsupportedBuiltinException(
                            "Builtin " + fun + " is not available in " + language +
                            " (requires " + fun.minLanguageVersion() + "+)");
                }
                throw new UnsupportedBuiltinException("Builtin not supported: " + fun);
            }
            return entry.signature();
        }

        public BuiltinRuntime getRuntime(DefaultFun fun) {
            var entry = table.get(fun);
            if (entry == null) {
                if (TABLE.containsKey(fun)) {
                    throw new UnsupportedBuiltinException(
                            "Builtin " + fun + " is not available in " + language +
                            " (requires " + fun.minLanguageVersion() + "+)");
                }
                throw new UnsupportedBuiltinException("Builtin not supported: " + fun);
            }
            return entry.runtime();
        }

        public boolean isSupported(DefaultFun fun) {
            return table.containsKey(fun);
        }

        public PlutusLanguage language() {
            return language;
        }
    }
}
