package evidence;

import com.bloxbean.cardano.julc.verification.dsl.VerificationSpecification;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslDomain;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslPropertySet;

import static com.bloxbean.cardano.julc.verification.dsl.VerificationDsl.property;

public final class AuthorizedReviewedAdaptersSpec implements VerificationSpecification {
    @Override
    public DslPropertySet properties() {
        var contract = new AuthorizedReviewedAdaptersModel();
        var tx = contract.context().txInfo();
        var authorization = contract.authorization();
        var authority = authorization.authorities(
                authorization.fixed("41".repeat(28)));

        var deadlineAndAuthority = contract.datum().exists(datum ->
                tx.validityRangeReviewed().contains(datum.deadline())
                        .and(authority.allSigned()));
        return contract.properties(property("reviewed.time-and-authority",
                DslDomain.VALID_SPENDING_V3_PINNED, deadlineAndAuthority));
    }
}
