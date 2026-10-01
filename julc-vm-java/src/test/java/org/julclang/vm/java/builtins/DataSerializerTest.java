package org.julclang.vm.java.builtins;

import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.core.Term;
import org.julclang.core.cbor.PlutusDataCborDecoder;
import org.julclang.core.cbor.PlutusDataCborEncoder;
import org.julclang.vm.EvalResult;
import org.julclang.vm.JulcVm;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the {@code SerialiseData} builtin, which now encodes through {@link PlutusDataCborEncoder} (issue #229).
 * <p>
 * Golden vectors for issue #48. Non-empty lists and constructor fields MUST encode as
 * indefinite-length arrays (0x9f ... 0xff); empty collections stay definite (0x80). Values are verified
 * byte-for-byte against cardano-client-lib 0.7.x and Scalus 0.17.0. A regression changes the on-chain result of
 * {@code blake2b_256(serialiseData(datum))} (datum commitments, CIP-68 token names, ...).
 */
class DataSerializerTest {

    static final HexFormat HEX = HexFormat.of();
    static JulcVm vm;

    @BeforeAll
    static void setUp() {
        vm = JulcVm.create("Java");
    }

    private static String direct(PlutusData d) {
        return HEX.formatHex(PlutusDataCborEncoder.encode(d));
    }

    /** Serialise via the actual serialiseData builtin through the CEK machine. */
    private String viaBuiltin(PlutusData d) {
        Term t = Term.apply(Term.builtin(DefaultFun.SerialiseData), Term.const_(Constant.data(d)));
        var r = vm.evaluate(Program.plutusV3(t));
        assertInstanceOf(EvalResult.Success.class, r, () -> "expected success: " + r);
        var val = ((Term.Const) ((EvalResult.Success) r).resultTerm()).value();
        return HEX.formatHex(((Constant.ByteStringConst) val).value());
    }

    private void assertBytes(String expected, PlutusData d) {
        assertEquals(expected, direct(d), "PlutusDataCborEncoder.encode");
        assertEquals(expected, viaBuiltin(d), "serialiseData builtin");
    }

    @Test
    void constrNonEmptyFieldsAreIndefinite() {
        assertBytes("d8799f182aff", PlutusData.constr(0, PlutusData.integer(42)));
        assertBytes("d8799f0102ff", PlutusData.constr(0, PlutusData.integer(1), PlutusData.integer(2)));
        assertBytes("d905009f01ff", PlutusData.constr(7, PlutusData.integer(1)));
    }

    @Test
    void constrEmptyFieldsAreDefinite() {
        assertBytes("d87980", PlutusData.constr(0));
    }

    @Test
    void listNonEmptyIsIndefiniteEmptyIsDefinite() {
        assertBytes("9f0102ff", PlutusData.list(PlutusData.integer(1), PlutusData.integer(2)));
        assertBytes("80", PlutusData.list());
    }

    @Test
    void constrGeneralFormHasDefiniteOuterIndefiniteInner() {
        assertBytes("d8668218829f05ff", PlutusData.constr(130, PlutusData.integer(5)));
    }

    @Test
    void constrGeneralFormSupportsMaxWord64Tag() {
        var maximum = java.math.BigInteger.ONE.shiftLeft(64).subtract(java.math.BigInteger.ONE);
        assertBytes("d866821bffffffffffffffff80",
                PlutusData.ConstrData.fromUnsignedTag(maximum, java.util.List.of()));
    }

    @Test
    void nestedConstrListBytesMatchesCanonical() {
        assertBytes("d8799f9fd87a9f09ffff41abff",
                PlutusData.constr(0,
                        PlutusData.list(PlutusData.constr(1, PlutusData.integer(9))),
                        PlutusData.bytes(new byte[]{(byte) 0xab})));
    }

    @Test
    void mapPreservesOrderAndDuplicateKeys() {
        // Plutus Data preserves map entry order and duplicate keys (definite-length map).
        // reordered {3:30, 1:10} stays in insertion order (NOT sorted)
        assertBytes("a203181e010a", PlutusData.map(
                new PlutusData.Pair(PlutusData.integer(3), PlutusData.integer(30)),
                new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(10))));
        // duplicate {1:10, 1:20} keeps BOTH entries
        assertBytes("a2010a0114", PlutusData.map(
                new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(10)),
                new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(20))));
    }

    @Test
    void longByteStringInsideConstrHasBothBreaks() {
        byte[] big = new byte[100];
        for (int i = 0; i < big.length; i++) big[i] = (byte) i;
        String hex = direct(PlutusData.constr(0, PlutusData.bytes(big)));
        assertEquals("d8799f", hex.substring(0, 6));
        assertEquals("ffff", hex.substring(hex.length() - 4));
        assertEquals(hex, viaBuiltin(PlutusData.constr(0, PlutusData.bytes(big))));
    }

    /**
     * plutus-core {@code encodeInteger} puts a bignum's bytes through {@code encodeBs}: over 64 bytes they
     * become an indefinite byte string of 64-byte chunks (issue #226).
     */
    @Test
    void bignumOver64BytesIsChunked() {
        // 2^528 is 67 bytes: 01 then 66 zero bytes
        assertBytes("c25f5840" + "01" + "00".repeat(63) + "43" + "00".repeat(3) + "ff",
                PlutusData.integer(BigInteger.ONE.shiftLeft(528)));
        // -2^528 is stored as 2^528 - 1: 66 bytes of ff
        assertBytes("c35f5840" + "ff".repeat(64) + "42" + "ffff" + "ff",
                PlutusData.integer(BigInteger.ONE.shiftLeft(528).negate()));
    }

    @Test
    void bignumOfExactly64BytesIsDefinite() {
        assertBytes("c25840" + "80" + "00".repeat(63), PlutusData.integer(BigInteger.ONE.shiftLeft(511)));
        assertBytes("c25f5840" + "01" + "00".repeat(63) + "4100" + "ff",
                PlutusData.integer(BigInteger.ONE.shiftLeft(512)));
    }

    /** A general-form constructor tag is written by cborg's {@code encodeInteger}, which never chunks. */
    @Test
    void constrGeneralFormBignumTagIsNotChunked() {
        assertBytes("d86682c2584c" + "01" + "00".repeat(75) + "80",
                new PlutusData.ConstrData(BigInteger.ONE.shiftLeft(600), List.of()));
    }

    /**
     * Redeemer of preview tx {@code 511fb35074242cd923fd51cd5b0759e75b8f5bfb49e69db0ec68861a041843f4}
     * (withdrawal script {@code b39623ec...}): its integers are about 2000 bits and the chain encoded it
     * canonically, so {@code serialiseData} must reproduce the witness bytes exactly.
     */
    @Test
    void realRedeemerWithBignumsRoundTrips() throws IOException {
        String hex;
        try (var in = getClass().getResourceAsStream("/serialise-data/preview-511fb350-redeemer.hex")) {
            hex = new String(in.readAllBytes(), StandardCharsets.US_ASCII).strip();
        }
        assertBytes(hex, PlutusDataCborDecoder.decode(HEX.parseHex(hex)));
    }
}
