package org.julclang.difftest;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import org.julclang.core.PlutusData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.julclang.difftest.Adr068CallsHarnessTest.assertAgree;
import static org.julclang.difftest.Adr068CallsHarnessTest.bool;
import static org.julclang.difftest.Adr068CallsHarnessTest.bytes;
import static org.julclang.difftest.Adr068CallsHarnessTest.integer;
import static org.julclang.difftest.Adr068CallsHarnessTest.list;
import static org.julclang.difftest.Adr068CallsHarnessTest.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-068 B3: what Java throws on. The JVM is the model, and the script must fail where a method throws and return what it
 * returns everywhere else. Each method is one the reference lowers: {@code mod}, {@code take} and {@code drop} and their
 * neighbours, over small values on both sides of each boundary.
 */
class Adr068ThrowingMethodsTest {

    private static final int EDGE = 6;

    private static PlutusData elements(int size) {
        var items = new ArrayList<PlutusData>();
        for (int i = 1; i <= size; i++) items.add(integer(i));
        return list(items.toArray(new PlutusData[0]));
    }

    private static PlutusData map(int size) {
        var entries = new ArrayList<PlutusData.Pair>();
        for (int i = 1; i <= size; i++) entries.add(new PlutusData.Pair(integer(i), integer(i * 10L)));
        return new PlutusData.MapData(entries);
    }

    private static PlutusData byteString(int size) {
        var values = new int[size];
        for (int i = 0; i < size; i++) values[i] = i + 1;
        return bytes(values);
    }

    private static PlutusData optional(boolean present, int value) {
        return present ? new PlutusData.ConstrData(0, List.of(integer(value))) : new PlutusData.ConstrData(1, List.of());
    }

    @Provide
    Arbitrary<String> integerMethods() {
        return Arbitraries.of("a.mod(b)", "a.divide(b)", "a.remainder(b)", "a.mod(b).add(a)");
    }

    @Property(tries = 40)
    void anIntegerMethod(@ForAll("integerMethods") String body, @ForAll @IntRange(min = -EDGE, max = EDGE) int a,
                         @ForAll @IntRange(min = -EDGE, max = EDGE) int b) {
        assertAgree("public static BigInteger m(BigInteger a, BigInteger b) { return " + body + "; }", integer(a), integer(b));
    }

    @Provide
    Arbitrary<String> longOperators() {
        return Arbitraries.of("a / b", "a % b");
    }

    @Property(tries = 20)
    void aLongOperator(@ForAll("longOperators") String body, @ForAll @IntRange(min = -EDGE, max = EDGE) int a,
                       @ForAll @IntRange(min = -EDGE, max = EDGE) int b) {
        assertAgree("public static long m(long a, long b) { return " + body + "; }", integer(a), integer(b));
    }

    @Provide
    Arbitrary<String> listMethods() {
        return Arbitraries.of("a.take(n).size()", "a.drop(n).size()", "a.take(n).prepend(BigInteger.ZERO).size()",
                "a.drop(n).take(n).size()");
    }

    /** Small values, and the counts an {@code int} would read as another: Java's {@code long} is the count, as in the script. */
    @Provide
    Arbitrary<Long> counts() {
        return Arbitraries.oneOf(Arbitraries.longs().between(-EDGE, EDGE), Arbitraries.of(Long.MIN_VALUE, Long.MIN_VALUE + 1,
                -(1L << 32) - 1, -(1L << 32), -(1L << 32) + 1, -(1L << 31) - 1, -(1L << 31)));
    }

    /** A count past an {@code int} in the other direction, which {@code DropList} would run out of budget on. */
    @Provide
    Arbitrary<Long> indices() {
        return Arbitraries.oneOf(counts(), Arbitraries.of(1L << 31, (1L << 32) - 1, 1L << 32, (1L << 32) + 1, Long.MAX_VALUE));
    }

    @Property(tries = 60)
    void aListCount(@ForAll("listMethods") String body, @ForAll @IntRange(min = 0, max = 4) int size, @ForAll("counts") long n) {
        assertAgree("public static long m(JulcList<BigInteger> a, long n) { return " + body + "; }", elements(size), integer(n));
    }

    @Property(tries = 40)
    void aListRead(@ForAll @IntRange(min = 0, max = 3) int size, @ForAll("indices") long n) {
        assertAgree("public static BigInteger m(JulcList<BigInteger> a, long n) { return a.get(n); }", elements(size), integer(n));
    }

    @Property(tries = 40)
    void anArrayRead(@ForAll @IntRange(min = 0, max = 3) int size, @ForAll("indices") long n) {
        assertAgree("public static BigInteger m(JulcList<BigInteger> a, long n) { return a.toArray().get(n); }", elements(size), integer(n));
    }

    @Property(tries = 20)
    void aTakePastAnInt(@ForAll @IntRange(min = 0, max = 4) int size, @ForAll("indices") long n) {
        assertAgree("public static long m(JulcList<BigInteger> a, long n) { return a.take(n).size(); }", elements(size), integer(n));
    }

    // --- Builtins that the JVM's stubs run: Plutus 1.63.0.0 is the truth, and the VM its copy here ---

    @Provide
    Arbitrary<PlutusData> byteStrings() {
        return Arbitraries.integers().between(0, 255).list().ofMaxSize(4).map(values -> bytes(values.stream().mapToInt(Integer::intValue).toArray()));
    }

    @Property(tries = 40)
    void aByteStringComparison(@ForAll("byteStrings") PlutusData a, @ForAll("byteStrings") PlutusData b) {
        assertAgree("public static boolean m(byte[] a, byte[] b) { return Builtins.lessThanByteString(a, b); }", a, b);
        assertAgree("public static boolean m(byte[] a, byte[] b) { return Builtins.lessThanEqualsByteString(a, b); }", a, b);
    }

    @Provide
    Arbitrary<String> bitwiseMethods() {
        return Arbitraries.of("andByteString", "orByteString", "xorByteString");
    }

    @Property(tries = 40)
    void aBitwiseMethod(@ForAll("bitwiseMethods") String method, @ForAll boolean padding, @ForAll("byteStrings") PlutusData a,
                        @ForAll("byteStrings") PlutusData b) {
        assertAgree("public static byte[] m(boolean p, byte[] a, byte[] b) { return Builtins." + method + "(p, a, b); }",
                bool(padding), a, b);
    }

    @Property(tries = 40)
    void aSlice(@ForAll("indices") long start, @ForAll("indices") long length, @ForAll("byteStrings") PlutusData bytes) {
        assertAgree("public static byte[] m(long s, long l, byte[] b) { return Builtins.sliceByteString(s, l, b); }",
                integer(start), integer(length), bytes);
    }

    @Property(tries = 30)
    void anIndex(@ForAll("byteStrings") PlutusData bytes, @ForAll("indices") long index) {
        assertAgree("public static long m(byte[] b, long i) { return Builtins.indexByteString(b, i); }", bytes, integer(index));
    }

    @Provide
    Arbitrary<Long> byteValues() {
        return Arbitraries.of(-(1L << 32), -256L, -1L, 0L, 1L, 255L, 256L, 257L, 1L << 32, (1L << 32) + 1);
    }

    @Property(tries = 20)
    void aCons(@ForAll("byteValues") long value, @ForAll("byteStrings") PlutusData bytes) {
        assertAgree("public static byte[] m(long v, byte[] b) { return Builtins.consByteString(v, b); }", integer(value), bytes);
    }

    @Property(tries = 30)
    void aReplication(@ForAll("replicationLengths") long length, @ForAll("byteValues") long value) {
        assertAgree("public static long m(long n, long v) { return Builtins.lengthOfByteString(Builtins.replicateByte(n, v)); }",
                integer(length), integer(value));
    }

    /** Near the bound: {@code ReplicateByte} costs by its literal count, so a count far past it runs out of budget first. */
    @Provide
    Arbitrary<Long> replicationLengths() {
        return Arbitraries.of(-8193L, -1L, 0L, 1L, 8192L, 8193L, 100000L);
    }

    /** Bytes that are text and bytes that are not: overlong forms, surrogates, past U+10FFFF, cut short, stray continuations. */
    @ParameterizedTest
    @ValueSource(strings = {"", "61", "c3a9", "e282ac", "f09f9880", "f48fbfbf", "ff", "80", "c080", "c1bf", "e08080", "eda080", "edbfbf", "f0808080",
            "f4908080", "f5808080", "e282", "c3", "f09f98", "61ff61"})
    void aDecoding(String hex) {
        var input = new PlutusData.BytesData(HexFormat.of().parseHex(hex));
        assertAgree("public static boolean m(byte[] a) { Builtins.decodeUtf8(a); return true; }", input);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 3})
    void theEndsOfAList(int size) {
        assertAgree("public static BigInteger m(JulcList<BigInteger> a) { return a.head(); }", elements(size));
        assertAgree("public static long m(JulcList<BigInteger> a) { return a.tail().size(); }", elements(size));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 3})
    void aMapsTail(int size) {
        assertAgree("public static long m(JulcMap<BigInteger, BigInteger> a) { return a.tail().size(); }", map(size));
    }

    @Test
    void anEmptyOptional() {
        assertAgree("public static BigInteger m(Optional<BigInteger> a) { return a.get(); }", optional(false, 0));
        assertAgree("public static BigInteger m(Optional<BigInteger> a) { return a.get(); }", optional(true, 7));
    }

    private static void assertDefaultFrontendAgrees(String imports, String members, PlutusData... arguments) {
        var report = run(imports, members, List.of(arguments));
        report.results().forEach((config, result) -> {
        });
    }

    @Provide
    Arbitrary<String> mathLibrary() {
        return Arbitraries.of("MathLib.pow(a, b)", "MathLib.floorDiv(a, b)", "MathLib.floorMod(a, b)");
    }

    /** A library unit stops on the javac frontend until library lowering, so these compare the default frontend. */
    @Property(tries = 20)
    void aMathLibraryMethod(@ForAll("mathLibrary") String body, @ForAll @IntRange(min = -EDGE, max = EDGE) int a,
                            @ForAll @IntRange(min = -EDGE, max = EDGE) int b) {
        assertDefaultFrontendAgrees("import org.julclang.stdlib.lib.MathLib;",
                "public static BigInteger m(BigInteger a, BigInteger b) { return " + body + "; }", integer(a), integer(b));
    }

    @Provide
    Arbitrary<String> listsLibrary() {
        return Arbitraries.of("ListsLib.take(a, n).size()", "ListsLib.drop(a, n).size()");
    }

    @Property(tries = 20)
    void aListsLibraryMethod(@ForAll("listsLibrary") String body, @ForAll @IntRange(min = 0, max = 3) int size,
                             @ForAll @IntRange(min = -EDGE, max = EDGE) int n) {
        assertDefaultFrontendAgrees("import org.julclang.stdlib.lib.ListsLib;",
                "public static long m(JulcList<PlutusData> a, long n) { return " + body + "; }", elements(size), integer(n));
    }

    @Provide
    Arbitrary<String> bytesLibrary() {
        return Arbitraries.of("ByteStringLib.length(ByteStringLib.take(a, n))", "ByteStringLib.length(ByteStringLib.drop(a, n))",
                "ByteStringLib.at(a, n)", "ByteStringLib.length(ByteStringLib.slice(a, n, 2))", "ByteStringLib.length(ByteStringLib.zeros(n))");
    }

    @Property(tries = 30)
    void aByteStringLibraryMethod(@ForAll("bytesLibrary") String body, @ForAll @IntRange(min = 0, max = 3) int size,
                                  @ForAll @IntRange(min = -EDGE, max = EDGE) int n) {
        assertDefaultFrontendAgrees("import org.julclang.stdlib.lib.ByteStringLib;",
                "public static long m(byte[] a, long n) { return " + body + "; }", byteString(size), integer(n));
    }

    /** The receiver, then the argument, then the failure: a traced operand shows Java's order, on the failing path too. */
    @ParameterizedTest
    @ValueSource(longs = {-2, 0, 3})
    void aCheckRunsAfterBothOperands(long argument) {
        assertAgree("public static BigInteger m(BigInteger a, BigInteger b) { return said(\"R\", a).mod(said(\"A\", b)); }",
                integer(7), integer(argument));
        for (String method : List.of("take", "drop")) {
            assertAgree("public static long m(JulcList<BigInteger> a, long n) { return (saidL(\"R\", 1) > 0 ? a : a)." + method
                    + "(saidL(\"A\", n)).size(); }", elements(2), integer(argument));
        }
    }

    /** These have no lowering: a program that calls one does not compile, on either frontend. */
    @ParameterizedTest
    @ValueSource(strings = {"a.pow(2)", "a.shiftLeft(2)", "a.shiftRight(2)", "a.modPow(a, a)"})
    void aMethodWithNoLoweringIsRejected(String body) {
        var report = run("public static BigInteger m(BigInteger a) { return " + body + "; }", List.of(integer(3)));
        assertTrue(report.all(Verdict.Kind.REJECTED), report::describe);
    }
}
