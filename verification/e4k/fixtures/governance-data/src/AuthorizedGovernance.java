import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.stdlib.annotation.*;
import java.math.BigInteger;

@SpendingValidator
class AuthorizedGovernance {
    record Datum() {}
    record Redeemer() {}
    @Entrypoint static boolean validate(Datum datum, Redeemer redeemer, ScriptContext ctx) {
        if (ctx.txInfo().proposalProcedures().isEmpty()) return false;
        var proposal = ctx.txInfo().proposalProcedures().head();
        if (proposal.deposit().compareTo(BigInteger.TEN) < 0) return false;
        boolean publicKeyReturnAddress = switch (proposal.returnAddress()) {
            case Credential.PubKeyCredential ignored -> true;
            default -> false;
        };
        if (!publicKeyReturnAddress) return false;
        return switch (proposal.governanceAction()) {
            case GovernanceAction.HardForkInitiation hardFork ->
                    hardFork.protocolVersion().major().equals(BigInteger.valueOf(11));
            default -> false;
        };
    }
}
