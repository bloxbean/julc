package evidence;

import org.julclang.verification.dsl.VerificationSpecification;
import org.julclang.verification.dsl.ir.DslDomain;
import org.julclang.verification.dsl.ir.DslPropertySet;

import static org.julclang.verification.dsl.VerificationDsl.*;

public final class VacuousCertificatePayloadSpec
        implements VerificationSpecification {
    @Override public DslPropertySet properties() {
        var contract = new VacuousCertificatePayloadModel();
        var guarantee = contract.certificate().whenPoolRetire((pool, epoch) ->
                epoch.le(integer(100)));
        return contract.properties(property("certificate.vacuous-payload",
                DslDomain.VALID_CERTIFYING_V3_PINNED, guarantee));
    }
}
