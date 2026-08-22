package com.bloxbean.cardano.julc.verification.capability;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LedgerCapabilityInventoryTest {
    @TempDir
    Path tempDir;

    @Test
    void bundledInventoryClassifiesPinnedV3AndHonestGaps() {
        var inventory = LedgerCapabilityInventories.pinnedV3();

        assertEquals("5dab3c43f042b8735b6d067223baaa8d32ed28a1", inventory.revision());
        assertTrue(inventory.capabilities().size() >= 80);
        assertEquals(CapabilityStatus.TYPED,
                inventory.require("purpose.spending").status());
        assertEquals(CapabilityStatus.UNSUPPORTED_IR,
                inventory.require("purpose.voting").status());
        assertEquals(CapabilityStatus.RAW_DATA_ONLY,
                inventory.require("field.txInfo.validRange").status());
        assertEquals(CapabilityStatus.TYPED,
                inventory.require("helper.utxoConsumed").status());
        assertEquals(CapabilityStatus.TYPED,
                inventory.require("ledger.validMintingContext").status());
        assertEquals(CapabilityStatus.TYPED,
                inventory.require("purpose.rewarding").status());
        assertEquals(CapabilityStatus.TYPED,
                inventory.require("field.txInfo.withdrawals").status());
        assertEquals(CapabilityStatus.TYPED,
                inventory.require("ledger.validRewardingContext").status());
        assertEquals(CapabilityStatus.TYPED,
                inventory.require("purpose.certifying").status());
        assertEquals(CapabilityStatus.TYPED,
                inventory.require("field.txInfo.certificates").status());
        assertEquals(CapabilityStatus.TYPED,
                inventory.require("helper.isKnownCertificate").status());
        assertEquals(CapabilityStatus.TYPED,
                inventory.require("ledger.validCertifyingContext").status());
        assertEquals(CapabilityStatus.TYPED,
                inventory.require("field.txInfo.referenceInputs").status());
        assertEquals(CapabilityStatus.TYPED,
                inventory.require("field.txOut.datum").status());
        assertEquals(CapabilityStatus.TYPED,
                inventory.require("constructor.credential.pubKey").status());
        assertEquals(CapabilityStatus.TYPED,
                inventory.require("helper.scriptInfoToScriptPurpose").status());
        assertEquals(CapabilityStatus.TYPED,
                inventory.require("helper.findRedeemer").status());
        assertEquals(CapabilityStatus.TYPED,
                inventory.require("helper.findDatum").status());
        for (String capability : java.util.List.of(
                "field.txInfo.votes", "field.txInfo.proposals",
                "constructor.voter.committee", "constructor.voter.drep",
                "constructor.voter.stakePool", "constructor.vote.no",
                "constructor.vote.yes", "constructor.vote.abstain",
                "constructor.governance.parameterChange",
                "constructor.governance.hardFork",
                "constructor.governance.treasuryWithdrawals",
                "constructor.governance.noConfidence",
                "constructor.governance.updateCommittee",
                "constructor.governance.newConstitution",
                "constructor.governance.info",
                "helper.isKnownVoter", "helper.isKnownProposal")) {
            assertEquals(CapabilityStatus.TYPED,
                    inventory.require(capability).status(), capability);
        }
        for (String capability : java.util.List.of(
                "constructor.txCert.regStaking",
                "constructor.txCert.unRegStaking",
                "constructor.txCert.delegStaking",
                "constructor.txCert.regDeleg",
                "constructor.txCert.regDRep",
                "constructor.txCert.updateDRep",
                "constructor.txCert.unRegDRep",
                "constructor.txCert.poolRegister",
                "constructor.txCert.poolRetire",
                "constructor.txCert.authHotCommittee",
                "constructor.txCert.resignColdCommittee",
                "constructor.delegatee.stake",
                "constructor.delegatee.vote",
                "constructor.delegatee.stakeVote",
                "constructor.drep.credential",
                "constructor.drep.abstain",
                "constructor.drep.noConfidence")) {
            assertEquals(CapabilityStatus.TYPED,
                    inventory.require(capability).status(), capability);
        }
        assertEquals(CapabilityStatus.UNSUPPORTED_IR,
                inventory.require("helper.findDatumHash").status());
        assertEquals(CapabilityStatus.UNSUPPORTED_SOLVER,
                inventory.require("solver.proofReconstruction").status());
    }

    @Test
    void evidenceProfileAndAvailablePinnedSourcesMatchInventory() throws Exception {
        var inventory = LedgerCapabilityInventories.pinnedV3();
        Path repository = repositoryRoot();
        String profile = Files.readString(repository.resolve(
                "verification/blaster/config/verification-profile.json"));
        assertTrue(profile.contains("\"CardanoLedgerApiBlaster\": \""
                + inventory.revision() + "\""));

        Path sources = repository.resolve("verification/blaster/.lake/packages/"
                + "CardanoLedgerApi/CardanoLedgerApi/V3");
        if (Files.isDirectory(sources)) {
            LedgerCapabilityCompatibilityGate.verifyLeanSources(inventory, sources);
        }
    }

    @Test
    void revisionGateRejectsUnclassifiedUpgrade() {
        var inventory = LedgerCapabilityInventories.pinnedV3();
        LedgerCapabilityCompatibilityGate.requireRevision(inventory, inventory.revision());

        var error = assertThrows(IllegalArgumentException.class,
                () -> LedgerCapabilityCompatibilityGate.requireRevision(
                        inventory, "0000000000000000000000000000000000000000"));
        assertTrue(error.getMessage().contains("not classified"));
    }

    @Test
    void sourceGateDetectsMissingOrChangedSurface() throws Exception {
        var inventory = new LedgerCapabilityInventory(1, "CardanoLedgerApiBlaster", "V3",
                "5dab3c43f042b8735b6d067223baaa8d32ed28a1",
                java.util.List.of(
                        capability("purpose.spending", "Contexts.lean", "SpendingScript : Ref"),
                        capability("purpose.minting", "Contexts.lean", "MintingScript : Policy"),
                        capability("purpose.rewarding", "Contexts.lean", "RewardingScript : Cred"),
                        capability("purpose.certifying", "Contexts.lean", "CertifyingScript : Cert"),
                        capability("purpose.voting", "Contexts.lean", "VotingScript : Voter"),
                        capability("purpose.proposing", "Contexts.lean", "ProposingScript : Proposal")));
        Files.writeString(tempDir.resolve("Contexts.lean"), """
                SpendingScript : Ref
                MintingScript : Policy
                RewardingScript : Cred
                CertifyingScript : Cert
                VotingScript : Voter
                ProposingScript : Proposal
                """);
        LedgerCapabilityCompatibilityGate.verifyLeanSources(inventory, tempDir);

        Files.writeString(tempDir.resolve("Contexts.lean"), "-- SpendingScript removed\n");
        var error = assertThrows(IllegalStateException.class,
                () -> LedgerCapabilityCompatibilityGate.verifyLeanSources(inventory, tempDir));
        assertTrue(error.getMessage().contains("purpose.spending"));
    }

    private static LedgerCapability capability(String id, String source, String signature) {
        return new LedgerCapability(id, CapabilityKind.SCRIPT_PURPOSE,
                CapabilityStatus.UNSUPPORTED_IR, source, signature, "test classification");
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        for (Path candidate : java.util.List.of(current, current.getParent())) {
            if (candidate != null && Files.isRegularFile(candidate.resolve("settings.gradle"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("Cannot locate JuLC repository root from " + current);
    }
}
