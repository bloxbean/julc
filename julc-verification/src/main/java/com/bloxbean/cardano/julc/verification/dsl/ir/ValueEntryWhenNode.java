package com.bloxbean.cardano.julc.verification.dsl.ir;

import com.bloxbean.cardano.julc.verification.dsl.type.VerificationTypeRef;

import java.util.Objects;

/** Strict eliminator for one raw policy or token entry. Malformed entries are false. */
public record ValueEntryWhenNode(
        ValueEntryKind entryKind,
        PropertyNode entry,
        VerificationTypeRef entryType,
        String keyVariable,
        VerificationTypeRef keyType,
        String valueVariable,
        VerificationTypeRef valueType,
        PropertyNode predicate) implements PropertyNode {
    public ValueEntryWhenNode {
        entryKind = Objects.requireNonNull(entryKind, "entryKind");
        entry = Objects.requireNonNull(entry, "entry");
        entryType = Objects.requireNonNull(entryType, "entryType");
        keyVariable = Objects.requireNonNull(keyVariable, "keyVariable");
        keyType = Objects.requireNonNull(keyType, "keyType");
        valueVariable = Objects.requireNonNull(valueVariable, "valueVariable");
        valueType = Objects.requireNonNull(valueType, "valueType");
        predicate = Objects.requireNonNull(predicate, "predicate");
    }
    @Override public DslType resultType() { return DslType.BOOL; }

    public enum ValueEntryKind { POLICY, TOKEN }
}
