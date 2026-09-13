package evidence;

import org.julclang.verification.dsl.VerificationSpecification;
import org.julclang.verification.dsl.ir.DslDomain;
import org.julclang.verification.dsl.ir.DslPropertySet;

import static org.julclang.verification.dsl.VerificationDsl.property;

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
