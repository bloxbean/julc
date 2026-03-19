package com.bloxbean.cardano.julc.core;

import java.util.ServiceLoader;

/**
 * Validates BLS12-381 constant values during UPLC text parsing.
 * <p>
 * Implementations perform cryptographic validation (point on curve, in subgroup, etc.)
 * using a BLS12-381 library. Discovered via {@link ServiceLoader} — if no implementation
 * is on the classpath, validation is skipped.
 */
public interface BlsConstantValidator {

    /**
     * Validate a BLS12-381 G1 element in compressed form (48 bytes).
     *
     * @param compressed the compressed G1 point bytes
     * @throws IllegalArgumentException if the bytes do not represent a valid G1 element
     */
    void validateG1Element(byte[] compressed);

    /**
     * Validate a BLS12-381 G2 element in compressed form (96 bytes).
     *
     * @param compressed the compressed G2 point bytes
     * @throws IllegalArgumentException if the bytes do not represent a valid G2 element
     */
    void validateG2Element(byte[] compressed);

    /**
     * Get the validator instance, if available. Uses ServiceLoader discovery.
     *
     * @return the validator, or null if no implementation is on the classpath
     */
    static BlsConstantValidator getInstance() {
        return Holder.INSTANCE;
    }

    // Lazy holder for thread-safe singleton initialization
    final class Holder {
        static final BlsConstantValidator INSTANCE;
        static {
            BlsConstantValidator found = null;
            try {
                var loader = ServiceLoader.load(BlsConstantValidator.class);
                var first = loader.findFirst();
                if (first.isPresent()) {
                    found = first.get();
                }
            } catch (Exception e) {
                // ServiceLoader failed (e.g., native library unavailable) — skip validation
            }
            INSTANCE = found;
        }
    }
}
