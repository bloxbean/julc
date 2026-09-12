package evidence;

import org.julclang.verification.dsl.VerificationSpecification;
import org.julclang.verification.dsl.LedgerExpressions;
import org.julclang.verification.dsl.ir.DslDomain;
import org.julclang.verification.dsl.ir.DslPropertySet;

import static org.julclang.verification.dsl.VerificationDsl.property;

/** User-owned E.3 DSL source; the generated SaleModel is disposable. */
public final class SellerPaymentSpec implements VerificationSpecification {
    public SellerPaymentSpec() { }

    @Override
    public DslPropertySet properties() {
        var contract = new SaleModel();
        var paid = contract.datum().exists(datum ->
                contract.context().txInfo().outputs().exists(output ->
                        output.address().paymentCredential().whenPubKey(key -> key.eq(
                                LedgerExpressions.publicKeyHash(datum.seller()).typed()))
                                .and(output.value().lovelace().ge(datum.price()))));
        return contract.properties(property("seller-paid-at-least",
                DslDomain.VALID_SPENDING_V3_PINNED, paid));
    }
}
