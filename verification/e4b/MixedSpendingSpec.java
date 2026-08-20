package evidence;

import com.bloxbean.cardano.julc.verification.dsl.VerificationSpecification;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslDomain;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslPropertySet;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslPurpose;

import static com.bloxbean.cardano.julc.verification.dsl.VerificationDsl.*;

/** One established and one deliberately refuted claim over the same exact artifact. */
public final class MixedSpendingSpec implements VerificationSpecification {
    private static final String AUTHORITY =
            "4a554c435f5645524946595f415554484f524954595f303030303031";

    @Override
    public DslPropertySet properties() {
        var contract = new ComposedSaleModel();
        var paid = contract.context().txInfo().outputs().exists(output ->
                output.address().credential().matchesKeyHash(contract.datum().seller())
                        .and(output.value().lovelace().ge(contract.datum().price())));
        var signed = contract.context().txInfo().signatories()
                .contains(keyHash(AUTHORITY));
        return DslPropertySet.composed(DslPurpose.SPENDING,
                property("mixed.paid", DslDomain.VALID_SPENDING_V3_PINNED, paid),
                property("mixed.signed", DslDomain.VALID_SPENDING_V3_PINNED, signed));
    }
}
