package evidence;

import com.bloxbean.cardano.julc.verification.dsl.*;
import com.bloxbean.cardano.julc.verification.dsl.ir.*;

import static com.bloxbean.cardano.julc.verification.dsl.VerificationDsl.*;

/** Solver calibration only: retained results do not replace the positive theorem. */
public final class StrictValueCalibrationSpec implements VerificationSpecification {
    public DslPropertySet properties() {
        var contract = new AuthorizedValueModel();
        var policy = LedgerExpressions.currencySymbol(bytes(
                "31313131313131313131313131313131313131313131313131313131"));
        var token = LedgerExpressions.tokenName(bytes("746f6b656e"));
        var strict = contract.context().txInfo().outputs().at(integer(0))
                .exists(output -> output.value().quantitySumStrict(policy, token)
                        .exists(quantity -> new IntegerExpr(quantity.node())
                                .ge(integer(10))));
        return contract.properties(property("value.strict-summed-calibration",
                DslDomain.VALID_SPENDING_V3_PINNED, strict));
    }
}
