package evidence;

import com.bloxbean.cardano.julc.verification.dsl.VerificationSpecification;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslDomain;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslPropertySet;

import static com.bloxbean.cardano.julc.verification.dsl.VerificationDsl.property;

public final class VacuousReviewedAdaptersSpec implements VerificationSpecification {
    @Override
    public DslPropertySet properties() {
        var contract = new VacuousReviewedAdaptersModel();
        var range = contract.context().txInfo().validityRangeReviewed();
        return contract.properties(property("reviewed.non-vacuity-control",
                DslDomain.VALID_SPENDING_V3_PINNED,
                contract.datum().exists(datum -> range.contains(datum.deadline()))));
    }
}
