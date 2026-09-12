package evidence;

import org.julclang.verification.dsl.VerificationSpecification;
import org.julclang.verification.dsl.ir.DslDomain;
import org.julclang.verification.dsl.ir.DslPropertySet;

import static org.julclang.verification.dsl.VerificationDsl.property;

public final class VulnerableThresholdSpec implements VerificationSpecification {
    @Override public DslPropertySet properties() {
        var contract = new VulnerableThresholdModel();
        var auth = contract.authorization();
        var committee = auth.authorities(
                auth.fixed("41".repeat(28)),
                auth.fixed("42".repeat(28)),
                auth.fixed("43".repeat(28)));
        return contract.properties(property("authorization.vulnerable-threshold",
                DslDomain.VALID_SPENDING_V3_PINNED,
                committee.exactlySigned(2)));
    }
}
