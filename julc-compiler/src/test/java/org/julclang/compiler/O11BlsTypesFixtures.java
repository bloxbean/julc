package org.julclang.compiler;

import org.julclang.core.PlutusData;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/**
 * ADR-047 (O11) fixtures: {@code compileMethod} sources over the typed BLS12-381 surface
 * ({@code JulcG1}, {@code JulcG2}, {@code JulcMlResult}, the native lists and the two
 * multi-scalar multiplications), each returning {@code true} exactly when the typed program
 * agrees with the manual chain of base operations it replaces, or failing where the pinned
 * builtin semantics say it must. Every fixture is compiled at every level and evaluated on
 * Java, Truffle and Scalus by {@link O11BlsTypesTest}.
 */
final class O11BlsTypesFixtures {

    /** An input: {@code success} says whether evaluation succeeds, {@code value} the boolean it returns when it does. */
    record Input(String name, List<PlutusData> args, boolean success, boolean value) {
        static Input ok(String name, PlutusData... args) { return new Input(name, List.of(args), true, true); }
        static Input isFalse(String name, PlutusData... args) { return new Input(name, List.of(args), true, false); }
        static Input fails(String name, PlutusData... args) { return new Input(name, List.of(args), false, false); }
        @Override public String toString() { return name; }
    }

    record Fixture(String name, String source, String method, List<Input> inputs) {}

    static final String IMPORTS = """
            import org.julclang.core.PlutusData;
            import org.julclang.core.types.JulcG1;
            import org.julclang.core.types.JulcG2;
            import org.julclang.core.types.JulcMlResult;
            import org.julclang.core.types.JulcScalars;
            import org.julclang.core.types.JulcG1Points;
            import org.julclang.core.types.JulcG2Points;
            import org.julclang.core.types.JulcList;
            import org.julclang.stdlib.Builtins;
            import org.julclang.stdlib.lib.BlsLib;
            import org.julclang.stdlib.lib.ContextsLib;
            import java.math.BigInteger;
            """;

    private static String method(String body) {
        return IMPORTS + "class Fixture {\n" + body + "\n}\n";
    }

    /** The largest scalar the MSM builtins accept (512 bytes two's complement, ADR-047). */
    static final BigInteger MAX_SCALAR = BigInteger.ONE.shiftLeft(4095).subtract(BigInteger.ONE);
    /** The smallest scalar the MSM builtins accept. */
    static final BigInteger MIN_SCALAR = BigInteger.ONE.shiftLeft(4095).negate();

    /** The compressed BLS12-381 G1 generator (48 bytes). */
    static final byte[] G1_GENERATOR = HexFormat.of().parseHex(
            "97f1d3a73197d7942695638c4fa9ac0fc3688c4f9774b905a14e3a3f171bac586c55e83ff97a1aeffb3af00adb22c6bb");

    static final PlutusData DST = PlutusData.bytes(new byte[]{});

    static PlutusData integers(long... values) {
        return PlutusData.list(Arrays.stream(values).mapToObj(PlutusData::integer).toArray(PlutusData[]::new));
    }

    /** 0: G1 multi-scalar multiplication over literal scalars and hashed points equals the scalarMul/add chain. */
    static final String MSM_VS_CHAIN = method("""
                static boolean msmVsChain(byte[] dst) {
                    JulcG1 p1 = Builtins.bls12_381_G1_hashToGroup(new byte[]{1}, dst);
                    JulcG1 p2 = Builtins.bls12_381_G1_hashToGroup(new byte[]{2}, dst);
                    JulcG1 msm = BlsLib.g1MultiScalarMul(Builtins.scalars(BigInteger.valueOf(3), BigInteger.valueOf(5)), Builtins.g1Points(p1, p2));
                    JulcG1 chain = BlsLib.g1Add(BlsLib.g1ScalarMul(BigInteger.valueOf(3), p1), BlsLib.g1ScalarMul(BigInteger.valueOf(5), p2));
                    return BlsLib.g1Equal(msm, chain);
                }""");

    /** 1: scalars from a Data list at the boundary, points from a Data list of compressed encodings. */
    static final String FROM_LISTS = method("""
                static boolean fromLists(JulcList<BigInteger> scalars, byte[] dst) {
                    JulcG1 p1 = Builtins.bls12_381_G1_hashToGroup(new byte[]{1}, dst);
                    JulcG1 p2 = Builtins.bls12_381_G1_hashToGroup(new byte[]{2}, dst);
                    JulcList<byte[]> encoded = JulcList.of(BlsLib.g1Compress(p1), BlsLib.g1Compress(p2));
                    JulcG1 msm = BlsLib.g1MultiScalarMul(Builtins.scalarsFromList(scalars), Builtins.g1PointsFromCompressed(encoded));
                    JulcG1 chain = BlsLib.g1Add(BlsLib.g1ScalarMul(scalars.get(0), p1), BlsLib.g1ScalarMul(scalars.get(1), p2));
                    return BlsLib.g1Equal(msm, chain);
                }""");

    /** 2: compressed points supplied at the boundary; an invalid encoding or a non-bytes element fails in the converter. */
    static final String POINTS_FROM_DATA = method("""
                static boolean pointsFromData(JulcList<byte[]> encoded) {
                    JulcG1Points points = Builtins.g1PointsFromCompressed(encoded);
                    JulcG1 msm = BlsLib.g1MultiScalarMul(Builtins.scalars(BigInteger.ONE, BigInteger.ONE), points);
                    JulcG1 sum = BlsLib.g1Add(BlsLib.g1Uncompress(encoded.get(0)), BlsLib.g1Uncompress(encoded.get(1)));
                    return BlsLib.g1Equal(msm, sum);
                }""");

    /** 3: the empty product is the identity and the longer list's extra entries are ignored. */
    static final String EMPTY_AND_UNEVEN = method("""
                static boolean emptyAndUneven(byte[] dst) {
                    JulcG1 p1 = Builtins.bls12_381_G1_hashToGroup(new byte[]{1}, dst);
                    JulcG1 zero = BlsLib.g1ScalarMul(BigInteger.ZERO, p1);
                    boolean empty = BlsLib.g1Equal(BlsLib.g1MultiScalarMul(Builtins.scalars(), Builtins.g1Points()), zero);
                    boolean extraScalar = BlsLib.g1Equal(
                            BlsLib.g1MultiScalarMul(Builtins.scalars(BigInteger.valueOf(2), BigInteger.valueOf(9)), Builtins.g1Points(p1)),
                            BlsLib.g1ScalarMul(BigInteger.valueOf(2), p1));
                    boolean extraPoint = BlsLib.g1Equal(
                            BlsLib.g1MultiScalarMul(Builtins.scalars(BigInteger.valueOf(2)), Builtins.g1Points(p1, p1)),
                            BlsLib.g1ScalarMul(BigInteger.valueOf(2), p1));
                    return empty && extraScalar && extraPoint;
                }""");

    /** 4: a runtime scalar at and beyond the pinned bounds. */
    static final String SCALAR_BOUND = method("""
                static boolean scalarBound(byte[] dst, BigInteger s) {
                    JulcG1 p1 = Builtins.bls12_381_G1_hashToGroup(new byte[]{1}, dst);
                    JulcScalars scalars = Builtins.scalars(s);
                    return BlsLib.g1Equal(BlsLib.g1MultiScalarMul(scalars, Builtins.g1Points(p1)), BlsLib.g1ScalarMul(s, p1));
                }""");

    /** 5: every scalar is validated before the lists are zipped, the ones beyond the shorter list included. */
    static final String SCALAR_BEYOND_ZIP = method("""
                static boolean scalarBeyondZip(byte[] dst, BigInteger extra) {
                    JulcG1 p1 = Builtins.bls12_381_G1_hashToGroup(new byte[]{1}, dst);
                    JulcG1 msm = BlsLib.g1MultiScalarMul(Builtins.scalars(BigInteger.ONE, extra), Builtins.g1Points(p1));
                    return BlsLib.g1Equal(msm, p1);
                }""");

    /** 6: G2 through the literal list and through the compressed converter. */
    static final String G2_MSM = method("""
                static boolean g2Msm(byte[] dst) {
                    JulcG2 q = Builtins.bls12_381_G2_hashToGroup(new byte[]{7}, dst);
                    JulcG2 expected = BlsLib.g2ScalarMul(BigInteger.valueOf(4), q);
                    JulcG2 literal = BlsLib.g2MultiScalarMul(Builtins.scalars(BigInteger.valueOf(4)), Builtins.g2Points(q));
                    JulcG2Points decoded = Builtins.g2PointsFromCompressed(JulcList.of(BlsLib.g2Compress(q)));
                    JulcG2 converted = BlsLib.g2MultiScalarMul(Builtins.scalars(BigInteger.valueOf(4)), decoded);
                    return BlsLib.g2Equal(literal, expected) && BlsLib.g2Equal(converted, expected);
                }""");

    /** 7: pairing bilinearity through typed Miller-loop results and their product. */
    static final String PAIRING = method("""
                static boolean pairing(byte[] dst) {
                    JulcG1 p = Builtins.bls12_381_G1_hashToGroup(new byte[]{1}, dst);
                    JulcG2 q = Builtins.bls12_381_G2_hashToGroup(new byte[]{2}, dst);
                    JulcMlResult left = BlsLib.millerLoop(BlsLib.g1ScalarMul(BigInteger.TWO, p), q);
                    JulcMlResult right = BlsLib.millerLoop(p, BlsLib.g2ScalarMul(BigInteger.TWO, q));
                    JulcMlResult once = BlsLib.millerLoop(p, q);
                    return BlsLib.finalVerify(left, right) && BlsLib.finalVerify(left, BlsLib.mulMlResult(once, once));
                }""");

    /** 8: user helpers taking and returning the typed values. */
    static final String HELPERS = method("""
                static JulcG1 twice(JulcG1 p) {
                    return BlsLib.g1Add(p, p);
                }

                static JulcScalars two() {
                    return Builtins.scalars(BigInteger.TWO);
                }

                static boolean helpers(byte[] dst) {
                    JulcG1 p = Builtins.bls12_381_G1_hashToGroup(new byte[]{1}, dst);
                    return BlsLib.g1Equal(twice(p), BlsLib.g1MultiScalarMul(two(), Builtins.g1Points(p)));
                }""");

    /** 9: compression is the only way out and uncompression the only way back in. */
    static final String ROUND_TRIP = method("""
                static boolean roundTrip(byte[] dst) {
                    JulcG1 p = Builtins.bls12_381_G1_hashToGroup(new byte[]{1}, dst);
                    JulcG2 q = Builtins.bls12_381_G2_hashToGroup(new byte[]{2}, dst);
                    byte[] pBytes = BlsLib.g1Compress(p);
                    byte[] qBytes = BlsLib.g2Compress(q);
                    return BlsLib.g1Equal(BlsLib.g1Uncompress(pBytes), p) && BlsLib.g2Equal(BlsLib.g2Uncompress(qBytes), q);
                }""");

    /** 10: traces and an error guard around the multiplication keep their order. */
    static final String TRACE_ORDER = method("""
                static boolean traceOrder(byte[] dst, boolean flag) {
                    ContextsLib.trace("before");
                    JulcG1 p = Builtins.bls12_381_G1_hashToGroup(new byte[]{1}, dst);
                    JulcG1 msm = BlsLib.g1MultiScalarMul(Builtins.scalars(BigInteger.valueOf(3)), Builtins.g1Points(p));
                    ContextsLib.trace("after");
                    if (flag) {
                        Builtins.error();
                    }
                    return BlsLib.g1Equal(msm, BlsLib.g1ScalarMul(BigInteger.valueOf(3), p));
                }""");

    /** 11: {@code var} locals infer the typed values without naming them. */
    static final String VAR_LOCALS = method("""
                static boolean varLocals(byte[] dst) {
                    var p = Builtins.bls12_381_G1_hashToGroup(dst, dst);
                    var s = Builtins.scalars(BigInteger.ONE);
                    var ps = Builtins.g1Points(p);
                    return BlsLib.g1Equal(Builtins.bls12_381_G1_multiScalarMul(s, ps), p);
                }""");

    /** 12: a disagreeing pair returns {@code false}: the equality is decided by the builtin, not by construction. */
    static final String NOT_EQUAL = method("""
                static boolean notEqual(byte[] dst, BigInteger s) {
                    JulcG1 p = Builtins.bls12_381_G1_hashToGroup(new byte[]{1}, dst);
                    JulcG1 msm = BlsLib.g1MultiScalarMul(Builtins.scalars(BigInteger.valueOf(3)), Builtins.g1Points(p));
                    return BlsLib.g1Equal(msm, BlsLib.g1ScalarMul(s, p));
                }""");

    /** 13: an empty Data list through each converter, against a non-empty other list, is the identity. */
    static final String EMPTY_CONVERTERS = method("""
                static boolean emptyConverters(JulcList<BigInteger> noScalars, JulcList<byte[]> noPoints, byte[] dst) {
                    JulcG1 p = Builtins.bls12_381_G1_hashToGroup(new byte[]{1}, dst);
                    JulcG1 zero = BlsLib.g1ScalarMul(BigInteger.ZERO, p);
                    JulcG1 noScalarSum = BlsLib.g1MultiScalarMul(Builtins.scalarsFromList(noScalars), Builtins.g1Points(p));
                    JulcG1 noPointSum = BlsLib.g1MultiScalarMul(Builtins.scalars(BigInteger.ONE), Builtins.g1PointsFromCompressed(noPoints));
                    return BlsLib.g1Equal(noScalarSum, zero) && BlsLib.g1Equal(noPointSum, zero);
                }""");

    /** 14: a negative literal scalar takes the constant path (a negative integer inside the list constant). */
    static final String NEGATIVE_LITERAL = method("""
                static boolean negativeLiteral(byte[] dst) {
                    JulcG1 p = Builtins.bls12_381_G1_hashToGroup(new byte[]{1}, dst);
                    JulcG1 msm = BlsLib.g1MultiScalarMul(Builtins.scalars(new BigInteger("-3")), Builtins.g1Points(p));
                    return BlsLib.g1Equal(msm, BlsLib.g1Neg(BlsLib.g1ScalarMul(BigInteger.valueOf(3), p)));
                }""");

    /** 15: a producer nested inside another producer's element, and the same converter used twice in one method. */
    static final String NESTED_PRODUCERS = method("""
                static boolean nestedProducers(JulcList<BigInteger> xs, byte[] dst) {
                    JulcG1 p = Builtins.bls12_381_G1_hashToGroup(new byte[]{1}, dst);
                    JulcG1 q = Builtins.bls12_381_G1_hashToGroup(new byte[]{2}, dst);
                    JulcG1 outer = BlsLib.g1MultiScalarMul(
                            Builtins.scalars(BigInteger.TWO, BigInteger.ONE),
                            Builtins.g1Points(p, BlsLib.g1MultiScalarMul(Builtins.scalarsFromList(xs), Builtins.g1Points(q))));
                    JulcG1 again = BlsLib.g1MultiScalarMul(Builtins.scalarsFromList(xs), Builtins.g1Points(q));
                    return BlsLib.g1Equal(outer, BlsLib.g1Add(BlsLib.g1ScalarMul(BigInteger.TWO, p), again));
                }""");

    /**
     * 17: the boundaries the isolation check covers, with agreeing types: both branches of a
     * conditional (a point, a native list), a loop-local declaration, a loop-body local
     * reassigned, an accumulator reassigned in a loop (inside a bare nested block) and before
     * a {@code break} (PR #150 review). A native accumulator is always the loop's only one: a
     * multi-accumulator loop packs its accumulators as Data, which rejects a point at the pack.
     */
    static final String BRANCHES = method("""
                static boolean branches(JulcList<BigInteger> xs, byte[] dst, boolean flag) {
                    JulcG1 p = Builtins.bls12_381_G1_hashToGroup(new byte[]{1}, dst);
                    JulcG1 q = Builtins.bls12_381_G1_hashToGroup(new byte[]{2}, dst);
                    JulcG1 chosen = flag ? p : q;
                    JulcScalars s = flag ? Builtins.scalars(BigInteger.TWO) : Builtins.scalarsFromList(xs);
                    BigInteger k = flag ? BigInteger.TWO : xs.get(0);
                    JulcG1 acc = p;
                    for (var x : xs) {
                        JulcG1 step = BlsLib.g1ScalarMul(x, chosen);
                        step = BlsLib.g1Add(step, step);
                        acc = BlsLib.g1Add(acc, step);
                    }
                    BigInteger total = BigInteger.ZERO;
                    for (var x : xs) {
                        total = total.add(x);
                    }
                    JulcG1 sum = p;
                    for (var x : xs) {
                        {
                            sum = BlsLib.g1Add(sum, BlsLib.g1ScalarMul(x, chosen));
                        }
                    }
                    JulcG1 first = p;
                    for (var x : xs) {
                        if (x.equals(BigInteger.ONE)) {
                            first = BlsLib.g1Add(first, chosen);
                            break;
                        }
                    }
                    JulcG1 msm = BlsLib.g1MultiScalarMul(s, Builtins.g1Points(chosen));
                    return BlsLib.g1Equal(acc, BlsLib.g1Add(p, BlsLib.g1ScalarMul(total.multiply(BigInteger.TWO), chosen)))
                            && BlsLib.g1Equal(sum, BlsLib.g1Add(p, BlsLib.g1ScalarMul(total, chosen)))
                            && BlsLib.g1Equal(first, BlsLib.g1Add(p, chosen))
                            && BlsLib.g1Equal(msm, BlsLib.g1ScalarMul(k, chosen));
                }""");

    /**
     * 18: the converters' generated names ({@code go__scalars}, {@code lst__g1Points},
     * {@code __native_scalars}) are not hygienic; a user binding of the same name, in the
     * argument expression or around the call, must resolve to the user's value (PR #150 review).
     */
    static final String NAME_CAPTURE = method("""
                static boolean nameCapture(JulcList<BigInteger> go__scalars, byte[] dst) {
                    JulcG1 go__g1Points = Builtins.bls12_381_G1_hashToGroup(new byte[]{1}, dst);
                    JulcG2 go__g2Points = Builtins.bls12_381_G2_hashToGroup(new byte[]{2}, dst);
                    JulcList<byte[]> lst__g1Points = JulcList.of(BlsLib.g1Compress(go__g1Points));
                    BigInteger __native_scalars = go__scalars.get(0);
                    JulcScalars s = Builtins.scalarsFromList(go__scalars);
                    JulcG1Points ps = Builtins.g1PointsFromCompressed(lst__g1Points);
                    JulcG2Points qs = Builtins.g2PointsFromCompressed(JulcList.of(BlsLib.g2Compress(go__g2Points)));
                    return BlsLib.g1Equal(BlsLib.g1MultiScalarMul(s, ps), BlsLib.g1ScalarMul(__native_scalars, go__g1Points))
                            && BlsLib.g2Equal(BlsLib.g2MultiScalarMul(s, qs), BlsLib.g2ScalarMul(__native_scalars, go__g2Points));
                }""");

    private static final List<Input> RUN = List.of(Input.ok("run", DST));

    static final List<Fixture> FIXTURES = List.of(
            new Fixture("MSM_VS_CHAIN", MSM_VS_CHAIN, "msmVsChain", RUN),
            new Fixture("FROM_LISTS", FROM_LISTS, "fromLists", List.of(
                    Input.ok("two", integers(3, 5), DST),
                    Input.ok("extra-scalar-ignored", integers(3, 5, 7), DST),
                    Input.ok("negative", integers(-3, 5), DST),
                    Input.fails("one-scalar", integers(3), DST),
                    Input.fails("not-an-integer", PlutusData.list(PlutusData.integer(3), PlutusData.bytes(new byte[]{1})), DST))),
            new Fixture("POINTS_FROM_DATA", POINTS_FROM_DATA, "pointsFromData", List.of(
                    Input.ok("generator-twice", PlutusData.list(PlutusData.bytes(G1_GENERATOR), PlutusData.bytes(G1_GENERATOR))),
                    Input.fails("invalid-encoding", PlutusData.list(PlutusData.bytes(new byte[48]), PlutusData.bytes(G1_GENERATOR))),
                    Input.fails("not-bytes", PlutusData.list(PlutusData.integer(1), PlutusData.bytes(G1_GENERATOR))),
                    Input.fails("empty", PlutusData.list()))),
            new Fixture("EMPTY_AND_UNEVEN", EMPTY_AND_UNEVEN, "emptyAndUneven", RUN),
            new Fixture("SCALAR_BOUND", SCALAR_BOUND, "scalarBound", List.of(
                    Input.ok("zero", DST, PlutusData.integer(0)),
                    Input.ok("negative", DST, PlutusData.integer(-7)),
                    Input.ok("max", DST, PlutusData.integer(MAX_SCALAR)),
                    Input.fails("max-plus-one", DST, PlutusData.integer(MAX_SCALAR.add(BigInteger.ONE))),
                    Input.ok("min", DST, PlutusData.integer(MIN_SCALAR)),
                    Input.fails("min-minus-one", DST, PlutusData.integer(MIN_SCALAR.subtract(BigInteger.ONE))))),
            new Fixture("SCALAR_BEYOND_ZIP", SCALAR_BEYOND_ZIP, "scalarBeyondZip", List.of(
                    Input.ok("in-range", DST, PlutusData.integer(5)),
                    Input.fails("beyond-bound", DST, PlutusData.integer(MAX_SCALAR.add(BigInteger.ONE))))),
            new Fixture("G2_MSM", G2_MSM, "g2Msm", RUN),
            new Fixture("PAIRING", PAIRING, "pairing", RUN),
            new Fixture("HELPERS", HELPERS, "helpers", RUN),
            new Fixture("ROUND_TRIP", ROUND_TRIP, "roundTrip", RUN),
            new Fixture("TRACE_ORDER", TRACE_ORDER, "traceOrder", List.of(
                    Input.ok("pass", DST, PlutusData.constr(0)),
                    Input.fails("fail", DST, PlutusData.constr(1)))),
            new Fixture("VAR_LOCALS", VAR_LOCALS, "varLocals", RUN),
            new Fixture("NOT_EQUAL", NOT_EQUAL, "notEqual", List.of(
                    Input.isFalse("four", DST, PlutusData.integer(4)),
                    Input.ok("three", DST, PlutusData.integer(3)))),
            new Fixture("EMPTY_CONVERTERS", EMPTY_CONVERTERS, "emptyConverters", List.of(
                    Input.ok("both-empty", PlutusData.list(), PlutusData.list(), DST))),
            new Fixture("NEGATIVE_LITERAL", NEGATIVE_LITERAL, "negativeLiteral", RUN),
            new Fixture("NESTED_PRODUCERS", NESTED_PRODUCERS, "nestedProducers", List.of(
                    Input.ok("three", integers(3), DST))),
            new Fixture("BRANCHES", BRANCHES, "branches", List.of(
                    Input.ok("first", integers(1, 3), DST, PlutusData.constr(1)),
                    Input.ok("second", integers(1, 3), DST, PlutusData.constr(0)))),
            new Fixture("NAME_CAPTURE", NAME_CAPTURE, "nameCapture", List.of(
                    Input.ok("three", integers(3), DST))));
}
