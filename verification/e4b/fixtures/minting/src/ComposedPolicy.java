import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.*;
import java.math.BigInteger;

@MintingValidator
class ComposedPolicy {
    static final byte[] AUTHORITY = new byte[]{
        74,85,76,67,95,86,69,82,73,70,89,95,65,85,
        84,72,79,82,73,84,89,95,48,48,48,48,48,49};
    static final byte[] ANCHOR = new byte[]{
        1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,
        17,18,19,20,21,22,23,24,25,26,27,28,29,30,31,32};
    static final byte[] TOKEN = new byte[]{74,85,76,67};
    record Redeemer() {}

    @Entrypoint
    static boolean validate(Redeemer redeemer, ScriptContext ctx) {
        if (ctx.txInfo().signatories().isEmpty()
                || !Builtins.equalsByteString(
                    ctx.txInfo().signatories().head().hash(), AUTHORITY)) return false;
        if (ctx.txInfo().inputs().isEmpty()) return false;
        TxInInfo anchor = ctx.txInfo().inputs().head();
        if (!Builtins.equalsByteString(anchor.outRef().txId().hash(), ANCHOR)
                || !anchor.outRef().index().equals(BigInteger.ZERO)) return false;
        return switch (ctx.scriptInfo()) {
            case ScriptInfo.MintingScript minting ->
                    exactMint(ctx, minting.policyId(), BigInteger.ONE);
            default -> false;
        };
    }

    static boolean exactMint(ScriptContext ctx, PolicyId ownPolicy, BigInteger quantity) {
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
                && Builtins.unIData(Builtins.sndPair(token)).equals(quantity);
    }
}
