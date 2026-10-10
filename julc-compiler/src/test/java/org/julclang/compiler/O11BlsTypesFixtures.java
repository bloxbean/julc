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

    /** The compressed point 2·G; {@code O11BlsTypesTest} checks it is G + G on chain (48 bytes). */
    static final byte[] G1_DOUBLE = HexFormat.of().parseHex(
            "a572cbea904d67468808c8eb50a9450c9721db309128012543902d0ac358a62ae28f75bb8f1c7c42c39a8c5529bf0f4e");

    /** The compressed point at infinity: the compression and infinity flags, then 47 zero bytes. */
    static final byte[] G1_INFINITY = infinity();

    static final PlutusData DST = PlutusData.bytes(new byte[]{});

    /** The compressed negation of a compressed point: the sign flag (0x20 of the first byte) flipped. */
    static byte[] negated(byte[] compressed) {
        var out = compressed.clone();
        out[0] ^= 0x20;
        return out;
    }

    private static byte[] infinity() {
        var out = new byte[48];
        out[0] = (byte) 0xc0;
        return out;
    }

    static PlutusData points(byte[]... compressed) {
        return PlutusData.list(Arrays.stream(compressed).map(PlutusData::bytes).toArray(PlutusData[]::new));
    }

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

    /**
     * 19 (#240): the native lists built one element at a time by recursive helpers over raw
     * Data lists, the shape of ZeroJ's verifier (each point uncompressed once and kept); the
     * multi-scalar multiplication equals an independent {@code scalarMul}/{@code add} recursion
     * over the same lists, zipped to the shorter one.
     */
    static final String CONS_RECURSION = method("""
                static JulcG1Points collect(PlutusData encodedList) {
                    if (Builtins.nullList(encodedList)) return Builtins.g1PointsEmpty();
                    byte[] b = Builtins.unBData(Builtins.headList(encodedList));
                    JulcG1 p = Builtins.bls12_381_G1_uncompress(b);
                    return Builtins.g1PointsCons(p, collect(Builtins.tailList(encodedList)));
                }

                static JulcScalars collectScalars(PlutusData xs) {
                    if (Builtins.nullList(xs)) return Builtins.scalarsEmpty();
                    return Builtins.scalarsCons(Builtins.unIData(Builtins.headList(xs)), collectScalars(Builtins.tailList(xs)));
                }

                static JulcG1 chain(PlutusData ss, PlutusData ps, JulcG1 acc) {
                    if (Builtins.nullList(ss) || Builtins.nullList(ps)) return acc;
                    JulcG1 term = BlsLib.g1ScalarMul(Builtins.unIData(Builtins.headList(ss)),
                            BlsLib.g1Uncompress(Builtins.unBData(Builtins.headList(ps))));
                    return chain(Builtins.tailList(ss), Builtins.tailList(ps), BlsLib.g1Add(acc, term));
                }

                static boolean consRecursion(PlutusData encoded, PlutusData scalars) {
                    JulcG1 msm = Builtins.bls12_381_G1_multiScalarMul(
                            collectScalars(Builtins.unListData(scalars)), collect(Builtins.unListData(encoded)));
                    JulcG1 zero = BlsLib.g1ScalarMul(BigInteger.ZERO, Builtins.bls12_381_G1_hashToGroup(new byte[]{}, new byte[]{}));
                    return BlsLib.g1Equal(msm, chain(Builtins.unListData(scalars), Builtins.unListData(encoded), zero));
                }""");

    /**
     * 20 (#240): validate each point once (uncompress, re-compress to the same bytes, not
     * infinity) and keep the validated point in the list, instead of decompressing every
     * element a second time with {@code g1PointsFromCompressed}, which is the oracle here.
     */
    static final String VALIDATE_ONCE = method("""
                static JulcG1Points validated(JulcList<byte[]> encoded) {
                    if (encoded.isEmpty()) {
                        return Builtins.g1PointsEmpty();
                    }
                    byte[] b = encoded.head();
                    JulcG1 p = BlsLib.g1Uncompress(b);
                    boolean canonical = Builtins.equalsByteString(BlsLib.g1Compress(p), b);
                    boolean infinity = BlsLib.g1Equal(p, BlsLib.g1ScalarMul(BigInteger.ZERO, p));
                    if (!canonical || infinity) {
                        Builtins.error();
                    }
                    return Builtins.g1PointsCons(p, validated(encoded.tail()));
                }

                static boolean validateOnce(JulcList<byte[]> encoded, JulcList<BigInteger> scalars) {
                    JulcG1 msm = BlsLib.g1MultiScalarMul(Builtins.scalarsFromList(scalars), validated(encoded));
                    JulcG1 oracle = BlsLib.g1MultiScalarMul(Builtins.scalarsFromList(scalars), Builtins.g1PointsFromCompressed(encoded));
                    return BlsLib.g1Equal(msm, oracle);
                }""");

    /**
     * 21 (#240): a cons puts its element first, so a recursion builds the list in source order
     * and a loop accumulator builds it reversed; each agrees with the converters only when its
     * scalars are built the same way, and pairing forward scalars with reversed points does not.
     */
    static final String CONS_ORDER = method("""
                static JulcG1Points forward(JulcList<byte[]> xs) {
                    if (xs.isEmpty()) return Builtins.g1PointsEmpty();
                    return Builtins.g1PointsCons(BlsLib.g1Uncompress(xs.head()), forward(xs.tail()));
                }

                static JulcScalars forwardScalars(JulcList<BigInteger> xs) {
                    if (xs.isEmpty()) return Builtins.scalarsEmpty();
                    return Builtins.scalarsCons(xs.head(), forwardScalars(xs.tail()));
                }

                static boolean consOrder(JulcList<byte[]> encoded, JulcList<BigInteger> scalars) {
                    JulcG1Points reversed = Builtins.g1PointsEmpty();
                    for (var b : encoded) {
                        reversed = Builtins.g1PointsCons(BlsLib.g1Uncompress(b), reversed);
                    }
                    JulcScalars reversedScalars = Builtins.scalarsEmpty();
                    for (var s : scalars) {
                        reversedScalars = Builtins.scalarsCons(s, reversedScalars);
                    }
                    JulcG1 oracle = BlsLib.g1MultiScalarMul(Builtins.scalarsFromList(scalars), Builtins.g1PointsFromCompressed(encoded));
                    boolean forwardAgrees = BlsLib.g1Equal(BlsLib.g1MultiScalarMul(forwardScalars(scalars), forward(encoded)), oracle);
                    boolean reversedAgrees = BlsLib.g1Equal(BlsLib.g1MultiScalarMul(reversedScalars, reversed), oracle);
                    boolean mixedDiffers = !BlsLib.g1Equal(BlsLib.g1MultiScalarMul(forwardScalars(scalars), reversed), oracle);
                    return forwardAgrees && reversedAgrees && mixedDiffers;
                }""");

    /** 22 (#240): the G2 forms, through a recursive helper and directly; the empty G2 list and the empty scalars give the identity. */
    static final String G2_CONS = method("""
                static JulcG2Points g2Collect(JulcList<byte[]> xs) {
                    if (xs.isEmpty()) return Builtins.g2PointsEmpty();
                    return Builtins.g2PointsCons(BlsLib.g2Uncompress(xs.head()), g2Collect(xs.tail()));
                }

                static boolean g2Cons(byte[] dst) {
                    JulcG2 q1 = Builtins.bls12_381_G2_hashToGroup(new byte[]{1}, dst);
                    JulcG2 q2 = Builtins.bls12_381_G2_hashToGroup(new byte[]{2}, dst);
                    JulcList<byte[]> encoded = JulcList.of(BlsLib.g2Compress(q1), BlsLib.g2Compress(q2));
                    JulcScalars s = Builtins.scalarsCons(BigInteger.valueOf(4), Builtins.scalarsCons(BigInteger.valueOf(7), Builtins.scalarsEmpty()));
                    JulcG2 chain = BlsLib.g2Add(BlsLib.g2ScalarMul(BigInteger.valueOf(4), q1), BlsLib.g2ScalarMul(BigInteger.valueOf(7), q2));
                    JulcG2 collected = BlsLib.g2MultiScalarMul(s, g2Collect(encoded));
                    JulcG2 direct = BlsLib.g2MultiScalarMul(s, Builtins.g2PointsCons(q1, Builtins.g2PointsCons(q2, Builtins.g2PointsEmpty())));
                    JulcG2 zero = BlsLib.g2ScalarMul(BigInteger.ZERO, q1);
                    boolean empty = BlsLib.g2Equal(BlsLib.g2MultiScalarMul(s, Builtins.g2PointsEmpty()), zero)
                            && BlsLib.g2Equal(BlsLib.g2MultiScalarMul(Builtins.scalarsEmpty(), g2Collect(encoded)), zero);
                    return BlsLib.g2Equal(collected, chain) && BlsLib.g2Equal(direct, chain) && empty;
                }""");

    /**
     * 23 (#240): the empty forms give the identity against empty and non-empty other lists;
     * a cons onto a literal list, onto {@code scalarsFromList} and onto
     * {@code g1PointsFromCompressed} puts its element first; {@code var} infers the cons type.
     */
    static final String CONS_EMPTY_AND_MIXED = method("""
                static boolean consEmptyAndMixed(JulcList<BigInteger> xs, JulcList<byte[]> encoded, byte[] dst) {
                    JulcG1 p = Builtins.bls12_381_G1_hashToGroup(new byte[]{1}, dst);
                    JulcG1 q = Builtins.bls12_381_G1_hashToGroup(new byte[]{2}, dst);
                    JulcG1 zero = BlsLib.g1ScalarMul(BigInteger.ZERO, p);
                    boolean empty = BlsLib.g1Equal(BlsLib.g1MultiScalarMul(Builtins.scalarsEmpty(), Builtins.g1PointsEmpty()), zero)
                            && BlsLib.g1Equal(BlsLib.g1MultiScalarMul(Builtins.scalars(BigInteger.ONE), Builtins.g1PointsEmpty()), zero)
                            && BlsLib.g1Equal(BlsLib.g1MultiScalarMul(Builtins.scalarsEmpty(), Builtins.g1Points(p)), zero);
                    var onLiteral = Builtins.g1PointsCons(p, Builtins.g1Points(q));
                    JulcScalars onConverter = Builtins.scalarsCons(BigInteger.TWO, Builtins.scalarsFromList(xs));
                    JulcG1Points onDecoded = Builtins.g1PointsCons(q, Builtins.g1PointsFromCompressed(encoded));
                    boolean literal = BlsLib.g1Equal(BlsLib.g1MultiScalarMul(Builtins.scalars(BigInteger.valueOf(3), BigInteger.valueOf(5)), onLiteral),
                            BlsLib.g1Add(BlsLib.g1ScalarMul(BigInteger.valueOf(3), p), BlsLib.g1ScalarMul(BigInteger.valueOf(5), q)));
                    boolean converted = BlsLib.g1Equal(BlsLib.g1MultiScalarMul(onConverter, onDecoded),
                            BlsLib.g1Add(BlsLib.g1ScalarMul(BigInteger.TWO, q), BlsLib.g1ScalarMul(xs.get(0), BlsLib.g1Uncompress(encoded.get(0)))));
                    return empty && literal && converted;
                }""");

    private static final List<Input> RUN = List.of(Input.ok("run", DST));

    /** Three distinct points (G, 2G, −G) for the #240 fixtures; with the scalars 2, 3, 5 the sum is 3G, reversed points give 9G. */
    private static final PlutusData THREE_POINTS = points(G1_GENERATOR, G1_DOUBLE, negated(G1_GENERATOR));

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
                    Input.ok("three", integers(3), DST))),
            new Fixture("CONS_RECURSION", CONS_RECURSION, "consRecursion", List.of(
                    Input.ok("three", THREE_POINTS, integers(2, 3, 5)),
                    Input.ok("extra-scalar-ignored", points(G1_GENERATOR, G1_DOUBLE), integers(2, 3, 5)),
                    Input.ok("extra-point-ignored", THREE_POINTS, integers(2, 3)),
                    Input.ok("empty", points(), integers()),
                    Input.fails("invalid-encoding", points(G1_GENERATOR, new byte[48]), integers(2, 3)),
                    Input.fails("not-bytes", PlutusData.list(PlutusData.bytes(G1_GENERATOR), PlutusData.integer(1)), integers(2, 3)),
                    Input.fails("not-an-integer", THREE_POINTS, PlutusData.list(PlutusData.integer(2), PlutusData.bytes(new byte[]{1}))))),
            new Fixture("VALIDATE_ONCE", VALIDATE_ONCE, "validateOnce", List.of(
                    Input.ok("three", THREE_POINTS, integers(2, 3, 5)),
                    Input.ok("empty", points(), integers(2)),
                    Input.fails("infinity", points(G1_GENERATOR, G1_INFINITY), integers(2, 3)),
                    Input.fails("invalid-encoding", points(G1_GENERATOR, new byte[48]), integers(2, 3)))),
            new Fixture("CONS_ORDER", CONS_ORDER, "consOrder", List.of(
                    Input.ok("three", THREE_POINTS, integers(2, 3, 5)))),
            new Fixture("G2_CONS", G2_CONS, "g2Cons", RUN),
            new Fixture("CONS_EMPTY_AND_MIXED", CONS_EMPTY_AND_MIXED, "consEmptyAndMixed", List.of(
                    Input.ok("one", integers(3), points(G1_GENERATOR), DST))));
}
