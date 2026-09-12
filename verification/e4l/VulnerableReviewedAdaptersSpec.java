package evidence;

import org.julclang.verification.dsl.VerificationSpecification;
import org.julclang.verification.dsl.ir.DslDomain;
import org.julclang.verification.dsl.ir.DslPropertySet;

import static org.julclang.verification.dsl.VerificationDsl.property;

public final class VulnerableReviewedAdaptersSpec implements VerificationSpecification {
    @Override
    public DslPropertySet properties() {
        var contract = new VulnerableReviewedAdaptersModel();
        var range = contract.context().txInfo().validityRangeReviewed();
        return contract.properties(property("reviewed.time-vulnerable",
                DslDomain.VALID_SPENDING_V3_PINNED,
                contract.datum().exists(datum -> range.contains(datum.deadline()))));
    }
}
