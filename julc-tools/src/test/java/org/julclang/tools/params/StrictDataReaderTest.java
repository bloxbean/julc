package org.julclang.tools.params;

import org.julclang.core.PlutusData;
import org.julclang.core.flat.UplcFlatDecoder;
import org.julclang.tools.model.MockTransaction.DataInput;
import org.julclang.tools.params.ApplyParamsException.Kind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ADR-077 D6a: the strict, lossless value reader. */
class StrictDataReaderTest {

    static final BigInteger MAX_TAG = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
    static final BigInteger TOO_BIG = BigInteger.ONE.shiftLeft(64);

    static PlutusData json(String text) {
        return StrictDataReader.read(new DataInput("json", text));
    }

    static void refused(DataInput input, String messagePart) {
        var e = assertThrows(ApplyParamsException.class, () -> StrictDataReader.read(input));
        assertEquals(Kind.INVALID_REQUEST, e.kind());
        assertTrue(e.getMessage().contains(messagePart), () -> "'" + e.getMessage() + "' lacks '" + messagePart + "'");
    }

    static void refusedJson(String text, String messagePart) {
        refused(new DataInput("json", text), messagePart);
    }

    @Test
    void readsEveryShape() {
        assertEquals(PlutusData.integer(new BigInteger("-18446744073709551617")), json("{\"int\":-18446744073709551617}"));
        assertEquals(PlutusData.bytes(HexFormat.of().parseHex("cafe")), json("{\"bytes\":\"CaFe\"}"));
        assertEquals(PlutusData.bytes(new byte[0]), json("{\"bytes\":\"\"}"));
        assertEquals(new PlutusData.ListData(List.of(PlutusData.integer(1))), json("{\"list\":[{\"int\":1}]}"));
        assertEquals(new PlutusData.ConstrData(BigInteger.TWO, List.of()), json("{\"constructor\":2,\"fields\":[]}"));
        assertEquals(new PlutusData.ConstrData(BigInteger.ZERO, List.of()), json("{\"fields\":[],\"constructor\":0}"));
        // Whitespace around the document is not a second document.
        assertEquals(PlutusData.integer(1), json("  {\"int\":1}\n"));
    }

    @Test
    void mapEntriesKeepTheirOrderAndDuplicates() {
        var data = assertInstanceOf(PlutusData.MapData.class, json("{\"map\":["
                + "{\"k\":{\"int\":2},\"v\":{\"int\":1}},{\"k\":{\"int\":1},\"v\":{\"int\":2}},"
                + "{\"k\":{\"int\":2},\"v\":{\"int\":3}}]}"));
        assertEquals(3, data.entries().size());
        assertEquals(PlutusData.integer(2), data.entries().get(0).key());
        assertEquals(PlutusData.integer(1), data.entries().get(1).key());
        assertEquals(PlutusData.integer(3), data.entries().get(2).value());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"int\":1.5}", "{\"int\":1.0}", "{\"int\":1e3}", "{\"int\":\"42\"}", "{\"int\":true}",
            "{\"int\":null}"})
    void integersAreIntegerTokens(String text) {
        refusedJson(text, "int must be a JSON integer");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"constructor\":\"bogus\",\"fields\":[]}", "{\"constructor\":1.0,\"fields\":[]}",
            "{\"constructor\":1e1,\"fields\":[]}", "{\"constructor\":null,\"fields\":[]}"})
    void constructorIndicesAreIntegerTokens(String text) {
        refusedJson(text, "constructor must be a JSON integer");
    }

    @Test
    void shapesAreExact() {
        refusedJson("{\"int\":1,\"bytes\":\"00\"}", "exactly one shape");
        refusedJson("{\"constructor\":0,\"fields\":[],\"list\":[]}", "exactly one shape");
        refusedJson("{\"int\":1,\"extra\":2}", "unknown member 'extra'");
        refusedJson("{\"list\":[],\"fields\":[]}", "unknown member 'fields'");
        refusedJson("{\"constructor\":0}", "needs its fields");
        refusedJson("{\"constructor\":0,\"fields\":{}}", "fields must be an array");
        refusedJson("{}", "expected one of int");
        refusedJson("[]", "expected a Plutus data object");
        refusedJson("42", "expected a Plutus data object");
        refusedJson("{\"map\":[{\"k\":{\"int\":1}}]}", "exactly the members k and v");
        refusedJson("{\"map\":[{\"k\":{\"int\":1},\"v\":{\"int\":1},\"x\":{}}]}", "exactly the members k and v");
        refusedJson("{\"map\":{}}", "map must be an array");
        refusedJson("{\"list\":[{\"int\":1},{\"int\":1.5}]}", "at list[1]: int must be a JSON integer");
    }

    @Test
    void bytesAreEvenLengthHex() {
        refusedJson("{\"bytes\":\"abc\"}", "even-length hex");
        refusedJson("{\"bytes\":\"0xab\"}", "even-length hex");
        refusedJson("{\"bytes\":\"zz\"}", "even-length hex");
        refusedJson("{\"bytes\":12}", "even-length hex");
    }

    @Test
    void documentsAreSingleAndMembersUnique() {
        refusedJson("{\"int\":1} {\"int\":2}", "not strict JSON");
        refusedJson("{\"int\":1}x", "not strict JSON");
        refusedJson("{\"int\":1,\"int\":2}", "Duplicate field 'int'");
        refusedJson("{\"list\":[{\"bytes\":\"00\",\"bytes\":\"01\"}]}", "Duplicate field 'bytes'");
        refusedJson("{\"map\":[{\"k\":{\"int\":1},\"k\":{\"int\":2},\"v\":{\"int\":3}}]}", "Duplicate field 'k'");
        refusedJson("", "empty");
    }

    @Test
    void constructorBoundsAtTheTopAndNested() {
        assertEquals(MAX_TAG, ((PlutusData.ConstrData) json("{\"constructor\":" + MAX_TAG + ",\"fields\":[]}"))
                .constructorTag());
        refusedJson("{\"constructor\":" + TOO_BIG + ",\"fields\":[]}", "outside 0 to 2^64 - 1");
        refusedJson("{\"constructor\":-1,\"fields\":[]}", "outside 0 to 2^64 - 1");
        String nestedOk = "{\"list\":[{\"map\":[{\"k\":{\"int\":0},\"v\":{\"constructor\":" + MAX_TAG
                + ",\"fields\":[]}}]}]}";
        json(nestedOk);
        refusedJson(nestedOk.replace(MAX_TAG.toString(), TOO_BIG.toString()),
                "at list[0].map[0].v: constructor index 18446744073709551616");
        // UPLC text reads constructor indices as unsigned 64-bit words (M2-R4).
        assertEquals(MAX_TAG, ((PlutusData.ConstrData) StrictDataReader.read(new DataInput("uplc",
                "Constr " + MAX_TAG + " []"))).constructorTag());
        assertEquals(new BigInteger("2147483648"), ((PlutusData.ConstrData) StrictDataReader.read(new DataInput("uplc",
                "Constr 2147483648 []"))).constructorTag());
        refused(new DataInput("uplc", "Constr " + TOO_BIG + " []"), "constructor index 18446744073709551616 is outside"
                + " 0 to 2^64 - 1");
        refused(new DataInput("uplc", "List [Constr " + TOO_BIG + " []]"), "outside 0 to 2^64 - 1");
        refused(new DataInput("uplc", "Constr -1 []"), "expected a constructor index");
        // d866 821bffffffffffffffff 80: the canonical CBOR of Constr (2^64-1) [].
        StrictDataReader.read(new DataInput("cbor", "d866821bffffffffffffffff80"));
    }

    @Test
    void theBoundIsCheckedOnEveryDecodedValue() {
        // CBOR and UPLC text cannot reach 2^64 through today's decoders (CBOR refuses a bignum tag, the UPLC parser an
        // index over 2^31 - 1), so the check after decoding is exercised directly.
        var inside = new PlutusData.ListData(List.of(PlutusData.integer(1),
                new PlutusData.ConstrData(TOO_BIG, List.of())));
        var e = assertThrows(ApplyParamsException.class, () -> StrictDataReader.checkConstructorIndices(
                new PlutusData.MapData(List.of(new PlutusData.Pair(PlutusData.integer(0), inside)))));
        assertEquals("at map[0].v.list[1]: constructor index 18446744073709551616 is outside 0 to 2^64 - 1; Plutus data"
                + " constructor tags are unsigned 64-bit words", e.getMessage());
        StrictDataReader.checkConstructorIndices(new PlutusData.ConstrData(MAX_TAG, List.of(inside.items().getFirst())));
        refused(new DataInput("cbor", "d86682c24901000000000000000080"), "the cbor value is not one Plutus data item");
    }

    @Test
    void maximalConstructorIndexAppliesAndTheResultDecodes() {
        var response = ApplyParams.apply(ApplyParamsTest.request("V3", ApplyParamsTest.FLAT, List.of(
                new DataInput("json", "{\"constructor\":" + MAX_TAG + ",\"fields\":[]}"))));
        // The ADR's canonical script with index 2^64 - 1, applied to (lam p (con unit ())).
        assertEquals("010100324993010dd866821bffffffffffffffff800001",
                HexFormat.of().formatHex(CanonicalScript.unwrap(HexFormat.of().parseHex(response.cborHex()), "t")));
        var program = UplcFlatDecoder.decodeProgram(
                CanonicalScript.unwrap(HexFormat.of().parseHex(response.cborHex()), "t"));
        assertTrue(program.toString().contains(MAX_TAG.toString()), program.toString());
        // And it reads back through the canonical script rule.
        ApplyParams.apply(ApplyParamsTest.request("V3", response.cborHex(), List.of()));
        // Nested.
        ApplyParams.apply(ApplyParamsTest.request("V3", ApplyParamsTest.FLAT, List.of(new DataInput("json",
                "{\"constructor\":0,\"fields\":[{\"constructor\":" + MAX_TAG + ",\"fields\":[]}]}"))));
        var e = assertThrows(ApplyParamsException.class, () -> ApplyParams.apply(ApplyParamsTest.request("V3",
                ApplyParamsTest.FLAT, List.of(new DataInput("json", "{\"constructor\":0,\"fields\":[{\"constructor\":"
                        + TOO_BIG + ",\"fields\":[]}]}")))));
        assertTrue(e.getMessage().startsWith("Parameter 1: at fields[0]: constructor index"), e.getMessage());
    }

    @Test
    void cborValuesMustBeCanonical() {
        // A one-field constructor: non-empty fields are an indefinite array in Plutus's encoding.
        assertEquals(new PlutusData.ConstrData(0, List.of(PlutusData.integer(1))),
                StrictDataReader.read(new DataInput("cbor", "d8799f01ff")));
        refused(new DataInput("cbor", "d8798101"), "not in the canonical encoding");
        refused(new DataInput("cbor", "d8798101"), "expected d8799f01ff");
        refused(new DataInput("cbor", "1800"), "not in the canonical encoding");   // 0 with a one-byte argument
        refused(new DataInput("cbor", "8101"), "not in the canonical encoding");   // a definite non-empty list
        StrictDataReader.read(new DataInput("cbor", "9f01ff"));                    // the canonical one
        refused(new DataInput("cbor", "182a00"), "not one Plutus data item");      // a trailing byte
        refused(new DataInput("cbor", "0x182a"), "even-length hex");
        refused(new DataInput("cbor", ""), "even-length hex");
        // A long byte string must be in 64-byte chunks (the decoder already refuses a definite one).
        byte[] long65 = new byte[65];
        refused(new DataInput("cbor", "5841" + HexFormat.of().formatHex(long65)), "the cbor value");
        String chunked = "5f5840" + "00".repeat(64) + "4100" + "ff";
        assertEquals(PlutusData.bytes(long65), StrictDataReader.read(new DataInput("cbor", chunked)));
        // Chunks of another size decode to the same bytes, but are not canonical.
        refused(new DataInput("cbor", "5f4100" + "5840" + "00".repeat(64) + "ff"), "not in the canonical encoding");
    }

    @Test
    void uplcValuesAreStrict() {
        assertEquals(new PlutusData.ConstrData(0, List.of(PlutusData.integer(42))),
                StrictDataReader.read(new DataInput("uplc", "Constr 0 [I 42]")));
        refused(new DataInput("uplc", "I 42 I 43"), "unexpected text after the value");
        refused(new DataInput("uplc", "I 42)) (con integer 1"), "unexpected text after the value");
        refused(new DataInput("uplc", "I 42) (I 1"), "unexpected text after the value");
        refused(new DataInput("uplc", "Constr 0 [I 42"), "expected ']', found the end of the text");
        refused(new DataInput("uplc", ""), "found the end of the text");
        refused(new DataInput("uplc", "I 42x"), "unexpected character after a number");
        refused(new DataInput("uplc", "B #abc"), "even number of hex digits");
        refused(new DataInput("uplc", "Bogus 1"), "found 'Bogus'");
        refused(new DataInput("uplc", "Map [(I 1 I 2)]"), "expected ','");
        // M2-C5: a comment cannot hide or add structure; it ends at the end of its line.
        refused(new DataInput("uplc", "-- ((\nI 42)) --"), "unexpected text after the value");
        assertEquals(PlutusData.integer(42), StrictDataReader.read(new DataInput("uplc", "-- a (comment\n I 42 -- ) end")));
        assertEquals(new PlutusData.ConstrData(0, List.of(PlutusData.integer(1), PlutusData.bytes(new byte[]{1}))),
                StrictDataReader.read(new DataInput("uplc", "Constr 0 [ -- first (\n I 1, -- second )\n B #01 ]")));
        // Parentheses, maps and every shape, as UplcParser reads them.
        assertEquals(new PlutusData.MapData(List.of(new PlutusData.Pair(PlutusData.integer(1),
                        new PlutusData.ListData(List.of(PlutusData.bytes(new byte[0])))),
                        new PlutusData.Pair(PlutusData.integer(-2), PlutusData.integer(3)))),
                StrictDataReader.read(new DataInput("uplc", " ( Map [((I 1), List [B #]), (I -2, (I +3))] ) ")));
        assertEquals(new PlutusData.ListData(List.of()), StrictDataReader.read(new DataInput("uplc", "List []")));
        assertEquals(new PlutusData.MapData(List.of()), StrictDataReader.read(new DataInput("uplc", "Map [ ]")));
    }

    @Test
    void formatIsRequired() {
        refused(new DataInput(null, "{\"int\":1}"), "has no format");
        refused(new DataInput("", "{\"int\":1}"), "has no format");
        refused(new DataInput("auto", "{\"int\":1}"), "format auto is not accepted");
        refused(new DataInput("Json", "{\"int\":1}"), "unknown value format");
        refused(new DataInput("json", null), "missing");
        refused(null, "missing");
    }

    /** M2-C2: depth 10,000 in every format, read on a thread with a small stack. */
    @Test
    void deepValuesInEveryFormatNeedNoJavaStack() throws Exception {
        int depth = 10_000;
        var inputs = List.of(
                new DataInput("json", "{\"list\":[".repeat(depth) + "{\"int\":0}" + "]}".repeat(depth)),
                new DataInput("cbor", "9f".repeat(depth) + "00" + "ff".repeat(depth)),
                new DataInput("uplc", "List [".repeat(depth) + "I 0" + "]".repeat(depth)));
        for (var input : inputs) {
            PlutusData data = onSmallStack(() -> StrictDataReader.read(input));
            for (int i = 0; i < depth; i++) data = ((PlutusData.ListData) data).items().getFirst();
            assertEquals(PlutusData.integer(0), data, input.format());
        }
        // Non-canonical deep CBOR is refused as such, not by exhausting a stack.
        refused(new DataInput("cbor", "9f".repeat(depth) + "8100" + "ff".repeat(depth)), "not in the canonical encoding");
    }

    /** Runs {@code task} on a thread with a 256 KiB stack, as small as a Web Image's. */
    static <T> T onSmallStack(Callable<T> task) throws Exception {
        var result = new AtomicReference<Object>();
        var thread = new Thread(null, () -> {
            try {
                result.set(task.call());
            } catch (Throwable e) {
                result.set(e);
            }
        }, "small-stack", 256 * 1024);
        thread.start();
        thread.join();
        if (result.get() instanceof Throwable e) {
            if (e instanceof Exception ex) throw ex;
            throw new AssertionError(e);
        }
        @SuppressWarnings("unchecked") T value = (T) result.get();
        return value;
    }

    @Test
    void deepValuesNeedNoJavaStack() {
        int depth = 400;
        String text = "{\"list\":[".repeat(depth) + "{\"int\":1}" + "]}".repeat(depth);
        var data = json(text);
        for (int i = 0; i < depth; i++) data = ((PlutusData.ListData) data).items().getFirst();
        assertEquals(PlutusData.integer(1), data);
    }
}
