package evidence;

import org.julclang.verification.dsl.VerificationSpecification;
import org.julclang.verification.dsl.ir.DslDomain;
import org.julclang.verification.dsl.ir.DslPropertySet;

import static org.julclang.verification.dsl.VerificationDsl.*;

public final class VulnerableCertificatePayloadSpec
        implements VerificationSpecification {
    @Override public DslPropertySet properties() {
        var contract = new VulnerableCertificatePayloadModel();
        var guarantee = contract.certificate().whenRegDRep((credential, deposit) ->
                deposit.eq(integer(1)));
        return contract.properties(property("certificate.vulnerable-registration",
                DslDomain.VALID_CERTIFYING_V3_PINNED, guarantee));
    }
}
