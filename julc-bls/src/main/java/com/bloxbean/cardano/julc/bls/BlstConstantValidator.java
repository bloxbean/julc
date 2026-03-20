package com.bloxbean.cardano.julc.bls;

import com.bloxbean.cardano.julc.core.BlsConstantValidator;
import supranational.blst.P1_Affine;
import supranational.blst.P2_Affine;

/**
 * Validates BLS12-381 constants using the blst native library.
 * <p>
 * Registered via {@link java.util.ServiceLoader} so the UPLC parser
 * can discover and use it automatically when julc-bls is on the classpath.
 */
public class BlstConstantValidator implements BlsConstantValidator {

    @Override
    public void validateG1Element(byte[] compressed) {
        if (compressed.length != BlsOperations.G1_COMPRESSED_SIZE) {
            throw new IllegalArgumentException(
                    "bls12_381_G1_element: expected " + BlsOperations.G1_COMPRESSED_SIZE +
                    " bytes, got " + compressed.length);
        }
        try {
            P1_Affine affine = new P1_Affine(compressed);
            if (!affine.in_group()) {
                throw new IllegalArgumentException(
                        "bls12_381_G1_element: point not in subgroup");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "bls12_381_G1_element: invalid encoding - " + e.getMessage(), e);
        }
    }

    @Override
    public void validateG2Element(byte[] compressed) {
        if (compressed.length != BlsOperations.G2_COMPRESSED_SIZE) {
            throw new IllegalArgumentException(
                    "bls12_381_G2_element: expected " + BlsOperations.G2_COMPRESSED_SIZE +
                    " bytes, got " + compressed.length);
        }
        try {
            P2_Affine affine = new P2_Affine(compressed);
            if (!affine.in_group()) {
                throw new IllegalArgumentException(
                        "bls12_381_G2_element: point not in subgroup");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "bls12_381_G2_element: invalid encoding - " + e.getMessage(), e);
        }
    }
}
