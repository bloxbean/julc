package com.bloxbean.cardano.julc.verification.dsl.ir;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** Sealed, data-only property AST. Raw Java or Lean fragments are not nodes. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "op")
@JsonSubTypes({
        @JsonSubTypes.Type(value = RootNode.class, name = "root"),
        @JsonSubTypes.Type(value = FieldNode.class, name = "field"),
        @JsonSubTypes.Type(value = BoolBinaryNode.class, name = "bool-binary"),
        @JsonSubTypes.Type(value = ContainsNode.class, name = "contains"),
        @JsonSubTypes.Type(value = CompareNode.class, name = "compare"),
        @JsonSubTypes.Type(value = CredentialKeyHashNode.class, name = "credential-key-hash"),
        @JsonSubTypes.Type(value = ExistsNode.class, name = "exists"),
        @JsonSubTypes.Type(value = LiteralNode.class, name = "literal"),
        @JsonSubTypes.Type(value = BytesLiteralNode.class, name = "bytes-literal"),
        @JsonSubTypes.Type(value = TxOutRefLiteralNode.class, name = "tx-out-ref-literal"),
        @JsonSubTypes.Type(value = ConsumesNode.class, name = "consumes"),
        @JsonSubTypes.Type(value = ExactOwnPolicyAssetNode.class, name = "exact-own-policy-asset"),
        @JsonSubTypes.Type(value = TxCertKindNode.class, name = "tx-cert-kind"),
        @JsonSubTypes.Type(value = KnownCertificateNode.class, name = "known-certificate")
})
public sealed interface PropertyNode permits RootNode, FieldNode, BoolBinaryNode,
        ContainsNode, CompareNode, CredentialKeyHashNode, ExistsNode, LiteralNode,
        BytesLiteralNode, TxOutRefLiteralNode, ConsumesNode, ExactOwnPolicyAssetNode,
        TxCertKindNode, KnownCertificateNode {
    DslType resultType();
}
