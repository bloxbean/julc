import java.math.BigInteger;

@MintingValidator
class VerificationControlledMintBroken {
    record MintRedeemer(byte[] tokenName, BigInteger quantity) {}

    @Entrypoint
    static boolean validate(MintRedeemer redeemer, ScriptContext ctx) {
        var txInfo = ctx.txInfo();
        if (!redeemer.quantity().equals(BigInteger.ONE)) {
            return false;
        }

        return switch (ctx.scriptInfo()) {
            case ScriptInfo.MintingScript minting -> {
                var outer = Builtins.unMapData(txInfo.mint());
                if (Builtins.nullList(outer)) {
                    yield false;
                }
                if (Builtins.nullList(Builtins.tailList(outer))) {
                    var policyPair = Builtins.headList(outer);
                    var inner = Builtins.unMapData(Builtins.sndPair(policyPair));
                    if (Builtins.nullList(inner)) {
                        yield false;
                    }
                    if (Builtins.nullList(Builtins.tailList(inner))) {
                        var tokenPair = Builtins.headList(inner);
                        yield Builtins.equalsByteString(
                                    Builtins.unBData(Builtins.fstPair(tokenPair)),
                                    redeemer.tokenName())
                                && Builtins.unIData(Builtins.sndPair(tokenPair))
                                    .equals(redeemer.quantity());
                    } else {
                        yield false;
                    }
                } else {
                    yield false;
                }
            }
            default -> false;
        };
    }
}
