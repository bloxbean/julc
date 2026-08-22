package evidence;

import com.bloxbean.cardano.julc.verification.dsl.*;
import com.bloxbean.cardano.julc.verification.dsl.ir.*;
import static com.bloxbean.cardano.julc.verification.dsl.VerificationDsl.*;

public final class VulnerableGovernanceSpec implements VerificationSpecification {
    public DslPropertySet properties() {
        var contract = new VulnerableGovernanceModel();
        var guarantee = contract.context().txInfo().proposals().at(integer(0))
                .exists(proposal -> proposal.deposit().ge(integer(10))
                        .and(proposal.actionStrict().exists(action ->
                                action.whenHardFork((previous, version) ->
                                        version.major().eq(integer(11))))));
        return contract.properties(property("governance.hard-fork-v11",
                DslDomain.VALID_SPENDING_V3_PINNED, guarantee));
    }
}
