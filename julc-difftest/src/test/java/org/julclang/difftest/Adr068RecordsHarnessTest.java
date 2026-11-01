package org.julclang.difftest;

import org.julclang.compiler.CompilerFrontend;
import org.julclang.core.PlutusData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-068 D10 over object creation and member reads: each program on the JVM and, through each frontend at every level, on
 * the VM. A record that comes back is Data on-chain, and the JVM's value goes through the harness's mapping, written from the
 * documented encodings, so neither side decodes the other. Traced arguments pin the order of evaluation, and a failing one
 * at each position pins what runs before it.
 */
class Adr068RecordsHarnessTest {

    private static final DiffRunner RUNNER = new DiffRunner(configs(), 10_000);
    private static final long[] EDGES = {0, 1, -1, 7, 2147483647L, -2147483648L, 1L << 40};

    private static List<DiffConfig> configs() {
        var configs = new ArrayList<>(DiffConfig.allLevels());
        configs.addAll(DiffConfig.allLevels(CompilerFrontend.JAVAC));
        return configs;
    }

    private static List<DiffConfig> javacConfigs() {
        return DiffConfig.allLevels(CompilerFrontend.JAVAC);
    }

    private static final DiffRunner JAVAC_RUNNER = new DiffRunner(javacConfigs(), 10_000);

    private static String program(String members) {
        return """
                import java.math.BigInteger;
                import java.util.Optional;
                import org.julclang.core.PlutusData;
                import org.julclang.core.types.JulcList;
                import org.julclang.core.types.JulcMap;
                import org.julclang.core.types.Tuple2;
                import org.julclang.core.types.Tuple3;
                import org.julclang.stdlib.Builtins;
                import org.julclang.stdlib.annotation.NewType;

                public class Case {
                    static BigInteger said(String m, BigInteger v) { return Builtins.unIData(Builtins.trace(m, Builtins.iData(v))); }
                    static BigInteger fail(long z) { return Builtins.unIData(Builtins.error()); }
                    public record P(BigInteger x, BigInteger y) { }
                    public record Q(P p, String s) { }
                    public record E() { }
                    public record Three(BigInteger a, BigInteger b, BigInteger c) { }
                    public sealed interface S {
                        record A(BigInteger v) implements S { }
                        record B(String s, BigInteger w) implements S { }
                        record D() implements S { }
                    }
                    public sealed interface T permits Z, Y { }
                    public record Y(byte[] b) implements T { }
                    public record Z(BigInteger a) implements T { }
                    public record H(S s, T t) { }
                    public sealed interface L {
                        record Nil() implements L { }
                        record Cons(BigInteger h, L t) implements L { }
                    }
                    public static BigInteger sum(L l) {
                        if (l instanceof L.Cons c) { return c.h().add(sum(c.t())); }
                        return BigInteger.ZERO;
                    }
                    @NewType public record N(BigInteger value) { }
                    @NewType public record B(byte[] value) { }
                    public record W(N n, B b) { }
                    %s
                }
                """.formatted(members);
    }

    private static final TagTable TAGS = new TagTable(List.of(
            new TagTable.Entry("Case$S$A", "Case$S", 0), new TagTable.Entry("Case$S$B", "Case$S", 1), new TagTable.Entry("Case$S$D", "Case$S", 2),
            new TagTable.Entry("Case$Z", "Case$T", 0), new TagTable.Entry("Case$Y", "Case$T", 1),
            new TagTable.Entry("Case$L$Nil", "Case$L", 0), new TagTable.Entry("Case$L$Cons", "Case$L", 1)));

    private static PlutusData integer(long value) {
        return new PlutusData.IntData(BigInteger.valueOf(value));
    }

    private static PlutusData bytes(int... values) {
        var bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) bytes[i] = (byte) values[i];
        return new PlutusData.BytesData(bytes);
    }

    private static PlutusData text(String value) {
        return new PlutusData.BytesData(value.getBytes(StandardCharsets.UTF_8));
    }

    private static PlutusData bool(boolean value) {
        return new PlutusData.ConstrData(value ? 1 : 0, List.of());
    }

    private static PlutusData constr(int tag, PlutusData... fields) {
        return new PlutusData.ConstrData(tag, List.of(fields));
    }

    private static PlutusData list(long... values) {
        var items = new ArrayList<PlutusData>();
        for (long value : values) items.add(integer(value));
        return new PlutusData.ListData(items);
    }

    private static DiffRunner.Report run(DiffRunner runner, String members, PlutusData... args) {
        return runner.run(new DiffCase("records", program(members), "Case", "m", List.of(args), TAGS, null));
    }

    /** Every frontend at every level agrees with the JVM, and the frontends with each other on value, cost and size. */
    private static void assertAgree(String members, PlutusData... args) {
        var report = run(RUNNER, members, args);
        assertTrue(report.all(Verdict.Kind.AGREE), report::describe);
        assertEquals(8, report.results().size());
        report.results().forEach((config, result) -> {
            if (config.frontend() == CompilerFrontend.JAVAPARSER) {
                var javac = report.results().get(new DiffConfig(config.level(), "Java", CompilerFrontend.JAVAC));
                assertEquals(result.uplc(), javac.uplc(), "the frontends disagree at " + config);
                assertEquals(result.measurement(), javac.measurement(), "cost at " + config);
            }
        });
    }

    /** {@code JAVAC} agrees with the JVM at every level. The reference is not asked: its gate rejects the shape, or it differs. */
    private static void assertJavacAgrees(String source, TagTable tags, PlutusData... args) {
        var report = JAVAC_RUNNER.run(new DiffCase("records", source, "Case", "m", List.of(args), tags, null));
        assertTrue(report.all(Verdict.Kind.AGREE), report::describe);
    }

    private static void assertJavacAgrees(String source, PlutusData... args) {
        assertJavacAgrees(source, TagTable.EMPTY, args);
    }

    // --- Encodings ---

    @Test
    void everyFieldEncoding() {
        String members = """
                public record F(BigInteger i, long l, int k, boolean o, Boolean p, String s, byte[] b, PlutusData d) { }
                public static F m(BigInteger x, byte[] y, boolean z, String s, PlutusData d) { return new F(x, 2, -3, z, !z, s, y, d); }
                """;
        for (long edge : EDGES) assertAgree(members, integer(edge), bytes(170), bool(edge > 0), text("hi"), integer(edge));
        assertAgree(members, integer(5), bytes(), bool(false), text(""), constr(2, integer(1)));
        assertAgree(members, integer(5), bytes(0, 255), bool(true), text("é😀"), new PlutusData.ListData(List.of(integer(1))));
    }

    @Test
    void containersAndOptionals() {
        String members = """
                public record C(JulcList<BigInteger> a, JulcMap<BigInteger, BigInteger> b, Optional<BigInteger> c, Optional<BigInteger> e) { }
                public static C m(JulcList<BigInteger> l, JulcMap<BigInteger, BigInteger> v, BigInteger x) { return new C(l, v, Optional.of(x), Optional.empty()); }
                """;
        var map = new PlutusData.MapData(List.of(new PlutusData.Pair(integer(1), integer(2))));
        assertAgree(members, list(1, 2), map, integer(3));
        assertAgree(members, list(), new PlutusData.MapData(List.of()), integer(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "public record R(BigInteger a) { } public static BigInteger m(BigInteger x) { var r = new R(x); return r.a(); }",
            "public record R(long a) { } public static long m(BigInteger x) { var r = new R(x.longValue()); return r.a(); }",
            "public record R(boolean a) { } public static boolean m(BigInteger x) { var r = new R(x.signum() > 0); return r.a(); }",
            "public record R(String a) { } public static String m(BigInteger x) { var r = new R(\"q\"); return r.a(); }",
            "public record R(byte[] a) { } public static byte[] m(BigInteger x) { var r = new R(new byte[]{1, 2}); return r.a(); }",
            "public record R(JulcList<BigInteger> a) { } public static BigInteger m(BigInteger x) { var r = new R(JulcList.of(x, x)); return r.a().head(); }",
            "public record R(S a) { } public static boolean m(BigInteger x) { var r = new R(new S.A(x)); return r.a() instanceof S.A a; }",
            "public record R(P a) { } public static BigInteger m(BigInteger x) { var r = new R(new P(x, x)); return r.a().y(); }",
    })
    void aFieldReadsBackWhatWentIn(String members) {
        for (long edge : new long[]{-1, 0, 7}) assertAgree(members, integer(edge));
    }

    @Test
    void nestedRecords() {
        assertAgree("public static Q m(BigInteger a) { return new Q(new P(a, a.add(BigInteger.ONE)), \"s\"); }", integer(3));
        assertAgree("public static BigInteger m(BigInteger a) { var q = new Q(new P(a, a.add(BigInteger.ONE)), \"s\"); return q.p().y(); }", integer(3));
        assertAgree("public static BigInteger m(Q q) { return q.p().x(); }", constr(0, constr(0, integer(1), integer(2)), text("s")));
        assertAgree("public static E m() { return new E(); }");
        assertAgree("public static Q m(Q q) { return new Q(q.p(), q.s() + \"!\"); }", constr(0, constr(0, integer(1), integer(2)), text("s")));
    }

    @Test
    void recordsInListsAndMaps() {
        assertAgree("public static JulcList<P> m(BigInteger a) { return JulcList.of(new P(a, a), new P(a, a.add(a))); }", integer(3));
        assertAgree("public static BigInteger m(BigInteger a) { JulcList<P> l = JulcList.of(new P(a, a), new P(a, a.add(a))); return l.tail().head().y(); }", integer(3));
        assertAgree("public static boolean m(BigInteger a) { JulcList<P> l = JulcList.of(new P(a, a)); return l.contains(new P(a, a)); }", integer(3));
        assertAgree("public static boolean m(BigInteger a) { JulcList<P> l = JulcList.of(new P(a, a)); return l.contains(new P(a, a.add(BigInteger.ONE))); }", integer(3));
        assertAgree("public static BigInteger m(BigInteger a) { JulcMap<BigInteger, P> v = JulcMap.<BigInteger, P>empty().insert(a, new P(a, a)); return v.get(a).x(); }", integer(3));
        assertAgree("public static BigInteger m(BigInteger a) { JulcList<S> l = JulcList.of(new S.A(a), new S.D()); if (l.head() instanceof S.A v) { return v.v(); } return a; }", integer(3));
    }

    // --- Variants ---

    @Test
    void tagsFollowSourceOrderWithoutPermits() {
        assertAgree("public static S m(BigInteger a) { return new S.A(a); }", integer(4));
        assertAgree("public static S m(BigInteger a) { return new S.B(\"q\", a); }", integer(4));
        assertAgree("public static S m() { return new S.D(); }");
        assertAgree("public static S.B m(BigInteger a) { return new S.B(\"q\", a); }", integer(4));
    }

    @Test
    void tagsFollowPermits() {
        assertAgree("public static T m(BigInteger a) { return new Z(a); }", integer(4));
        assertAgree("public static T m(byte[] a) { return new Y(a); }", bytes(1));
        assertAgree("public static Y m(byte[] a) { return new Y(a); }", bytes(1));
    }

    @Test
    void aFieldOfASumType() {
        assertAgree("public static H m(BigInteger a) { return new H(new S.B(\"q\", a), new Y(new byte[]{1})); }", integer(4));
        assertAgree("public static H m(BigInteger a) { return new H(new S.D(), new Z(a)); }", integer(4));
        assertAgree("public static BigInteger m(H h) { if (h.s() instanceof S.A a) { return a.v(); } return BigInteger.ZERO; }",
                constr(0, constr(0, integer(5)), constr(0, integer(6))));
        assertAgree("public static boolean m(BigInteger a) { var h = new H(new S.A(a), new Z(a)); return h.t() instanceof Y y; }", integer(4));
        assertAgree("public static BigInteger m(BigInteger a) { var h = new H(new S.A(a), new Z(a)); if (h.t() instanceof Z z) { return z.a(); } return BigInteger.ZERO; }", integer(4));
    }

    @Test
    void aRecursiveSum() {
        assertAgree("public static L m(BigInteger a) { return new L.Cons(a, new L.Cons(a.add(a), new L.Nil())); }", integer(3));
        assertAgree("public static BigInteger m(BigInteger a) { return sum(new L.Cons(a, new L.Cons(a.add(a), new L.Nil()))); }", integer(3));
    }

    @Test
    void aConditionalOfVariants() {
        for (boolean c : new boolean[]{false, true}) {
            assertAgree("public static S m(boolean c, BigInteger a) { S s = c ? new S.A(a) : new S.B(\"q\", a); return s; }", bool(c), integer(4));
            assertAgree("public static S m(boolean c, BigInteger a) { return c ? new S.A(a) : new S.D(); }", bool(c), integer(4));
        }
    }

    /** F807: {@code permits X.A, Y.A} has two constructors named {@code A}, and each is its own; the gate rejects the name on the reference. */
    @Test
    void twoVariantsOfOneSimpleNameEachHaveTheirTag() {
        String shape = """
                import java.math.BigInteger;
                public class Case {
                    public sealed interface S permits X.A, Y.A { }
                    public static class X { public record A(BigInteger v) implements S { } }
                    public static class Y { public record A(byte[] v) implements S { } }
                    %s
                }
                """;
        var tags = new TagTable(List.of(new TagTable.Entry("Case$X$A", "Case$S", 0), new TagTable.Entry("Case$Y$A", "Case$S", 1)));
        for (var program : List.of(new DiffCase("first", shape.formatted("public static S m(BigInteger b) { return new X.A(b); }"), "Case", "m", List.of(integer(7)), tags, null),
                new DiffCase("second", shape.formatted("public static S m(byte[] b) { return new Y.A(b); }"), "Case", "m", List.of(bytes(170)), tags, null),
                new DiffCase("test", shape.formatted("public static boolean m(byte[] b) { S s = new Y.A(b); return s instanceof Y.A a; }"), "Case", "m", List.of(bytes(170)), tags, null),
                new DiffCase("other", shape.formatted("public static boolean m(byte[] b) { S s = new Y.A(b); return s instanceof X.A a; }"), "Case", "m", List.of(bytes(170)), tags, null))) {
            var report = JAVAC_RUNNER.run(program);
            assertTrue(report.all(Verdict.Kind.AGREE), report::describe);
        }
    }

    // --- @NewType and hashes ---

    @Test
    void aNewTypeIsItsValue() {
        for (long edge : EDGES) {
            assertAgree("public static BigInteger m(BigInteger a) { N n = new N(a); return n.value(); }", integer(edge));
            assertAgree("public static BigInteger m(BigInteger a) { var n = new N(a); return n.value().add(a); }", integer(edge));
        }
        assertAgree("public static byte[] m(byte[] a) { B b = new B(a); return b.value(); }", bytes(1, 2));
        assertAgree("public static long m(byte[] a) { B b = new B(a); return b.value().length; }", bytes(1, 2));
        assertAgree("public static BigInteger m(BigInteger a) { var w = new W(new N(a), new B(new byte[]{1})); return w.n().value(); }", integer(3));
        assertAgree("public static byte[] m(BigInteger a) { var w = new W(new N(a), new B(new byte[]{1})); return w.b().value(); }", integer(3));
    }

    /** {@code new} of a ledger hash is its bytes; the type's constructor checks run on the JVM only, and a value passes them. */
    @Test
    void aLedgerHashIsItsBytes() {
        String record = "public record K(org.julclang.ledger.PubKeyHash h, byte[] b) { } ";
        assertAgree(record + "public static byte[] m(byte[] a) { var k = new K(new org.julclang.ledger.PubKeyHash(a), a); return k.b(); }", bytes(1, 2));
    }

    @Test
    void aBigIntegerOfAString() {
        assertAgree("public static BigInteger m() { return new BigInteger(\"123\"); }");
        assertAgree("public static BigInteger m() { return new BigInteger(\"-5\"); }");
        assertAgree("public static BigInteger m() { return new BigInteger(\"123456789012345678901234567890\"); }");
        assertAgree("public static BigInteger m(BigInteger a) { return new BigInteger(\"5\").add(a); }", integer(2));
    }

    @Test
    void theLengthOfABytesRead() {
        assertAgree("public static long m(byte[] a) { return a.length; }", bytes());
        assertAgree("public static long m(byte[] a) { return a.length; }", bytes(1, 2, 3));
        assertAgree("public static long m() { return new byte[]{1, 2}.length; }");
        assertAgree("public record R(byte[] a) { } public static long m(BigInteger x) { var r = new R(new byte[]{9}); return r.a().length; }", integer(0));
    }

    // --- Scoping ---

    @Test
    void aParameterShadowsAComponent() {
        assertAgree("public record R(BigInteger a) { } public static BigInteger m(BigInteger a) { var r = new R(a.add(a)); return r.a().add(a); }", integer(3));
        assertAgree("public static BigInteger m(P p, BigInteger x) { return p.x().add(x); }", constr(0, integer(1), integer(2)), integer(5));
        assertAgree("public static BigInteger m(BigInteger x, BigInteger y) { var p = new P(y, x); return p.x().subtract(x); }", integer(1), integer(5));
        assertAgree("public record R(BigInteger a) { static BigInteger a(BigInteger a) { return a; } } public static BigInteger m(BigInteger a) { var r = new R(a); return r.a(); }", integer(3));
    }

    @Test
    void aLocalNamedLikeAComponentOrARecord() {
        assertAgree("public static BigInteger m(BigInteger a) { var x = new P(a, a.add(a)); var y = x.y(); return y; }", integer(3));
        assertJavacAgrees(program("public static BigInteger m(BigInteger a) { var P = new P(a, a); return P.x(); }"), TAGS, integer(3));
        assertAgree("static final BigInteger x = BigInteger.TEN; public static BigInteger m(BigInteger a) { var p = new P(a, x); return p.y(); }", integer(3));
        assertAgree("static final P K = new P(BigInteger.ONE, BigInteger.TWO); public static BigInteger m(BigInteger a) { return K.y().add(a); }", integer(3));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "if (c) { var p = new P(a, a); if (p.x().signum() > 0) { return p.y(); } } return a;",
            "var p = new P(a, a.add(a)); if (c) { var q = new P(p.y(), a); return q.x(); } return p.y();",
            "if (c) { var r = new Three(a, a, a); return r.c(); } else { var r = new P(a, a); return r.y(); }",
    })
    void aRecordInEachBranch(String body) {
        for (boolean c : new boolean[]{false, true}) {
            for (long a : new long[]{-2, 0, 3}) assertAgree("public static BigInteger m(boolean c, BigInteger a) { " + body + " }", bool(c), integer(a));
        }
    }

    // --- Evaluation order ---

    @Test
    void argumentsRunLeftToRight() {
        assertAgree("public static Three m(BigInteger x) { return new Three(said(\"1\", x), said(\"2\", x), said(\"3\", x)); }", integer(3));
        assertAgree("public static S m(BigInteger x) { return new S.B(\"q\", said(\"1\", x)); }", integer(3));
        assertAgree("public static P m(BigInteger x) { return new P(said(\"1\", x), said(\"2\", x)); }", integer(3));
        assertAgree("public static Q m(BigInteger x) { return new Q(new P(said(\"1\", x), said(\"2\", x)), said(\"3\", x).compareTo(BigInteger.ZERO) > 0 ? \"a\" : \"b\"); }", integer(3));
        assertAgree("public static BigInteger m(BigInteger x) { var t = new Tuple2<>(said(\"1\", x), said(\"2\", x)); return said(\"3\", t.second()); }", integer(3));
        assertAgree("public static BigInteger m(BigInteger x) { var t = new Tuple3<>(said(\"1\", x), said(\"2\", x), said(\"3\", x)); return t.third(); }", integer(3));
        assertAgree("public static BigInteger m(BigInteger x) { var n = new N(said(\"1\", x)); return said(\"2\", n.value()); }", integer(3));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "new Three(fail(0), said(\"2\", x), said(\"3\", x))",
            "new Three(said(\"1\", x), fail(0), said(\"3\", x))",
            "new Three(said(\"1\", x), said(\"2\", x), fail(0))",
            "new Three(said(\"1\", x), x.divide(z), said(\"3\", x))",
            "new Three(said(\"1\", x), said(\"2\", x), x.mod(z))",
    })
    void aFailingArgumentStopsWhatFollows(String creation) {
        assertAgree("public static BigInteger m(BigInteger x, BigInteger z) { var r = " + creation + "; return said(\"after\", r.a()); }", integer(3), integer(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "new S.B(\"q\", fail(0))",
            "new Q(new P(said(\"1\", x), fail(0)), \"s\")",
            "new H(new S.A(said(\"1\", x)), new Z(fail(0)))",
            "new L.Cons(said(\"1\", x), new L.Cons(fail(0), new L.Nil()))",
            "new Tuple2<>(said(\"1\", x), fail(0))",
            "new Tuple2<BigInteger, BigInteger>(fail(0), said(\"2\", x))",
            "new Tuple3<>(said(\"1\", x), fail(0), said(\"3\", x))",
            "new N(fail(0))",
    })
    void aFailingArgumentOfEachShape(String creation) {
        assertAgree("public static BigInteger m(BigInteger x) { var r = " + creation + "; return said(\"after\", x); }", integer(3));
    }

    // --- Tuples ---

    @Test
    void tuplesHoldEachComponentByItsType() {
        assertAgree("public static BigInteger m(BigInteger a) { Tuple2<BigInteger, String> t = new Tuple2<>(a, \"s\"); return t.first(); }", integer(1));
        assertAgree("public static String m(BigInteger a) { var t = new Tuple2<BigInteger, String>(a, \"s\"); return t.second(); }", integer(1));
        assertAgree("public static boolean m(BigInteger a) { var t = new Tuple3<>(a, \"s\", a.signum() > 0); return t.third(); }", integer(1));
        assertAgree("public static byte[] m(byte[] a) { var t = new Tuple2<>(a, a); return t.second(); }", bytes(1));
        assertAgree("public static BigInteger m(BigInteger a) { var t = new Tuple2<>(new P(a, a), a); return t.first().y(); }", integer(1));
        assertAgree("public static BigInteger m(BigInteger a) { var t = new Tuple2<>(JulcList.of(a), a); return t.second(); }", integer(1));
        assertAgree("public static PlutusData m(PlutusData d) { var t = new Tuple2<>(Builtins.tailList(Builtins.unListData(d)), BigInteger.ONE); return t.first(); }", list(1, 2));
        assertAgree("public static BigInteger m(BigInteger a) { Tuple2<BigInteger, BigInteger> t = pair(a); return t.second(); } static Tuple2<BigInteger, BigInteger> pair(BigInteger a) { return new Tuple2<>(a, a.add(a)); }", integer(2));
    }

    /** A tuple's {@code first} on javac is the element javac typed, so a list element reads as its own type. */
    @Test
    void aTupleOfAListReadsItsElements() {
        assertAgree("public static BigInteger m(BigInteger a) { JulcList<BigInteger> l = JulcList.of(a); var t = new Tuple2<>(l, a); return t.first().head().add(a); }", integer(2));
    }

    // --- T33 and spellings: a name is what javac bound ---

    private static final String BIG = "import java.math.BigInteger;\n";

    @Test
    void aUserTypeNamedLikeAnApiType() {
        assertJavacAgrees(BIG + "public class Case { public record Tuple2(BigInteger only) { } public static BigInteger m(BigInteger a) { var t = new Tuple2(a); return t.only(); } }", integer(1));
        assertJavacAgrees(BIG + "public class Case { public record PubKeyHash(BigInteger n, BigInteger m) { } public static PubKeyHash m(BigInteger a) { return new PubKeyHash(a, a); } }", integer(1));
        assertJavacAgrees(BIG + "public class Case { public record Value(BigInteger n) { } public static BigInteger m(BigInteger a) { var v = new Value(a); return v.n(); } }", integer(1));
        assertJavacAgrees(BIG + "public class Case { public record Rational(BigInteger n) { } public static Rational m(BigInteger a) { return new Rational(a); } }", integer(1));
        assertJavacAgrees("public class Case { public record BigInteger(String s) { } public static String m() { var b = new BigInteger(\"5\"); return b.s(); } }");
    }

    /** D7: the annotation is JuLC's by identity, however it is written, and a user's of that name is not it. */
    @Test
    void aNewTypeIsItsAnnotationsIdentity() {
        String written = BIG + "import org.julclang.stdlib.annotation.NewType;\npublic class Case { @NewType() public record N(BigInteger value) { } public static BigInteger m(BigInteger a) { var n = new N(a); return n.value(); } }";
        assertJavacAgrees(written, integer(5));
        String qualified = BIG + "public class Case { @org.julclang.stdlib.annotation.NewType public record N(BigInteger value) { } public static BigInteger m(BigInteger a) { var n = new N(a); return n.value(); } }";
        assertJavacAgrees(qualified, integer(5));
        String own = BIG + "public class Case { @interface NewType { } @NewType public record N(BigInteger value) { } public static N m(BigInteger a) { return new N(a); } }";
        JAVAC_RUNNER.run(new DiffCase("own", own, "Case", "m", List.of(integer(5)), TagTable.EMPTY, null)).results().values().forEach(result ->
                assertTrue(result.uplc().toString().contains("JULC0084"), result.uplc()::toString));
    }
}
