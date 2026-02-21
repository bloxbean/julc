package com.bloxbean.cardano.julc.decompiler.input;

import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.flat.UplcFlatDecoder;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;

import java.io.ByteArrayInputStream;
import java.util.HexFormat;
import java.util.List;

/**
 * Loads UPLC programs from various input formats:
 * <ul>
 *   <li>Double-CBOR-wrapped hex (on-chain format)</li>
 *   <li>Single-CBOR-wrapped hex</li>
 *   <li>Raw FLAT bytes (hex-encoded)</li>
 *   <li>UPLC text format</li>
 * </ul>
 */
public final class ScriptLoader {

    private ScriptLoader() {}

    /**
     * Load a script from a hex string, auto-detecting the format.
     * Tries double-CBOR unwrap first, then single-CBOR, then raw FLAT.
     *
     * @param hexString the hex-encoded script data
     * @return the decoded Program
     * @throws ScriptLoadException if the hex cannot be decoded in any format
     */
    public static Program fromHex(String hexString) {
        hexString = hexString.strip();
        if (hexString.startsWith("0x") || hexString.startsWith("0X")) {
            hexString = hexString.substring(2);
        }

        byte[] bytes;
        try {
            bytes = HexFormat.of().parseHex(hexString);
        } catch (IllegalArgumentException e) {
            throw new ScriptLoadException("Invalid hex string: " + e.getMessage(), e);
        }

        // Try double-CBOR unwrap (on-chain format)
        try {
            byte[] inner = cborUnwrapBytes(bytes);
            byte[] flat = cborUnwrapBytes(inner);
            return UplcFlatDecoder.decodeProgram(flat);
        } catch (Exception ignored) {}

        // Try single-CBOR unwrap
        try {
            byte[] flat = cborUnwrapBytes(bytes);
            return UplcFlatDecoder.decodeProgram(flat);
        } catch (Exception ignored) {}

        // Try raw FLAT
        try {
            return UplcFlatDecoder.decodeProgram(bytes);
        } catch (Exception e) {
            throw new ScriptLoadException(
                    "Could not decode hex as double-CBOR, single-CBOR, or raw FLAT", e);
        }
    }

    /**
     * Load a script from raw FLAT bytes.
     *
     * @param flatBytes the FLAT-encoded program bytes
     * @return the decoded Program
     */
    public static Program fromFlatBytes(byte[] flatBytes) {
        try {
            return UplcFlatDecoder.decodeProgram(flatBytes);
        } catch (Exception e) {
            throw new ScriptLoadException("Failed to decode FLAT bytes", e);
        }
    }

    /**
     * Load a script from double-CBOR-wrapped hex (the standard on-chain format).
     *
     * @param doubleCborHex the double-CBOR hex string
     * @return the decoded Program
     */
    public static Program fromDoubleCborHex(String doubleCborHex) {
        doubleCborHex = doubleCborHex.strip();
        if (doubleCborHex.startsWith("0x") || doubleCborHex.startsWith("0X")) {
            doubleCborHex = doubleCborHex.substring(2);
        }

        try {
            byte[] outer = HexFormat.of().parseHex(doubleCborHex);
            byte[] inner = cborUnwrapBytes(outer);
            byte[] flat = cborUnwrapBytes(inner);
            return UplcFlatDecoder.decodeProgram(flat);
        } catch (Exception e) {
            throw new ScriptLoadException("Failed to decode double-CBOR hex", e);
        }
    }

    private static byte[] cborUnwrapBytes(byte[] cborData) throws Exception {
        var stream = new ByteArrayInputStream(cborData);
        List<DataItem> items = new CborDecoder(stream).decode();
        if (items.isEmpty()) {
            throw new ScriptLoadException("Empty CBOR data");
        }
        DataItem first = items.getFirst();
        if (!(first instanceof ByteString bs)) {
            throw new ScriptLoadException("Expected CBOR ByteString, got: " + first.getMajorType());
        }
        return bs.getBytes();
    }

    public static class ScriptLoadException extends RuntimeException {
        public ScriptLoadException(String message) {
            super(message);
        }

        public ScriptLoadException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
