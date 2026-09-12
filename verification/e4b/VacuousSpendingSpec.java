package evidence;

import org.julclang.verification.dsl.VerificationSpecification;
import org.julclang.verification.dsl.ir.DslDomain;
import org.julclang.verification.dsl.ir.DslPropertySet;

import static org.julclang.verification.dsl.VerificationDsl.*;

/** A deliberately vacuous control: the exact validator has no successful execution. */
public final class VacuousSpendingSpec implements VerificationSpecification {
    private static final String AUTHORITY =
            "4a554c435f5645524946595f415554484f524954595f303030303031";

    @Override
    public DslPropertySet properties() {
        var contract = new VacuousSpendingModel();
        return contract.properties(
                property("vacuous.signed", DslDomain.NONE,
                        contract.context().txInfo().signatories()
                                .contains(keyHash(AUTHORITY))));
    }
}
