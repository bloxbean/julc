package evidence;

import org.julclang.verification.dsl.VerificationSpecification;
import org.julclang.verification.dsl.ir.DslDomain;
import org.julclang.verification.dsl.ir.DslPropertySet;

import static org.julclang.verification.dsl.VerificationDsl.property;

public final class VulnerableCollectionSpec implements VerificationSpecification {
    @Override
    public DslPropertySet properties() {
        var contract = new VulnerableCollectionModel();
        var guarantee = contract.redeemer().exists(action -> action.isUse());
        return contract.properties(property("collections.vulnerable",
                DslDomain.NONE, guarantee));
    }
}
