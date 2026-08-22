package com.bloxbean.cardano.julc.verification.capability;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewedRawDataAdapterAuditTest {
    @Test
    void auditPinsAuthorityDiscrepanciesWithoutPromotingRawCapabilities() {
        var audit = ReviewedRawDataAdapterAudits.v1();
        assertEquals("5dab3c43f042b8735b6d067223baaa8d32ed28a1",
                audit.proofModelRevision());
        assertEquals("f424a79ade53b427fb1e5adc0f8cc9a9689c81f7",
                audit.plutusRevision());
        assertEquals("bbf77221512003c3ed45ce409d47356aefe325df",
                audit.cardanoLedgerRevision());

        assertEquals(Set.of(
                        "adapter.validity-range.pinned-v1",
                        "adapter.current-treasury.strict-optional-integer",
                        "adapter.treasury-donation.strict-optional-integer",
                        "adapter.changed-parameters.integer-key-index",
                        "adapter.quorum.plutus-rational-v1"),
                audit.adapters().stream().map(
                        ReviewedRawDataAdapterAudit.Adapter::id).collect(
                                java.util.stream.Collectors.toSet()));
        assertTrue(audit.adapters().stream().allMatch(adapter ->
                adapter.status()
                        == ReviewedRawDataAdapterAudit.AdapterStatus.READY_FOR_IMPLEMENTATION));
        assertTrue(audit.require("adapter.validity-range.pinned-v1").discrepancies()
                .stream().anyMatch(value -> value.contains("false closure")));
        assertTrue(audit.require("adapter.current-treasury.strict-optional-integer")
                .discrepancies().stream().anyMatch(value -> value.contains("raw Integer")));
        assertTrue(audit.require("adapter.quorum.plutus-rational-v1").constraints()
                .stream().anyMatch(value -> value.contains("unit-interval")));

        var capabilities = LedgerCapabilityInventories.pinnedV3();
        for (String raw : Set.of(
                "field.txInfo.validRange",
                "field.txInfo.currentTreasuryAmount",
                "field.txInfo.treasuryDonation")) {
            assertEquals(CapabilityStatus.RAW_DATA_ONLY,
                    capabilities.require(raw).status(), raw);
        }
        for (var adapter : audit.adapters()) {
            assertEquals(CapabilityStatus.TYPED,
                    capabilities.require(adapter.id()).status(), adapter.id());
        }
    }
}
