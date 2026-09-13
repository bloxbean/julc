package evidence;

import org.julclang.verification.dsl.*;
import org.julclang.verification.dsl.ir.*;
import static org.julclang.verification.dsl.VerificationDsl.*;

public final class VacuousGovernanceSpec implements VerificationSpecification {
    public DslPropertySet properties() {
        var contract = new VacuousGovernanceModel();
        var guarantee = contract.context().txInfo().proposals().at(integer(0))
                .exists(proposal -> proposal.deposit().ge(integer(10)));
        return contract.properties(property("governance.non-vacuity-control",
                DslDomain.VALID_SPENDING_V3_PINNED, guarantee));
    }
}
