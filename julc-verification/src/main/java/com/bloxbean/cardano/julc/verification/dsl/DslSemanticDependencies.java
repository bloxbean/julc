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
        if (node instanceof LedgerRootNode root) {
            switch (root.name()) {
                case "ledgerContext" -> state.capabilities.add("dsl.ledger.context");
                case "currentCertificate" -> {
                    state.certificate = true;
                    state.capabilities.add("purpose.certifying");
                }
                case "rewardingCredential" -> {
                    state.rewardingCredential = true;
                    state.capabilities.add("helper.credentialInWithdrawals");
                    state.capabilities.add("purpose.rewarding");
                }
                default -> throw new IllegalArgumentException(
                        "No dependency mapping for ledger root " + root.name());
            }
            return;
        }
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
        if (node instanceof LedgerFieldNode field) {
            state.capabilities.add(LedgerTypeAuthority.fieldCapability(
                    field.ownerType(), field.name()));
            visit(field.target(), state);
            return;
        }
        if (node instanceof LedgerVariantFieldNode field) {
            state.capabilities.add(LedgerTypeAuthority.constructorCapability(
                    field.sumType(), field.constructor()));
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
        if (node instanceof StrictDecodeNode decoded) {
            state.capabilities.add("dsl.schema.strict-data-decode");
            state.capabilities.add("encoding.isDataV3");
            visit(decoded.data(), state);
            withBinder(state, decoded.variable(),
                    () -> visit(decoded.predicate(), state));
            return;
        }
        if (node instanceof LedgerVariantIsNode variant) {
            state.capabilities.add(LedgerTypeAuthority.constructorCapability(
                    variant.sumType(), variant.constructor()));
            visit(variant.value(), state);
            return;
        }
        if (node instanceof LedgerVariantWhenNode variant) {
            state.capabilities.add(LedgerTypeAuthority.constructorCapability(
                    variant.sumType(), variant.constructor()));
            visit(variant.value(), state);
            if (!state.binders.add(variant.variable())) {
                throw new IllegalArgumentException(
                        "Duplicate dependency-plan binder " + variant.variable());
            }
            visit(variant.predicate(), state);
            state.binders.remove(variant.variable());
            return;
        }
        if (node instanceof LedgerHelperNode helper) {
            state.capabilities.add(switch (helper.helper()) {
                case CURRENT_OUTPUT_REF -> "purpose.spending";
                case CURRENT_SCRIPT_PURPOSE -> "helper.scriptInfoToScriptPurpose";
                case FIND_OWN_INPUT -> "helper.findOwnInput";
                case RESOLVE_INPUT -> "helper.resolveInput";
                case FILTER_PAYMENT_KEY_INPUTS -> "helper.findPubKeyInputs";
                case FILTER_SCRIPT_INPUTS -> "helper.findScriptInputs";
                case CONTINUING_OUTPUTS -> "helper.continuingOutputs";
                case LOVELACE_OF -> "dsl.helper.lovelaceOf";
                case VALUE_SPENT -> "helper.valueSpent";
                case VALUE_PRODUCED -> "helper.valueProduced";
                case AGGREGATE_INPUT_VALUES -> "dsl.value.aggregate-inputs";
                case AGGREGATE_OUTPUT_VALUES -> "dsl.value.aggregate-outputs";
                case FILTER_ADDRESS_OUTPUTS -> "dsl.value.filter-full-address";
                case FILTER_PAYMENT_CREDENTIAL_OUTPUTS ->
                        "dsl.value.filter-payment-credential";
                case IS_BALANCED -> "ledger.isBalanced";
                case DECODE_GOVERNANCE_ACTION -> "dsl.governance.decode-action-strict";
                case IS_KNOWN_VOTER -> "helper.isKnownVoter";
                case IS_KNOWN_PROPOSAL -> "helper.isKnownProposal";
            });
            if (helper.helper() == LedgerHelperNode.LedgerHelperKind.VALUE_SPENT
                    || helper.helper() == LedgerHelperNode.LedgerHelperKind.VALUE_PRODUCED
                    || helper.helper()
                        == LedgerHelperNode.LedgerHelperKind.AGGREGATE_INPUT_VALUES
                    || helper.helper()
                        == LedgerHelperNode.LedgerHelperKind.AGGREGATE_OUTPUT_VALUES) {
                state.capabilities.add("helper.valueMerge");
            }
            helper.arguments().forEach(argument -> visit(argument, state));
            if (helper.helper() == LedgerHelperNode.LedgerHelperKind.IS_BALANCED) {
                state.rules.add("domain-implied:is-balanced");
            }
            return;
        }
        if (node instanceof ValueEntriesNode entries) {
            state.capabilities.add("dsl.value.raw-policy-entries");
            visit(entries.value(), state);
            return;
        }
        if (node instanceof ValueEntryWhenNode entry) {
            state.capabilities.add(entry.entryKind()
                    == ValueEntryWhenNode.ValueEntryKind.POLICY
                    ? "dsl.value.strict-policy-entry"
                    : "dsl.value.strict-token-entry");
            visit(entry.entry(), state);
            if (!state.binders.add(entry.keyVariable())
                    || !state.binders.add(entry.valueVariable())) {
                throw new IllegalArgumentException("Duplicate value-entry binder");
            }
            visit(entry.predicate(), state);
            state.binders.remove(entry.valueVariable());
            state.binders.remove(entry.keyVariable());
            return;
        }
        if (node instanceof ValueQuantityNode quantity) {
            state.capabilities.add(quantity.quantityKind()
                    == ValueQuantityNode.ValueQuantityKind.FIRST_MATCH
                    ? "helper.valueOf"
                    : "dsl.value.quantity-sum-strict");
            state.rules.add("value-quantity:"
                    + quantity.quantityKind().name().toLowerCase(java.util.Locale.ROOT));
            visit(quantity.value(), state);
            visit(quantity.policy(), state);
            visit(quantity.token(), state);
            return;
        }
        if (node instanceof ValueRelationNode relation) {
            state.capabilities.add("dsl.value.relation:"
                    + relation.relation().name().toLowerCase(java.util.Locale.ROOT));
            state.rules.add("value-relation:"
                    + relation.relation().name().toLowerCase(java.util.Locale.ROOT));
            visit(relation.left(), state);
            visit(relation.right(), state);
            return;
        }
        if (node instanceof ValueArithmeticNode arithmetic) {
            state.capabilities.add("dsl.value.arithmetic:"
                    + arithmetic.arithmetic().name().toLowerCase(java.util.Locale.ROOT));
            arithmetic.arguments().forEach(argument -> visit(argument, state));
            return;
        }
        if (node instanceof ReviewedAdapterPredicateNode adapter) {
            state.capabilities.add(adapterCapability(adapter.predicate()));
            adapter.arguments().forEach(argument -> visit(argument, state));
            return;
        }
        if (node instanceof ReviewedAdapterWhenNode adapter) {
            state.capabilities.add(switch (adapter.eliminator()) {
                case CURRENT_TREASURY_PRESENT ->
                        "adapter.current-treasury.strict-optional-integer";
                case TREASURY_DONATION_PRESENT ->
                        "adapter.treasury-donation.strict-optional-integer";
                case QUORUM_DECODED -> "adapter.quorum.plutus-rational-v1";
            });
            visit(adapter.source(), state);
            Runnable predicate = () -> visit(adapter.predicate(), state);
            for (int index = adapter.variables().size() - 1; index >= 0; index--) {
                String variable = adapter.variables().get(index);
                Runnable nested = predicate;
                predicate = () -> withBinder(state, variable, nested);
            }
            predicate.run();
            return;
        }
        if (node instanceof LedgerByteAliasNode alias) {
            state.capabilities.add("dsl.ledger.byte-alias:" + alias.aliasType().ledgerType());
            visit(alias.bytes(), state);
            return;
        }
        if (node instanceof AuthorityKeyHashNode authority) {
            state.capabilities.add("dsl.authorization.source:"
                    + authority.sourceKind());
            visit(authority.bytes(), state);
            return;
        }
        if (node instanceof AuthorityListNode authorities) {
            state.capabilities.add("dsl.authorization.static-authorities");
            authorities.authorities().forEach(authority -> visit(authority, state));
            return;
        }
        if (node instanceof AuthorityListFromBytesNode authorities) {
            state.capabilities.add("dsl.authorization.dynamic-contract-list");
            visit(authorities.bytesList(), state);
            return;
        }
        if (node instanceof AuthorizationNode authorization) {
            state.capabilities.add("field.txInfo.signatories");
            state.capabilities.add("dsl.authorization.distinct-identities");
            visit(authorization.authorities(), state);
            return;
        }
        if (node instanceof NoSignersNode) {
            state.capabilities.add("field.txInfo.signatories");
            state.capabilities.add("dsl.authorization.no-signers");
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
        if (node instanceof ListSingletonWhenNode list) {
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
            case StrictDecodeNode decoded -> strictDecodeRule(decoded);
            case BoolLiteralNode literal -> "bool-literal:" + literal.value();
            case BoolNotNode ignored -> "bool:not";
            case IntegerArithmeticNode arithmetic ->
                    "integer-arithmetic:" + arithmetic.operator();
            case TypedEqualityNode equality ->
                    "typed-equality:" + (equality.negated() ? "ne" : "eq");
            case OptionStateNode option -> "option-state:" + option.state();
            case ListStateNode list -> "list-state:" + list.state();
            case ListQuantifierNode list -> "list-quantifier:" + list.quantifier();
            case ListSingletonWhenNode ignored -> "list-singleton-when";
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
            case LedgerRootNode root -> "ledger-root:" + root.name();
            case LedgerFieldNode field -> "ledger-field:"
                    + field.ownerType().ledgerType() + "." + field.name();
            case LedgerVariantFieldNode field -> "ledger-variant-field:"
                    + field.sumType().ledgerType() + "." + field.constructor()
                    + "." + field.name();
            case LedgerVariantIsNode variant -> "ledger-variant-is:"
                    + variant.sumType().ledgerType() + "." + variant.constructor();
            case LedgerVariantWhenNode variant -> "ledger-variant-when:"
                    + variant.sumType().ledgerType() + "." + variant.constructor();
            case LedgerHelperNode helper -> "ledger-helper:" + helper.helper();
            case LedgerByteAliasNode alias -> "ledger-byte-alias:"
                    + alias.aliasType().ledgerType();
            case AuthorityKeyHashNode authority -> "authority-source:"
                    + authority.sourceKind();
            case AuthorityListNode ignored -> "authority-list:bounded-static";
            case AuthorityListFromBytesNode ignored ->
                    "authority-list:dynamic-contract-bytes";
            case AuthorizationNode authorization -> "authorization:"
                    + authorization.relation()
                    + (authorization.threshold() == null
                            ? "" : ":" + authorization.threshold());
            case NoSignersNode ignored -> "authorization:NO_SIGNERS";
            case ValueEntriesNode ignored -> "value-raw-policy-entries";
            case ValueEntryWhenNode entry -> "value-entry-when:" + entry.entryKind();
            case ValueQuantityNode quantity -> "value-quantity:" + quantity.quantityKind();
            case ValueRelationNode relation -> "value-relation:" + relation.relation();
            case ValueArithmeticNode arithmetic ->
                    "value-arithmetic:" + arithmetic.arithmetic();
            case ReviewedAdapterPredicateNode adapter ->
                    "reviewed-adapter:" + adapter.predicate();
            case ReviewedAdapterWhenNode adapter ->
                    "reviewed-adapter-when:" + adapter.eliminator();
        };
    }

    private static String strictDecodeRule(StrictDecodeNode decoded) {
        if (!(decoded.decodedType()
                instanceof com.bloxbean.cardano.julc.verification.dsl.type.NominalTypeRef
                nominal)) {
            throw new IllegalArgumentException(
                    "Strict contract decode target is not nominal");
        }
        return "strict-decode:" + nominal.stableId();
    }

    private static String adapterCapability(
            ReviewedAdapterPredicateNode.ReviewedAdapterPredicate predicate) {
        return switch (predicate) {
            case VALIDITY_DECODER_VALID, VALIDITY_CANONICAL_ENCODING,
                    VALIDITY_EMPTY, VALIDITY_CONTAINS, VALIDITY_INCLUDES,
                    VALIDITY_ENTIRELY_BEFORE, VALIDITY_ENTIRELY_AFTER ->
                    "adapter.validity-range.pinned-v1";
            case CURRENT_TREASURY_WELL_FORMED, CURRENT_TREASURY_ABSENT,
                    CURRENT_TREASURY_MALFORMED ->
                    "adapter.current-treasury.strict-optional-integer";
            case TREASURY_DONATION_WELL_FORMED, TREASURY_DONATION_ABSENT,
                    TREASURY_DONATION_MALFORMED ->
                    "adapter.treasury-donation.strict-optional-integer";
            case CHANGED_PARAMETERS_WELL_FORMED, CHANGED_PARAMETERS_NON_EMPTY,
                    CHANGED_PARAMETERS_STRICTLY_ASCENDING_UNIQUE,
                    CHANGED_PARAMETERS_CONTAINS_ID,
                    CHANGED_PARAMETERS_COUNT_ID_EQUALS ->
                    "adapter.changed-parameters.integer-key-index";
            case QUORUM_DECODER_VALID, QUORUM_CANONICAL_ENCODING,
                    QUORUM_UNIT_INTERVAL -> "adapter.quorum.plutus-rational-v1";
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
