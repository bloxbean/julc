package m3b.fixtures;

import org.julclang.core.PlutusData;
import org.julclang.ledger.PubKeyHash;
import org.julclang.ledger.ScriptContext;
import org.julclang.ledger.TokenName;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.NewType;
import org.julclang.stdlib.annotation.SpendingValidator;

/**
 * ADR-068 M3b: a wrapper read from a variable. The reference applies UnBData to bytes, which fails on-chain where Java
 * returns them, so these stop on javac, and the program's row says so.
 */
@SpendingValidator
class M3bWrapperReads {
    @NewType record Tag(byte[] value) { }
    record Holder(PubKeyHash signer) { }

    static byte[] hashOfParameter(PubKeyHash p) { return p.hash(); }
    static byte[] hashParenthesized(Holder h) { return (h.signer()).hash(); }
    static byte[] nameOfParameter(TokenName t) { return t.name(); }
    static byte[] newTypeOfParameter(Tag t) { return t.value(); }
    static PubKeyHash hashFromData(PlutusData d) { return PubKeyHash.fromPlutusData(d); }

    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        return true;
    }
}
