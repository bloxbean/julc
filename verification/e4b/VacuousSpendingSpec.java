package evidence;

import com.bloxbean.cardano.julc.verification.dsl.VerificationSpecification;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslDomain;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslPropertySet;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslPurpose;

import static com.bloxbean.cardano.julc.verification.dsl.VerificationDsl.*;

/** A deliberately vacuous control: the exact validator has no successful execution. */
public final class VacuousSpendingSpec implements VerificationSpecification {
    private static final String AUTHORITY =
            "4a554c435f5645524946595f415554484f524954595f303030303031";

    @Override
    public DslPropertySet properties() {
        var contract = new VacuousSpendingModel();
        return DslPropertySet.composed(DslPurpose.SPENDING,
                property("vacuous.signed", DslDomain.NONE,
                        contract.context().txInfo().signatories()
                                .contains(keyHash(AUTHORITY))));
    }
}
