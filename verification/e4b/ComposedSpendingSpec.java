package evidence;

import org.julclang.verification.dsl.VerificationSpecification;
import org.julclang.verification.dsl.LedgerExpressions;
import org.julclang.verification.dsl.ir.DslDomain;
import org.julclang.verification.dsl.ir.DslPropertySet;

import static org.julclang.verification.dsl.VerificationDsl.*;

/** Two freely composed spending claims; neither is a privileged whole-tree template. */
public final class ComposedSpendingSpec implements VerificationSpecification {
    private static final String FALLBACK_AUTHORITY =
            "4a554c435f5645524946595f415554484f524954595f303030303031";

    @Override
    public DslPropertySet properties() {
        var contract = new ComposedSaleModel();
        var paid = contract.datum().exists(datum ->
                contract.context().txInfo().outputs().exists(output ->
                        output.address().paymentCredential().whenPubKey(key -> key.eq(
                                LedgerExpressions.publicKeyHash(datum.seller()).typed()))
                                .and(output.value().lovelace().ge(datum.price()))));
        var signed = contract.context().txInfo().signatories()
                .contains(keyHash(FALLBACK_AUTHORITY));
        return contract.properties(
                property("sale.paid", DslDomain.VALID_SPENDING_V3_PINNED, paid),
                property("sale.paid-or-fallback-signed",
                        DslDomain.VALID_SPENDING_V3_PINNED, paid.or(signed)));
    }
}
