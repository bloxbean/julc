package com.bloxbean.cardano.julc.verification;

import java.util.List;
import java.util.Objects;

/** Certificate-facing IR for the E.3 seller-paid-at-least DSL vertical slice. */
public record SellerPaymentProperty(
        int schemaVersion,
        String template,
        String propertyId,
        String validatorTitle,
        String scriptPurpose,
        String sourcePath,
        String sellerField,
        String priceField,
        String datumType,
        String canonicalDslJson,
        List<String> domainAssumptions,
        List<String> guaranteeRules,
        boolean ledgerValidityModeled) implements VerificationProperty {

    public static final int SCHEMA_VERSION = 1;
    public static final String TEMPLATE = "julc.dsl.seller-paid-at-least/v1";

    public SellerPaymentProperty {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported seller-payment schema "
                    + schemaVersion);
        }
        if (!TEMPLATE.equals(template) || !"spending".equals(scriptPurpose)) {
            throw new IllegalArgumentException("Unsupported seller-payment template or purpose");
        }
        propertyId = Objects.requireNonNull(propertyId, "propertyId");
        validatorTitle = Objects.requireNonNull(validatorTitle, "validatorTitle");
        sourcePath = Objects.requireNonNull(sourcePath, "sourcePath");
        sellerField = Objects.requireNonNull(sellerField, "sellerField");
        priceField = Objects.requireNonNull(priceField, "priceField");
        datumType = Objects.requireNonNull(datumType, "datumType");
        canonicalDslJson = Objects.requireNonNull(canonicalDslJson, "canonicalDslJson");
        domainAssumptions = List.copyOf(domainAssumptions);
        guaranteeRules = List.copyOf(guaranteeRules);
        if (!ledgerValidityModeled
                || !domainAssumptions.equals(List.of("validSpendingContext/v3-pinned"))) {
            throw new IllegalArgumentException(
                    "Seller-payment v1 requires the pinned valid spending context domain");
        }
    }
}
