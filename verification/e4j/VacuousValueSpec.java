package evidence;

import com.bloxbean.cardano.julc.verification.dsl.*;
import com.bloxbean.cardano.julc.verification.dsl.ir.*;
import static com.bloxbean.cardano.julc.verification.dsl.VerificationDsl.*;

public final class VacuousValueSpec implements VerificationSpecification {
    public DslPropertySet properties() {
        var contract = new VacuousValueModel();
        var policy = LedgerExpressions.currencySymbol(bytes(
                "31313131313131313131313131313131313131313131313131313131"));
        var token = LedgerExpressions.tokenName(bytes("746f6b656e"));
        var paid = contract.context().txInfo().outputs().at(integer(0))
                .exists(output -> output.value().quantityFirst(policy, token)
                        .ge(integer(10)));
        return contract.properties(property("value.first-match-payment",
                DslDomain.VALID_SPENDING_V3_PINNED, paid));
    }
}
