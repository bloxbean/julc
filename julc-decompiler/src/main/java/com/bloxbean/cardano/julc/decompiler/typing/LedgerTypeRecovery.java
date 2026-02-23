package com.bloxbean.cardano.julc.decompiler.typing;

import com.bloxbean.cardano.julc.decompiler.hir.HirType;

import java.util.*;

/**
 * Recovers ledger types by matching field access patterns against known
 * Cardano ledger type definitions from LedgerTypeRegistry.
 * <p>
 * This class mirrors the structure defined in
 * {@code com.bloxbean.cardano.julc.compiler.resolve.LedgerTypeRegistry}.
 */
public final class LedgerTypeRecovery {

    private LedgerTypeRecovery() {}

    /** All known ledger record types with their fields. */
    private static final Map<String, HirType.RecordType> RECORDS = new LinkedHashMap<>();
    /** All known ledger sum types with their constructors. */
    private static final Map<String, HirType.SumType> SUM_TYPES = new LinkedHashMap<>();

    static {
        var INT = HirType.INTEGER;
        var BS = HirType.BYTE_STRING;
        var BOOL = HirType.BOOL;
        var DATA = HirType.DATA;

        // --- Tier 1: Leaf records ---
        var txOutRef = record("TxOutRef", List.of(
                field("txId", BS), field("index", INT)));

        var value = record("Value", List.of(
                field("inner", new HirType.MapType(BS, new HirType.MapType(BS, INT)))));

        var tuple2 = record("Tuple2", List.of(
                field("first", DATA), field("second", DATA)));

        var tuple3 = record("Tuple3", List.of(
                field("first", DATA), field("second", DATA), field("third", DATA)));

        // --- Sum types ---
        var intervalBoundType = sumType("IntervalBoundType", List.of(
                constr("NegInf", 0, List.of()),
                constr("Finite", 1, List.of(field("time", INT))),
                constr("PosInf", 2, List.of())));

        var intervalBound = record("IntervalBound", List.of(
                field("boundType", intervalBoundType), field("isInclusive", BOOL)));

        var credential = sumType("Credential", List.of(
                constr("PubKeyCredential", 0, List.of(field("hash", BS))),
                constr("ScriptCredential", 1, List.of(field("hash", BS)))));

        var outputDatum = sumType("OutputDatum", List.of(
                constr("NoOutputDatum", 0, List.of()),
                constr("OutputDatumHash", 1, List.of(field("hash", BS))),
                constr("OutputDatumInline", 2, List.of(field("datum", DATA)))));

        var scriptInfo = sumType("ScriptInfo", List.of(
                constr("MintingScript", 0, List.of(field("policyId", BS))),
                constr("SpendingScript", 1, List.of(field("txOutRef", DATA), field("datum", DATA))),
                constr("RewardingScript", 2, List.of(field("credential", DATA))),
                constr("CertifyingScript", 3, List.of(field("index", INT), field("cert", DATA))),
                constr("VotingScript", 4, List.of(field("voter", DATA))),
                constr("ProposingScript", 5, List.of(field("index", INT), field("procedure", DATA)))));

        // --- Composite records ---
        var interval = record("Interval", List.of(
                field("from", intervalBound), field("to", intervalBound)));

        var address = record("Address", List.of(
                field("credential", credential), field("stakingCredential", DATA)));

        var txOut = record("TxOut", List.of(
                field("address", address), field("value", value),
                field("datum", outputDatum), field("referenceScript", DATA)));

        var txInInfo = record("TxInInfo", List.of(
                field("outRef", txOutRef), field("resolved", txOut)));

        // --- Top-level: TxInfo (16 fields), ScriptContext (3 fields) ---
        var txInfo = record("TxInfo", List.of(
                field("inputs", new HirType.ListType(txInInfo)),               // 0
                field("referenceInputs", new HirType.ListType(txInInfo)),      // 1
                field("outputs", new HirType.ListType(txOut)),                 // 2
                field("fee", INT),                                             // 3
                field("mint", value),                                          // 4
                field("certificates", new HirType.ListType(DATA)),             // 5
                field("withdrawals", new HirType.MapType(DATA, INT)),          // 6
                field("validRange", interval),                                 // 7
                field("signatories", new HirType.ListType(BS)),                // 8
                field("redeemers", new HirType.MapType(DATA, DATA)),           // 9
                field("datums", new HirType.MapType(DATA, DATA)),              // 10
                field("id", BS),                                               // 11
                field("votes", new HirType.MapType(DATA, DATA)),               // 12
                field("proposalProcedures", new HirType.ListType(DATA)),       // 13
                field("currentTreasuryAmount", DATA),                          // 14
                field("treasuryDonation", DATA)));                             // 15

        record("ScriptContext", List.of(
                field("txInfo", txInfo),
                field("redeemer", DATA),
                field("scriptInfo", scriptInfo)));
    }

    private static HirType.Field field(String name, HirType type) {
        return new HirType.Field(name, type);
    }

    private static HirType.RecordType record(String name, List<HirType.Field> fields) {
        var rt = new HirType.RecordType(name, fields);
        RECORDS.put(name, rt);
        return rt;
    }

    private static HirType.SumType sumType(String name, List<HirType.Constructor> constructors) {
        var st = new HirType.SumType(name, constructors);
        SUM_TYPES.put(name, st);
        return st;
    }

    private static HirType.Constructor constr(String name, int tag, List<HirType.Field> fields) {
        return new HirType.Constructor(name, tag, fields);
    }

    /**
     * Look up a known record type by name.
     */
    public static Optional<HirType.RecordType> lookupRecord(String name) {
        return Optional.ofNullable(RECORDS.get(name));
    }

    /**
     * Look up a known sum type by name.
     */
    public static Optional<HirType.SumType> lookupSumType(String name) {
        return Optional.ofNullable(SUM_TYPES.get(name));
    }

    /**
     * Get field name for a known record type at the given field index.
     */
    public static String fieldName(String typeName, int fieldIndex) {
        var rt = RECORDS.get(typeName);
        if (rt != null && fieldIndex >= 0 && fieldIndex < rt.fields().size()) {
            return rt.fields().get(fieldIndex).name();
        }
        return "field" + fieldIndex;
    }

    /**
     * Get the field type for a known record type at the given field index.
     */
    public static HirType fieldType(String typeName, int fieldIndex) {
        var rt = RECORDS.get(typeName);
        if (rt != null && fieldIndex >= 0 && fieldIndex < rt.fields().size()) {
            return rt.fields().get(fieldIndex).type();
        }
        return HirType.UNKNOWN;
    }

    /**
     * Get the constructor name for a known sum type at the given tag.
     */
    public static String constructorName(String typeName, int tag) {
        var st = SUM_TYPES.get(typeName);
        if (st != null) {
            for (var c : st.constructors()) {
                if (c.tag() == tag) return c.name();
            }
        }
        return "Constructor" + tag;
    }

    /**
     * Try to identify a record type by its field count.
     * Returns the type name if unique, null if ambiguous.
     */
    public static String identifyByFieldCount(int fieldCount) {
        List<String> matches = new ArrayList<>();
        for (var entry : RECORDS.entrySet()) {
            if (entry.getValue().fields().size() == fieldCount) {
                matches.add(entry.getKey());
            }
        }
        if (matches.size() == 1) return matches.getFirst();
        // TxInfo has 16 fields (unique), ScriptContext has 3
        if (fieldCount == 16) return "TxInfo";
        if (fieldCount == 3) return "ScriptContext";
        return null;
    }

    /**
     * Get all known record types.
     */
    public static Map<String, HirType.RecordType> allRecords() {
        return Collections.unmodifiableMap(RECORDS);
    }

    /**
     * Get all known sum types.
     */
    public static Map<String, HirType.SumType> allSumTypes() {
        return Collections.unmodifiableMap(SUM_TYPES);
    }
}
