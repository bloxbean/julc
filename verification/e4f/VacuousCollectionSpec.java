package evidence;

import com.bloxbean.cardano.julc.verification.dsl.VerificationSpecification;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslDomain;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslPropertySet;

import static com.bloxbean.cardano.julc.verification.dsl.VerificationDsl.property;

public final class VacuousCollectionSpec implements VerificationSpecification {
    @Override
    public DslPropertySet properties() {
        var contract = new VacuousCollectionModel();
        var guarantee = contract.datum().isPresent()
                .and(contract.redeemer().isPresent());
        return contract.properties(property("collections.vacuous",
                DslDomain.NONE, guarantee));
    }
}
