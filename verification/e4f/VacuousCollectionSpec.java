package evidence;

import org.julclang.verification.dsl.VerificationSpecification;
import org.julclang.verification.dsl.ir.DslDomain;
import org.julclang.verification.dsl.ir.DslPropertySet;

import static org.julclang.verification.dsl.VerificationDsl.property;

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
