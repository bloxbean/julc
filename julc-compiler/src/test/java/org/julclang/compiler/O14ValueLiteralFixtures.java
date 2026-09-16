package org.julclang.compiler;

import org.julclang.core.PlutusData;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * ADR-045 (O14) fixtures: {@code compileMethod} sources built from the typed native Value
 * literal producers ({@code Builtins.emptyValue}, {@code singletonValue}, {@code lovelaceValue}) and
 * the seven Value builtins, in shapes the literal fold must fold completely, in part, or not
 * at all. Every fixture is compiled with the rule off and on and evaluated on Java, Truffle
 * and Scalus by {@link O14ValueLiteralFoldTest}; {@code folds} is the expected rule provenance
 * at the safe profile, {@code callsBefore}/{@code callsAfter} the number of Value builtin call
 * sites (bare or through a {@code NativeValueLib} wrapper) left in the user code.
 */
final class O14ValueLiteralFixtures {

    record Input(String name, List<PlutusData> args, boolean success) {
        static Input ok(String name, PlutusData... args) { return new Input(name, List.of(args), true); }
        static Input fails(String name, PlutusData... args) { return new Input(name, List.of(args), false); }
        @Override public String toString() { return name; }
    }

    record Fixture(String name, String source, String method, boolean folds, int callsBefore, int callsAfter,
                   List<Input> inputs) {}

    static final String IMPORTS = """
            import org.julclang.core.PlutusData;
            import org.julclang.core.types.JulcValue;
            import org.julclang.stdlib.Builtins;
            import org.julclang.stdlib.lib.ContextsLib;
            import org.julclang.stdlib.lib.NativeValueLib;
            import java.math.BigInteger;
            """;

    /** {@code new byte[]{1, 1, ...}} of the given length. */
    static String bytesLiteral(int length) {
        return "new byte[]{" + IntStream.range(0, length).mapToObj(i -> "1").collect(Collectors.joining(", ")) + "}";
    }

    static final String P = "new byte[]{1, 2, 3}";
    static final String T = "new byte[]{9}";
    static final String P32 = bytesLiteral(32);
    static final String P33 = bytesLiteral(33);
    static final String MAX = "new BigInteger(\"170141183460469231731687303715884105727\")";
    static final String OVER = "new BigInteger(\"170141183460469231731687303715884105728\")";
    static final String MIN = "new BigInteger(\"-170141183460469231731687303715884105728\")";
    static final String UNDER = "new BigInteger(\"-170141183460469231731687303715884105729\")";

    static final BigInteger MAX_QUANTITY = BigInteger.ONE.shiftLeft(127).subtract(BigInteger.ONE);

    private static String method(String body) {
        return IMPORTS + "class Fixture {\n" + body + "\n}\n";
    }

    /** 0: a runtime key against the empty literal: nothing folds, the literal is a constant. */
    static final String EMPTY_LOOKUP = method("""
                static BigInteger emptyLookup(byte[] p) {
                    return NativeValueLib.lookupCoin(p, p, Builtins.emptyValue());
                }""");

    /** 1: a literal singleton looked up with literal keys folds to the quantity. */
    static final String SINGLETON_LOOKUP = method("""
                static BigInteger singletonLookup() {
                    return NativeValueLib.lookupCoin(%s, %s, Builtins.singletonValue(%s, %s, BigInteger.valueOf(100)));
                }""".formatted(P, T, P, T));

    /** 2: lovelace literals unioned and looked up with the empty key. */
    static final String LOVELACE_UNION = method("""
                static BigInteger lovelaceUnion() {
                    return NativeValueLib.lookupCoin(new byte[]{}, new byte[]{},
                            NativeValueLib.union(Builtins.lovelaceValue(BigInteger.valueOf(5)), Builtins.lovelaceValue(BigInteger.valueOf(7))));
                }""");

    /** 3: a chain of literal inserts converted to Data folds to a Data constant, policies sorted. */
    static final String INSERT_CHAIN = method("""
                static PlutusData insertChain() {
                    return NativeValueLib.toData(NativeValueLib.insertCoin(new byte[]{1}, new byte[]{1}, BigInteger.valueOf(3),
                            NativeValueLib.insertCoin(new byte[]{2}, new byte[]{2}, BigInteger.valueOf(2), Builtins.emptyValue())));
                }""");

    /**
     * 4: quantities that cancel in a union leave the empty Value ({@code new BigInteger("-5")}: a
     * negated literal is not a constant in PIR). The empty Value's Data literal is one byte larger
     * than the conversion call, so {@code toData(empty)} stays a call under the size objective.
     */
    static final String UNION_CANCEL = method("""
                static PlutusData unionCancel() {
                    return NativeValueLib.toData(NativeValueLib.union(
                            Builtins.singletonValue(%s, %s, BigInteger.valueOf(5)), Builtins.singletonValue(%s, %s, new BigInteger("-5"))));
                }""".formatted(P, T, P, T));

    /** 5: scaling a literal. */
    static final String SCALE = method("""
                static BigInteger scale() {
                    return NativeValueLib.lookupCoin(%s, %s, NativeValueLib.scale(BigInteger.valueOf(3), Builtins.singletonValue(%s, %s, BigInteger.valueOf(4))));
                }""".formatted(P, T, P, T));

    /** 6: scaling by zero is the empty Value; its Data conversion stays a call (size objective). */
    static final String SCALE_ZERO = method("""
                static PlutusData scaleZero() {
                    return NativeValueLib.toData(NativeValueLib.scale(BigInteger.ZERO, Builtins.singletonValue(%s, %s, BigInteger.valueOf(4))));
                }""".formatted(P, T));

    /** 7: containment of literals, true. */
    static final String CONTAINS_TRUE = method("""
                static boolean containsTrue() {
                    return NativeValueLib.contains(Builtins.singletonValue(%s, %s, BigInteger.valueOf(5)), Builtins.singletonValue(%s, %s, BigInteger.valueOf(3)));
                }""".formatted(P, T, P, T));

    /** 8: containment of literals, false. */
    static final String CONTAINS_FALSE = method("""
                static boolean containsFalse() {
                    return NativeValueLib.contains(Builtins.singletonValue(%s, %s, BigInteger.valueOf(3)), Builtins.singletonValue(%s, %s, BigInteger.valueOf(5)));
                }""".formatted(P, T, P, T));

    /** 9: a literal through Data and back folds through both conversions. */
    static final String ROUND_TRIP = method("""
                static BigInteger roundTrip() {
                    return NativeValueLib.lookupCoin(%s, %s, NativeValueLib.fromData(NativeValueLib.toData(Builtins.singletonValue(%s, %s, BigInteger.valueOf(9)))));
                }""".formatted(P, T, P, T));

    /** 10: a runtime quantity: nothing folds. */
    static final String RUNTIME_QUANTITY = method("""
                static BigInteger runtimeQuantity(BigInteger q) {
                    return NativeValueLib.lookupCoin(%s, %s, Builtins.singletonValue(%s, %s, q));
                }""".formatted(P, T, P, T));

    /** 11: a runtime Value beside a literal: the literal folds, the containment stays. */
    static final String RUNTIME_VALUE = method("""
                static boolean runtimeValue(PlutusData d) {
                    return NativeValueLib.contains(NativeValueLib.fromData(d), Builtins.singletonValue(%s, %s, BigInteger.ONE));
                }""".formatted(P, T));

    /** 12: a local bound to a literal feeds the calls below it. */
    static final String LOCAL_LITERAL = method("""
                static BigInteger localLiteral() {
                    JulcValue base = Builtins.singletonValue(%s, %s, BigInteger.ONE);
                    JulcValue twice = NativeValueLib.union(base, base);
                    return NativeValueLib.lookupCoin(%s, %s, twice);
                }""".formatted(P, T, P, T));

    /** 13: a local aliasing the empty literal is a literal. */
    static final String ALIAS_LOCAL = method("""
                static BigInteger aliasLocal() {
                    JulcValue e = Builtins.emptyValue();
                    JulcValue one = NativeValueLib.insertCoin(%s, %s, BigInteger.ONE, e);
                    return NativeValueLib.lookupCoin(%s, %s, one);
                }""".formatted(P, T, P, T));

    /** 14: a 33-byte key with a non-zero quantity fails at runtime; the call is left as written. */
    static final String KEY_TOO_LONG = method("""
                static BigInteger keyTooLong() {
                    return NativeValueLib.lookupCoin(%s, %s, Builtins.singletonValue(%s, %s, BigInteger.ONE));
                }""".formatted(P33, T, P33, T));

    /** 15: a 32-byte key is the limit and folds. */
    static final String KEY_MAX = method("""
                static BigInteger keyMax() {
                    return NativeValueLib.lookupCoin(%s, %s, Builtins.singletonValue(%s, %s, BigInteger.ONE));
                }""".formatted(P32, T, P32, T));

    /** 16: a zero quantity skips the key checks and yields the empty Value (the reference's long-key-zero case); its Data conversion stays a call. */
    static final String LONG_KEY_ZERO = method("""
                static PlutusData longKeyZero() {
                    return NativeValueLib.toData(Builtins.singletonValue(%s, %s, BigInteger.ZERO));
                }""".formatted(P33, T));

    /** 17: the largest quantity folds. */
    static final String QUANTITY_MAX = method("""
                static BigInteger quantityMax() {
                    return NativeValueLib.lookupCoin(%s, %s, Builtins.singletonValue(%s, %s, %s));
                }""".formatted(P, T, P, T, MAX));

    /** 18: one past the largest quantity fails at runtime and is left as written. */
    static final String QUANTITY_OVERFLOW = method("""
                static BigInteger quantityOverflow() {
                    return NativeValueLib.lookupCoin(%s, %s, Builtins.singletonValue(%s, %s, %s));
                }""".formatted(P, T, P, T, OVER));

    /** 19: the smallest quantity folds. */
    static final String QUANTITY_MIN = method("""
                static BigInteger quantityMin() {
                    return NativeValueLib.lookupCoin(%s, %s, Builtins.singletonValue(%s, %s, %s));
                }""".formatted(P, T, P, T, MIN));

    /** 20: one below the smallest quantity fails at runtime. */
    static final String QUANTITY_UNDERFLOW = method("""
                static BigInteger quantityUnderflow() {
                    return NativeValueLib.lookupCoin(%s, %s, Builtins.singletonValue(%s, %s, %s));
                }""".formatted(P, T, P, T, UNDER));

    /** 21: the singletons fold, the overflowing union stays and fails at runtime. */
    static final String UNION_OVERFLOW = method("""
                static BigInteger unionOverflow() {
                    return NativeValueLib.lookupCoin(%s, %s, NativeValueLib.union(
                            Builtins.singletonValue(%s, %s, %s), Builtins.singletonValue(%s, %s, BigInteger.ONE)));
                }""".formatted(P, T, P, T, MAX, P, T));

    /** 22: the singleton folds, the overflowing scale stays and fails at runtime. */
    static final String SCALE_OVERFLOW = method("""
                static BigInteger scaleOverflow() {
                    return NativeValueLib.lookupCoin(%s, %s, NativeValueLib.scale(BigInteger.TWO, Builtins.singletonValue(%s, %s, %s)));
                }""".formatted(P, T, P, T, MAX));

    /** 23: containment over a negative literal fails at runtime; the singleton folds, the containment stays. */
    static final String CONTAINS_NEGATIVE = method("""
                static boolean containsNegative() {
                    return NativeValueLib.contains(Builtins.singletonValue(%s, %s, new BigInteger("-1")), Builtins.emptyValue());
                }""".formatted(P, T));

    /** 24: a trace before the literal call: the fold happens inside the trace body, the trace order is kept. */
    static final String TRACE_AROUND = method("""
                static BigInteger traceAround() {
                    ContextsLib.trace("literal");
                    return NativeValueLib.lookupCoin(%s, %s, Builtins.singletonValue(%s, %s, BigInteger.ONE));
                }""".formatted(P, T, P, T));

    /** 25: an error guard before the literal call. */
    static final String ERROR_ARM = method("""
                static BigInteger errorArm(boolean flag) {
                    if (flag) {
                        Builtins.error();
                    }
                    return NativeValueLib.lookupCoin(%s, %s, Builtins.singletonValue(%s, %s, BigInteger.ONE));
                }""".formatted(P, T, P, T));

    /** 26: a runtime key against a literal union: the union folds, the lookup stays. */
    static final String MIXED_KEY = method("""
                static BigInteger mixedKey(byte[] p) {
                    return NativeValueLib.lookupCoin(p, %s, NativeValueLib.union(Builtins.singletonValue(%s, %s, BigInteger.ONE), Builtins.lovelaceValue(BigInteger.TWO)));
                }""".formatted(T, P, T));

    /**
     * 27: a user helper with a parameter it never uses, called with a runtime decode in that
     * position: the decode must still run (and fail on non-integer Data), so nothing folds.
     */
    static final String UNUSED_PARAM = method("""
                static JulcValue mk(BigInteger unused, BigInteger q) {
                    return Builtins.singletonValue(%s, %s, q);
                }
                static BigInteger unusedParam(PlutusData d) {
                    return NativeValueLib.lookupCoin(%s, %s, mk(Builtins.unIData(d), BigInteger.ONE));
                }""".formatted(P, T, P, T));

    /** 28: a user wrapper over a bare builtin with every parameter used folds like a library wrapper. */
    static final String USER_WRAPPER = method("""
                static JulcValue both(JulcValue a, JulcValue b) {
                    return Builtins.unionValue(a, b);
                }
                static BigInteger userWrapper() {
                    return NativeValueLib.lookupCoin(%s, %s, both(Builtins.singletonValue(%s, %s, BigInteger.ONE), Builtins.singletonValue(%s, %s, BigInteger.TWO)));
                }""".formatted(P, T, P, T, P, T));

    /**
     * 29: a literal local with 32-byte keys shared by two literal calls and a runtime one: the
     * producer folds into the binding, the calls stay. Each call stands in the artifact as a
     * reference to the local, so folding it would copy the keys into the call site while the
     * binding stays live for the others (the second review found the guard measuring the
     * local as its constant and the artifact growing from 128 to 243 bytes).
     */
    static final String SHARED_LITERAL = method("""
                static PlutusData sharedLiteral(BigInteger n) {
                    JulcValue v = Builtins.singletonValue(%s, %s, BigInteger.ONE);
                    JulcValue a = NativeValueLib.scale(BigInteger.TWO, v);
                    JulcValue b = NativeValueLib.scale(BigInteger.valueOf(3), v);
                    return NativeValueLib.toData(NativeValueLib.union(NativeValueLib.scale(n, v), NativeValueLib.union(a, b)));
                }""".formatted(P32, T));

    /** 30: the same local shared by two literal calls only: neither may copy it, so both stay and the local stays bound once. */
    static final String SHARED_LITERAL_ONLY = method("""
                static PlutusData sharedLiteralOnly() {
                    JulcValue v = Builtins.singletonValue(%s, %s, BigInteger.ONE);
                    JulcValue a = NativeValueLib.scale(BigInteger.TWO, v);
                    JulcValue b = NativeValueLib.scale(BigInteger.valueOf(3), v);
                    return NativeValueLib.toData(NativeValueLib.union(a, b));
                }""".formatted(P32, T));

    /**
     * 31: the second review's alias case: `alias = v` dies with its one call, but only its
     * reference binding disappears; `v` stays live for the other calls, so the call may not
     * copy the constant (the first fix credited the dying alias with it: 128 → 185 bytes).
     */
    static final String SHARED_ALIAS = method("""
                static PlutusData sharedAlias(BigInteger n) {
                    JulcValue v = Builtins.singletonValue(%s, %s, BigInteger.ONE);
                    JulcValue alias = v;
                    JulcValue a = NativeValueLib.scale(BigInteger.TWO, alias);
                    JulcValue b = NativeValueLib.scale(BigInteger.valueOf(3), v);
                    return NativeValueLib.toData(NativeValueLib.union(NativeValueLib.scale(n, v), NativeValueLib.union(a, b)));
                }""".formatted(P32, T));

    /** The bytes of {@link #P32}. */
    static final byte[] P32_BYTES = new byte[32];
    static { Arrays.fill(P32_BYTES, (byte) 1); }

    static final PlutusData P_DATA = PlutusData.bytes(new byte[]{1, 2, 3});
    static final PlutusData T_DATA = PlutusData.bytes(new byte[]{9});
    static final PlutusData TRUE = PlutusData.constr(1);
    static final PlutusData FALSE = PlutusData.constr(0);

    /** The canonical Data of a singleton Value. */
    static PlutusData valueData(byte[] policy, byte[] token, long quantity) {
        return PlutusData.map(new PlutusData.Pair(PlutusData.bytes(policy),
                PlutusData.map(new PlutusData.Pair(PlutusData.bytes(token), PlutusData.integer(quantity)))));
    }

    private static final List<Input> NONE = List.of(Input.ok("run"));
    private static final List<Input> FAILS = List.of(Input.fails("run"));

    static final List<Fixture> FIXTURES = List.of(
            new Fixture("EMPTY_LOOKUP", EMPTY_LOOKUP, "emptyLookup", false, 1, 1, List.of(Input.ok("key", P_DATA))),
            new Fixture("SINGLETON_LOOKUP", SINGLETON_LOOKUP, "singletonLookup", true, 2, 0, NONE),
            new Fixture("LOVELACE_UNION", LOVELACE_UNION, "lovelaceUnion", true, 4, 0, NONE),
            new Fixture("INSERT_CHAIN", INSERT_CHAIN, "insertChain", true, 3, 0, NONE),
            new Fixture("UNION_CANCEL", UNION_CANCEL, "unionCancel", true, 4, 1, NONE),
            new Fixture("SCALE", SCALE, "scale", true, 3, 0, NONE),
            new Fixture("SCALE_ZERO", SCALE_ZERO, "scaleZero", true, 3, 1, NONE),
            new Fixture("CONTAINS_TRUE", CONTAINS_TRUE, "containsTrue", true, 3, 0, NONE),
            new Fixture("CONTAINS_FALSE", CONTAINS_FALSE, "containsFalse", true, 3, 0, NONE),
            new Fixture("ROUND_TRIP", ROUND_TRIP, "roundTrip", true, 4, 0, NONE),
            new Fixture("RUNTIME_QUANTITY", RUNTIME_QUANTITY, "runtimeQuantity", false, 2, 2, List.of(
                    Input.ok("four", PlutusData.integer(4)),
                    Input.ok("zero", PlutusData.integer(0)),
                    Input.fails("overflow", PlutusData.integer(MAX_QUANTITY.add(BigInteger.ONE))))),
            new Fixture("RUNTIME_VALUE", RUNTIME_VALUE, "runtimeValue", true, 3, 2, List.of(
                    Input.ok("holds", valueData(new byte[]{1, 2, 3}, new byte[]{9}, 2)),
                    Input.ok("empty", PlutusData.map()),
                    Input.fails("not-a-map", PlutusData.integer(1)),
                    Input.fails("zero-quantity", valueData(new byte[]{1, 2, 3}, new byte[]{9}, 0)))),
            new Fixture("LOCAL_LITERAL", LOCAL_LITERAL, "localLiteral", true, 3, 0, NONE),
            new Fixture("ALIAS_LOCAL", ALIAS_LOCAL, "aliasLocal", true, 2, 0, NONE),
            new Fixture("KEY_TOO_LONG", KEY_TOO_LONG, "keyTooLong", false, 2, 2, FAILS),
            new Fixture("KEY_MAX", KEY_MAX, "keyMax", true, 2, 0, NONE),
            new Fixture("LONG_KEY_ZERO", LONG_KEY_ZERO, "longKeyZero", true, 2, 1, NONE),
            new Fixture("QUANTITY_MAX", QUANTITY_MAX, "quantityMax", true, 2, 0, NONE),
            new Fixture("QUANTITY_OVERFLOW", QUANTITY_OVERFLOW, "quantityOverflow", false, 2, 2, FAILS),
            new Fixture("QUANTITY_MIN", QUANTITY_MIN, "quantityMin", true, 2, 0, NONE),
            new Fixture("QUANTITY_UNDERFLOW", QUANTITY_UNDERFLOW, "quantityUnderflow", false, 2, 2, FAILS),
            new Fixture("UNION_OVERFLOW", UNION_OVERFLOW, "unionOverflow", true, 4, 2, FAILS),
            new Fixture("SCALE_OVERFLOW", SCALE_OVERFLOW, "scaleOverflow", true, 3, 2, FAILS),
            new Fixture("CONTAINS_NEGATIVE", CONTAINS_NEGATIVE, "containsNegative", true, 2, 1, FAILS),
            new Fixture("TRACE_AROUND", TRACE_AROUND, "traceAround", true, 2, 0, NONE),
            new Fixture("ERROR_ARM", ERROR_ARM, "errorArm", true, 2, 0, List.of(
                    Input.ok("pass", FALSE),
                    Input.fails("guard", TRUE))),
            new Fixture("MIXED_KEY", MIXED_KEY, "mixedKey", true, 4, 1, List.of(
                    Input.ok("present", P_DATA),
                    Input.ok("absent", PlutusData.bytes(new byte[]{7})))),
            new Fixture("UNUSED_PARAM", UNUSED_PARAM, "unusedParam", false, 2, 2, List.of(
                    Input.ok("integer", PlutusData.integer(3)),
                    Input.fails("bytes", PlutusData.bytes(new byte[]{1})))),
            new Fixture("USER_WRAPPER", USER_WRAPPER, "userWrapper", true, 4, 1, NONE),
            new Fixture("SHARED_LITERAL", SHARED_LITERAL, "sharedLiteral", true, 7, 6, List.of(
                    Input.ok("one", PlutusData.integer(1)),
                    Input.ok("zero", PlutusData.integer(0)),
                    Input.ok("cancel", PlutusData.integer(-5)))),
            new Fixture("SHARED_LITERAL_ONLY", SHARED_LITERAL_ONLY, "sharedLiteralOnly", true, 5, 4, NONE),
            new Fixture("SHARED_ALIAS", SHARED_ALIAS, "sharedAlias", true, 7, 6, List.of(
                    Input.ok("one", PlutusData.integer(1)),
                    Input.ok("zero", PlutusData.integer(0)),
                    Input.ok("cancel", PlutusData.integer(-5)))));

    private O14ValueLiteralFixtures() {}
}
