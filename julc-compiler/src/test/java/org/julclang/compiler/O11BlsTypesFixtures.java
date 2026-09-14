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

    record Input(String name, List<PlutusData> args, boolean success) {
        static Input ok(String name, PlutusData... args) { return new Input(name, List.of(args), true); }
        static Input fails(String name, PlutusData... args) { return new Input(name, List.of(args), false); }
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
            new Fixture("VAR_LOCALS", VAR_LOCALS, "varLocals", RUN));
}
