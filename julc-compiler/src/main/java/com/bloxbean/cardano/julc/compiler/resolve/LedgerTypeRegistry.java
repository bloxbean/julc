package com.bloxbean.cardano.julc.compiler.resolve;

import com.bloxbean.cardano.julc.compiler.pir.PirType;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pre-registers all Cardano ledger types as {@link PirType.RecordType} or {@link PirType.SumType}
 * in the {@link TypeResolver}, enabling typed field access on ScriptContext, TxInfo, etc.
 * <p>
 * Types are registered in dependency order (leaves first) so that nested types
 * resolve correctly when referenced as field types.
 * <p>
 * Hash types (PubKeyHash, TxId, etc.) are already handled as ByteStringType by
 * TypeResolver.LEDGER_HASH_TYPES and do not need registration here.
 */
public final class LedgerTypeRegistry {

    /** The package for all ledger types. */
    public static final String LEDGER_PACKAGE = "com.bloxbean.cardano.julc.ledger";

    private LedgerTypeRegistry() {}

    /**
     * Register all ledger types into the given TypeResolver.
     * Must be called before user-defined records are registered, so that
     * user records can reference ledger types as field types.
     */
    public static void registerAll(TypeResolver typeResolver) {
        registerTier1Records(typeResolver);
        registerSealedInterfaces(typeResolver);
        registerCompositeRecords(typeResolver);
        registerTopLevelTypes(typeResolver);
    }

    /**
     * Return all known ledger FQCNs (records, sealed interfaces, variants, hash types, data types).
     * Used by ImportResolver to populate its knownFqcns set.
     */
    public static Set<String> allLedgerFqcns() {
        var fqcns = new LinkedHashSet<String>();
        // Record types
        for (var name : List.of("TxOutRef", "IntervalBound", "Tuple2", "Tuple3", "Value",
                "Interval", "Address", "TxOut", "TxInInfo", "TxInfo", "ScriptContext")) {
            fqcns.add(LEDGER_PACKAGE + "." + name);
        }
        // Sealed interface types
        for (var name : List.of("IntervalBoundType", "Credential", "OutputDatum", "ScriptInfo")) {
            fqcns.add(LEDGER_PACKAGE + "." + name);
        }
        // Variant types (constructors of sealed interfaces)
        for (var name : List.of("NegInf", "Finite", "PosInf",
                "PubKeyCredential", "ScriptCredential",
                "NoOutputDatum", "OutputDatumHash", "OutputDatumInline",
                "MintingScript", "SpendingScript", "RewardingScript",
                "CertifyingScript", "VotingScript", "ProposingScript")) {
            fqcns.add(LEDGER_PACKAGE + "." + name);
        }
        // Hash types (resolved as ByteStringType, not registered as records)
        for (var name : List.of("PubKeyHash", "ScriptHash", "ValidatorHash",
                "PolicyId", "TokenName", "DatumHash", "TxId")) {
            fqcns.add(LEDGER_PACKAGE + "." + name);
        }
        // Opaque data types
        for (var name : List.of("StakingCredential", "ScriptPurpose",
                "Vote", "Voter", "DRep", "Delegatee",
                "GovernanceActionId", "GovernanceAction", "ProposalProcedure",
                "TxCert", "Rational", "ProtocolVersion", "Committee")) {
            fqcns.add(LEDGER_PACKAGE + "." + name);
        }
        return fqcns;
    }

    private static String fqcn(String simpleName) {
        return LEDGER_PACKAGE + "." + simpleName;
    }

    // --- Tier 1: Simple leaf records ---

    private static void registerTier1Records(TypeResolver typeResolver) {
        // TxOutRef: txId (ByteString), index (Integer)
        typeResolver.registerLedgerRecord("TxOutRef", fqcn("TxOutRef"), List.of(
                new PirType.Field("txId", new PirType.ByteStringType()),
                new PirType.Field("index", new PirType.IntegerType())
        ));

        // IntervalBound: boundType (SumType), isInclusive (Bool)
        // Note: boundType is registered as DataType here; will be overridden after SumType registration
        typeResolver.registerLedgerRecord("IntervalBound", fqcn("IntervalBound"), List.of(
                new PirType.Field("boundType", new PirType.DataType()),
                new PirType.Field("isInclusive", new PirType.BoolType())
        ));

        // Tuple2: first (Data), second (Data)
        typeResolver.registerLedgerRecord("Tuple2", fqcn("Tuple2"), List.of(
                new PirType.Field("first", new PirType.DataType()),
                new PirType.Field("second", new PirType.DataType())
        ));

        // Tuple3: first (Data), second (Data), third (Data)
        typeResolver.registerLedgerRecord("Tuple3", fqcn("Tuple3"), List.of(
                new PirType.Field("first", new PirType.DataType()),
                new PirType.Field("second", new PirType.DataType()),
                new PirType.Field("third", new PirType.DataType())
        ));

        // Value: inner (Map<ByteString, Map<ByteString, Integer>>)
        typeResolver.registerLedgerRecord("Value", fqcn("Value"), List.of(
                new PirType.Field("inner", new PirType.MapType(
                        new PirType.ByteStringType(),
                        new PirType.MapType(new PirType.ByteStringType(), new PirType.IntegerType())
                ))
        ));
    }

    // --- Sealed interfaces (SumTypes) ---

    private static void registerSealedInterfaces(TypeResolver typeResolver) {
        // IntervalBoundType: NegInf(0), Finite(1), PosInf(2)
        typeResolver.registerLedgerSumType("IntervalBoundType", fqcn("IntervalBoundType"), List.of(
                new PirType.Constructor("NegInf", 0, List.of()),
                new PirType.Constructor("Finite", 1, List.of(
                        new PirType.Field("time", new PirType.IntegerType())
                )),
                new PirType.Constructor("PosInf", 2, List.of())
        ));

        // Re-register IntervalBound now that IntervalBoundType SumType is available
        var intervalBoundTypeSumType = typeResolver.lookupSumType("IntervalBoundType")
                .orElseThrow(() -> new IllegalStateException("IntervalBoundType must be registered first"));
        typeResolver.registerLedgerRecord("IntervalBound", fqcn("IntervalBound"), List.of(
                new PirType.Field("boundType", intervalBoundTypeSumType),
                new PirType.Field("isInclusive", new PirType.BoolType())
        ));

        // Credential: PubKeyCredential(0), ScriptCredential(1)
        typeResolver.registerLedgerSumType("Credential", fqcn("Credential"), List.of(
                new PirType.Constructor("PubKeyCredential", 0, List.of(
                        new PirType.Field("hash", new PirType.ByteStringType())
                )),
                new PirType.Constructor("ScriptCredential", 1, List.of(
                        new PirType.Field("hash", new PirType.ByteStringType())
                ))
        ));

        // OutputDatum: NoOutputDatum(0), OutputDatumHash(1), OutputDatumInline(2)
        typeResolver.registerLedgerSumType("OutputDatum", fqcn("OutputDatum"), List.of(
                new PirType.Constructor("NoOutputDatum", 0, List.of()),
                new PirType.Constructor("OutputDatumHash", 1, List.of(
                        new PirType.Field("hash", new PirType.ByteStringType())
                )),
                new PirType.Constructor("OutputDatumInline", 2, List.of(
                        new PirType.Field("datum", new PirType.DataType())
                ))
        ));

        // ScriptInfo: MintingScript(0), SpendingScript(1), RewardingScript(2),
        //             CertifyingScript(3), VotingScript(4), ProposingScript(5)
        typeResolver.registerLedgerSumType("ScriptInfo", fqcn("ScriptInfo"), List.of(
                new PirType.Constructor("MintingScript", 0, List.of(
                        new PirType.Field("policyId", new PirType.ByteStringType())
                )),
                new PirType.Constructor("SpendingScript", 1, List.of(
                        new PirType.Field("txOutRef", new PirType.DataType()),
                        new PirType.Field("datum", new PirType.DataType())
                )),
                new PirType.Constructor("RewardingScript", 2, List.of(
                        new PirType.Field("credential", new PirType.DataType())
                )),
                new PirType.Constructor("CertifyingScript", 3, List.of(
                        new PirType.Field("index", new PirType.IntegerType()),
                        new PirType.Field("cert", new PirType.DataType())
                )),
                new PirType.Constructor("VotingScript", 4, List.of(
                        new PirType.Field("voter", new PirType.DataType())
                )),
                new PirType.Constructor("ProposingScript", 5, List.of(
                        new PirType.Field("index", new PirType.IntegerType()),
                        new PirType.Field("procedure", new PirType.DataType())
                ))
        ));
    }

    // --- Composite records that reference other registered types ---

    private static void registerCompositeRecords(TypeResolver typeResolver) {
        // Interval: from (IntervalBound), to (IntervalBound)
        var intervalBoundType = typeResolver.lookupRecord("IntervalBound")
                .orElseThrow(() -> new IllegalStateException("IntervalBound must be registered first"));
        typeResolver.registerLedgerRecord("Interval", fqcn("Interval"), List.of(
                new PirType.Field("from", intervalBoundType),
                new PirType.Field("to", intervalBoundType)
        ));

        // Address: credential (SumType Credential), stakingCredential (DataType)
        var credentialType = typeResolver.lookupSumType("Credential")
                .orElseThrow(() -> new IllegalStateException("Credential must be registered first"));
        typeResolver.registerLedgerRecord("Address", fqcn("Address"), List.of(
                new PirType.Field("credential", credentialType),
                new PirType.Field("stakingCredential", new PirType.DataType())
        ));

        // TxOut: address (Address), value (Value), datum (OutputDatum), referenceScript (DataType)
        var addressType = typeResolver.lookupRecord("Address")
                .orElseThrow(() -> new IllegalStateException("Address must be registered first"));
        var valueType = typeResolver.lookupRecord("Value")
                .orElseThrow(() -> new IllegalStateException("Value must be registered first"));
        var outputDatumType = typeResolver.lookupSumType("OutputDatum")
                .orElseThrow(() -> new IllegalStateException("OutputDatum must be registered first"));
        typeResolver.registerLedgerRecord("TxOut", fqcn("TxOut"), List.of(
                new PirType.Field("address", addressType),
                new PirType.Field("value", valueType),
                new PirType.Field("datum", outputDatumType),
                new PirType.Field("referenceScript", new PirType.DataType())
        ));

        // TxInInfo: outRef (TxOutRef), resolved (TxOut)
        var txOutRefType = typeResolver.lookupRecord("TxOutRef")
                .orElseThrow(() -> new IllegalStateException("TxOutRef must be registered first"));
        var txOutType = typeResolver.lookupRecord("TxOut")
                .orElseThrow(() -> new IllegalStateException("TxOut must be registered first"));
        typeResolver.registerLedgerRecord("TxInInfo", fqcn("TxInInfo"), List.of(
                new PirType.Field("outRef", txOutRefType),
                new PirType.Field("resolved", txOutType)
        ));
    }

    // --- Top-level types: TxInfo, ScriptContext ---

    private static void registerTopLevelTypes(TypeResolver typeResolver) {
        var txInInfoType = typeResolver.lookupRecord("TxInInfo")
                .orElseThrow(() -> new IllegalStateException("TxInInfo must be registered first"));
        var txOutType = typeResolver.lookupRecord("TxOut")
                .orElseThrow(() -> new IllegalStateException("TxOut must be registered first"));
        var valueType = typeResolver.lookupRecord("Value")
                .orElseThrow(() -> new IllegalStateException("Value must be registered first"));
        var intervalType = typeResolver.lookupRecord("Interval")
                .orElseThrow(() -> new IllegalStateException("Interval must be registered first"));

        // TxInfo: 16 fields
        typeResolver.registerLedgerRecord("TxInfo", fqcn("TxInfo"), List.of(
                new PirType.Field("inputs", new PirType.ListType(txInInfoType)),           // 0
                new PirType.Field("referenceInputs", new PirType.ListType(txInInfoType)),  // 1
                new PirType.Field("outputs", new PirType.ListType(txOutType)),             // 2
                new PirType.Field("fee", new PirType.IntegerType()),                       // 3
                new PirType.Field("mint", valueType),                                      // 4
                new PirType.Field("certificates", new PirType.ListType(new PirType.DataType())),  // 5
                new PirType.Field("withdrawals", new PirType.MapType(new PirType.DataType(), new PirType.IntegerType())),  // 6
                new PirType.Field("validRange", intervalType),                             // 7
                new PirType.Field("signatories", new PirType.ListType(new PirType.ByteStringType())),  // 8
                new PirType.Field("redeemers", new PirType.MapType(new PirType.DataType(), new PirType.DataType())),  // 9
                new PirType.Field("datums", new PirType.MapType(new PirType.DataType(), new PirType.DataType())),  // 10
                new PirType.Field("id", new PirType.ByteStringType()),                     // 11
                new PirType.Field("votes", new PirType.MapType(new PirType.DataType(), new PirType.DataType())),  // 12
                new PirType.Field("proposalProcedures", new PirType.ListType(new PirType.DataType())),  // 13
                new PirType.Field("currentTreasuryAmount", new PirType.OptionalType(new PirType.IntegerType())),  // 14
                new PirType.Field("treasuryDonation", new PirType.OptionalType(new PirType.IntegerType()))  // 15
        ));

        // ScriptContext: txInfo (TxInfo), redeemer (DataType), scriptInfo (ScriptInfo)
        var txInfoType = typeResolver.lookupRecord("TxInfo")
                .orElseThrow(() -> new IllegalStateException("TxInfo must be registered first"));
        var scriptInfoType = typeResolver.lookupSumType("ScriptInfo")
                .orElseThrow(() -> new IllegalStateException("ScriptInfo must be registered first"));
        typeResolver.registerLedgerRecord("ScriptContext", fqcn("ScriptContext"), List.of(
                new PirType.Field("txInfo", txInfoType),
                new PirType.Field("redeemer", new PirType.DataType()),
                new PirType.Field("scriptInfo", scriptInfoType)
        ));
    }
}
