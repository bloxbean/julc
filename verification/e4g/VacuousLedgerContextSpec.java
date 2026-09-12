package evidence;

import org.julclang.verification.dsl.VerificationSpecification;
import org.julclang.verification.dsl.ir.DslDomain;
import org.julclang.verification.dsl.ir.DslPropertySet;

import static org.julclang.verification.dsl.VerificationDsl.*;

public final class VacuousLedgerContextSpec implements VerificationSpecification {
    @Override public DslPropertySet properties() {
        var contract = new VacuousLedgerContextModel();
        return contract.properties(property("ledger-context.vacuous",
                DslDomain.VALID_SPENDING_V3_PINNED,
                contract.context().txInfo().referenceInputs().isNotEmpty()));
    }
}
