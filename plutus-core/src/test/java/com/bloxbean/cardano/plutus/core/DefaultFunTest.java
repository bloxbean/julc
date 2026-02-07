package com.bloxbean.cardano.plutus.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class DefaultFunTest {

    @Test
    void totalEnumCount() {
        assertEquals(102, DefaultFun.values().length);
    }

    // --- FLAT code spot checks (V1) ---

    @ParameterizedTest
    @CsvSource({
            "AddInteger, 0",
            "SubtractInteger, 1",
            "MultiplyInteger, 2",
            "DivideInteger, 3",
            "QuotientInteger, 4",
            "RemainderInteger, 5",
            "ModInteger, 6",
            "EqualsInteger, 7",
            "LessThanInteger, 8",
            "LessThanEqualsInteger, 9"
    })
    void integerOps(String name, int expectedCode) {
        assertEquals(expectedCode, DefaultFun.valueOf(name).flatCode());
    }

    @ParameterizedTest
    @CsvSource({
            "AppendByteString, 10",
            "ConsByteString, 11",
            "SliceByteString, 12",
            "LengthOfByteString, 13",
            "IndexByteString, 14",
            "EqualsByteString, 15",
            "LessThanByteString, 16",
            "LessThanEqualsByteString, 17"
    })
    void byteStringOps(String name, int expectedCode) {
        assertEquals(expectedCode, DefaultFun.valueOf(name).flatCode());
    }

    @ParameterizedTest
    @CsvSource({
            "Sha2_256, 18",
            "Sha3_256, 19",
            "Blake2b_256, 20",
            "VerifyEd25519Signature, 21"
    })
    void cryptoOps(String name, int expectedCode) {
        assertEquals(expectedCode, DefaultFun.valueOf(name).flatCode());
    }

    @ParameterizedTest
    @CsvSource({
            "AppendString, 22",
            "EqualsString, 23",
            "EncodeUtf8, 24",
            "DecodeUtf8, 25"
    })
    void stringOps(String name, int expectedCode) {
        assertEquals(expectedCode, DefaultFun.valueOf(name).flatCode());
    }

    @ParameterizedTest
    @CsvSource({
            "IfThenElse, 26",
            "ChooseUnit, 27",
            "Trace, 28"
    })
    void controlOps(String name, int expectedCode) {
        assertEquals(expectedCode, DefaultFun.valueOf(name).flatCode());
    }

    @ParameterizedTest
    @CsvSource({
            "ChooseData, 36",
            "ConstrData, 37",
            "MapData, 38",
            "ListData, 39",
            "IData, 40",
            "BData, 41",
            "UnConstrData, 42",
            "UnMapData, 43",
            "UnListData, 44",
            "UnIData, 45",
            "UnBData, 46",
            "EqualsData, 47",
            "MkPairData, 48",
            "MkNilData, 49",
            "MkNilPairData, 50"
    })
    void dataOps(String name, int expectedCode) {
        assertEquals(expectedCode, DefaultFun.valueOf(name).flatCode());
    }

    // --- V2 additions ---

    @ParameterizedTest
    @CsvSource({
            "SerialiseData, 51",
            "VerifyEcdsaSecp256k1Signature, 52",
            "VerifySchnorrSecp256k1Signature, 53"
    })
    void v2Additions(String name, int expectedCode) {
        assertEquals(expectedCode, DefaultFun.valueOf(name).flatCode());
    }

    // --- V3 BLS12-381 ---

    @ParameterizedTest
    @CsvSource({
            "Bls12_381_G1_add, 54",
            "Bls12_381_G1_neg, 55",
            "Bls12_381_G1_scalarMul, 56",
            "Bls12_381_G1_equal, 57",
            "Bls12_381_G1_compress, 58",
            "Bls12_381_G1_uncompress, 59",
            "Bls12_381_G1_hashToGroup, 60",
            "Bls12_381_G2_add, 61",
            "Bls12_381_G2_neg, 62",
            "Bls12_381_G2_scalarMul, 63",
            "Bls12_381_G2_equal, 64",
            "Bls12_381_G2_compress, 65",
            "Bls12_381_G2_uncompress, 66",
            "Bls12_381_G2_hashToGroup, 67",
            "Bls12_381_millerLoop, 68",
            "Bls12_381_mulMlResult, 69",
            "Bls12_381_finalVerify, 70"
    })
    void bls12_381Ops(String name, int expectedCode) {
        assertEquals(expectedCode, DefaultFun.valueOf(name).flatCode());
    }

    // --- V3 bitwise and hash ---

    @ParameterizedTest
    @CsvSource({
            "Keccak_256, 71",
            "Blake2b_224, 72",
            "IntegerToByteString, 73",
            "ByteStringToInteger, 74",
            "AndByteString, 75",
            "OrByteString, 76",
            "XorByteString, 77",
            "ComplementByteString, 78",
            "ReadBit, 79",
            "WriteBits, 80",
            "ReplicateByte, 81",
            "ShiftByteString, 82",
            "RotateByteString, 83",
            "CountSetBits, 84",
            "FindFirstSetBit, 85",
            "Ripemd_160, 86",
            "ExpModInteger, 87"
    })
    void v3BitwiseAndHash(String name, int expectedCode) {
        assertEquals(expectedCode, DefaultFun.valueOf(name).flatCode());
    }

    // --- V4 additions ---

    @ParameterizedTest
    @CsvSource({
            "DropList, 88",
            "LengthOfArray, 89",
            "ListToArray, 90",
            "IndexArray, 91",
            "Bls12_381_G1_multiScalarMul, 92",
            "Bls12_381_G2_multiScalarMul, 93",
            "InsertCoin, 94",
            "LookupCoin, 95",
            "UnionValue, 96",
            "ValueContains, 97",
            "ValueData, 98",
            "UnValueData, 99",
            "ScaleValue, 100",
            "MultiIndexArray, 101"
    })
    void v4Additions(String name, int expectedCode) {
        assertEquals(expectedCode, DefaultFun.valueOf(name).flatCode());
    }

    // --- fromFlatCode ---

    @Test
    void fromFlatCodeRoundTrip() {
        for (DefaultFun fun : DefaultFun.values()) {
            assertEquals(fun, DefaultFun.fromFlatCode(fun.flatCode()),
                    "Round-trip failed for " + fun.name());
        }
    }

    @Test
    void fromFlatCodeFirst() {
        assertEquals(DefaultFun.AddInteger, DefaultFun.fromFlatCode(0));
    }

    @Test
    void fromFlatCodeLast() {
        assertEquals(DefaultFun.MultiIndexArray, DefaultFun.fromFlatCode(101));
    }

    @Test
    void fromFlatCodeInvalidNegative() {
        assertThrows(IllegalArgumentException.class, () -> DefaultFun.fromFlatCode(-1));
    }

    @Test
    void fromFlatCodeInvalidTooLarge() {
        assertThrows(IllegalArgumentException.class, () -> DefaultFun.fromFlatCode(200));
    }

    // --- Unique FLAT codes ---

    @Test
    void allFlatCodesAreUnique() {
        var codes = new java.util.HashSet<java.lang.Integer>();
        for (DefaultFun fun : DefaultFun.values()) {
            assertTrue(codes.add(fun.flatCode()),
                    "Duplicate FLAT code " + fun.flatCode() + " for " + fun.name());
        }
    }

    // --- Contiguous from 0 to 101 ---

    @Test
    void flatCodesAreContiguous() {
        // All codes from 0..101 should be assigned
        for (int i = 0; i <= 101; i++) {
            assertNotNull(DefaultFun.fromFlatCode(i), "Missing FLAT code: " + i);
        }
    }
}
