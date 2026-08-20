package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.capability.CapabilityStatus;
import com.bloxbean.cardano.julc.verification.capability.LedgerCapabilityInventories;
import com.bloxbean.cardano.julc.verification.dsl.ir.*;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Exhaustive semantic dependency and capability plan for one admitted guarantee. */
public final class DslSemanticDependencies {
    private DslSemanticDependencies() { }

    public static Plan collect(DslProperty property, DslPurpose purpose) {
        var state = new State();
        state.capabilities.add(purpose == DslPurpose.SPENDING
                ? "purpose.spending" : "purpose.minting");
        state.capabilities.add("field.scriptContext.scriptInfo");
        switch (property.domain()) {
            case NONE -> { }
            case VALID_SPENDING_V3_PINNED -> {
                state.spendingDomain = true;
                state.capabilities.add("ledger.validSpendingContext");
            }
            case VALID_MINTING_V3_PINNED -> {
                state.mintingDomain = true;
                state.capabilities.add("ledger.validMintingContext");
            }
        }
        visit(property.expression(), state);
        var capabilities = state.capabilities.stream().sorted().toList();
        var inventory = LedgerCapabilityInventories.pinnedV3();
        capabilities.stream().filter(id -> !id.startsWith("dsl."))
                .forEach(id -> {
                    if (inventory.require(id).status() != CapabilityStatus.TYPED) {
                        throw new IllegalArgumentException(
                                "Ledger capability is not admitted as TYPED: " + id);
                    }
                });
        return new Plan(capabilities,
                state.rules.stream().sorted().toList(), state.datum,
                state.redeemerDecode, state.ownPolicy, state.rawMint,
                state.spendingDomain, state.mintingDomain);
    }

    private static void visit(PropertyNode node, State state) {
        state.rules.add(rule(node));
        if (node instanceof RootNode root) {
            switch (root.name()) {
                case "datum" -> {
                    state.datum = true;
                    state.capabilities.add("encoding.isDataV3");
                    state.capabilities.add("dsl.schema.datum.field");
                }
                case "context" -> { }
                case "redeemerStrictlyDecodes" -> {
                    state.redeemerDecode = true;
                    state.capabilities.add("field.scriptContext.redeemer");
                    state.capabilities.add("encoding.isDataV3");
                }
                case "ownPolicy" -> {
                    state.ownPolicy = true;
                    state.capabilities.add("helper.ownCurrencySymbol");
                }
                default -> {
                    if (!state.binders.contains(root.name())) {
                        throw new IllegalArgumentException(
                                "No dependency mapping for symbolic root " + root.name());
                    }
                }
            }
            return;
        }
        if (node instanceof FieldNode field) {
            state.capabilities.add(capability(field));
            visit(field.target(), state);
            return;
        }
        if (node instanceof BoolBinaryNode binary) {
            visit(binary.left(), state);
            visit(binary.right(), state);
            return;
        }
        if (node instanceof ContainsNode contains) {
            visit(contains.collection(), state);
            visit(contains.value(), state);
            if (contains.collection().resultType() == DslType.LIST_BYTE_STRING) {
                state.capabilities.add("helper.txSignedBy");
            }
            return;
        }
        if (node instanceof ConsumesNode consumes) {
            state.capabilities.add("helper.utxoConsumed");
            visit(consumes.inputs(), state);
            visit(consumes.outputReference(), state);
            return;
        }
        if (node instanceof ExactOwnPolicyAssetNode exact) {
            state.rawMint = true;
            state.capabilities.add("field.txInfo.mint");
            visit(exact.mint(), state);
            visit(exact.policy(), state);
            visit(exact.tokenName(), state);
            visit(exact.quantity(), state);
            return;
        }
        if (node instanceof CompareNode comparison) {
            visit(comparison.left(), state);
            visit(comparison.right(), state);
            return;
        }
        if (node instanceof CredentialKeyHashNode credential) {
            visit(credential.credential(), state);
            visit(credential.keyHash(), state);
            return;
        }
        if (node instanceof ExistsNode exists) {
            visit(exists.collection(), state);
            if (!state.binders.add(exists.variable())) {
                throw new IllegalArgumentException(
                        "Duplicate dependency-plan binder " + exists.variable());
            }
            visit(exists.predicate(), state);
            state.binders.remove(exists.variable());
            return;
        }
        if (node instanceof LiteralNode || node instanceof BytesLiteralNode
                || node instanceof TxOutRefLiteralNode) return;
        throw new IllegalArgumentException("No dependency mapping for node " + node.getClass());
    }

    private static String capability(FieldNode field) {
        if (field.target().resultType() == DslType.DATA) return "dsl.schema.datum.field";
        return switch (field.target().resultType() + "." + field.name()) {
            case "SCRIPT_CONTEXT.txInfo" -> "field.scriptContext.txInfo";
            case "TX_INFO.signatories" -> "field.txInfo.signatories";
            case "TX_INFO.outputs" -> "field.txInfo.outputs";
            case "TX_INFO.inputs" -> "field.txInfo.inputs";
            case "TX_INFO.mint" -> "field.txInfo.mint";
            case "TX_OUT.address" -> "dsl.field.txOut.address";
            case "TX_OUT.value" -> "dsl.field.txOut.value";
            case "ADDRESS.credential" -> "dsl.field.address.credential";
            case "VALUE.lovelace" -> "dsl.helper.lovelaceOf";
            default -> throw new IllegalArgumentException(
                    "No capability mapping for field " + field.name());
        };
    }

    private static String rule(PropertyNode node) {
        return switch (node) {
            case RootNode root -> "root:" + root.name();
            case FieldNode field -> "field:" + field.target().resultType() + "." + field.name();
            case BoolBinaryNode binary -> "bool:" + binary.operator();
            case ContainsNode ignored -> "contains";
            case ConsumesNode ignored -> "consumes-output-reference";
            case ExactOwnPolicyAssetNode ignored -> "exact-own-policy-asset";
            case CompareNode comparison -> "compare:" + comparison.operator();
            case CredentialKeyHashNode ignored -> "credential-key-hash";
            case ExistsNode ignored -> "exists-output";
            case LiteralNode ignored -> "integer-literal";
            case BytesLiteralNode literal -> "bytes-literal:" + literal.kind();
            case TxOutRefLiteralNode ignored -> "tx-out-ref-literal";
        };
    }

    public record Plan(
            List<String> capabilities,
            List<String> guaranteeRules,
            boolean needsDatumDecode,
            boolean needsRedeemerDecode,
            boolean needsOwnPolicy,
            boolean needsRawMint,
            boolean needsSpendingDomain,
            boolean needsMintingDomain) { }

    private static final class State {
        private final Set<String> capabilities = new LinkedHashSet<>();
        private final Set<String> rules = new LinkedHashSet<>();
        private final Set<String> binders = new LinkedHashSet<>();
        private boolean datum;
        private boolean redeemerDecode;
        private boolean ownPolicy;
        private boolean rawMint;
        private boolean spendingDomain;
        private boolean mintingDomain;
    }
}
