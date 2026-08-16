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
        @JsonSubTypes.Type(value = LiteralNode.class, name = "literal")
})
public sealed interface PropertyNode permits RootNode, FieldNode, BoolBinaryNode,
        ContainsNode, CompareNode, CredentialKeyHashNode, ExistsNode, LiteralNode {
    DslType resultType();
}
