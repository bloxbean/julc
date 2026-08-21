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
        state.capabilities.add(switch (purpose) {
            case SPENDING -> "purpose.spending";
            case MINTING -> "purpose.minting";
            case REWARDING -> "purpose.rewarding";
            case CERTIFYING -> "purpose.certifying";
        });
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
            case VALID_REWARDING_V3_PINNED -> {
                state.rewardingDomain = true;
                state.capabilities.add("ledger.validRewardingContext");
            }
            case VALID_CERTIFYING_V3_PINNED -> {
                state.certifyingDomain = true;
                state.capabilities.add("ledger.validCertifyingContext");
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
                state.spendingDomain, state.mintingDomain,
                state.rewardingCredential, state.rewardingDomain,
                state.certificate, state.certificateIndex, state.certifyingDomain);
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
                case "rewardingCredential" -> {
                    state.rewardingCredential = true;
                    state.capabilities.add("helper.credentialInWithdrawals");
                }
                case "certificate" -> state.certificate = true;
                case "certificateIndex" -> state.certificateIndex = true;
                default -> {
                    if (!state.binders.contains(root.name())) {
                        throw new IllegalArgumentException(
                                "No dependency mapping for symbolic root " + root.name());
                    }
                }
            }
            return;
        }
        if (node instanceof TypedRootNode root) {
            switch (root.name()) {
                case "typedDatum" -> state.datum = true;
                case "typedRedeemer" -> state.redeemerDecode = true;
                default -> throw new IllegalArgumentException(
                        "No dependency mapping for typed root " + root.name());
            }
            state.capabilities.add("encoding.isDataV3");
            state.capabilities.add("dsl.schema.typed-root");
            return;
        }
        if (node instanceof TypedVariableNode) return;
        if (node instanceof FieldNode field) {
            state.capabilities.add(capability(field));
            visit(field.target(), state);
            return;
        }
        if (node instanceof TypedFieldNode field) {
            state.capabilities.add("dsl.schema.typed-field");
            visit(field.target(), state);
            return;
        }
        if (node instanceof VariantFieldNode field) {
            state.capabilities.add("dsl.schema.variant-field");
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
        if (node instanceof TxCertKindNode kind) {
            state.certificate = true;
            state.capabilities.add("dsl.txCert.kind." + kind.kind());
            visit(kind.certificate(), state);
            return;
        }
        if (node instanceof KnownCertificateNode known) {
            state.certificate = true;
            state.certificateIndex = true;
            state.capabilities.add("helper.isKnownCertificate");
            visit(known.certificate(), state);
            visit(known.index(), state);
            visit(known.certificates(), state);
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
        if (node instanceof OptionExistsNode exists) {
            state.capabilities.add("dsl.option.exists");
            visit(exists.optional(), state);
            if (!state.binders.add(exists.variable())) {
                throw new IllegalArgumentException(
                        "Duplicate dependency-plan binder " + exists.variable());
            }
            visit(exists.predicate(), state);
            state.binders.remove(exists.variable());
            return;
        }
        if (node instanceof VariantIsNode variant) {
            state.capabilities.add("dsl.variant.is-constructor");
            visit(variant.value(), state);
            return;
        }
        if (node instanceof VariantWhenNode variant) {
            state.capabilities.add("dsl.variant.when-constructor");
            visit(variant.value(), state);
            if (!state.binders.add(variant.variable())) {
                throw new IllegalArgumentException(
                        "Duplicate dependency-plan binder " + variant.variable());
            }
            visit(variant.predicate(), state);
            state.binders.remove(variant.variable());
            return;
        }
        if (node instanceof BoolLiteralNode) return;
        if (node instanceof BoolNotNode not) {
            visit(not.value(), state);
            return;
        }
        if (node instanceof IntegerArithmeticNode arithmetic) {
            visit(arithmetic.left(), state);
            if (arithmetic.right() != null) visit(arithmetic.right(), state);
            return;
        }
        if (node instanceof TypedEqualityNode equality) {
            visit(equality.left(), state);
            visit(equality.right(), state);
            return;
        }
        if (node instanceof OptionStateNode option) {
            visit(option.optional(), state);
            return;
        }
        if (node instanceof ListStateNode list) {
            visit(list.list(), state);
            return;
        }
        if (node instanceof ListQuantifierNode list) {
            visit(list.list(), state);
            withBinder(state, list.variable(), () -> visit(list.predicate(), state));
            return;
        }
        if (node instanceof ListContainsNode list) {
            visit(list.list(), state);
            visit(list.value(), state);
            return;
        }
        if (node instanceof ListCountNode list) {
            visit(list.list(), state);
            withBinder(state, list.variable(), () -> visit(list.predicate(), state));
            return;
        }
        if (node instanceof ListAtNode list) {
            visit(list.list(), state);
            visit(list.index(), state);
            return;
        }
        if (node instanceof StructuralEqualsNode equality) {
            visit(equality.left(), state);
            visit(equality.right(), state);
            return;
        }
        if (node instanceof MapQuantifierNode map) {
            visit(map.map(), state);
            withBinder(state, map.keyVariable(), () -> withBinder(
                    state, map.valueVariable(), () -> visit(map.predicate(), state)));
            return;
        }
        if (node instanceof MapCountEntryNode map) {
            visit(map.map(), state);
            withBinder(state, map.keyVariable(), () -> withBinder(
                    state, map.valueVariable(), () -> visit(map.predicate(), state)));
            return;
        }
        if (node instanceof MapContainsKeyNode map) {
            visit(map.map(), state);
            visit(map.key(), state);
            return;
        }
        if (node instanceof MapCountKeyNode map) {
            visit(map.map(), state);
            visit(map.key(), state);
            return;
        }
        if (node instanceof MapLookupFirstNode map) {
            visit(map.map(), state);
            visit(map.key(), state);
            return;
        }
        if (node instanceof MapLookupAllNode map) {
            visit(map.map(), state);
            visit(map.key(), state);
            return;
        }
        if (node instanceof LiteralNode || node instanceof BytesLiteralNode
                || node instanceof TxOutRefLiteralNode) return;
        throw new IllegalArgumentException("No dependency mapping for node " + node.getClass());
    }

    private static void withBinder(State state, String binder, Runnable body) {
        if (!state.binders.add(binder)) {
            throw new IllegalArgumentException("Duplicate dependency-plan binder " + binder);
        }
        try {
            body.run();
        } finally {
            state.binders.remove(binder);
        }
    }

    private static String capability(FieldNode field) {
        if (field.target().resultType() == DslType.DATA) return "dsl.schema.datum.field";
        return switch (field.target().resultType() + "." + field.name()) {
            case "SCRIPT_CONTEXT.txInfo" -> "field.scriptContext.txInfo";
            case "TX_INFO.signatories" -> "field.txInfo.signatories";
            case "TX_INFO.outputs" -> "field.txInfo.outputs";
            case "TX_INFO.inputs" -> "field.txInfo.inputs";
            case "TX_INFO.mint" -> "field.txInfo.mint";
            case "TX_INFO.withdrawals" -> "field.txInfo.withdrawals";
            case "TX_INFO.certificates" -> "field.txInfo.certificates";
            case "TX_OUT.address" -> "dsl.field.txOut.address";
            case "TX_OUT.value" -> "dsl.field.txOut.value";
            case "ADDRESS.credential" -> "dsl.field.address.credential";
            case "VALUE.lovelace" -> "dsl.helper.lovelaceOf";
            case "WITHDRAWAL_ENTRY.credential", "WITHDRAWAL_ENTRY.amount" ->
                    "field.txInfo.withdrawals";
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
            case ExistsNode exists -> "exists:" + exists.collection().resultType();
            case LiteralNode ignored -> "integer-literal";
            case BytesLiteralNode literal -> "bytes-literal:" + literal.kind();
            case TxOutRefLiteralNode ignored -> "tx-out-ref-literal";
            case TxCertKindNode kind -> "tx-cert-kind:" + kind.kind();
            case KnownCertificateNode ignored -> "known-certificate";
            case TypedRootNode root -> "typed-root:" + root.name();
            case TypedVariableNode ignored -> "typed-variable";
            case TypedFieldNode field -> "typed-field:" + field.ownerType().stableId()
                    + "." + field.name();
            case VariantFieldNode field -> "variant-field:"
                    + field.sumType().stableId() + "." + field.constructor()
                    + "." + field.name();
            case OptionExistsNode ignored -> "option-exists";
            case VariantIsNode variant -> "variant-is:" + variant.constructor();
            case VariantWhenNode variant -> "variant-when:" + variant.constructor();
            case BoolLiteralNode literal -> "bool-literal:" + literal.value();
            case BoolNotNode ignored -> "bool:not";
            case IntegerArithmeticNode arithmetic ->
                    "integer-arithmetic:" + arithmetic.operator();
            case TypedEqualityNode equality ->
                    "typed-equality:" + (equality.negated() ? "ne" : "eq");
            case OptionStateNode option -> "option-state:" + option.state();
            case ListStateNode list -> "list-state:" + list.state();
            case ListQuantifierNode list -> "list-quantifier:" + list.quantifier();
            case ListContainsNode ignored -> "list-contains";
            case ListCountNode ignored -> "list-count";
            case ListAtNode ignored -> "list-at";
            case StructuralEqualsNode equality ->
                    "structural-equality:" + (equality.negated() ? "ne" : "eq");
            case MapQuantifierNode map -> "map-quantifier:" + map.quantifier();
            case MapCountEntryNode ignored -> "map-count-entry";
            case MapContainsKeyNode ignored -> "map-contains-key";
            case MapCountKeyNode ignored -> "map-count-key";
            case MapLookupFirstNode ignored -> "map-lookup-first";
            case MapLookupAllNode ignored -> "map-lookup-all";
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
            boolean needsMintingDomain,
            boolean needsRewardingCredential,
            boolean needsRewardingDomain,
            boolean needsCertificate,
            boolean needsCertificateIndex,
            boolean needsCertifyingDomain) { }

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
        private boolean rewardingCredential;
        private boolean rewardingDomain;
        private boolean certificate;
        private boolean certificateIndex;
        private boolean certifyingDomain;
    }
}
