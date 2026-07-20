package com.bloxbean.cardano.julc.vm.java.builtins;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.JulcVm;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden-vector tests for the VM's PlutusData CBOR serializer (used by the {@code serialiseData}
 * builtin) — issue #48. Non-empty lists and constructor fields MUST encode as indefinite-length
 * arrays (0x9f ... 0xff); empty collections stay definite (0x80). Values are verified byte-for-byte
 * against cardano-client-lib 0.7.x and Scalus 0.17.0. A regression changes the on-chain result of
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
        return HEX.formatHex(DataSerializer.serialize(d));
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
        assertEquals(expected, direct(d), "DataSerializer.serialize");
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
    void nestedConstrListBytesMatchesCanonical() {
        assertBytes("d8799f9fd87a9f09ffff41abff",
                PlutusData.constr(0,
                        PlutusData.list(PlutusData.constr(1, PlutusData.integer(9))),
                        PlutusData.bytes(new byte[]{(byte) 0xab})));
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
}
