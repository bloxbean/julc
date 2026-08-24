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

    public static TxCertExpr currentCertificate() {
        return new TxCertExpr(new LedgerRootNode(
                "currentCertificate", LedgerTypeAuthority.TX_CERT));
    }

    public static IntegerExpr currentCertificateIndex() {
        return new IntegerExpr(new com.bloxbean.cardano.julc.verification.dsl.ir.RootNode(
                "certificateIndex",
                com.bloxbean.cardano.julc.verification.dsl.ir.DslType.INTEGER));
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
    public static LedgerByteAliasExpr tokenName(ByteStringExpr bytes) {
        return alias(bytes, LedgerTypeAuthority.TOKEN_NAME);
    }
    public static ValueDeltaOptionExpr singletonValueDelta(
            LedgerByteAliasExpr policy, LedgerByteAliasExpr token, IntegerExpr quantity) {
        java.util.Objects.requireNonNull(quantity, "quantity");
        ValueAlgebra.requireAliases(policy, token);
        return ValueAlgebra.arithmetic(
                com.bloxbean.cardano.julc.verification.dsl.ir.ValueArithmeticNode
                        .ValueArithmeticKind.SINGLETON,
                java.util.List.of(policy.node(), token.node(), quantity.node()),
                java.util.List.of(LedgerTypeAuthority.CURRENCY_SYMBOL,
                        LedgerTypeAuthority.TOKEN_NAME, LedgerTypeAuthority.INTEGER));
    }
    private static LedgerByteAliasExpr alias(
            ByteStringExpr bytes,
            com.bloxbean.cardano.julc.verification.dsl.type.LedgerTypeRef type) {
        java.util.Objects.requireNonNull(bytes, "bytes");
        return new LedgerByteAliasExpr(new LedgerByteAliasNode(bytes.node(), type), type);
    }
}
