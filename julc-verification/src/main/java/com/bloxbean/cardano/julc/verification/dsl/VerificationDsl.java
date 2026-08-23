package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.DslProperty;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslDomain;
import com.bloxbean.cardano.julc.verification.dsl.ir.BytesLiteralKind;
import com.bloxbean.cardano.julc.verification.dsl.ir.BytesLiteralNode;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslType;
import com.bloxbean.cardano.julc.verification.dsl.ir.LiteralNode;
import com.bloxbean.cardano.julc.verification.dsl.ir.TxOutRefLiteralNode;

public final class VerificationDsl {
    private VerificationDsl() { }
    public static DslProperty property(String id, DslDomain domain, BoolExpr guarantee) {
        return new DslProperty(id, domain, guarantee.node());
    }
    public static IntegerExpr integer(long value) {
        return integer(Long.toString(value));
    }
    public static BoolExpr bool(boolean value) {
        return new BoolExpr(new com.bloxbean.cardano.julc.verification.dsl.ir.BoolLiteralNode(value));
    }
    public static IntegerExpr integer(String canonicalValue) {
        return new IntegerExpr(new LiteralNode(DslType.INTEGER, canonicalValue));
    }
    public static ByteStringExpr bytes(String hex) {
        return bytes(hex, BytesLiteralKind.BYTES);
    }
    public static ByteStringExpr keyHash(String hex) {
        return bytes(hex, BytesLiteralKind.KEY_HASH);
    }
    public static ByteStringExpr tokenName(String hex) {
        return bytes(hex, BytesLiteralKind.TOKEN_NAME);
    }
    public static PolicyIdExpr policyId(String hex) {
        return new PolicyIdExpr(new BytesLiteralNode(
                DslType.POLICY_ID, BytesLiteralKind.POLICY_ID, hex));
    }
    public static TxOutRefExpr txOutRef(String transactionIdHex, long outputIndex) {
        return new TxOutRefExpr(new TxOutRefLiteralNode(
                DslType.TX_OUT_REF, transactionIdHex, Long.toString(outputIndex)));
    }
    private static ByteStringExpr bytes(String hex, BytesLiteralKind kind) {
        return new ByteStringExpr(new BytesLiteralNode(DslType.BYTE_STRING, kind, hex));
    }
}
