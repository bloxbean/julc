package com.bloxbean.cardano.julc.verification.dsl.ir;

import com.bloxbean.cardano.julc.verification.dsl.type.VerificationTypeRef;

import java.util.List;
import java.util.Objects;

/** Closed helpers whose semantics are pinned to CardanoLedgerApiBlaster V3. */
public record LedgerHelperNode(
        LedgerHelperKind helper,
        List<PropertyNode> arguments,
        VerificationTypeRef valueType) implements PropertyNode {
    public LedgerHelperNode {
        helper = Objects.requireNonNull(helper, "helper");
        arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
        valueType = Objects.requireNonNull(valueType, "valueType");
    }
    @Override public DslType resultType() { return DslType.TYPED_VALUE; }

    public enum LedgerHelperKind {
        CURRENT_OUTPUT_REF,
        CURRENT_SCRIPT_PURPOSE,
        FIND_OWN_INPUT,
        RESOLVE_INPUT,
        FILTER_PAYMENT_KEY_INPUTS,
        FILTER_SCRIPT_INPUTS,
        CONTINUING_OUTPUTS,
        LOVELACE_OF
    }
}
