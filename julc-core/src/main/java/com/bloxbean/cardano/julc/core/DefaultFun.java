package com.bloxbean.cardano.julc.core;

/**
 * All 102 Plutus Core built-in functions (V1 through V3).
 * <p>
 * Each entry has a 7-bit FLAT encoding code that must match the Plutus specification exactly.
 * The codes are contiguous from 0 to 101.
 * <p>
 * Organized by Plutus version:
 * <ul>
 *   <li>V1 (0-50): Integer, ByteString, String, Crypto, Control, Pair, List, Data ops</li>
 *   <li>V2 (51-53): SerialiseData, SECP256k1 signature verification</li>
 *   <li>V3 (54-87): BLS12-381, bitwise ops, hash functions, integer/bytestring conversion (CIP-381/121/122/123/127/109)</li>
 *   <li>V3 PV11 Batch 6 (88-101): Array ops, DropList, BLS MSM, MaryEraValue ops (CIP-156/158/153/133)</li>
 * </ul>
 */
public enum DefaultFun {

    // Integer operations (V1)
    AddInteger(0),
    SubtractInteger(1),
    MultiplyInteger(2),
    DivideInteger(3),
    QuotientInteger(4),
    RemainderInteger(5),
    ModInteger(6),
    EqualsInteger(7),
    LessThanInteger(8),
    LessThanEqualsInteger(9),

    // ByteString operations (V1)
    AppendByteString(10),
    ConsByteString(11),
    SliceByteString(12),
    LengthOfByteString(13),
    IndexByteString(14),
    EqualsByteString(15),
    LessThanByteString(16),
    LessThanEqualsByteString(17),

    // Cryptographic hash functions (V1)
    Sha2_256(18),
    Sha3_256(19),
    Blake2b_256(20),
    VerifyEd25519Signature(21),

    // String operations (V1)
    AppendString(22),
    EqualsString(23),
    EncodeUtf8(24),
    DecodeUtf8(25),

    // Control flow (V1)
    IfThenElse(26),
    ChooseUnit(27),

    // Tracing (V1)
    Trace(28),

    // Pair operations (V1)
    FstPair(29),
    SndPair(30),

    // List operations (V1)
    ChooseList(31),
    MkCons(32),
    HeadList(33),
    TailList(34),
    NullList(35),

    // Data type operations (V1)
    ChooseData(36),
    ConstrData(37),
    MapData(38),
    ListData(39),
    IData(40),
    BData(41),
    UnConstrData(42),
    UnMapData(43),
    UnListData(44),
    UnIData(45),
    UnBData(46),
    EqualsData(47),
    MkPairData(48),
    MkNilData(49),
    MkNilPairData(50),

    // V2 additions
    SerialiseData(51),
    VerifyEcdsaSecp256k1Signature(52),
    VerifySchnorrSecp256k1Signature(53),

    // BLS12-381 G1 operations (V3, CIP-381)
    Bls12_381_G1_add(54),
    Bls12_381_G1_neg(55),
    Bls12_381_G1_scalarMul(56),
    Bls12_381_G1_equal(57),
    Bls12_381_G1_compress(58),
    Bls12_381_G1_uncompress(59),
    Bls12_381_G1_hashToGroup(60),

    // BLS12-381 G2 operations (V3, CIP-381)
    Bls12_381_G2_add(61),
    Bls12_381_G2_neg(62),
    Bls12_381_G2_scalarMul(63),
    Bls12_381_G2_equal(64),
    Bls12_381_G2_compress(65),
    Bls12_381_G2_uncompress(66),
    Bls12_381_G2_hashToGroup(67),

    // BLS12-381 pairing operations (V3, CIP-381)
    Bls12_381_millerLoop(68),
    Bls12_381_mulMlResult(69),
    Bls12_381_finalVerify(70),

    // Additional hash functions (V3)
    Keccak_256(71),
    Blake2b_224(72),

    // Integer/ByteString conversions (V3, CIP-121)
    IntegerToByteString(73),
    ByteStringToInteger(74),

    // Bitwise logical operations (V3, CIP-122)
    AndByteString(75),
    OrByteString(76),
    XorByteString(77),
    ComplementByteString(78),
    ReadBit(79),
    WriteBits(80),
    ReplicateByte(81),

    // Bitwise shift/rotate operations (V3, CIP-123)
    ShiftByteString(82),
    RotateByteString(83),
    CountSetBits(84),
    FindFirstSetBit(85),

    // RIPEMD-160 hash (V3, CIP-127)
    Ripemd_160(86),

    // Modular exponentiation (V3, CIP-109)
    ExpModInteger(87),

    // List extensions (PV11 Batch 6, CIP-158)
    DropList(88),

    // Array operations (PV11 Batch 6, CIP-156)
    LengthOfArray(89),
    ListToArray(90),
    IndexArray(91),

    // BLS12-381 multi-scalar multiplication (PV11 Batch 6, CIP-133)
    Bls12_381_G1_multiScalarMul(92),
    Bls12_381_G2_multiScalarMul(93),

    // MaryEraValue operations (PV11 Batch 6, CIP-153)
    InsertCoin(94),
    LookupCoin(95),
    UnionValue(96),
    ValueContains(97),
    ValueData(98),
    UnValueData(99),
    ScaleValue(100),

    // Array multi-index (PV11 Batch 6, CIP-156)
    MultiIndexArray(101);

    private final int flatCode;

    DefaultFun(int flatCode) {
        this.flatCode = flatCode;
    }

    /** The 7-bit FLAT encoding code for this built-in function. */
    public int flatCode() {
        return flatCode;
    }

    /**
     * The minimum Plutus language version that includes this builtin.
     * <p>
     * Returns 1 for V1 builtins (codes 0-50), 2 for V2 (51-53),
     * 3 for V3 (54-101, includes PV11 Batch 6).
     */
    public int minLanguageVersion() {
        if (flatCode <= 50) return 1;
        if (flatCode <= 53) return 2;
        return 3;  // codes 54-101 all available in V3 (PV11 Batch 6)
    }

    /**
     * Check if this builtin is available in the given Plutus language version.
     *
     * @param languageVersion 1 for PlutusV1, 2 for PlutusV2, 3 for PlutusV3
     * @return true if the builtin is available in that version
     */
    public boolean isAvailableIn(int languageVersion) {
        return minLanguageVersion() <= languageVersion;
    }

    private static final DefaultFun[] BY_FLAT_CODE;

    static {
        int maxCode = 0;
        for (DefaultFun fun : values()) {
            maxCode = Math.max(maxCode, fun.flatCode);
        }
        BY_FLAT_CODE = new DefaultFun[maxCode + 1];
        for (DefaultFun fun : values()) {
            BY_FLAT_CODE[fun.flatCode] = fun;
        }
    }

    /**
     * Look up a built-in function by its FLAT encoding code.
     *
     * @param code the 7-bit FLAT code
     * @return the corresponding DefaultFun
     * @throws IllegalArgumentException if the code is invalid
     */
    public static DefaultFun fromFlatCode(int code) {
        if (code < 0 || code >= BY_FLAT_CODE.length || BY_FLAT_CODE[code] == null) {
            throw new IllegalArgumentException("Invalid builtin function FLAT code: " + code);
        }
        return BY_FLAT_CODE[code];
    }
}
