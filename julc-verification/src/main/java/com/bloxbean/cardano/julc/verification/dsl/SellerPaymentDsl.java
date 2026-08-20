package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.compiler.pir.PirType;
import com.bloxbean.cardano.julc.compiler.schema.ContractSchema;
import com.bloxbean.cardano.julc.verification.SellerPaymentProperty;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslPropertySet;

import java.util.List;

import static com.bloxbean.cardano.julc.verification.dsl.VerificationDsl.property;

/** Reviewed E.3 DSL expression and authoritative promotion to certificate IR. */
public final class SellerPaymentDsl {
    private SellerPaymentDsl() { }

    public static DslPropertySet propertySet(
            String propertyId, String sellerField, String priceField) {
        var contract = new SpendingContractModel();
        var seller = contract.datum().bytesField(sellerField);
        var price = contract.datum().integerField(priceField);
        var paid = contract.context().txInfo().outputs().exists(output ->
                output.address().credential().matchesKeyHash(seller)
                        .and(output.value().lovelace().ge(price)));
        var domainAndExecution = contract.validSpendingContext()
                .and(contract.exactUplcSucceeds());
        return DslPropertySet.of(property(propertyId, domainAndExecution.implies(paid)));
    }

    public static SellerPaymentProperty resolve(
            DslPropertySet candidate,
            ContractSchema schema,
            String validatorTitle,
            String sellerField,
            String priceField,
            String sourcePath) {
        DslPropertyValidator.validate(candidate, schema, DslPropertyValidator.MAX_AST_NODES);
        String propertyId = candidate.properties().getFirst().id();
        String observed = PropertyIrCodec.canonicalJson(candidate);
        String expected = PropertyIrCodec.canonicalJson(
                propertySet(propertyId, sellerField, priceField));
        if (!expected.equals(observed)) {
            throw new IllegalArgumentException("DSL property does not match reviewed "
                    + SellerPaymentProperty.TEMPLATE + " semantics");
        }
        String datumType = datumType(schema, sellerField, priceField);
        return new SellerPaymentProperty(
                SellerPaymentProperty.SCHEMA_VERSION,
                SellerPaymentProperty.TEMPLATE,
                propertyId,
                validatorTitle,
                "spending",
                sourcePath,
                sellerField,
                priceField,
                datumType,
                observed,
                List.of("validSpendingContext/v3-pinned"),
                List.of("strict-datum", "public-key-seller-output",
                        "lovelace-paid-at-least"),
                true);
    }

    private static String datumType(
            ContractSchema schema, String sellerField, String priceField) {
        PirType datum = resolve(schema.datum().type(), schema);
        if (!(datum instanceof PirType.RecordType record)) {
            throw new IllegalArgumentException("Seller-payment datum must be a record");
        }
        requireField(record, sellerField, PirType.ByteStringType.class);
        requireField(record, priceField, PirType.IntegerType.class);
        return record.name();
    }

    private static void requireField(
            PirType.RecordType record, String name, Class<? extends PirType> expected) {
        PirType field = record.fields().stream()
                .filter(candidate -> candidate.name().equals(name))
                .map(PirType.Field::type)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown datum field " + name));
        if (!expected.isInstance(field)) {
            throw new IllegalArgumentException("Datum field " + name + " must be "
                    + expected.getSimpleName());
        }
    }

    private static PirType resolve(PirType type, ContractSchema schema) {
        if (type instanceof PirType.NamedTypeRef ref) {
            PirType result = schema.namedDefinitions().get(ref.stableId());
            if (result == null) result = schema.namedDefinitions().get(ref.name());
            if (result == null) throw new IllegalArgumentException("Unknown datum type " + ref.name());
            return result;
        }
        return type;
    }
}
