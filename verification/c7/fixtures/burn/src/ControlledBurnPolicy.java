import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.*;
import com.bloxbean.cardano.julc.verification.annotation.*;
import java.math.BigInteger;

@ControlledMint(
    authority="4a554c435f5645524946595f415554484f524954595f303030303031",
    tokenName="4a554c43", quantity=1, action=MintAction.BURN)
@MintingValidator
class ControlledBurnPolicy {
    static final byte[] AUTHORITY = new byte[]{
        74,85,76,67,95,86,69,82,73,70,89,95,65,85,
        84,72,79,82,73,84,89,95,48,48,48,48,48,49};
    static final byte[] TOKEN = new byte[]{74,85,76,67};
    record Redeemer() {}

    @Entrypoint
    static boolean validate(Redeemer redeemer, ScriptContext ctx) {
        if (ctx.txInfo().signatories().isEmpty()
                || !Builtins.equalsByteString(
                    ctx.txInfo().signatories().head().hash(), AUTHORITY)) return false;
        return switch (ctx.scriptInfo()) {
            case ScriptInfo.MintingScript minting ->
                    exactBurn(ctx, minting.policyId());
            default -> false;
        };
    }

    static boolean exactBurn(ScriptContext ctx, PolicyId ownPolicy) {
        var outer = Builtins.unMapData(ctx.txInfo().mint());
        if (Builtins.nullList(outer) || !Builtins.nullList(Builtins.tailList(outer))) {
            return false;
        }
        var policy = Builtins.headList(outer);
        var inner = Builtins.unMapData(Builtins.sndPair(policy));
        if (Builtins.nullList(inner) || !Builtins.nullList(Builtins.tailList(inner))) {
            return false;
        }
        var token = Builtins.headList(inner);
        return Builtins.equalsByteString(
                    Builtins.unBData(Builtins.fstPair(policy)),
                    (byte[])(Object) ownPolicy)
                && Builtins.equalsByteString(
                    Builtins.unBData(Builtins.fstPair(token)), TOKEN)
                && Builtins.unIData(Builtins.sndPair(token))
                    .equals(BigInteger.ONE.negate());
    }
}
