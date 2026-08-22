package evidence;

import com.bloxbean.cardano.julc.verification.dsl.VerificationSpecification;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslDomain;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslPropertySet;

import static com.bloxbean.cardano.julc.verification.dsl.VerificationDsl.*;

public final class VulnerableLedgerContextSpec implements VerificationSpecification {
    @Override public DslPropertySet properties() {
        var contract = new VulnerableLedgerContextModel();
        var guarantee = contract.context().txInfo().referenceInputs()
                .exists(input -> input.resolved().datum().isInline())
                .and(contract.ownInput().isPresent());
        return contract.properties(property("ledger-context.vulnerable",
                DslDomain.VALID_SPENDING_V3_PINNED, guarantee));
    }
}
