package org.julclang.compiler.frontend.javac.lower;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.BigRange;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.julclang.compiler.CompilerFrontend;
import org.julclang.core.PlutusData;
import org.julclang.core.types.JulcList;
import org.julclang.stdlib.lib.BitwiseLib;
import org.julclang.stdlib.lib.ByteStringLib;
import org.julclang.stdlib.lib.CryptoLib;
import org.julclang.stdlib.lib.IntervalLib;
import org.julclang.stdlib.lib.ListsLib;
import org.julclang.stdlib.lib.MathLib;
import org.julclang.testkit.CallResult;
import org.julclang.testkit.JulcEval;
import org.julclang.testkit.JvmCryptoProvider;
import org.julclang.stdlib.Builtins;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ADR-068 D8: stdlib library methods, each compiled with its unit as the root and the class path's library sources as the
 * pool, against the method's own Java body on the JVM, on random inputs, on both frontends. Where the body has no JVM value
 * (a view through a cast), the frontends are compared with each other.
 */
class StdlibLibrariesPropertyTest {

    private static final List<JulcEval> MATH = frontends("MathLib");
    private static final List<JulcEval> BYTES = frontends("ByteStringLib");
    private static final List<JulcEval> LISTS = frontends("ListsLib");
    private static final List<JulcEval> CRYPTO = frontends("CryptoLib");
    private static final List<JulcEval> BITWISE = frontends("BitwiseLib");
    private static final List<JulcEval> INTERVALS = frontends("IntervalLib");
    private static final List<JulcEval> MAPS = frontends("MapLib");
    private static final List<JulcEval> VALUES = frontends("ValuesLib");

    private static List<JulcEval> frontends(String library) {
        String source = JavacLibrariesTest.stdlibSource(library);
        return List.of(JulcEval.forSource(source).frontend(CompilerFrontend.JAVAPARSER), JulcEval.forSource(source).frontend(CompilerFrontend.JAVAC));
    }

    private static <T> void agrees(List<JulcEval> evals, T expected, Function<CallResult, T> read, String method, Object... arguments) {
        for (var eval : evals) assertEquals(expected, read.apply(eval.call(method, arguments)), method);
    }

    @Property(tries = 20)
    void mathOfOneInteger(@ForAll @BigRange(min = "-1000000000000000000000", max = "1000000000000000000000") BigInteger x) {
        agrees(MATH, MathLib.abs(x), CallResult::asInteger, "abs", x);
        agrees(MATH, MathLib.sign(x), CallResult::asInteger, "sign", x);
    }

    @Property(tries = 20)
    void mathOfTwoIntegers(@ForAll @BigRange(min = "-1000000000000", max = "1000000000000") BigInteger a,
                           @ForAll @BigRange(min = "-1000000000000", max = "1000000000000") BigInteger b) {
        agrees(MATH, MathLib.max(a, b), CallResult::asInteger, "max", a, b);
        agrees(MATH, MathLib.min(a, b), CallResult::asInteger, "min", a, b);
        if (b.signum() != 0) {
            agrees(MATH, MathLib.floorDiv(a, b), CallResult::asInteger, "floorDiv", a, b);
            agrees(MATH, MathLib.floorMod(a, b), CallResult::asInteger, "floorMod", a, b);
        }
    }

    @Property(tries = 15)
    void powers(@ForAll @BigRange(min = "-50", max = "50") BigInteger base, @ForAll @IntRange(min = 0, max = 12) int exp) {
        agrees(MATH, MathLib.pow(base, BigInteger.valueOf(exp)), CallResult::asInteger, "pow", base, BigInteger.valueOf(exp));
    }

    @Property(tries = 20)
    void bytes(@ForAll @Size(max = 12) byte[] a, @ForAll @Size(max = 12) byte[] b, @ForAll @IntRange(min = 0, max = 14) int n) {
        agrees(BYTES, ByteStringLib.length(a), CallResult::asLong, "length", a);
        agrees(BYTES, ByteStringLib.equals(a, b), CallResult::asBoolean, "equals", a, b);
        agrees(BYTES, ByteStringLib.lessThan(a, b), CallResult::asBoolean, "lessThan", a, b);
        for (var eval : BYTES) {
            assertArrayEquals(ByteStringLib.append(a, b), eval.call("append", a, b).asByteString());
            assertArrayEquals(ByteStringLib.take(a, n), eval.call("take", a, (long) n).asByteString());
            assertArrayEquals(ByteStringLib.drop(a, n), eval.call("drop", a, (long) n).asByteString());
            assertArrayEquals(ByteStringLib.toHex(a), eval.call("toHex", a).asByteString());
        }
    }

    private static List<PlutusData> elements(JulcList<PlutusData> list) {
        var elements = new ArrayList<PlutusData>();
        list.forEach(elements::add);
        return elements;
    }

    private static JulcList<PlutusData> integers(List<Integer> values) {
        JulcList<PlutusData> list = JulcList.empty();
        for (int i = values.size() - 1; i >= 0; i--) list = list.prepend(PlutusData.integer(values.get(i)));
        return list;
    }

    @Property(tries = 15)
    void lists(@ForAll @Size(max = 8) List<@IntRange(min = -5, max = 5) Integer> values, @ForAll @IntRange(min = 0, max = 9) int n) {
        var list = integers(values);
        agrees(LISTS, ListsLib.length(list), CallResult::asLong, "length", list);
        agrees(LISTS, ListsLib.contains(list, PlutusData.integer(n)), CallResult::asBoolean, "contains", list, PlutusData.integer(n));
        agrees(LISTS, ListsLib.hasDuplicateInts(list), CallResult::asBoolean, "hasDuplicateInts", list);
        agrees(LISTS, elements(ListsLib.reverse(list)), CallResult::asList, "reverse", list);
        agrees(LISTS, elements(ListsLib.take(list, n)), CallResult::asList, "take", list, (long) n);
        agrees(LISTS, elements(ListsLib.drop(list, n)), CallResult::asList, "drop", list, (long) n);
        if (n < values.size()) agrees(LISTS, ListsLib.nth(list, n), CallResult::asData, "nth", list, (long) n);
    }

    @Property(tries = 10)
    void hashes(@ForAll @Size(max = 40) byte[] a) {
        Builtins.setCryptoProvider(new JvmCryptoProvider());
        for (var eval : CRYPTO) {
            assertArrayEquals(CryptoLib.sha2_256(a), eval.call("sha2_256", a).asByteString());
            assertArrayEquals(CryptoLib.blake2b_256(a), eval.call("blake2b_256", a).asByteString());
            assertArrayEquals(CryptoLib.sha3_256(a), eval.call("sha3_256", a).asByteString());
            assertArrayEquals(CryptoLib.blake2b_224(a), eval.call("blake2b_224", a).asByteString());
            assertArrayEquals(CryptoLib.keccak_256(a), eval.call("keccak_256", a).asByteString());
            assertArrayEquals(CryptoLib.ripemd_160(a), eval.call("ripemd_160", a).asByteString());
        }
    }

    @Property(tries = 10)
    void bits(@ForAll @Size(min = 1, max = 8) byte[] a, @ForAll @IntRange(min = -20, max = 20) int n) {
        agrees(BITWISE, BitwiseLib.countSetBits(a), CallResult::asLong, "countSetBits", a);
        agrees(BITWISE, BitwiseLib.findFirstSetBit(a), CallResult::asLong, "findFirstSetBit", a);
        agrees(BITWISE, BitwiseLib.readBit(a, Math.abs(n) % (8 * a.length)), CallResult::asBoolean, "readBit", a,
                (long) (Math.abs(n) % (8 * a.length)));
        for (var eval : BITWISE) {
            assertArrayEquals(BitwiseLib.complementByteString(a), eval.call("complementByteString", a).asByteString());
            assertArrayEquals(BitwiseLib.shiftByteString(a, n), eval.call("shiftByteString", a, (long) n).asByteString());
            assertArrayEquals(BitwiseLib.rotateByteString(a, n), eval.call("rotateByteString", a, (long) n).asByteString());
        }
    }

    @Property(tries = 15)
    void intervals(@ForAll @BigRange(min = "-100", max = "100") BigInteger low, @ForAll @BigRange(min = "-100", max = "100") BigInteger high,
                   @ForAll @BigRange(min = "-120", max = "120") BigInteger point) {
        var interval = IntervalLib.between(low, high);
        agrees(INTERVALS, IntervalLib.contains(interval, point), CallResult::asBoolean, "contains", interval, point);
        agrees(INTERVALS, IntervalLib.isEmpty(interval), CallResult::asBoolean, "isEmpty", interval);
        agrees(INTERVALS, IntervalLib.contains(IntervalLib.after(low), point), CallResult::asBoolean, "contains", IntervalLib.after(low), point);
    }

    /**
     * {@code MapLib} and {@code ValuesLib} view Data through casts that throw on the JVM ({@code (T)(Object) x}), so their Java
     * bodies have no JVM value to compare with: the two frontends are compared with each other, and with what the map holds.
     */
    @Property(tries = 15)
    void mapsAndValues(@ForAll @Size(max = 5) List<@IntRange(min = 0, max = 6) Integer> keys, @ForAll @IntRange(min = 0, max = 6) int key) {
        var entries = new LinkedHashMap<PlutusData, PlutusData>();
        for (int k : keys) entries.putIfAbsent(PlutusData.integer(k), PlutusData.integer(k * 10L));
        var map = new PlutusData.MapData(entries.entrySet().stream()
                .map(e -> new PlutusData.Pair(e.getKey(), e.getValue())).toList());
        var target = PlutusData.integer(key);
        agrees(MAPS, entries.containsKey(target), CallResult::asBoolean, "member", map, target);
        agrees(MAPS, (long) entries.size(), CallResult::asLong, "size", map);
        agrees(MAPS, List.copyOf(entries.keySet()), CallResult::asList, "keys", map);

        var policies = new LinkedHashMap<PlutusData, PlutusData>();
        for (int k : keys) {
            var policy = PlutusData.bytes(k == 0 ? new byte[0] : new byte[]{(byte) k});
            var tokens = new PlutusData.MapData(List.of(new PlutusData.Pair(PlutusData.bytes(new byte[0]), PlutusData.integer(k * 3L - 4))));
            policies.putIfAbsent(policy, tokens);
        }
        var value = new PlutusData.MapData(policies.entrySet().stream().map(e -> new PlutusData.Pair(e.getKey(), e.getValue())).toList());
        var expected = VALUES.getFirst();
        for (var method : List.of("lovelaceOf")) {
            agrees(VALUES, expected.call(method, value).asInteger(), CallResult::asInteger, method, value);
        }
        agrees(VALUES, expected.call("isZero", value).asBoolean(), CallResult::asBoolean, "isZero", value);
        agrees(VALUES, expected.call("containsPolicy", value, new byte[]{(byte) key}).asBoolean(), CallResult::asBoolean,
                "containsPolicy", value, new byte[]{(byte) key});
    }
}
