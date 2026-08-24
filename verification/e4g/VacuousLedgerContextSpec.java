package evidence;

import com.bloxbean.cardano.julc.verification.dsl.VerificationSpecification;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslDomain;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslPropertySet;

import static com.bloxbean.cardano.julc.verification.dsl.VerificationDsl.*;

public final class VacuousLedgerContextSpec implements VerificationSpecification {
    @Override public DslPropertySet properties() {
        var contract = new VacuousLedgerContextModel();
        return contract.properties(property("ledger-context.vacuous",
                DslDomain.VALID_SPENDING_V3_PINNED,
                contract.context().txInfo().referenceInputs().isNotEmpty()));
    }
}
