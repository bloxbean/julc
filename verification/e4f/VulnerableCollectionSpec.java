package evidence;

import com.bloxbean.cardano.julc.verification.dsl.VerificationSpecification;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslDomain;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslPropertySet;

import static com.bloxbean.cardano.julc.verification.dsl.VerificationDsl.property;

public final class VulnerableCollectionSpec implements VerificationSpecification {
    @Override
    public DslPropertySet properties() {
        var contract = new VulnerableCollectionModel();
        var guarantee = contract.redeemer().exists(action -> action.isUse());
        return contract.properties(property("collections.vulnerable",
                DslDomain.NONE, guarantee));
    }
}
