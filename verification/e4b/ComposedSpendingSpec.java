package evidence;

import com.bloxbean.cardano.julc.verification.dsl.VerificationSpecification;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslDomain;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslPropertySet;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslPurpose;

import static com.bloxbean.cardano.julc.verification.dsl.VerificationDsl.*;

/** Two freely composed spending claims; neither is a privileged whole-tree template. */
public final class ComposedSpendingSpec implements VerificationSpecification {
    private static final String FALLBACK_AUTHORITY =
            "4a554c435f5645524946595f415554484f524954595f303030303031";

    @Override
    public DslPropertySet properties() {
        var contract = new ComposedSaleModel();
        var paid = contract.context().txInfo().outputs().exists(output ->
                output.address().credential().matchesKeyHash(contract.datum().seller())
                        .and(output.value().lovelace().ge(contract.datum().price())));
        var signed = contract.context().txInfo().signatories()
                .contains(keyHash(FALLBACK_AUTHORITY));
        return DslPropertySet.composed(DslPurpose.SPENDING,
                property("sale.paid", DslDomain.VALID_SPENDING_V3_PINNED, paid),
                property("sale.paid-or-fallback-signed",
                        DslDomain.VALID_SPENDING_V3_PINNED, paid.or(signed)));
    }
}
