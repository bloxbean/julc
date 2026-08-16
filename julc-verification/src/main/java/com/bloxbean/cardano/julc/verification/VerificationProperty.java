package com.bloxbean.cardano.julc.verification;

import java.util.List;

/** Common certificate-facing contract for versioned typed verification properties. */
public sealed interface VerificationProperty
        permits RequiresSignerProperty, StatefulSpendingProperty, ControlledMintProperty,
        SellerPaymentProperty {
    int schemaVersion();
    String template();
    String propertyId();
    String validatorTitle();
    String scriptPurpose();
    String sourcePath();
    List<String> domainAssumptions();
    List<String> guaranteeRules();
    boolean ledgerValidityModeled();
}
