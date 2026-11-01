package org.julclang.difftest;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import org.julclang.compiler.CompilerFrontend;
import org.julclang.core.PlutusData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.julclang.difftest.Adr068CallsHarnessTest.assertAgree;
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

    @Property(tries = 40)
    void aListCount(@ForAll("listMethods") String body, @ForAll @IntRange(min = 0, max = 4) int size,
                    @ForAll @IntRange(min = -EDGE, max = EDGE) int n) {
        assertAgree("public static long m(JulcList<BigInteger> a, long n) { return " + body + "; }", elements(size), integer(n));
    }

    @Property(tries = 20)
    void aListRead(@ForAll @IntRange(min = 0, max = 3) int size, @ForAll @IntRange(min = -EDGE, max = EDGE) int n) {
        assertAgree("public static BigInteger m(JulcList<BigInteger> a, long n) { return a.get(n); }", elements(size), integer(n));
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
            if (config.frontend() == CompilerFrontend.JAVAPARSER) assertEquals(Verdict.Kind.AGREE, result.verdict().kind(), report::describe);
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
