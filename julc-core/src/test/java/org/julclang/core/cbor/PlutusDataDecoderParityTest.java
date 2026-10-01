package org.julclang.core.cbor;

import org.julclang.core.Constant;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.core.Term;
import org.julclang.core.flat.UplcFlatDecoder;
import org.julclang.core.flat.UplcFlatEncoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * For a single CBOR item, the decoder accepts exactly what Plutus {@code decodeData}
 * (PlutusCore/Data.hs, the {@code Serialise Data} instance behind FLAT {@code data} constants)
 * accepts. Every verdict and value here was checked against that code (plutus 1.65.0.0)
 * running on cborg 0.2.8.0.
 */
class PlutusDataDecoderParityTest {
    private static final HexFormat HEX = HexFormat.of();
    private static final String ZEROS_64 = "00".repeat(64);
    private static final String ZEROS_65 = "00".repeat(65);
    private static final String BYTES_64 = "5840" + ZEROS_64;
    private static final String BYTES_65 = "5841" + ZEROS_65;

    static Stream<Arguments> accepted() {
        PlutusData zeros64 = PlutusData.bytes(new byte[64]);
        return Stream.of(
                Arguments.of(BYTES_64, zeros64),
                Arguments.of("5b0000000000000040" + ZEROS_64, zeros64),
                Arguments.of("5f" + BYTES_64 + BYTES_64 + "ff", PlutusData.bytes(new byte[128])),
                Arguments.of("5fff", PlutusData.bytes(new byte[0])),
                Arguments.of("c25840ff" + "00".repeat(63),
                        PlutusData.integer(new BigInteger("ff" + "00".repeat(63), 16))),
                Arguments.of("c3" + BYTES_64, PlutusData.integer(-1)),
                // An indefinite bignum payload is bounded per chunk, not in total.
                Arguments.of("c25f" + "5840" + "00".repeat(63) + "01" + BYTES_64 + "ff",
                        PlutusData.integer(new BigInteger("01" + ZEROS_64, 16))),
                Arguments.of("c240", PlutusData.integer(0)),
                Arguments.of("d87980", PlutusData.constr(0)),
                Arguments.of("d87f8101", PlutusData.constr(6, PlutusData.integer(1))),
                Arguments.of("d8799f01ff", PlutusData.constr(0, PlutusData.integer(1))),
                Arguments.of("d9007980", PlutusData.constr(0)),
                Arguments.of("db000000000000007980", PlutusData.constr(0)),
                Arguments.of("d9050080", PlutusData.constr(7)),
                Arguments.of("d9057880", PlutusData.constr(127)),
                Arguments.of("d86682188080", PlutusData.constr(128)),
                Arguments.of("d8669f0080ff", PlutusData.constr(0)),
                Arguments.of("d86682009fff", PlutusData.constr(0)),
                Arguments.of("d866821bffffffffffffffff80", new PlutusData.ConstrData(
                        new BigInteger("18446744073709551615"), List.of())),
                Arguments.of("9f0102ff", PlutusData.list(PlutusData.integer(1), PlutusData.integer(2))),
                Arguments.of("9b00000000000000020102",
                        PlutusData.list(PlutusData.integer(1), PlutusData.integer(2))),
                Arguments.of("1817", PlutusData.integer(23)),
                Arguments.of("3bffffffffffffffff", PlutusData.integer(BigInteger.ONE.shiftLeft(64).negate())));
    }

    @ParameterizedTest
    @MethodSource("accepted")
    void acceptsWhatPlutusAccepts(String hex, PlutusData expected) {
        assertEquals(expected, PlutusDataCborDecoder.decode(HEX.parseHex(hex)));
    }

    static Stream<String> rejected() {
        return Stream.of(
                    // ByteString exceeds 64 bytes: definite, in an indefinite chunk, and as a bignum payload.
                    BYTES_65,
                    "5b0000000000000041" + ZEROS_65,
                    "5f" + BYTES_65 + "ff",
                    "c2" + BYTES_65,
                    "c3" + BYTES_65,
                    "c25f" + BYTES_65 + "ff",
                    // Indefinite byte string chunks must be definite byte strings.
                    "5f5f4101ffff",
                    "5fc14101ff",
                    "5f6161ff",
                    // Tags 2 and 3 are bignums only in the headers c2/c3, and wrap a byte string.
                    "d8024101",
                    "d900024101",
                    "d8034101",
                    "c201",
                    "c2c24101",
                    "c2c14101",
                    // Unrecognized tags.
                    "c100",
                    "c000",
                    "d8184101",
                    "d81e820102",
                    "d87880",
                    "d88080",
                    "d904ff80",
                    "d9057980",
                    "dbffffffffffffffff80",
                "d903e8a10102",
                    // Constructor fields and the general form's members are untagged arrays and words.
                    "d879d87a80",
                    "d879c180",
                    "d87901",
                    "d879a0",
                    "d86683008000",
                    "d8668100",
                    "d8669f0080f5ff",
                    "d86682c24901000000000000000080",
                    "d866822080",
                    "d86682c10080",
                    "d8668200c180",
                    "d8668200d87980",
                    "d866c1820080",
                    // Simple values, breaks and text are not Data, also inside containers.
                    "81f5",
                    "9ff5ff",
                    "8201ff",
                    "81f6",
                    "81f90000",
                    "816161",
                    "81c100",
                    "a101c100",
                    "f5",
                    "6161",
                    // Integers and tags have no indefinite form; lengths are unsigned.
                    "1f",
                    "3f",
                    "df00",
                    "9b8000000000000000",
                    "");
    }

    @ParameterizedTest
    @MethodSource("rejected")
    void rejectsWhatPlutusRejects(String hex) {
        assertThrows(CborDecodingException.class, () -> PlutusDataCborDecoder.decode(HEX.parseHex(hex)));
    }

    /** A FLAT {@code data} constant is the CBOR above (Flat Data is deriving via FlatViaSerialise). */
    @Test
    void flatDataConstantWithAnOver64ByteStringIsRejected() {
        var program = Program.plutusV3(Term.const_(Constant.data(PlutusData.bytes(HEX.parseHex("ab".repeat(64))))));
        byte[] flat = UplcFlatEncoder.encodeProgram(program);
        assertEquals(program, UplcFlatDecoder.decodeProgram(flat));
        // FLAT stores the 66 CBOR bytes 5840ab.. as one chunk; grow it to a definite 65-byte string.
        String chunk = "425840" + "ab".repeat(64);
        String flatHex = HEX.formatHex(flat);
        assertTrue(flatHex.contains(chunk));
        byte[] over = HEX.parseHex(flatHex.replace(chunk, "435841" + "ab".repeat(65)));
        assertThrows(CborDecodingException.class, () -> UplcFlatDecoder.decodeProgram(over));
    }

    /** decode is strict (inline datums: decodeFull'); decodeFirst ignores the rest (deserialiseOrFail). */
    @ParameterizedTest
    @ValueSource(strings = {"0001", "00a1", "001c", "00" + "c100"})
    void trailingBytesAreRejectedByDecodeAndIgnoredByDecodeFirst(String hex) {
        assertThrows(CborDecodingException.class, () -> PlutusDataCborDecoder.decode(HEX.parseHex(hex)));
        assertEquals(PlutusData.integer(0), PlutusDataCborDecoder.decodeFirst(HEX.parseHex(hex)));
    }

    @Test
    void decodeFirstStillRejectsAMalformedFirstItem() {
        assertThrows(CborDecodingException.class, () -> PlutusDataCborDecoder.decodeFirst(HEX.parseHex("c10000")));
        assertThrows(CborDecodingException.class, () -> PlutusDataCborDecoder.decodeFirst(new byte[0]));
    }

    /** A FLAT data constant ignores bytes after its CBOR item, even malformed ones. */
    @Test
    void flatDataConstantIgnoresTrailingBytes() {
        var program = Program.plutusV3(Term.const_(Constant.data(PlutusData.bytes(HEX.parseHex("ab".repeat(64))))));
        String chunk = "425840" + "ab".repeat(64);
        String flatHex = HEX.formatHex(UplcFlatEncoder.encodeProgram(program));
        assertTrue(flatHex.contains(chunk));
        byte[] trailing = HEX.parseHex(flatHex.replace(chunk, "445840" + "ab".repeat(64) + "011c"));
        assertEquals(program, UplcFlatDecoder.decodeProgram(trailing));
        byte[] malformedFirst = HEX.parseHex(flatHex.replace(chunk, "44c100" + "5840" + "ab".repeat(64)));
        assertThrows(CborDecodingException.class, () -> UplcFlatDecoder.decodeProgram(malformedFirst));
    }
}
