package evidence;

import com.bloxbean.cardano.julc.verification.dsl.*;
import com.bloxbean.cardano.julc.verification.dsl.ir.*;
import static com.bloxbean.cardano.julc.verification.dsl.VerificationDsl.*;

public final class AuthorizedGovernanceSpec implements VerificationSpecification {
    public DslPropertySet properties() {
        var contract = new AuthorizedGovernanceModel();
        var guarantee = contract.context().txInfo().proposals().at(integer(0))
                .exists(proposal -> proposal.deposit().ge(integer(10)));
        return contract.properties(property("governance.minimum-deposit",
                DslDomain.VALID_SPENDING_V3_PINNED, guarantee));
    }
}
