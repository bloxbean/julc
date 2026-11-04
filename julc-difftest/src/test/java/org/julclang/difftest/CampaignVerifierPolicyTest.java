package org.julclang.difftest;

import org.julclang.compiler.CompilerTypeDiagnostics;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Only a rejection a decision of ADR-068 names passes a campaign (D10, r20): not another code, nor another of the code's rejections. */
class CampaignVerifierPolicyTest {

    @Test
    void aDecisionNamesTheRejection() {
        assertNotNull(CampaignVerifier.policyDecision("JULC0078", "Value.equals compares Value with byte[], which Java keeps distinct"));
        assertNotNull(CampaignVerifier.policyDecision("JULC0012",
                "Cannot resolve type: the type of a switch expression whose arms have different types, A and B. Give the arms one type"));
        assertNotNull(CampaignVerifier.policyDecision("JULC0012",
                "Cannot resolve type: the type of a conditional whose arms have different types, A and B. Give the arms one type"));
    }

    @Test
    void anyOtherRejectionStaysAFailure() {
        assertNull(CampaignVerifier.policyDecision("JULC0012", "Cannot resolve type: Foo"));
        // Arms of one Java type that the lowering types apart are a defect, not the decision.
        assertNull(CampaignVerifier.policyDecision("JULC0012", "Cannot resolve type: " + CompilerTypeDiagnostics.UNKNOWN_TYPE_ENCODED + ", which it would Data-encode."));
        assertNull(CampaignVerifier.policyDecision("JULC0085", "whose arms have different types"));
        assertNull(CampaignVerifier.policyDecision("JULC0120", "Reassignment of switch case-pattern variable 'v1' is not supported"));
    }
}
