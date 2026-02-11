package com.bloxbean.cardano.julc.compiler.resolve;

import com.bloxbean.cardano.julc.compiler.pir.PirType;

import java.util.List;

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

    // --- Tier 1: Simple leaf records ---

    private static void registerTier1Records(TypeResolver typeResolver) {
        // TxOutRef: txId (ByteString), index (Integer)
        typeResolver.registerLedgerRecord("TxOutRef", List.of(
                new PirType.Field("txId", new PirType.ByteStringType()),
                new PirType.Field("index", new PirType.IntegerType())
        ));

        // IntervalBound: boundType (SumType), isInclusive (Bool)
        // Note: boundType is registered as DataType here; will be overridden after SumType registration
        typeResolver.registerLedgerRecord("IntervalBound", List.of(
                new PirType.Field("boundType", new PirType.DataType()),
                new PirType.Field("isInclusive", new PirType.BoolType())
        ));

        // Value: inner (Map<ByteString, Map<ByteString, Integer>>)
        typeResolver.registerLedgerRecord("Value", List.of(
                new PirType.Field("inner", new PirType.MapType(
                        new PirType.ByteStringType(),
                        new PirType.MapType(new PirType.ByteStringType(), new PirType.IntegerType())
                ))
        ));
    }

    // --- Sealed interfaces (SumTypes) ---

    private static void registerSealedInterfaces(TypeResolver typeResolver) {
        // IntervalBoundType: NegInf(0), Finite(1), PosInf(2)
        typeResolver.registerLedgerSumType("IntervalBoundType", List.of(
                new PirType.Constructor("NegInf", 0, List.of()),
                new PirType.Constructor("Finite", 1, List.of(
                        new PirType.Field("time", new PirType.IntegerType())
                )),
                new PirType.Constructor("PosInf", 2, List.of())
        ));

        // Credential: PubKeyCredential(0), ScriptCredential(1)
        typeResolver.registerLedgerSumType("Credential", List.of(
                new PirType.Constructor("PubKeyCredential", 0, List.of(
                        new PirType.Field("hash", new PirType.ByteStringType())
                )),
                new PirType.Constructor("ScriptCredential", 1, List.of(
                        new PirType.Field("hash", new PirType.ByteStringType())
                ))
        ));

        // OutputDatum: NoOutputDatum(0), OutputDatumHash(1), OutputDatumInline(2)
        typeResolver.registerLedgerSumType("OutputDatum", List.of(
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
        typeResolver.registerLedgerSumType("ScriptInfo", List.of(
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
        typeResolver.registerLedgerRecord("Interval", List.of(
                new PirType.Field("from", intervalBoundType),
                new PirType.Field("to", intervalBoundType)
        ));

        // Address: credential (SumType Credential), stakingCredential (DataType)
        var credentialType = typeResolver.lookupSumType("Credential")
                .orElseThrow(() -> new IllegalStateException("Credential must be registered first"));
        typeResolver.registerLedgerRecord("Address", List.of(
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
        typeResolver.registerLedgerRecord("TxOut", List.of(
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
        typeResolver.registerLedgerRecord("TxInInfo", List.of(
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
        typeResolver.registerLedgerRecord("TxInfo", List.of(
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
        typeResolver.registerLedgerRecord("ScriptContext", List.of(
                new PirType.Field("txInfo", txInfoType),
                new PirType.Field("redeemer", new PirType.DataType()),
                new PirType.Field("scriptInfo", scriptInfoType)
        ));
    }
}
