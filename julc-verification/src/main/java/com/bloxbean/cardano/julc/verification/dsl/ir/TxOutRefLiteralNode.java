package com.bloxbean.cardano.julc.verification.dsl.ir;

import java.util.Objects;

public record TxOutRefLiteralNode(
        DslType resultType, String transactionIdHex, String outputIndex)
        implements PropertyNode {
    public TxOutRefLiteralNode {
        resultType = Objects.requireNonNull(resultType, "resultType");
        transactionIdHex = Objects.requireNonNull(transactionIdHex, "transactionIdHex");
        outputIndex = Objects.requireNonNull(outputIndex, "outputIndex");
    }
}
