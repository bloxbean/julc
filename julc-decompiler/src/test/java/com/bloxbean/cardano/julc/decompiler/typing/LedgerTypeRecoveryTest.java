package com.bloxbean.cardano.julc.decompiler.typing;

import com.bloxbean.cardano.julc.decompiler.hir.HirType;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for LedgerTypeRecovery: known Cardano ledger type definitions.
 */
class LedgerTypeRecoveryTest {

    @Test
    void testScriptContextFields() {
        var sc = LedgerTypeRecovery.lookupRecord("ScriptContext");
        assertTrue(sc.isPresent());
        assertEquals(3, sc.get().fields().size());
        assertEquals("txInfo", sc.get().fields().get(0).name());
        assertEquals("redeemer", sc.get().fields().get(1).name());
        assertEquals("scriptInfo", sc.get().fields().get(2).name());
    }

    @Test
    void testTxInfoFields() {
        var txInfo = LedgerTypeRecovery.lookupRecord("TxInfo");
        assertTrue(txInfo.isPresent());
        assertEquals(16, txInfo.get().fields().size());
        assertEquals("inputs", txInfo.get().fields().get(0).name());
        assertEquals("outputs", txInfo.get().fields().get(2).name());
        assertEquals("fee", txInfo.get().fields().get(3).name());
        assertEquals("signatories", txInfo.get().fields().get(8).name());
        assertEquals("id", txInfo.get().fields().get(11).name());
    }

    @Test
    void testTxOutFields() {
        var txOut = LedgerTypeRecovery.lookupRecord("TxOut");
        assertTrue(txOut.isPresent());
        assertEquals(4, txOut.get().fields().size());
        assertEquals("address", txOut.get().fields().get(0).name());
        assertEquals("value", txOut.get().fields().get(1).name());
        assertEquals("datum", txOut.get().fields().get(2).name());
        assertEquals("referenceScript", txOut.get().fields().get(3).name());
    }

    @Test
    void testValueFields() {
        var value = LedgerTypeRecovery.lookupRecord("Value");
        assertTrue(value.isPresent());
        assertEquals(1, value.get().fields().size());
        assertEquals("inner", value.get().fields().get(0).name());
        assertInstanceOf(HirType.MapType.class, value.get().fields().get(0).type());
    }

    @Test
    void testCredentialSumType() {
        var cred = LedgerTypeRecovery.lookupSumType("Credential");
        assertTrue(cred.isPresent());
        assertEquals(2, cred.get().constructors().size());
        assertEquals("PubKeyCredential", cred.get().constructors().get(0).name());
        assertEquals(0, cred.get().constructors().get(0).tag());
        assertEquals("ScriptCredential", cred.get().constructors().get(1).name());
        assertEquals(1, cred.get().constructors().get(1).tag());
    }

    @Test
    void testOutputDatumSumType() {
        var od = LedgerTypeRecovery.lookupSumType("OutputDatum");
        assertTrue(od.isPresent());
        assertEquals(3, od.get().constructors().size());
        assertEquals("NoOutputDatum", od.get().constructors().get(0).name());
        assertEquals("OutputDatumHash", od.get().constructors().get(1).name());
        assertEquals("OutputDatumInline", od.get().constructors().get(2).name());
    }

    @Test
    void testScriptInfoSumType() {
        var si = LedgerTypeRecovery.lookupSumType("ScriptInfo");
        assertTrue(si.isPresent());
        assertEquals(6, si.get().constructors().size());
        assertEquals("MintingScript", si.get().constructors().get(0).name());
        assertEquals("SpendingScript", si.get().constructors().get(1).name());
    }

    @Test
    void testFieldNameLookup() {
        assertEquals("inputs", LedgerTypeRecovery.fieldName("TxInfo", 0));
        assertEquals("outputs", LedgerTypeRecovery.fieldName("TxInfo", 2));
        assertEquals("signatories", LedgerTypeRecovery.fieldName("TxInfo", 8));
        assertEquals("field99", LedgerTypeRecovery.fieldName("TxInfo", 99));
    }

    @Test
    void testConstructorNameLookup() {
        assertEquals("PubKeyCredential", LedgerTypeRecovery.constructorName("Credential", 0));
        assertEquals("ScriptCredential", LedgerTypeRecovery.constructorName("Credential", 1));
        assertEquals("Constructor5", LedgerTypeRecovery.constructorName("Credential", 5));
    }

    @Test
    void testIdentifyByFieldCount() {
        assertEquals("TxInfo", LedgerTypeRecovery.identifyByFieldCount(16));
        assertEquals("ScriptContext", LedgerTypeRecovery.identifyByFieldCount(3));
    }

    @Test
    void testIntervalBoundType() {
        var ibt = LedgerTypeRecovery.lookupSumType("IntervalBoundType");
        assertTrue(ibt.isPresent());
        assertEquals(3, ibt.get().constructors().size());
        assertEquals("NegInf", ibt.get().constructors().get(0).name());
        assertEquals("Finite", ibt.get().constructors().get(1).name());
        assertEquals("PosInf", ibt.get().constructors().get(2).name());
        // Finite has one field: time
        assertEquals(1, ibt.get().constructors().get(1).fields().size());
        assertEquals("time", ibt.get().constructors().get(1).fields().get(0).name());
    }

    @Test
    void testAllRecordsPopulated() {
        var records = LedgerTypeRecovery.allRecords();
        assertTrue(records.containsKey("ScriptContext"));
        assertTrue(records.containsKey("TxInfo"));
        assertTrue(records.containsKey("TxOut"));
        assertTrue(records.containsKey("TxInInfo"));
        assertTrue(records.containsKey("Address"));
        assertTrue(records.containsKey("Value"));
        assertTrue(records.containsKey("Interval"));
        assertTrue(records.containsKey("IntervalBound"));
        assertTrue(records.containsKey("TxOutRef"));
    }

    @Test
    void testAllSumTypesPopulated() {
        var sumTypes = LedgerTypeRecovery.allSumTypes();
        assertTrue(sumTypes.containsKey("Credential"));
        assertTrue(sumTypes.containsKey("OutputDatum"));
        assertTrue(sumTypes.containsKey("ScriptInfo"));
        assertTrue(sumTypes.containsKey("IntervalBoundType"));
    }

    @Test
    void testUnknownTypeReturnsEmpty() {
        assertFalse(LedgerTypeRecovery.lookupRecord("UnknownType").isPresent());
        assertFalse(LedgerTypeRecovery.lookupSumType("UnknownType").isPresent());
    }
}
