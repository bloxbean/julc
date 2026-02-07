package com.bloxbean.cardano.plutus.clientlib;

import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.plutus.core.Program;
import com.bloxbean.cardano.plutus.core.flat.UplcFlatEncoder;

import co.nstant.in.cbor.CborBuilder;
import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.model.ByteString;

import java.io.ByteArrayOutputStream;

/**
 * Adapter for converting plutus-java {@link Program} to
 * cardano-client-lib {@link PlutusV3Script}.
 * <p>
 * Performs FLAT encoding + double-CBOR-wrapping as required by the Cardano ledger.
 */
public final class PlutusScriptAdapter {

    private PlutusScriptAdapter() {}

    /**
     * Convert a compiled Program to a PlutusV3Script suitable for on-chain use.
     * <p>
     * The conversion pipeline:
     * <ol>
     *   <li>FLAT-encode the Program to bytes</li>
     *   <li>CBOR-wrap the FLAT bytes (inner wrapping: {@code CBOR_ByteString(FLAT_bytes)})</li>
     *   <li>CBOR-wrap again (outer wrapping: {@code CBOR_ByteString(inner_CBOR)})</li>
     * </ol>
     * <p>
     * The double-CBOR wrapping is required because cardano-client-lib's {@code serializeAsDataItem()}
     * CBOR-decodes {@code cborHex} to get a ByteString DataItem, whose payload is placed directly
     * in the transaction witness set. The Cardano ledger expects that payload to be valid CBOR
     * ({@code plutus_v3_script = bytes .cbor bytes}), so the payload must itself be a CBOR-encoded
     * bytestring containing the raw FLAT bytes.
     */
    public static PlutusV3Script fromProgram(Program program) {
        // 1. FLAT-encode the program
        byte[] flatBytes = UplcFlatEncoder.encodeProgram(program);

        // 2. Inner CBOR wrap: encode flatBytes as a CBOR bytestring
        byte[] innerCbor = cborWrapBytes(flatBytes);

        // 3. Outer CBOR wrap: encode innerCbor as a CBOR bytestring
        //    cardano-client-lib's serializeAsDataItem() will CBOR-decode this to get
        //    a ByteString whose payload is the inner CBOR bytes
        byte[] outerCbor = cborWrapBytes(innerCbor);

        // 4. Create PlutusV3Script from the double-CBOR-wrapped bytes
        return PlutusV3Script.builder()
                .cborHex(bytesToHex(outerCbor))
                .build();
    }

    /**
     * Get the script hash of a compiled program.
     */
    public static String scriptHash(Program program) {
        var script = fromProgram(program);
        try {
            return bytesToHex(script.getScriptHash());
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute script hash", e);
        }
    }

    private static byte[] cborWrapBytes(byte[] data) {
        try {
            var baos = new ByteArrayOutputStream();
            new CborEncoder(baos).encode(new CborBuilder()
                    .add(data)
                    .build());
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("CBOR encoding failed", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        var sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
}
