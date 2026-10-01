package org.julclang.core.flat;

import org.julclang.core.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip tests for UPLC FLAT encoding/decoding.
 * Encode → Decode → verify equality.
 */
class UplcFlatRoundTripTest {

    // --- Program round-trips ---

    @Test
    void programWithError() {
        var program = Program.plutusV3(Term.error());
        var decoded = roundTrip(program);
        assertEquals(program, decoded);
    }

    @Test
    void programV1Identity() {
        var program = Program.plutusV1(Term.lam("x", Term.var(new NamedDeBruijn("i0", 0))));
        var decoded = roundTrip(program);
        assertEquals(1, decoded.major());
        assertEquals(0, decoded.minor());
        assertEquals(0, decoded.patch());
        assertInstanceOf(Term.Lam.class, decoded.term());
    }

    @Test
    void programV3() {
        var program = Program.plutusV3(Term.const_(Constant.integer(42)));
        var decoded = roundTrip(program);
        assertEquals(1, decoded.major());
        assertEquals(1, decoded.minor());
        assertEquals(0, decoded.patch());
    }

    // --- Term round-trips ---

    @Test
    void termVar() {
        roundTripTerm(Term.var(0));
        roundTripTerm(Term.var(5));
        roundTripTerm(Term.var(127));
    }

    @Test
    void termLam() {
        // Note: lambda param name is NOT preserved in FLAT encoding
        var term = Term.lam("x", Term.var(0));
        var decoded = roundTripTermGet(term);
        assertInstanceOf(Term.Lam.class, decoded);
        assertInstanceOf(Term.Var.class, ((Term.Lam) decoded).body());
    }

    @Test
    void termApply() {
        var term = Term.apply(
                Term.lam("x", Term.var(0)),
                Term.const_(Constant.integer(42)));
        var decoded = roundTripTermGet(term);
        assertInstanceOf(Term.Apply.class, decoded);
    }

    @Test
    void termForce() {
        var term = Term.force(Term.delay(Term.const_(Constant.unit())));
        var decoded = roundTripTermGet(term);
        assertInstanceOf(Term.Force.class, decoded);
        assertInstanceOf(Term.Delay.class, ((Term.Force) decoded).term());
    }

    @Test
    void termDelay() {
        var term = Term.delay(Term.error());
        var decoded = roundTripTermGet(term);
        assertInstanceOf(Term.Delay.class, decoded);
    }

    @Test
    void termConst() {
        roundTripTerm(Term.const_(Constant.integer(0)));
        roundTripTerm(Term.const_(Constant.integer(42)));
        roundTripTerm(Term.const_(Constant.integer(-1)));
        roundTripTerm(Term.const_(Constant.bool(true)));
        roundTripTerm(Term.const_(Constant.bool(false)));
        roundTripTerm(Term.const_(Constant.unit()));
        roundTripTerm(Term.const_(Constant.string("hello")));
        roundTripTerm(Term.const_(Constant.string("")));
        roundTripTerm(Term.const_(Constant.byteString(new byte[]{1, 2, 3})));
        roundTripTerm(Term.const_(Constant.byteString(new byte[]{})));
    }

    @Test
    void termBuiltinAllFunctions() {
        for (DefaultFun fun : DefaultFun.values()) {
            var term = Term.builtin(fun);
            var decoded = roundTripTermGet(term);
            assertInstanceOf(Term.Builtin.class, decoded);
            assertEquals(fun, ((Term.Builtin) decoded).fun(),
                    "Builtin round-trip failed for " + fun.name());
        }
    }

    @Test
    void termError() {
        var decoded = roundTripTermGet(Term.error());
        assertInstanceOf(Term.Error.class, decoded);
    }

    @Test
    void termConstr() {
        var term = Term.constr(0, Term.const_(Constant.integer(1)), Term.const_(Constant.integer(2)));
        var decoded = roundTripTermGet(term);
        assertInstanceOf(Term.Constr.class, decoded);
        var constr = (Term.Constr) decoded;
        assertEquals(0, constr.tag());
        assertEquals(2, constr.fields().size());
    }

    @Test
    void termConstrEmpty() {
        var term = Term.constr(42);
        var decoded = roundTripTermGet(term);
        assertInstanceOf(Term.Constr.class, decoded);
        assertEquals(42, ((Term.Constr) decoded).tag());
        assertTrue(((Term.Constr) decoded).fields().isEmpty());
    }

    @Test
    void termCase() {
        var term = Term.case_(
                Term.var(0),
                Term.const_(Constant.string("a")),
                Term.const_(Constant.string("b")));
        var decoded = roundTripTermGet(term);
        assertInstanceOf(Term.Case.class, decoded);
        assertEquals(2, ((Term.Case) decoded).branches().size());
    }

    // --- Constant round-trips ---

    @Test
    void constInteger() {
        assertConstRoundTrip(Constant.integer(0));
        assertConstRoundTrip(Constant.integer(1));
        assertConstRoundTrip(Constant.integer(-1));
        assertConstRoundTrip(Constant.integer(127));
        assertConstRoundTrip(Constant.integer(-128));
        assertConstRoundTrip(Constant.integer(Long.MAX_VALUE));
        assertConstRoundTrip(Constant.integer(new BigInteger("999999999999999999999999999999")));
    }

    @Test
    void constByteString() {
        assertConstRoundTrip(Constant.byteString(new byte[]{}));
        assertConstRoundTrip(Constant.byteString(new byte[]{0x00}));
        assertConstRoundTrip(Constant.byteString(new byte[]{(byte) 0xFF}));
        assertConstRoundTrip(Constant.byteString(new byte[]{1, 2, 3, 4, 5}));
    }

    @Test
    void constString() {
        assertConstRoundTrip(Constant.string(""));
        assertConstRoundTrip(Constant.string("hello"));
        assertConstRoundTrip(Constant.string("UTF-8: \u00e9\u00e8\u00ea"));
    }

    @Test
    void constUnit() {
        assertConstRoundTrip(Constant.unit());
    }

    @Test
    void constBool() {
        assertConstRoundTrip(Constant.bool(true));
        assertConstRoundTrip(Constant.bool(false));
    }

    @Test
    void constData() {
        assertConstRoundTrip(Constant.data(PlutusData.integer(42)));
        assertConstRoundTrip(Constant.data(PlutusData.bytes(new byte[]{(byte) 0xAB, (byte) 0xCD})));
        assertConstRoundTrip(Constant.data(PlutusData.constr(0, PlutusData.integer(1))));
        assertConstRoundTrip(Constant.data(PlutusData.list(PlutusData.integer(1), PlutusData.integer(2))));
    }

    @Test
    void constList() {
        var list = new Constant.ListConst(DefaultUni.INTEGER,
                List.of(Constant.integer(1), Constant.integer(2), Constant.integer(3)));
        assertConstRoundTrip(list);
    }

    @Test
    void constListEmpty() {
        var list = new Constant.ListConst(DefaultUni.INTEGER, List.of());
        assertConstRoundTrip(list);
    }

    @Test
    void constListOfByteStrings() {
        var list = new Constant.ListConst(DefaultUni.BYTESTRING,
                List.of(Constant.byteString(new byte[]{1}), Constant.byteString(new byte[]{2})));
        assertConstRoundTrip(list);
    }

    @Test
    void constPair() {
        var pair = new Constant.PairConst(Constant.integer(1), Constant.string("hello"));
        assertConstRoundTrip(pair);
    }

    @Test
    void constPairIntBool() {
        var pair = new Constant.PairConst(Constant.integer(42), Constant.bool(true));
        assertConstRoundTrip(pair);
    }

    // --- PlutusData round-trips ---

    @Test
    void dataInteger() {
        assertDataRoundTrip(PlutusData.integer(0));
        assertDataRoundTrip(PlutusData.integer(42));
        assertDataRoundTrip(PlutusData.integer(-100));
        assertDataRoundTrip(PlutusData.integer(new BigInteger("999999999999999999")));
    }

    @Test
    void dataByteString() {
        assertDataRoundTrip(PlutusData.bytes(new byte[]{}));
        assertDataRoundTrip(PlutusData.bytes(new byte[]{1, 2, 3}));
    }

    @Test
    void dataConstr() {
        assertDataRoundTrip(PlutusData.constr(0));
        assertDataRoundTrip(PlutusData.constr(0, PlutusData.integer(1)));
        assertDataRoundTrip(PlutusData.constr(1, PlutusData.integer(1), PlutusData.bytes(new byte[]{2})));
    }

    @Test
    void dataList() {
        assertDataRoundTrip(PlutusData.list());
        assertDataRoundTrip(PlutusData.list(PlutusData.integer(1), PlutusData.integer(2)));
    }

    @Test
    void dataMap() {
        assertDataRoundTrip(PlutusData.map());
        assertDataRoundTrip(PlutusData.map(
                new PlutusData.Pair(PlutusData.integer(1), PlutusData.bytes(new byte[]{0x0A})),
                new PlutusData.Pair(PlutusData.integer(2), PlutusData.bytes(new byte[]{0x0B}))));
    }

    @Test
    void dataNested() {
        var nested = PlutusData.constr(0,
                PlutusData.list(PlutusData.integer(1), PlutusData.integer(2)),
                PlutusData.map(new PlutusData.Pair(PlutusData.integer(3), PlutusData.bytes(new byte[]{4}))));
        assertDataRoundTrip(nested);
    }

    // --- Complex programs ---

    @Test
    void addIntegersProgram() {
        // (program 1.1.0 [[(builtin addInteger) (con integer 3)] (con integer 4)])
        var program = Program.plutusV3(
                Term.apply(
                        Term.apply(
                                Term.builtin(DefaultFun.AddInteger),
                                Term.const_(Constant.integer(3))),
                        Term.const_(Constant.integer(4))));
        var decoded = roundTrip(program);
        assertEquals(1, decoded.major());
        assertEquals(1, decoded.minor());
        assertInstanceOf(Term.Apply.class, decoded.term());
    }

    @Test
    void identityAppliedProgram() {
        // (program 1.1.0 [(\x -> x) (con integer 42)])
        var program = Program.plutusV3(
                Term.apply(
                        Term.lam("x", Term.var(0)),
                        Term.const_(Constant.integer(42))));
        var decoded = roundTrip(program);
        assertInstanceOf(Term.Apply.class, decoded.term());
    }

    @Test
    void ifThenElseProgram() {
        // (force ifThenElse) true "yes" "no"
        var program = Program.plutusV3(
                Term.apply(
                        Term.apply(
                                Term.apply(
                                        Term.force(Term.builtin(DefaultFun.IfThenElse)),
                                        Term.const_(Constant.bool(true))),
                                Term.const_(Constant.string("yes"))),
                        Term.const_(Constant.string("no"))));
        var decoded = roundTrip(program);
        assertInstanceOf(Term.Apply.class, decoded.term());
    }

    @Test
    void constrCaseProgram() {
        // (program 1.1.0 (case (constr 1 (con integer 42)) (\x -> error) (\x -> x)))
        var program = Program.plutusV3(
                Term.case_(
                        Term.constr(1, Term.const_(Constant.integer(42))),
                        Term.lam("unused", Term.error()),
                        Term.lam("x", Term.var(0))));
        var decoded = roundTrip(program);
        assertInstanceOf(Term.Case.class, decoded.term());
        var caseT = (Term.Case) decoded.term();
        assertInstanceOf(Term.Constr.class, caseT.scrutinee());
        assertEquals(2, caseT.branches().size());
    }

    // --- Edge cases ---

    @Test
    void constLargeByteString() {
        var data = new byte[1000];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i & 0xFF);
        assertConstRoundTrip(Constant.byteString(data));
    }

    @Test
    void constVeryLargeInteger() {
        var big = BigInteger.TWO.pow(256);
        assertConstRoundTrip(Constant.integer(big));
        assertConstRoundTrip(Constant.integer(big.negate()));
    }

    /** plutus-core has no size limit for Integer constants (issue #225); 896 bits was julc's old cap. */
    @Test
    void constIntegerBeyond896Bits() {
        assertConstRoundTrip(Constant.integer(BigInteger.ONE.shiftLeft(2047)));
        assertConstRoundTrip(Constant.integer(BigInteger.ONE.shiftLeft(4096).negate()));
        assertConstRoundTrip(Constant.integer(BigInteger.ONE.shiftLeft(4096).subtract(BigInteger.ONE)));
    }

    /**
     * PlutusV3 script {@code 4f8c8e2263748472c48439bbeacc9de439ea3089b143c6a7c667b9b2} from preview tx
     * {@code b0e24e31a5e7e5e6e7d2675288a4eb7190c0bce0d622c8b2f514fbcd18e835c9} holds a 2048-bit integer
     * constant; the chain accepts it (issue #225).
     */
    @Test
    void realScriptWith2048BitIntegerConstant() throws IOException {
        byte[] flat;
        try (var in = getClass().getResourceAsStream("/flat/preview-script-4f8c8e22.flat.hex")) {
            flat = HexFormat.of().parseHex(new String(in.readAllBytes(), StandardCharsets.US_ASCII).strip());
        }
        var program = UplcFlatDecoder.decodeProgram(flat);
        assertEquals(2048, maxIntegerBitLength(program.term()));
        assertArrayEquals(flat, UplcFlatEncoder.encodeProgram(program));
    }

    @Test
    void oversizedVersionNumberIsADecodingError() {
        var w = new FlatWriter();
        w.natural(BigInteger.ONE.shiftLeft(40));
        w.natural(BigInteger.ONE);
        w.natural(BigInteger.ZERO);
        assertThrows(FlatDecodingException.class, () -> UplcFlatDecoder.decodeProgram(w.toByteArray()));
    }

    /** De Bruijn indices and constructor tags are Word64s: plutus-core's {@code dWord64} reads at most ten groups. */
    @Test
    void variableIndexAndConstructorTagAreBoundedWord64s() {
        var tenGroups = (Term.Lam) UplcFlatDecoder.decodeProgram(lamVar(10)).term();
        assertEquals(1, ((Term.Var) tenGroups.body()).name().index());
        assertEquals(1L, ((Term.Constr) UplcFlatDecoder.decodeProgram(constr(10)).term()).tag());
        assertThrows(FlatDecodingException.class, () -> UplcFlatDecoder.decodeProgram(lamVar(11)));
        assertThrows(FlatDecodingException.class, () -> UplcFlatDecoder.decodeProgram(constr(11)));
    }

    /** Program 1.0.0 {@code (lam (var 1))} with the index written in {@code groups} vli7 groups. */
    private static byte[] lamVar(int groups) {
        var w = version100();
        w.bits(4, 2);
        w.bits(4, 0);
        writePaddedOne(w, groups);
        w.filler();
        return w.toByteArray();
    }

    /** Program 1.0.0 {@code (constr 1)} with the tag written in {@code groups} vli7 groups. */
    private static byte[] constr(int groups) {
        var w = version100();
        w.bits(4, 8);
        writePaddedOne(w, groups);
        w.listNil();
        w.filler();
        return w.toByteArray();
    }

    private static FlatWriter version100() {
        var w = new FlatWriter();
        w.natural(BigInteger.ONE);
        w.natural(BigInteger.ZERO);
        w.natural(BigInteger.ZERO);
        return w;
    }

    private static void writePaddedOne(FlatWriter w, int groups) {
        for (byte b : FlatWriterReaderTest.paddedOne(groups)) {
            w.byte_(b & 0xFF);
        }
    }

    private static int maxIntegerBitLength(Term term) {
        return switch (term) {
            case Term.Const c when c.value() instanceof Constant.IntegerConst i -> i.value().bitLength();
            case Term.Lam l -> maxIntegerBitLength(l.body());
            case Term.Apply a -> Math.max(maxIntegerBitLength(a.function()), maxIntegerBitLength(a.argument()));
            case Term.Force f -> maxIntegerBitLength(f.term());
            case Term.Delay d -> maxIntegerBitLength(d.term());
            case Term.Constr c -> c.fields().stream().mapToInt(UplcFlatRoundTripTest::maxIntegerBitLength).max().orElse(0);
            case Term.Case c -> Math.max(maxIntegerBitLength(c.scrutinee()),
                    c.branches().stream().mapToInt(UplcFlatRoundTripTest::maxIntegerBitLength).max().orElse(0));
            default -> 0;
        };
    }

    @Test
    void deeplyNestedProgram() {
        // Build nested delays: delay(delay(delay(...error...)))
        Term t = Term.error();
        for (int i = 0; i < 100; i++) {
            t = Term.delay(t);
        }
        var program = Program.plutusV3(t);
        var decoded = roundTrip(program);
        // Verify structure
        Term d = decoded.term();
        for (int i = 0; i < 100; i++) {
            assertInstanceOf(Term.Delay.class, d);
            d = ((Term.Delay) d).term();
        }
        assertInstanceOf(Term.Error.class, d);
    }

    @Test
    void dataNestedConstr() {
        // Constr(0, [Constr(1, [Constr(2, [Int(42)])])])
        var inner = PlutusData.constr(2, PlutusData.integer(42));
        var mid = PlutusData.constr(1, inner);
        var outer = PlutusData.constr(0, mid);
        assertDataRoundTrip(outer);
    }

    @Test
    void dataMapWithManyEntries() {
        var entries = new PlutusData.Pair[50];
        for (int i = 0; i < 50; i++) {
            entries[i] = new PlutusData.Pair(PlutusData.integer(i), PlutusData.bytes(new byte[]{(byte) i}));
        }
        assertDataRoundTrip(PlutusData.map(entries));
    }

    @Test
    void termConstrLargeFields() {
        var fields = new Term[20];
        for (int i = 0; i < 20; i++) {
            fields[i] = Term.const_(Constant.integer(i));
        }
        var term = Term.constr(0, fields);
        var decoded = roundTripTermGet(term);
        assertInstanceOf(Term.Constr.class, decoded);
        assertEquals(20, ((Term.Constr) decoded).fields().size());
    }

    @Test
    void constArrayRoundTrip() {
        var array = new Constant.ArrayConst(DefaultUni.BOOL,
                List.of(Constant.bool(true), Constant.bool(false)));
        assertConstRoundTrip(array);
    }

    @Test
    void constArrayEmpty() {
        var array = new Constant.ArrayConst(DefaultUni.INTEGER, List.of());
        assertConstRoundTrip(array);
    }

    @Test
    void constValueRoundTrip() {
        var value = new Constant.ValueConst(List.of(
                new Constant.ValueConst.ValueEntry(new byte[]{0x01, 0x02},
                        List.of(new Constant.ValueConst.TokenEntry(new byte[]{0x03}, BigInteger.valueOf(100))))
        ));
        assertConstRoundTrip(value);
    }

    @Test
    void constValueEmpty() {
        var value = new Constant.ValueConst(List.of());
        assertConstRoundTrip(value);
    }

    @Test
    void constListOfData() {
        var list = new Constant.ListConst(DefaultUni.DATA,
                List.of(Constant.data(PlutusData.integer(1)),
                        Constant.data(PlutusData.constr(0, PlutusData.integer(2)))));
        assertConstRoundTrip(list);
    }

    // --- Helpers ---

    private Program roundTrip(Program program) {
        byte[] encoded = UplcFlatEncoder.encodeProgram(program);
        return UplcFlatDecoder.decodeProgram(encoded);
    }

    private void roundTripTerm(Term term) {
        roundTripTermGet(term);
    }

    private Term roundTripTermGet(Term term) {
        var program = Program.plutusV3(term);
        var decoded = roundTrip(program);
        return decoded.term();
    }

    private void assertConstRoundTrip(Constant original) {
        var term = Term.const_(original);
        var decoded = roundTripTermGet(term);
        assertInstanceOf(Term.Const.class, decoded);
        assertEquals(original, ((Term.Const) decoded).value());
    }

    private void assertDataRoundTrip(PlutusData original) {
        var constant = Constant.data(original);
        var term = Term.const_(constant);
        var decoded = roundTripTermGet(term);
        assertInstanceOf(Term.Const.class, decoded);
        var dataConst = (Constant.DataConst) ((Term.Const) decoded).value();
        assertEquals(original, dataConst.value());
    }
}
