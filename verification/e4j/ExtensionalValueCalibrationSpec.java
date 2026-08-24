package evidence;

import com.bloxbean.cardano.julc.verification.dsl.*;
import com.bloxbean.cardano.julc.verification.dsl.ir.*;

import static com.bloxbean.cardano.julc.verification.dsl.VerificationDsl.*;

/** Solver calibration only: the full extensional formula can exceed useful bounds. */
public final class ExtensionalValueCalibrationSpec implements VerificationSpecification {
    public DslPropertySet properties() {
        var contract = new AuthorizedValueModel();
        var extensional = contract.context().txInfo().outputs().at(integer(0))
                .exists(output -> output.value().extensionallyEquals(output.value()));
        return contract.properties(property("value.extensional-calibration",
                DslDomain.VALID_SPENDING_V3_PINNED, extensional));
    }
}
