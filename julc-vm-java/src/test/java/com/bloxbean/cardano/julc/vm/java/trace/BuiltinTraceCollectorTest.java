package com.bloxbean.cardano.julc.vm.java.trace;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.vm.java.CekValue;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BuiltinTraceCollectorTest {

    @Test
    void emptyCollector_returnsEmptyList() {
        var collector = new BuiltinTraceCollector(5);
        assertEquals(List.of(), collector.getEntries());
    }

    @Test
    void recordSingleEntry() {
        var collector = new BuiltinTraceCollector(5);
        collector.record(DefaultFun.AddInteger,
                List.of(vcon(Constant.integer(3)), vcon(Constant.integer(5))),
                vcon(Constant.integer(8)));

        var entries = collector.getEntries();
        assertEquals(1, entries.size());
        assertEquals(DefaultFun.AddInteger, entries.getFirst().fun());
        assertEquals("3, 5", entries.getFirst().argSummary());
        assertEquals("8", entries.getFirst().resultSummary());
    }

    @Test
    void recordMultipleEntries_preservesOrder() {
        var collector = new BuiltinTraceCollector(5);
        collector.record(DefaultFun.UnIData,
                List.of(vcon(Constant.data(new PlutusData.IntData(BigInteger.valueOf(42))))),
                vcon(Constant.integer(42)));
        collector.record(DefaultFun.EqualsInteger,
                List.of(vcon(Constant.integer(42)), vcon(Constant.integer(42))),
                vcon(Constant.bool(true)));

        var entries = collector.getEntries();
        assertEquals(2, entries.size());
        assertEquals(DefaultFun.UnIData, entries.get(0).fun());
        assertEquals(DefaultFun.EqualsInteger, entries.get(1).fun());
    }

    @Test
    void ringBuffer_evictsOldest() {
        var collector = new BuiltinTraceCollector(3);
        for (int i = 0; i < 5; i++) {
            collector.record(DefaultFun.AddInteger,
                    List.of(vcon(Constant.integer(i))),
                    vcon(Constant.integer(i + 1)));
        }

        var entries = collector.getEntries();
        assertEquals(3, entries.size());
        // Should have entries for i=2,3,4 (oldest 0,1 evicted)
        assertEquals("2", entries.get(0).argSummary());
        assertEquals("3", entries.get(1).argSummary());
        assertEquals("4", entries.get(2).argSummary());
    }

    @Test
    void ringBuffer_exactCapacity() {
        var collector = new BuiltinTraceCollector(3);
        for (int i = 0; i < 3; i++) {
            collector.record(DefaultFun.AddInteger,
                    List.of(vcon(Constant.integer(i))),
                    vcon(Constant.integer(i)));
        }
        var entries = collector.getEntries();
        assertEquals(3, entries.size());
        assertEquals("0", entries.get(0).argSummary());
        assertEquals("2", entries.get(2).argSummary());
    }

    @Test
    void formatValue_integer() {
        assertEquals("42", BuiltinTraceCollector.formatValue(vcon(Constant.integer(42))));
    }

    @Test
    void formatValue_bool() {
        assertEquals("True", BuiltinTraceCollector.formatValue(vcon(Constant.bool(true))));
        assertEquals("False", BuiltinTraceCollector.formatValue(vcon(Constant.bool(false))));
    }

    @Test
    void formatValue_byteString_short() {
        assertEquals("#a1b2", BuiltinTraceCollector.formatValue(
                vcon(Constant.byteString(new byte[]{(byte) 0xa1, (byte) 0xb2}))));
    }

    @Test
    void formatValue_byteString_truncated() {
        byte[] longBytes = new byte[32]; // e.g., TxId
        for (int i = 0; i < longBytes.length; i++) longBytes[i] = (byte) i;
        String result = BuiltinTraceCollector.formatValue(vcon(Constant.byteString(longBytes)));
        assertTrue(result.startsWith("#"));
        assertTrue(result.endsWith("..."));
        // 8 bytes = 16 hex chars + # + ...
        assertEquals("#0001020304050607...", result);
    }

    @Test
    void formatValue_byteString_empty() {
        assertEquals("#", BuiltinTraceCollector.formatValue(
                vcon(Constant.byteString(new byte[0]))));
    }

    @Test
    void formatValue_string() {
        assertEquals("\"hello\"", BuiltinTraceCollector.formatValue(
                vcon(Constant.string("hello"))));
    }

    @Test
    void formatValue_string_truncated() {
        String longStr = "a".repeat(30);
        String result = BuiltinTraceCollector.formatValue(vcon(Constant.string(longStr)));
        assertTrue(result.endsWith("...\""));
        // "aaaaaaaaaaaaaaaaa..." → quote(1) + 17 chars + ...(3) + quote(1) = 22
        assertEquals(22, result.length());
    }

    @Test
    void formatValue_unit() {
        assertEquals("()", BuiltinTraceCollector.formatValue(vcon(Constant.unit())));
    }

    @Test
    void formatValue_data() {
        assertEquals("<Data>", BuiltinTraceCollector.formatValue(
                vcon(Constant.data(new PlutusData.IntData(BigInteger.ONE)))));
    }

    @Test
    void formatValue_list() {
        assertEquals("[2 elems]", BuiltinTraceCollector.formatValue(
                vcon(new Constant.ListConst(
                        com.bloxbean.cardano.julc.core.DefaultUni.INTEGER,
                        List.of(Constant.integer(1), Constant.integer(2))))));
    }

    @Test
    void formatValue_pair() {
        assertEquals("<Pair>", BuiltinTraceCollector.formatValue(
                vcon(new Constant.PairConst(Constant.integer(1), Constant.integer(2)))));
    }

    @Test
    void formatValue_delay() {
        assertEquals("<Delay>", BuiltinTraceCollector.formatValue(
                new CekValue.VDelay(null, null)));
    }

    @Test
    void formatValue_lambda() {
        assertEquals("<Lambda>", BuiltinTraceCollector.formatValue(
                new CekValue.VLam("x", null, null)));
    }

    @Test
    void toString_format() {
        var collector = new BuiltinTraceCollector(5);
        collector.record(DefaultFun.LessThanEqualsInteger,
                List.of(vcon(Constant.integer(5)), vcon(Constant.integer(3))),
                vcon(Constant.bool(false)));

        var entry = collector.getEntries().getFirst();
        assertEquals("LessThanEqualsInteger(5, 3) → False", entry.toString());
    }

    @Test
    void recordError_capturesExceptionType() {
        var collector = new BuiltinTraceCollector(5);
        collector.recordError(DefaultFun.HeadList,
                List.of(vcon(new Constant.ListConst(
                        com.bloxbean.cardano.julc.core.DefaultUni.INTEGER, List.of()))),
                new RuntimeException("boom"));

        var entries = collector.getEntries();
        assertEquals(1, entries.size());
        assertEquals(DefaultFun.HeadList, entries.getFirst().fun());
        assertEquals("<ERROR: RuntimeException>", entries.getFirst().resultSummary());
    }

    @Test
    void invalidCapacity_throws() {
        assertThrows(IllegalArgumentException.class, () -> new BuiltinTraceCollector(0));
        assertThrows(IllegalArgumentException.class, () -> new BuiltinTraceCollector(-1));
    }

    private static CekValue vcon(Constant c) {
        return new CekValue.VCon(c);
    }
}
