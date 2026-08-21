package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.LedgerRootNode;
import com.bloxbean.cardano.julc.verification.dsl.ir.LedgerByteAliasNode;

/** Entry points for the closed schema-5 Cardano ledger expression surface. */
public final class LedgerExpressions {
    private LedgerExpressions() { }

    public static LedgerContextExpr context() {
        return new LedgerContextExpr(new LedgerRootNode(
                "ledgerContext", LedgerTypeAuthority.SCRIPT_CONTEXT));
    }

    public static LedgerByteAliasExpr transactionId(ByteStringExpr bytes) {
        return alias(bytes, LedgerTypeAuthority.TX_ID);
    }
    public static LedgerByteAliasExpr datumHash(ByteStringExpr bytes) {
        return alias(bytes, LedgerTypeAuthority.DATUM_HASH);
    }
    public static LedgerByteAliasExpr scriptHash(ByteStringExpr bytes) {
        return alias(bytes, LedgerTypeAuthority.SCRIPT_HASH);
    }
    public static LedgerByteAliasExpr publicKeyHash(ByteStringExpr bytes) {
        return alias(bytes, LedgerTypeAuthority.PUB_KEY_HASH);
    }
    public static LedgerByteAliasExpr currencySymbol(ByteStringExpr bytes) {
        return alias(bytes, LedgerTypeAuthority.CURRENCY_SYMBOL);
    }
    private static LedgerByteAliasExpr alias(
            ByteStringExpr bytes,
            com.bloxbean.cardano.julc.verification.dsl.type.LedgerTypeRef type) {
        java.util.Objects.requireNonNull(bytes, "bytes");
        return new LedgerByteAliasExpr(new LedgerByteAliasNode(bytes.node(), type), type);
    }
}
