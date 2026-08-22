package evidence;

import com.bloxbean.cardano.julc.verification.dsl.VerificationSpecification;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslDomain;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslPropertySet;

import static com.bloxbean.cardano.julc.verification.dsl.VerificationDsl.*;

public final class AuthorizedCertificatePayloadSpec
        implements VerificationSpecification {
    @Override public DslPropertySet properties() {
        var contract = new AuthorizedCertificatePayloadModel();
        var guarantee = contract.certificate().whenRegDRep((credential, deposit) ->
                deposit.eq(integer(1)));
        return contract.properties(property("certificate.registration-deposit",
                DslDomain.VALID_CERTIFYING_V3_PINNED, guarantee));
    }
}
