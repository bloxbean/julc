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
        @JsonSubTypes.Type(value = KnownCertificateNode.class, name = "known-certificate"),
        @JsonSubTypes.Type(value = TypedRootNode.class, name = "typed-root"),
        @JsonSubTypes.Type(value = TypedVariableNode.class, name = "typed-variable"),
        @JsonSubTypes.Type(value = TypedFieldNode.class, name = "typed-field"),
        @JsonSubTypes.Type(value = VariantFieldNode.class, name = "variant-field"),
        @JsonSubTypes.Type(value = OptionExistsNode.class, name = "option-exists"),
        @JsonSubTypes.Type(value = VariantIsNode.class, name = "variant-is"),
        @JsonSubTypes.Type(value = VariantWhenNode.class, name = "variant-when"),
        @JsonSubTypes.Type(value = BoolLiteralNode.class, name = "bool-literal"),
        @JsonSubTypes.Type(value = BoolNotNode.class, name = "bool-not"),
        @JsonSubTypes.Type(value = IntegerArithmeticNode.class, name = "integer-arithmetic"),
        @JsonSubTypes.Type(value = TypedEqualityNode.class, name = "typed-equality"),
        @JsonSubTypes.Type(value = OptionStateNode.class, name = "option-state"),
        @JsonSubTypes.Type(value = ListStateNode.class, name = "list-state"),
        @JsonSubTypes.Type(value = ListQuantifierNode.class, name = "list-quantifier"),
        @JsonSubTypes.Type(value = ListContainsNode.class, name = "list-contains"),
        @JsonSubTypes.Type(value = ListCountNode.class, name = "list-count"),
        @JsonSubTypes.Type(value = ListAtNode.class, name = "list-at"),
        @JsonSubTypes.Type(value = StructuralEqualsNode.class, name = "structural-equals"),
        @JsonSubTypes.Type(value = MapQuantifierNode.class, name = "map-quantifier"),
        @JsonSubTypes.Type(value = MapCountEntryNode.class, name = "map-count-entry"),
        @JsonSubTypes.Type(value = MapContainsKeyNode.class, name = "map-contains-key"),
        @JsonSubTypes.Type(value = MapCountKeyNode.class, name = "map-count-key"),
        @JsonSubTypes.Type(value = MapLookupFirstNode.class, name = "map-lookup-first"),
        @JsonSubTypes.Type(value = MapLookupAllNode.class, name = "map-lookup-all")
})
public sealed interface PropertyNode permits RootNode, FieldNode, BoolBinaryNode,
        ContainsNode, CompareNode, CredentialKeyHashNode, ExistsNode, LiteralNode,
        BytesLiteralNode, TxOutRefLiteralNode, ConsumesNode, ExactOwnPolicyAssetNode,
        TxCertKindNode, KnownCertificateNode, TypedRootNode, TypedVariableNode,
        TypedFieldNode, VariantFieldNode, OptionExistsNode, VariantIsNode,
        VariantWhenNode, BoolLiteralNode, BoolNotNode, IntegerArithmeticNode,
        TypedEqualityNode, OptionStateNode, ListStateNode, ListQuantifierNode,
        ListContainsNode, ListCountNode, ListAtNode, StructuralEqualsNode,
        MapQuantifierNode, MapCountEntryNode, MapContainsKeyNode, MapCountKeyNode,
        MapLookupFirstNode, MapLookupAllNode {
    DslType resultType();
}
