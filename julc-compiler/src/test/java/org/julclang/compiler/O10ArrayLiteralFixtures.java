package org.julclang.compiler;

import org.julclang.core.PlutusData;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * ADR-046 (O10) fixtures: {@code compileMethod} sources built from {@code JulcArray.of},
 * {@code list.toArray()} and {@code JulcArray.fromList} over literal lists, indexed and
 * measured, in shapes the array literal fold must fold completely, in part, or not at all.
 * Every fixture is compiled with the rule off and on and evaluated on Java, Truffle and Scalus
 * by {@link O10ArrayLiteralFoldTest}; {@code folds} is the expected rule provenance at the
 * safe profile, {@code callsBefore}/{@code callsAfter} the number of array builtin call sites
 * ({@code ListToArray}, {@code LengthOfArray}, {@code IndexArray}) left in the user code.
 */
final class O10ArrayLiteralFixtures {

    record Input(String name, List<PlutusData> args, boolean success) {
        static Input ok(String name, PlutusData... args) { return new Input(name, List.of(args), true); }
        static Input fails(String name, PlutusData... args) { return new Input(name, List.of(args), false); }
        @Override public String toString() { return name; }
    }

    record Fixture(String name, String source, String method, boolean folds, int callsBefore, int callsAfter,
                   List<Input> inputs) {}

    static final String IMPORTS = """
            import org.julclang.core.PlutusData;
            import org.julclang.core.types.JulcArray;
            import org.julclang.core.types.JulcList;
            import org.julclang.stdlib.Builtins;
            import org.julclang.stdlib.lib.ContextsLib;
            import java.math.BigInteger;
            """;

    private static String method(String body) {
        return IMPORTS + "class Fixture {\n" + body + "\n}\n";
    }

    /** A three-element integer table. */
    static final String TABLE = "JulcArray<BigInteger> t = JulcArray.of(BigInteger.ONE, BigInteger.TWO, BigInteger.valueOf(3));";

    /** 0: the length of a literal array folds to a constant. */
    static final String LENGTH = method("""
                static BigInteger length() {
                    %s
                    return BigInteger.valueOf(t.length());
                }""".formatted(TABLE));

    /** 1: a literal index into a literal array folds through the integer decode. */
    static final String GET_LITERAL = method("""
                static BigInteger getLiteral() {
                    %s
                    return t.get(1);
                }""".formatted(TABLE));

    /** 2: a runtime index: the literal array is embedded as a constant, the access stays. */
    static final String GET_RUNTIME = method("""
                static BigInteger getRuntime(BigInteger i) {
                    %s
                    return t.get(i);
                }""".formatted(TABLE));

    /** 3: a literal index out of bounds stays and fails at runtime with the builtin's text. */
    static final String OUT_OF_RANGE_LITERAL = method("""
                static BigInteger outOfRangeLiteral() {
                    %s
                    return t.get(5);
                }""".formatted(TABLE));

    /** 4: a negative literal index stays and fails. */
    static final String NEGATIVE_LITERAL = method("""
                static BigInteger negativeLiteral() {
                    %s
                    return t.get(new BigInteger("-1"));
                }""".formatted(TABLE));

    /** 5: a literal index beyond the machine range stays and fails with the other text. */
    static final String HUGE_INDEX = method("""
                static BigInteger hugeIndex() {
                    %s
                    return t.get(new BigInteger("9223372036854775808"));
                }""".formatted(TABLE));

    /** 6: byte string elements fold through the byte string decode. */
    static final String BYTES = method("""
                static BigInteger bytes() {
                    JulcArray<byte[]> k = JulcArray.of(new byte[]{1, 2}, new byte[]{3});
                    return BigInteger.valueOf(Builtins.lengthOfByteString(k.get(0)));
                }""");

    /** 7: string elements fold through the UTF-8 decode. */
    static final String STRING = method("""
                static boolean string() {
                    JulcArray<String> s = JulcArray.of("ab", "cde");
                    return s.get(1).equals("cde");
                }""");

    /** 8: boolean elements fold through the constructor-tag decode. */
    static final String BOOL = method("""
                static BigInteger bool() {
                    JulcArray<Boolean> f = JulcArray.of(true, false);
                    if (f.get(1)) {
                        return BigInteger.ONE;
                    }
                    return BigInteger.ZERO;
                }""");

    /** 9: nested list elements fold through the list decode to a list constant. */
    static final String NESTED_LIST = method("""
                static BigInteger nestedList() {
                    JulcArray<JulcList<BigInteger>> rows = JulcArray.of(JulcList.of(BigInteger.ONE, BigInteger.TWO), JulcList.of(BigInteger.valueOf(3)));
                    return BigInteger.valueOf(rows.get(1).size());
                }""");

    /** 10: the empty literal array. */
    static final String EMPTY = method("""
                static BigInteger empty() {
                    JulcArray<BigInteger> e = JulcArray.of();
                    return BigInteger.valueOf(e.length());
                }""");

    /** 11: indexing the empty literal array stays and fails. */
    static final String EMPTY_GET = method("""
                static BigInteger emptyGet() {
                    JulcArray<BigInteger> e = JulcArray.of();
                    return e.get(0);
                }""");

    /**
     * 12: a once-bound list local converted to an array and still used afterwards: the
     * conversion would copy the list into an array constant while the list stays live, so
     * the call-site objective (ADR-045, second review) keeps it and nothing folds.
     */
    static final String LOCAL_LIST = method("""
                static BigInteger localList() {
                    JulcList<BigInteger> xs = JulcList.of(BigInteger.ONE, BigInteger.TWO);
                    JulcArray<BigInteger> a = xs.toArray();
                    return a.get(0).add(BigInteger.valueOf(xs.size()));
                }""");

    /**
     * 12b: the list local's only use is the conversion: the local dies with the fold, so it is
     * credited and the conversion, the access and the decode all fold.
     */
    static final String LOCAL_LIST_ONCE = method("""
                static BigInteger localListOnce() {
                    JulcList<BigInteger> xs = JulcList.of(BigInteger.ONE, BigInteger.TWO);
                    JulcArray<BigInteger> a = xs.toArray();
                    return a.get(0).add(a.get(1));
                }""");

    /** 13: {@code JulcArray.fromList} of a list literal. */
    static final String FROM_LIST = method("""
                static BigInteger fromList() {
                    return BigInteger.valueOf(JulcArray.fromList(JulcList.of(BigInteger.ONE, BigInteger.TWO)).length());
                }""");

    /** 14: a runtime element: nothing is a literal, nothing folds. */
    static final String RUNTIME_ELEMENT = method("""
                static BigInteger runtimeElement(BigInteger x) {
                    JulcArray<BigInteger> a = JulcArray.of(x, BigInteger.ONE);
                    return a.get(0).add(a.get(1));
                }""");

    /** 15: a trace before the literal access: the fold happens after the trace. */
    static final String TRACE_AROUND = method("""
                static BigInteger traceAround() {
                    ContextsLib.trace("table");
                    %s
                    return t.get(2);
                }""".formatted(TABLE));

    /** 16: an error guard before the literal access. */
    static final String ERROR_ARM = method("""
                static BigInteger errorArm(boolean flag) {
                    if (flag) {
                        Builtins.error();
                    }
                    %s
                    return t.get(0);
                }""".formatted(TABLE));

    /** 17: two literal arrays, both accessed at literals. */
    static final String TWO_ARRAYS = method("""
                static BigInteger twoArrays() {
                    JulcArray<BigInteger> a = JulcArray.of(BigInteger.valueOf(10), BigInteger.valueOf(20));
                    JulcArray<BigInteger> b = JulcArray.of(BigInteger.valueOf(1), BigInteger.valueOf(2));
                    return a.get(0).add(b.get(1));
                }""");

    /**
     * 18: the access sits in a helper whose body is the decoded access, not a bare builtin, so
     * the helper is not a wrapper: the literal array is passed as a constant, the access stays.
     */
    static final String HELPER_GET = method("""
                static BigInteger at(JulcArray<BigInteger> a, BigInteger i) {
                    return a.get(i);
                }
                static BigInteger helperGet() {
                    return at(JulcArray.of(BigInteger.ONE, BigInteger.TWO), BigInteger.ONE);
                }""");

    /** {@code new byte[]{1, 1, ...}} of the given length. */
    static String bytesLiteral(int length) {
        return "new byte[]{" + IntStream.range(0, length).mapToObj(i -> "1").collect(Collectors.joining(", ")) + "}";
    }

    static final String B32 = bytesLiteral(32);
    static final String B256 = bytesLiteral(256);
    /** The bytes of {@link #B256}, once and twice. */
    static final byte[] B32_BYTES = new byte[32];
    static final byte[] B256_BYTES = new byte[256];
    static final byte[] B256_TWICE = new byte[512];
    static { Arrays.fill(B32_BYTES, (byte) 1); Arrays.fill(B256_BYTES, (byte) 1); Arrays.fill(B256_TWICE, (byte) 1); }

    /**
     * 19: a 256-byte local shared by both elements of the literal and by a runtime use: the
     * conversion would copy it twice into an array constant while the local stays live, so
     * nothing folds (the review's reproducer: 306 → 825 bytes when the list literal was
     * measured as its expanded constant).
     */
    static final String SHARED_ELEMENT = method("""
                static byte[] sharedElement(BigInteger i) {
                    byte[] b = %s;
                    JulcArray<byte[]> a = JulcArray.of(b, b);
                    return Builtins.appendByteString(a.get(i), b);
                }""".formatted(B256));

    /** 20: the local's only uses are the two elements: the array would hold two copies against the one binding that dies, so the conversion stays. */
    static final String SHARED_ELEMENT_ONCE = method("""
                static byte[] sharedElementOnce() {
                    byte[] b = %s;
                    JulcArray<byte[]> a = JulcArray.of(b, b);
                    return a.get(0);
                }""".formatted(B256));

    /** 21: a 32-byte local used once, as the single element: it dies with the conversion, is credited, and everything folds. */
    static final String ELEMENT_ONCE = method("""
                static byte[] elementOnce() {
                    byte[] b = %s;
                    JulcArray<byte[]> a = JulcArray.of(b);
                    return a.get(0);
                }""".formatted(B32));

    /**
     * 22: the same with the 256-byte local: credited, but the array constant (the element as
     * CBOR Data) is longer than the raw byte-string constant plus its wrapping, so the
     * objective keeps the conversion (measured in the direct-PIR objective probe).
     */
    static final String WIDE_ELEMENT_ONCE = method("""
                static byte[] wideElementOnce() {
                    byte[] b = %s;
                    JulcArray<byte[]> a = JulcArray.of(b);
                    return a.get(0);
                }""".formatted(B256));

    static final PlutusData TRUE = PlutusData.constr(1);
    static final PlutusData FALSE = PlutusData.constr(0);
    private static final List<Input> NONE = List.of(Input.ok("run"));
    private static final List<Input> FAILS = List.of(Input.fails("run"));

    static final List<Fixture> FIXTURES = List.of(
            new Fixture("LENGTH", LENGTH, "length", true, 2, 0, NONE),
            new Fixture("GET_LITERAL", GET_LITERAL, "getLiteral", true, 2, 0, NONE),
            new Fixture("GET_RUNTIME", GET_RUNTIME, "getRuntime", true, 2, 1, List.of(
                    Input.ok("first", PlutusData.integer(0)),
                    Input.ok("last", PlutusData.integer(2)),
                    Input.fails("past-the-end", PlutusData.integer(3)),
                    Input.fails("negative", PlutusData.integer(-1)),
                    Input.fails("huge", PlutusData.integer(new java.math.BigInteger("9223372036854775808"))))),
            new Fixture("OUT_OF_RANGE_LITERAL", OUT_OF_RANGE_LITERAL, "outOfRangeLiteral", true, 2, 1, FAILS),
            new Fixture("NEGATIVE_LITERAL", NEGATIVE_LITERAL, "negativeLiteral", true, 2, 1, FAILS),
            new Fixture("HUGE_INDEX", HUGE_INDEX, "hugeIndex", true, 2, 1, FAILS),
            new Fixture("BYTES", BYTES, "bytes", true, 2, 0, NONE),
            new Fixture("STRING", STRING, "string", true, 2, 0, NONE),
            new Fixture("BOOL", BOOL, "bool", true, 2, 0, NONE),
            new Fixture("NESTED_LIST", NESTED_LIST, "nestedList", true, 2, 0, NONE),
            new Fixture("EMPTY", EMPTY, "empty", true, 2, 0, NONE),
            new Fixture("EMPTY_GET", EMPTY_GET, "emptyGet", true, 2, 1, FAILS),
            new Fixture("LOCAL_LIST", LOCAL_LIST, "localList", false, 2, 2, NONE),
            new Fixture("LOCAL_LIST_ONCE", LOCAL_LIST_ONCE, "localListOnce", true, 3, 0, NONE),
            new Fixture("FROM_LIST", FROM_LIST, "fromList", true, 2, 0, NONE),
            new Fixture("RUNTIME_ELEMENT", RUNTIME_ELEMENT, "runtimeElement", false, 3, 3, List.of(
                    Input.ok("five", PlutusData.integer(5)))),
            new Fixture("TRACE_AROUND", TRACE_AROUND, "traceAround", true, 2, 0, NONE),
            new Fixture("ERROR_ARM", ERROR_ARM, "errorArm", true, 2, 0, List.of(
                    Input.ok("pass", FALSE),
                    Input.fails("guard", TRUE))),
            new Fixture("TWO_ARRAYS", TWO_ARRAYS, "twoArrays", true, 4, 0, NONE),
            new Fixture("HELPER_GET", HELPER_GET, "helperGet", true, 2, 1, NONE),
            new Fixture("SHARED_ELEMENT", SHARED_ELEMENT, "sharedElement", false, 2, 2, List.of(
                    Input.ok("first", PlutusData.integer(0)),
                    Input.ok("second", PlutusData.integer(1)),
                    Input.fails("past-the-end", PlutusData.integer(2)))),
            new Fixture("SHARED_ELEMENT_ONCE", SHARED_ELEMENT_ONCE, "sharedElementOnce", false, 2, 2, NONE),
            new Fixture("ELEMENT_ONCE", ELEMENT_ONCE, "elementOnce", true, 2, 0, NONE),
            new Fixture("WIDE_ELEMENT_ONCE", WIDE_ELEMENT_ONCE, "wideElementOnce", false, 2, 2, NONE));

    private O10ArrayLiteralFixtures() {}
}
