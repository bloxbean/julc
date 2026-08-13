package com.bloxbean.cardano.julc.verification.capability;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Versioned, machine-readable coverage declaration for one pinned ledger model. */
public record LedgerCapabilityInventory(
        int schemaVersion,
        String ledgerApi,
        String ledgerVersion,
        String revision,
        List<LedgerCapability> capabilities) {

    public static final int SCHEMA_VERSION = 1;

    public LedgerCapabilityInventory {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported capability inventory schema "
                    + schemaVersion);
        }
        if (!"CardanoLedgerApiBlaster".equals(ledgerApi)) {
            throw new IllegalArgumentException("Unsupported ledger API: " + ledgerApi);
        }
        if (!"V3".equals(ledgerVersion)) {
            throw new IllegalArgumentException("Unsupported ledger version: " + ledgerVersion);
        }
        if (revision == null || !revision.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException("Ledger revision must be a full lowercase commit ID");
        }
        capabilities = List.copyOf(capabilities == null ? List.of() : capabilities);
        if (capabilities.isEmpty()) {
            throw new IllegalArgumentException("Capability inventory must not be empty");
        }
        Map<String, LedgerCapability> unique = new LinkedHashMap<>();
        for (LedgerCapability capability : capabilities) {
            if (unique.putIfAbsent(capability.id(), capability) != null) {
                throw new IllegalArgumentException("Duplicate capability ID: " + capability.id());
            }
        }
        for (String purpose : List.of("spending", "minting", "rewarding", "certifying",
                "voting", "proposing")) {
            if (!unique.containsKey("purpose." + purpose)) {
                throw new IllegalArgumentException("Missing V3 purpose classification: " + purpose);
            }
        }
    }

    public Map<String, LedgerCapability> byId() {
        var result = new LinkedHashMap<String, LedgerCapability>();
        capabilities.forEach(capability -> result.put(capability.id(), capability));
        return Map.copyOf(result);
    }

    public LedgerCapability require(String id) {
        LedgerCapability capability = byId().get(id);
        if (capability == null) {
            throw new IllegalArgumentException("Unclassified ledger capability: " + id);
        }
        return capability;
    }
}
