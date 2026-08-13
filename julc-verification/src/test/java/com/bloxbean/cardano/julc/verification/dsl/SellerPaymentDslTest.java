package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import com.bloxbean.cardano.julc.verification.SellerPaymentProperty;
import com.bloxbean.cardano.julc.verification.dsl.ir.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SellerPaymentDslTest {

    @Test
    void resolvesReviewedPaymentShapeAndExplicitLedgerDomain() {
        var schema = compiler().compileContract(source()).contractSchema();
        var candidate = SellerPaymentDsl.propertySet(
                "Sale.seller-paid-at-least", "seller", "price");

        SellerPaymentProperty property = SellerPaymentDsl.resolve(
                candidate, schema, "Sale", "seller", "price", "SalePayment.java");

        assertEquals(SellerPaymentProperty.TEMPLATE, property.template());
        assertTrue(property.ledgerValidityModeled());
        assertEquals(java.util.List.of("validSpendingContext/v3-pinned"),
                property.domainAssumptions());
        assertEquals(PropertyIrCodec.canonicalJson(candidate), property.canonicalDslJson());
        assertTrue(PropertyLeanRenderer.render(candidate).contains("List.any"));
    }

    @Test
    void rejectsWeakerPaymentOrWrongContractFieldTypes() {
        var schema = compiler().compileContract(source()).contractSchema();
        var model = new SpendingContractModel();
        var weaker = DslPropertySet.of(new DslProperty("Sale.seller-paid-at-least",
                model.exactUplcSucceeds().implies(
                        model.context().txInfo().outputs().exists(out ->
                                out.value().lovelace().ge(model.datum().integerField("price"))))
                        .node()));
        var error = assertThrows(IllegalArgumentException.class,
                () -> SellerPaymentDsl.resolve(
                        weaker, schema, "Sale", "seller", "price", "Weak.java"));
        assertTrue(error.getMessage().contains("does not match reviewed"));

        assertThrows(IllegalArgumentException.class,
                () -> SellerPaymentDsl.resolve(
                        SellerPaymentDsl.propertySet(
                                "Sale.bad", "price", "seller"),
                        schema, "Sale", "price", "seller", "Bad.java"));
    }

    private static JulcCompiler compiler() {
        return new JulcCompiler(StdlibRegistry.defaultRegistry());
    }

    private static String source() {
        return """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import java.math.BigInteger;
                @SpendingValidator
                class Sale {
                    record Datum(byte[] seller, BigInteger price) {}
                    record Redeemer() {}
                    @Entrypoint
                    static boolean validate(Datum datum, Redeemer redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;
    }
}
