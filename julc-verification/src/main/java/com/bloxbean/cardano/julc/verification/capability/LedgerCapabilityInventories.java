package com.bloxbean.cardano.julc.verification.capability;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

/** Loads the inventory bundled with the verification module. */
public final class LedgerCapabilityInventories {
    public static final String V3_RESOURCE =
            "/com/bloxbean/cardano/julc/verification/cardano-ledger-api-v3-capabilities.json";

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private LedgerCapabilityInventories() {
    }

    public static LedgerCapabilityInventory pinnedV3() {
        try (var input = LedgerCapabilityInventories.class.getResourceAsStream(V3_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing bundled capability inventory " + V3_RESOURCE);
            }
            return JSON.readValue(input, LedgerCapabilityInventory.class);
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Invalid bundled capability inventory: "
                    + e.getMessage(), e);
        }
    }

    public static byte[] pinnedV3Bytes() {
        try (var input = LedgerCapabilityInventories.class.getResourceAsStream(V3_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing bundled capability inventory "
                        + V3_RESOURCE);
            }
            return input.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read bundled capability inventory", e);
        }
    }
}
