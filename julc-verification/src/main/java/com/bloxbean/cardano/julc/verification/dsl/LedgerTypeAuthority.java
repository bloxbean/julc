package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.capability.CapabilityStatus;
import com.bloxbean.cardano.julc.verification.capability.LedgerCapabilityInventories;
import com.bloxbean.cardano.julc.verification.dsl.type.*;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Closed schema-5 view of the pinned CardanoLedgerApiBlaster V3 type surface. */
final class LedgerTypeAuthority {
    static final LedgerTypeRef SCRIPT_CONTEXT = ledger(LedgerTypeRef.LedgerKind.SCRIPT_CONTEXT);
    static final LedgerTypeRef TX_INFO = ledger(LedgerTypeRef.LedgerKind.TX_INFO);
    static final LedgerTypeRef TX_IN_INFO = ledger(LedgerTypeRef.LedgerKind.TX_IN_INFO);
    static final LedgerTypeRef TX_OUT_REF = ledger(LedgerTypeRef.LedgerKind.TX_OUT_REF);
    static final LedgerTypeRef TX_ID = ledger(LedgerTypeRef.LedgerKind.TX_ID);
    static final LedgerTypeRef TX_OUT = ledger(LedgerTypeRef.LedgerKind.TX_OUT);
    static final LedgerTypeRef VALUE = ledger(LedgerTypeRef.LedgerKind.VALUE);
    static final LedgerTypeRef MINT_VALUE = ledger(LedgerTypeRef.LedgerKind.MINT_VALUE);
    static final LedgerTypeRef VALUE_DELTA = ledger(LedgerTypeRef.LedgerKind.VALUE_DELTA);
    static final LedgerTypeRef VALUE_POLICY_ENTRY = ledger(
            LedgerTypeRef.LedgerKind.VALUE_POLICY_ENTRY);
    static final LedgerTypeRef VALUE_TOKEN_ENTRY = ledger(
            LedgerTypeRef.LedgerKind.VALUE_TOKEN_ENTRY);
    static final LedgerTypeRef TOKEN_NAME = ledger(LedgerTypeRef.LedgerKind.TOKEN_NAME);
    static final LedgerTypeRef ADDRESS = ledger(LedgerTypeRef.LedgerKind.ADDRESS);
    static final LedgerTypeRef CREDENTIAL = ledger(LedgerTypeRef.LedgerKind.CREDENTIAL);
    static final LedgerTypeRef STAKING_CREDENTIAL = ledger(
            LedgerTypeRef.LedgerKind.STAKING_CREDENTIAL);
    static final LedgerTypeRef OUTPUT_DATUM = ledger(LedgerTypeRef.LedgerKind.OUTPUT_DATUM);
    static final LedgerTypeRef DATUM_HASH = ledger(LedgerTypeRef.LedgerKind.DATUM_HASH);
    static final LedgerTypeRef SCRIPT_HASH = ledger(LedgerTypeRef.LedgerKind.SCRIPT_HASH);
    static final LedgerTypeRef PUB_KEY_HASH = ledger(LedgerTypeRef.LedgerKind.PUB_KEY_HASH);
    static final LedgerTypeRef SCRIPT_PURPOSE = ledger(LedgerTypeRef.LedgerKind.SCRIPT_PURPOSE);
    static final LedgerTypeRef CURRENCY_SYMBOL = ledger(
            LedgerTypeRef.LedgerKind.CURRENCY_SYMBOL);
    static final LedgerTypeRef TX_CERT = ledger(LedgerTypeRef.LedgerKind.TX_CERT);
    static final LedgerTypeRef DELEGATEE = ledger(LedgerTypeRef.LedgerKind.DELEGATEE);
    static final LedgerTypeRef DREP = ledger(LedgerTypeRef.LedgerKind.DREP);
    static final LedgerTypeRef VOTER = ledger(LedgerTypeRef.LedgerKind.VOTER);
    static final LedgerTypeRef VOTE = ledger(LedgerTypeRef.LedgerKind.VOTE);
    static final LedgerTypeRef GOVERNANCE_ACTION_ID = ledger(
            LedgerTypeRef.LedgerKind.GOVERNANCE_ACTION_ID);
    static final LedgerTypeRef PROTOCOL_VERSION = ledger(
            LedgerTypeRef.LedgerKind.PROTOCOL_VERSION);
    static final LedgerTypeRef PROPOSAL_PROCEDURE = ledger(
            LedgerTypeRef.LedgerKind.PROPOSAL_PROCEDURE);
    static final LedgerTypeRef GOVERNANCE_ACTION = ledger(
            LedgerTypeRef.LedgerKind.GOVERNANCE_ACTION);
    static final LedgerTypeRef OPAQUE_VOTER = ledger(LedgerTypeRef.LedgerKind.OPAQUE_VOTER);
    static final LedgerTypeRef OPAQUE_PROPOSAL = ledger(
            LedgerTypeRef.LedgerKind.OPAQUE_PROPOSAL);

    static final BuiltinTypeRef INTEGER = builtin(BuiltinTypeRef.BuiltinKind.INTEGER);
    static final BuiltinTypeRef BOOL = builtin(BuiltinTypeRef.BuiltinKind.BOOLEAN);
    static final BuiltinTypeRef DATA = builtin(BuiltinTypeRef.BuiltinKind.DATA);

    private static final Map<FieldKey, Field> FIELDS = fields();
    private static final Map<ConstructorKey, Constructor> CONSTRUCTORS = constructors();

    private LedgerTypeAuthority() { }

    static VerificationTypeRef field(
            LedgerTypeRef owner, String name, VerificationTypeRef claimed) {
        Field field = FIELDS.get(new FieldKey(owner.ledgerType(), name));
        if (field == null) {
            throw new IllegalArgumentException(
                    "Unknown pinned ledger field " + owner.ledgerType() + "." + name);
        }
        requireTyped(field.capability());
        requireKnown(field.type());
        if (!field.type().equals(claimed)) {
            throw new IllegalArgumentException(
                    "Ledger field result does not match pinned model for "
                            + owner.ledgerType() + "." + name);
        }
        return field.type();
    }

    static String fieldCapability(LedgerTypeRef owner, String name) {
        Field field = FIELDS.get(new FieldKey(owner.ledgerType(), name));
        if (field == null) {
            throw new IllegalArgumentException(
                    "Unknown pinned ledger field " + owner.ledgerType() + "." + name);
        }
        return field.capability();
    }

    static Constructor constructor(
            LedgerTypeRef sum, String name) {
        Constructor constructor = CONSTRUCTORS.get(
                new ConstructorKey(sum.ledgerType(), name));
        if (constructor == null) {
            throw new IllegalArgumentException(
                    "Unknown pinned ledger constructor " + sum.ledgerType() + "." + name);
        }
        requireTyped(constructor.capability());
        constructor.fields().values().forEach(LedgerTypeAuthority::requireKnown);
        return constructor;
    }

    static String constructorCapability(LedgerTypeRef sum, String name) {
        return constructor(sum, name).capability();
    }

    static VerificationTypeRef variantField(
            LedgerTypeRef sum,
            String constructor,
            String field,
            VerificationTypeRef claimed) {
        Constructor admitted = constructor(sum, constructor);
        if (admitted.hiddenFields().contains(field)) {
            throw new IllegalArgumentException(
                    "Pinned raw governance payload is not exposed by the typed DSL: "
                            + sum.ledgerType() + "." + constructor + "." + field);
        }
        VerificationTypeRef expected = admitted.fields().get(field);
        if (expected == null || !expected.equals(claimed)) {
            throw new IllegalArgumentException(
                    "Ledger constructor payload does not match pinned model for "
                            + sum.ledgerType() + "." + constructor + "." + field);
        }
        return expected;
    }

    static void requireKnown(VerificationTypeRef type) {
        switch (type) {
            case BuiltinTypeRef ignored -> { }
            // Presence in the closed enum is the type identity. Opaque governance
            // values may be transported as ScriptPurpose payloads but their fields,
            // equality, and constructors are independently rejected by admission.
            case LedgerTypeRef ignored -> { }
            case OptionalTypeRef optional -> requireKnown(optional.elementType());
            case ListTypeRef list -> requireKnown(list.elementType());
            case AssocMapTypeRef map -> {
                requireKnown(map.keyType());
                requireKnown(map.valueType());
            }
            case NominalTypeRef ignored -> throw new IllegalArgumentException(
                    "Contract nominal type requires compiler ContractSchema authority");
        }
    }

    static boolean equalityAdmitted(LedgerTypeRef type) {
        return type.ledgerType() != LedgerTypeRef.LedgerKind.OPAQUE_VOTER
                && type.ledgerType() != LedgerTypeRef.LedgerKind.OPAQUE_PROPOSAL
                && type.ledgerType() != LedgerTypeRef.LedgerKind.PROPOSAL_PROCEDURE
                && type.ledgerType() != LedgerTypeRef.LedgerKind.GOVERNANCE_ACTION;
    }

    static void requireByteAlias(LedgerTypeRef alias) {
        if (!List.of(TX_ID, DATUM_HASH, SCRIPT_HASH, PUB_KEY_HASH, CURRENCY_SYMBOL,
                        TOKEN_NAME)
                .contains(alias)) {
            throw new IllegalArgumentException(
                    "Ledger type is not an admitted byte-string alias: " + alias.ledgerType());
        }
    }

    static void requireTypedCapability(String id) {
        requireTyped(id);
    }

    private static Map<FieldKey, Field> fields() {
        var fields = new LinkedHashMap<FieldKey, Field>();
        add(fields, SCRIPT_CONTEXT, "txInfo", TX_INFO, "field.scriptContext.txInfo");
        add(fields, TX_INFO, "inputs", new ListTypeRef(TX_IN_INFO), "field.txInfo.inputs");
        add(fields, TX_INFO, "referenceInputs", new ListTypeRef(TX_IN_INFO),
                "field.txInfo.referenceInputs");
        add(fields, TX_INFO, "outputs", new ListTypeRef(TX_OUT), "field.txInfo.outputs");
        add(fields, TX_INFO, "fee", INTEGER, "field.txInfo.fee");
        add(fields, TX_INFO, "mint", MINT_VALUE, "field.txInfo.mint");
        add(fields, TX_INFO, "certificates", new ListTypeRef(TX_CERT),
                "field.txInfo.certificates");
        add(fields, TX_INFO, "datums", new AssocMapTypeRef(DATUM_HASH, DATA),
                "field.txInfo.data");
        add(fields, TX_INFO, "redeemers", new AssocMapTypeRef(SCRIPT_PURPOSE, DATA),
                "field.txInfo.redeemers");
        add(fields, TX_INFO, "votes", new AssocMapTypeRef(VOTER,
                        new AssocMapTypeRef(GOVERNANCE_ACTION_ID, VOTE)),
                "field.txInfo.votes");
        add(fields, TX_INFO, "proposals", new ListTypeRef(PROPOSAL_PROCEDURE),
                "field.txInfo.proposals");
        add(fields, TX_INFO, "id", TX_ID, "field.txInfo.id");
        add(fields, TX_IN_INFO, "outRef", TX_OUT_REF, "field.txInInfo.outRef");
        add(fields, TX_IN_INFO, "resolved", TX_OUT, "field.txInInfo.resolved");
        add(fields, TX_OUT_REF, "id", TX_ID, "field.txOutRef.id");
        add(fields, TX_OUT_REF, "index", INTEGER, "field.txOutRef.idx");
        add(fields, TX_OUT, "address", ADDRESS, "field.txOut.address");
        add(fields, TX_OUT, "value", VALUE, "field.txOut.value");
        add(fields, TX_OUT, "datum", OUTPUT_DATUM, "field.txOut.datum");
        add(fields, TX_OUT, "referenceScript", new OptionalTypeRef(SCRIPT_HASH),
                "field.txOut.referenceScript");
        add(fields, ADDRESS, "paymentCredential", CREDENTIAL,
                "field.address.credential");
        add(fields, ADDRESS, "stakingCredential", new OptionalTypeRef(STAKING_CREDENTIAL),
                "field.address.stakingCredential");
        add(fields, GOVERNANCE_ACTION_ID, "txId", TX_ID,
                "field.governanceActionId.txId");
        add(fields, GOVERNANCE_ACTION_ID, "index", INTEGER,
                "field.governanceActionId.index");
        add(fields, PROTOCOL_VERSION, "major", INTEGER,
                "field.protocolVersion.major");
        add(fields, PROTOCOL_VERSION, "minor", INTEGER,
                "field.protocolVersion.minor");
        add(fields, PROPOSAL_PROCEDURE, "deposit", INTEGER,
                "field.proposalProcedure.deposit");
        add(fields, PROPOSAL_PROCEDURE, "returnAddress", CREDENTIAL,
                "field.proposalProcedure.returnAddress");
        return Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    private static Map<ConstructorKey, Constructor> constructors() {
        var constructors = new LinkedHashMap<ConstructorKey, Constructor>();
        add(constructors, CREDENTIAL, "PubKeyCredential",
                "constructor.credential.pubKey", Map.of("keyHash", PUB_KEY_HASH));
        add(constructors, CREDENTIAL, "ScriptCredential",
                "constructor.credential.script", Map.of("scriptHash", SCRIPT_HASH));
        add(constructors, STAKING_CREDENTIAL, "StakingHash",
                "constructor.stakingCredential.hash", Map.of("credential", CREDENTIAL));
        var pointer = new LinkedHashMap<String, VerificationTypeRef>();
        pointer.put("slot", INTEGER);
        pointer.put("transactionIndex", INTEGER);
        pointer.put("certificateIndex", INTEGER);
        add(constructors, STAKING_CREDENTIAL, "StakingPtr",
                "constructor.stakingCredential.pointer", pointer);
        add(constructors, OUTPUT_DATUM, "NoOutputDatum",
                "constructor.outputDatum.none", Map.of());
        add(constructors, OUTPUT_DATUM, "OutputDatumHash",
                "constructor.outputDatum.hash", Map.of("datumHash", DATUM_HASH));
        add(constructors, OUTPUT_DATUM, "OutputDatum",
                "constructor.outputDatum.inline", Map.of("datum", DATA));
        add(constructors, SCRIPT_PURPOSE, "Minting",
                "constructor.scriptPurpose.minting", Map.of("policy", CURRENCY_SYMBOL));
        add(constructors, SCRIPT_PURPOSE, "Spending",
                "constructor.scriptPurpose.spending", Map.of("outRef", TX_OUT_REF));
        add(constructors, SCRIPT_PURPOSE, "Rewarding",
                "constructor.scriptPurpose.rewarding", Map.of("credential", CREDENTIAL));
        var certifying = new LinkedHashMap<String, VerificationTypeRef>();
        certifying.put("index", INTEGER);
        certifying.put("certificate", TX_CERT);
        add(constructors, SCRIPT_PURPOSE, "Certifying",
                "constructor.scriptPurpose.certifying", certifying);
        add(constructors, SCRIPT_PURPOSE, "Voting",
                "constructor.scriptPurpose.voting", Map.of("voter", OPAQUE_VOTER));
        var proposing = new LinkedHashMap<String, VerificationTypeRef>();
        proposing.put("index", INTEGER);
        proposing.put("proposal", OPAQUE_PROPOSAL);
        add(constructors, SCRIPT_PURPOSE, "Proposing",
                "constructor.scriptPurpose.proposing", proposing);
        add(constructors, DREP, "DRep",
                "constructor.drep.credential", Map.of("credential", CREDENTIAL));
        add(constructors, DREP, "DRepAlwaysAbstain",
                "constructor.drep.abstain", Map.of());
        add(constructors, DREP, "DRepAlwaysNoConfidence",
                "constructor.drep.noConfidence", Map.of());
        add(constructors, DELEGATEE, "DelegStake",
                "constructor.delegatee.stake", Map.of("pool", PUB_KEY_HASH));
        add(constructors, DELEGATEE, "DelegVote",
                "constructor.delegatee.vote", Map.of("drep", DREP));
        add(constructors, DELEGATEE, "DelegStakeVote",
                "constructor.delegatee.stakeVote",
                payload("pool", PUB_KEY_HASH, "drep", DREP));
        add(constructors, TX_CERT, "TxCertRegStaking",
                "constructor.txCert.regStaking",
                payload("credential", CREDENTIAL, "deposit",
                        new OptionalTypeRef(INTEGER)));
        add(constructors, TX_CERT, "TxCertUnRegStaking",
                "constructor.txCert.unRegStaking",
                payload("credential", CREDENTIAL, "refund",
                        new OptionalTypeRef(INTEGER)));
        add(constructors, TX_CERT, "TxCertDelegStaking",
                "constructor.txCert.delegStaking",
                payload("credential", CREDENTIAL, "delegatee", DELEGATEE));
        add(constructors, TX_CERT, "TxCertRegDeleg",
                "constructor.txCert.regDeleg",
                payload("credential", CREDENTIAL, "delegatee", DELEGATEE,
                        "deposit", INTEGER));
        add(constructors, TX_CERT, "TxCertRegDRep",
                "constructor.txCert.regDRep",
                payload("credential", CREDENTIAL, "deposit", INTEGER));
        add(constructors, TX_CERT, "TxCertUpdateDRep",
                "constructor.txCert.updateDRep", Map.of("credential", CREDENTIAL));
        add(constructors, TX_CERT, "TxCertUnRegDRep",
                "constructor.txCert.unRegDRep",
                payload("credential", CREDENTIAL, "refund", INTEGER));
        add(constructors, TX_CERT, "TxCertPoolRegister",
                "constructor.txCert.poolRegister",
                payload("pool", PUB_KEY_HASH, "vrf", PUB_KEY_HASH));
        add(constructors, TX_CERT, "TxCertPoolRetire",
                "constructor.txCert.poolRetire",
                payload("pool", PUB_KEY_HASH, "epoch", INTEGER));
        add(constructors, TX_CERT, "TxCertAuthHotCommittee",
                "constructor.txCert.authHotCommittee",
                payload("coldCredential", CREDENTIAL,
                        "hotCredential", CREDENTIAL));
        add(constructors, TX_CERT, "TxCertResignColdCommittee",
                "constructor.txCert.resignColdCommittee",
                Map.of("coldCredential", CREDENTIAL));
        add(constructors, VOTER, "CommitteeVoter", "constructor.voter.committee",
                Map.of("credential", CREDENTIAL));
        add(constructors, VOTER, "DRepVoter", "constructor.voter.drep",
                Map.of("credential", CREDENTIAL));
        add(constructors, VOTER, "StakePoolVoter", "constructor.voter.stakePool",
                Map.of("pool", PUB_KEY_HASH));
        add(constructors, VOTE, "VoteNo", "constructor.vote.no", Map.of());
        add(constructors, VOTE, "VoteYes", "constructor.vote.yes", Map.of());
        add(constructors, VOTE, "Abstain", "constructor.vote.abstain", Map.of());
        addHidden(constructors, GOVERNANCE_ACTION, "ParameterChange",
                "constructor.governance.parameterChange",
                payload("previous", new OptionalTypeRef(GOVERNANCE_ACTION_ID),
                        "changedParameters", DATA,
                        "constitutionScript", new OptionalTypeRef(SCRIPT_HASH)),
                Set.of("changedParameters"));
        add(constructors, GOVERNANCE_ACTION, "HardForkInitiation",
                "constructor.governance.hardFork",
                payload("previous", new OptionalTypeRef(GOVERNANCE_ACTION_ID),
                        "version", PROTOCOL_VERSION));
        add(constructors, GOVERNANCE_ACTION, "TreasuryWithdrawals",
                "constructor.governance.treasuryWithdrawals",
                payload("withdrawals", new AssocMapTypeRef(CREDENTIAL, INTEGER),
                        "constitutionScript", new OptionalTypeRef(SCRIPT_HASH)));
        add(constructors, GOVERNANCE_ACTION, "NoConfidence",
                "constructor.governance.noConfidence",
                Map.of("previous", new OptionalTypeRef(GOVERNANCE_ACTION_ID)));
        addHidden(constructors, GOVERNANCE_ACTION, "UpdateCommittee",
                "constructor.governance.updateCommittee",
                payload("previous", new OptionalTypeRef(GOVERNANCE_ACTION_ID),
                        "oldMembers", new ListTypeRef(CREDENTIAL),
                        "newMembers", new AssocMapTypeRef(CREDENTIAL, INTEGER),
                        "quorum", DATA), Set.of("quorum"));
        add(constructors, GOVERNANCE_ACTION, "NewConstitution",
                "constructor.governance.newConstitution",
                payload("previous", new OptionalTypeRef(GOVERNANCE_ACTION_ID),
                        "constitutionScript", new OptionalTypeRef(SCRIPT_HASH)));
        add(constructors, GOVERNANCE_ACTION, "InfoAction",
                "constructor.governance.info", Map.of());
        return Collections.unmodifiableMap(new LinkedHashMap<>(constructors));
    }

    private static Map<String, VerificationTypeRef> payload(
            String firstName, VerificationTypeRef firstType,
            String secondName, VerificationTypeRef secondType) {
        var fields = new LinkedHashMap<String, VerificationTypeRef>();
        fields.put(firstName, firstType);
        fields.put(secondName, secondType);
        return fields;
    }

    private static Map<String, VerificationTypeRef> payload(
            String firstName, VerificationTypeRef firstType,
            String secondName, VerificationTypeRef secondType,
            String thirdName, VerificationTypeRef thirdType,
            String fourthName, VerificationTypeRef fourthType) {
        var fields = new LinkedHashMap<String, VerificationTypeRef>();
        fields.put(firstName, firstType);
        fields.put(secondName, secondType);
        fields.put(thirdName, thirdType);
        fields.put(fourthName, fourthType);
        return fields;
    }

    private static Map<String, VerificationTypeRef> payload(
            String firstName, VerificationTypeRef firstType,
            String secondName, VerificationTypeRef secondType,
            String thirdName, VerificationTypeRef thirdType) {
        var fields = new LinkedHashMap<String, VerificationTypeRef>();
        fields.put(firstName, firstType);
        fields.put(secondName, secondType);
        fields.put(thirdName, thirdType);
        return fields;
    }

    private static void add(
            Map<FieldKey, Field> fields,
            LedgerTypeRef owner,
            String name,
            VerificationTypeRef type,
            String capability) {
        fields.put(new FieldKey(owner.ledgerType(), name), new Field(type, capability));
    }

    private static void add(
            Map<ConstructorKey, Constructor> constructors,
            LedgerTypeRef sum,
            String name,
            String capability,
            Map<String, VerificationTypeRef> fields) {
        constructors.put(new ConstructorKey(sum.ledgerType(), name),
                new Constructor(Collections.unmodifiableMap(new LinkedHashMap<>(fields)),
                        capability, Set.of()));
    }

    private static void addHidden(
            Map<ConstructorKey, Constructor> constructors,
            LedgerTypeRef sum,
            String name,
            String capability,
            Map<String, VerificationTypeRef> fields,
            Set<String> hiddenFields) {
        constructors.put(new ConstructorKey(sum.ledgerType(), name),
                new Constructor(Collections.unmodifiableMap(new LinkedHashMap<>(fields)),
                        capability, Set.copyOf(hiddenFields)));
    }

    private static void requireTyped(String id) {
        var capability = LedgerCapabilityInventories.pinnedV3().require(id);
        if (capability.status() != CapabilityStatus.TYPED) {
            throw new IllegalArgumentException(
                    "Ledger capability is not admitted as TYPED: " + id
                            + " (" + capability.status() + ")");
        }
    }

    private static LedgerTypeRef ledger(LedgerTypeRef.LedgerKind kind) {
        return new LedgerTypeRef(kind);
    }

    private static BuiltinTypeRef builtin(BuiltinTypeRef.BuiltinKind kind) {
        return new BuiltinTypeRef(kind);
    }

    record Constructor(Map<String, VerificationTypeRef> fields, String capability,
                       Set<String> hiddenFields) { }
    private record Field(VerificationTypeRef type, String capability) { }
    private record FieldKey(LedgerTypeRef.LedgerKind owner, String field) { }
    private record ConstructorKey(LedgerTypeRef.LedgerKind sum, String constructor) { }
}
