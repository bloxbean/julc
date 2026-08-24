package evidence;

import com.bloxbean.cardano.julc.verification.dsl.VerificationSpecification;
import com.bloxbean.cardano.julc.verification.dsl.LedgerExpressions;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslDomain;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslPropertySet;

import static com.bloxbean.cardano.julc.verification.dsl.VerificationDsl.property;

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
