package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.compiler.schema.ContractSchema;
import com.bloxbean.cardano.julc.verification.ComposedDslProperty;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslDomain;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslPropertySet;
import com.bloxbean.cardano.julc.verification.dsl.type.ContractTypeProjection;
import com.bloxbean.cardano.julc.verification.dsl.type.ProjectedContractTypes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashSet;

/** Authoritative generic promotion of an admitted schema-3 property set. */
public final class ComposedDslPromotion {
    private ComposedDslPromotion() { }

    public static ComposedDslProperty promote(
            DslPropertySet candidate,
            ContractSchema schema,
            String validatorTitle,
            String sourcePath) {
        if (candidate.schemaVersion() != DslPropertySet.COMPOSITION_SCHEMA_VERSION
                && candidate.schemaVersion() != DslPropertySet.TYPED_SCHEMA_VERSION
                && candidate.schemaVersion() != DslPropertySet.LEDGER_SCHEMA_VERSION
                && candidate.schemaVersion()
                        != DslPropertySet.AUTHORIZATION_SCHEMA_VERSION
                && candidate.schemaVersion()
                        != DslPropertySet.CERTIFICATE_PAYLOAD_SCHEMA_VERSION
                && candidate.schemaVersion()
                        != DslPropertySet.VALUE_ALGEBRA_SCHEMA_VERSION
                && candidate.schemaVersion()
                        != DslPropertySet.GOVERNANCE_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "Generic promotion requires DSL property schema 3 through 9");
        }
        DslPropertySet normalized = DslPropertyValidator.validateAndNormalize(
                candidate, schema, DslPropertyValidator.MAX_AST_NODES);
        ProjectedContractTypes projected = candidate.schemaVersion()
                >= DslPropertySet.TYPED_SCHEMA_VERSION
                ? ContractTypeProjection.project(schema) : null;
        return promoteNormalized(normalized, validatorTitle, sourcePath, projected);
    }

    private static ComposedDslProperty promoteNormalized(
            DslPropertySet normalized,
            String validatorTitle,
            String sourcePath,
            ProjectedContractTypes projected) {
        String canonical = PropertyIrCodec.canonicalJson(normalized);
        var domains = new LinkedHashSet<String>();
        var rules = new LinkedHashSet<String>();
        var claims = normalized.properties().stream().map(property -> {
            var plan = DslSemanticDependencies.collect(property, normalized.purpose());
            rules.addAll(plan.guaranteeRules());
            String domain = domainName(property.domain());
            if (!domain.isEmpty()) domains.add(domain);
            byte[] guarantee = PropertyIrCodec.canonicalNodeBytes(property.expression());
            String guaranteeHash = sha256(guarantee);
            String envelope = "schema=1\npurpose=" + normalized.purpose()
                    + "\ndomain=" + property.domain()
                    + "\nexactUplcSucceeds=true\nguaranteeSha256=" + guaranteeHash + "\n";
            return new ComposedDslProperty.Claim(
                    property.id(), generatedName(property.id()), property.domain().name(),
                    guaranteeHash, sha256(envelope.getBytes(StandardCharsets.UTF_8)),
                    plan.capabilities(), plan.guaranteeRules(),
                    counterexampleDomain(property.domain(), normalized.purpose()),
                    false, false);
        }).toList();
        boolean ledger = normalized.schemaVersion() >= DslPropertySet.LEDGER_SCHEMA_VERSION;
        return new ComposedDslProperty(
                projected == null ? ComposedDslProperty.SCHEMA_VERSION
                        : ledger ? ComposedDslProperty.LEDGER_SCHEMA_VERSION
                        : ComposedDslProperty.TYPED_SCHEMA_VERSION,
                projected == null ? ComposedDslProperty.TEMPLATE
                        : ledger ? ComposedDslProperty.LEDGER_TEMPLATE
                        : ComposedDslProperty.TYPED_TEMPLATE,
                validatorTitle + ".dsl-property-set",
                validatorTitle,
                normalized.purpose().name().toLowerCase(),
                sourcePath,
                canonical,
                claims,
                domains.stream().toList(),
                rules.stream().sorted().toList(),
                !domains.isEmpty(),
                projected == null ? null : ContractTypeProjection.canonicalJson(projected),
                projected == null ? null : ContractTypeProjection.sha256(projected));
    }

    public static String generatedName(String id) {
        return id.replaceAll("[^A-Za-z0-9_]", "_");
    }

    /** Re-derive all non-source metadata before workspace publication. */
    public static DslPropertySet verifyIntegrity(ComposedDslProperty promoted) {
        boolean legacy = promoted.schemaVersion() == ComposedDslProperty.SCHEMA_VERSION
                && ComposedDslProperty.TEMPLATE.equals(promoted.template());
        boolean typed = promoted.schemaVersion() == ComposedDslProperty.TYPED_SCHEMA_VERSION
                && ComposedDslProperty.TYPED_TEMPLATE.equals(promoted.template());
        boolean ledger = promoted.schemaVersion() == ComposedDslProperty.LEDGER_SCHEMA_VERSION
                && ComposedDslProperty.LEDGER_TEMPLATE.equals(promoted.template());
        if (!legacy && !typed && !ledger) {
            throw new IllegalArgumentException("Unsupported composed DSL property IR");
        }
        final DslPropertySet normalized;
        try {
            normalized = PropertyIrCodec.readCanonical(
                    promoted.canonicalDslJson(), PropertyIrCodec.MAX_CANONICAL_BYTES);
        } catch (IOException invalid) {
            throw new IllegalArgumentException("Invalid canonical composed DSL IR", invalid);
        }
        int expectedSchema = ledger
                ? normalized.schemaVersion()
                : typed ? DslPropertySet.TYPED_SCHEMA_VERSION
                : DslPropertySet.COMPOSITION_SCHEMA_VERSION;
        if (ledger && expectedSchema != DslPropertySet.LEDGER_SCHEMA_VERSION
                && expectedSchema != DslPropertySet.AUTHORIZATION_SCHEMA_VERSION
                && expectedSchema != DslPropertySet.CERTIFICATE_PAYLOAD_SCHEMA_VERSION
                && expectedSchema != DslPropertySet.VALUE_ALGEBRA_SCHEMA_VERSION
                && expectedSchema != DslPropertySet.GOVERNANCE_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "Ledger DSL property requires inner schema 5 through 9");
        }
        if (normalized.schemaVersion() != expectedSchema
                || !promoted.scriptPurpose().equals(
                        normalized.purpose().name().toLowerCase())) {
            throw new IllegalArgumentException("Composed DSL purpose or schema mismatch");
        }
        ProjectedContractTypes projected = null;
        if (typed || ledger) {
            try {
                projected = ContractTypeProjection.readCanonical(
                        promoted.projectedContractTypesJson(),
                        PropertyIrCodec.MAX_CANONICAL_BYTES);
            } catch (IOException invalid) {
                throw new IllegalArgumentException(
                        "Invalid canonical projected contract types", invalid);
            }
            String hash = ContractTypeProjection.sha256(projected);
            if (!hash.equals(promoted.contractSchemaSha256())
                    || !hash.equals(normalized.contractSchemaSha256())) {
                throw new IllegalArgumentException(
                        "Typed DSL contract schema hash mismatch");
            }
        }
        ComposedDslProperty expected = promoteNormalized(
                normalized, promoted.validatorTitle(), promoted.sourcePath(), projected);
        if (!expected.equals(promoted)) {
            throw new IllegalArgumentException(
                    "Composed DSL property does not match canonical guarantee IR");
        }
        return normalized;
    }

    private static String domainName(DslDomain domain) {
        return switch (domain) {
            case NONE -> "";
            case VALID_SPENDING_V3_PINNED -> "validSpendingContext/v3-pinned";
            case VALID_MINTING_V3_PINNED -> "validMintingContext/v3-pinned";
            case VALID_REWARDING_V3_PINNED -> "validRewardingContext/v3-pinned";
            case VALID_CERTIFYING_V3_PINNED -> "validCertifyingContext/v3-pinned";
        };
    }

    private static String counterexampleDomain(
            DslDomain domain,
            com.bloxbean.cardano.julc.verification.dsl.ir.DslPurpose purpose) {
        return switch (domain) {
            case VALID_SPENDING_V3_PINNED -> "BLASTER_VALID_SPENDING_SUPERSET";
            case VALID_MINTING_V3_PINNED -> "BLASTER_VALID_MINTING_SUPERSET";
            case VALID_REWARDING_V3_PINNED -> "BLASTER_VALID_REWARDING_SUPERSET";
            case VALID_CERTIFYING_V3_PINNED -> "BLASTER_VALID_CERTIFYING_SUPERSET";
            case NONE -> switch (purpose) {
                case SPENDING -> "BLASTER_SPENDING_SYMBOLIC_CONTEXT";
                case MINTING -> "BLASTER_MINTING_SYMBOLIC_CONTEXT";
                case REWARDING -> "BLASTER_REWARDING_SYMBOLIC_CONTEXT";
                case CERTIFYING -> "BLASTER_CERTIFYING_SYMBOLIC_CONTEXT";
            };
        };
    }

    private static String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
