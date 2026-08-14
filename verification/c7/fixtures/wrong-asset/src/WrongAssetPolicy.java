import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.stdlib.annotation.*;
import com.bloxbean.cardano.julc.verification.annotation.*;

@ControlledMint(authority="4a554c435f5645524946595f415554484f524954595f303030303031",
    tokenName="4a554c43", quantity=1, action=MintAction.MINT)
@MintingValidator
class WrongAssetPolicy {
    record Redeemer() {}
    @Entrypoint
    static boolean validate(Redeemer redeemer, ScriptContext ctx) { return true; }
}
