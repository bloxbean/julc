package org.julclang.compiler.frontend.javac.lower;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerFrontend;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.frontend.javac.NotPorted;
import org.julclang.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Object creation and member reads: each program compiles to the reference's bytes at every level, with and without a source
 * map, and a rejection is the reference's. What these evaluate to against the JVM is {@code Adr068RecordsHarnessTest}'s; this
 * is I1 and I2a on one screen. What stops is {@code JavacStopsTest}'s.
 */
class JavacRecordsTest {

    private static final String IMPORTS = """
            import java.math.BigInteger;
            import java.util.Optional;
            import org.julclang.core.PlutusData;
            import org.julclang.core.types.AssetEntry;
            import org.julclang.core.types.JulcArray;
            import org.julclang.core.types.JulcList;
            import org.julclang.core.types.JulcMap;
            import org.julclang.core.types.JulcValue;
            import org.julclang.core.types.Tuple2;
            import org.julclang.core.types.Tuple3;
            import org.julclang.ledger.Address;
            import org.julclang.ledger.Credential;
            import org.julclang.ledger.OutputDatum;
            import org.julclang.ledger.PolicyId;
            import org.julclang.ledger.PubKeyHash;
            import org.julclang.ledger.TokenName;
            import org.julclang.ledger.TxId;
            import org.julclang.ledger.TxOutRef;
            import org.julclang.ledger.Value;
            import org.julclang.stdlib.Builtins;
            import org.julclang.stdlib.annotation.NewType;
            """;

    /** A sum whose tags follow its members, another whose tags follow {@code permits}, and a record of each. */
    private static final String TYPES = """
            record P(BigInteger x, BigInteger y) { }
            record Q(P p, String s) { }
            record E() { }
            sealed interface S { record A(BigInteger v) implements S { } record B(String s, BigInteger w) implements S { } record C() implements S { } }
            sealed interface T permits Z, Y { }
            record Y(byte[] b) implements T { }
            record Z(BigInteger a) implements T { }
            record H(S s, T t) { }
            sealed interface L { record Nil() implements L { } record Cons(BigInteger h, L t) implements L { } }
            @NewType record N(BigInteger value) { }
            @NewType record B(byte[] value) { }
            """;

    private static String source(String members) {
        return IMPORTS + "class Main {\n" + TYPES + members + "\n}\n";
    }

    private static void same(String members) {
        LoweringFixture.assertSameMethodAsReference(source(members), "m", StdlibRegistry.defaultRegistry());
    }

    private static void rejected(String members) {
        LoweringFixture.assertSameMethodRejectionAsReference(source(members), "m", StdlibRegistry.defaultRegistry());
    }

    private static CompilerException javac(String members) {
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions().setFrontend(CompilerFrontend.JAVAC));
        return assertThrows(CompilerException.class, () -> compiler.compileMethod(source(members), "m", List.of()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "static P m(BigInteger a) { return new P(a, a); }",
            "static P m(BigInteger a) { return new Main.P(a, a); }",
            "static E m() { return new E(); }",
            "static Q m(BigInteger a) { return new Q(new P(a, a.add(a)), \"s\"); }",
            "static BigInteger m(BigInteger a) { var p = new P(a, a); return p.y(); }",
            "static BigInteger m(BigInteger a) { P p = new P(a, a); return p.x().add(p.y()); }",
            "static String m(BigInteger a) { var q = new Q(new P(a, a), \"s\"); return q.s(); }",
            "static BigInteger m(BigInteger a) { var q = new Q(new P(a, a), \"s\"); return q.p().y(); }",
            "static boolean m(BigInteger a) { return new P(a, a).equals(new P(a, a)); }",
            "static final P K = new P(BigInteger.ONE, BigInteger.TWO); static BigInteger m() { return K.y(); }",
            "static P h(BigInteger a) { return new P(a, a); } static BigInteger m(BigInteger a) { var p = h(a); return p.x(); }",
            "static BigInteger h(P p) { return p.y(); } static BigInteger m(BigInteger a) { return h(new P(a, a)); }",
            "static JulcList<P> m(BigInteger a) { return JulcList.of(new P(a, a), new P(a, a)); }",
            "static BigInteger m(BigInteger a) { JulcList<P> l = JulcList.of(new P(a, a)); return l.head().y(); }",
            "static boolean m(BigInteger a) { JulcList<P> l = JulcList.of(new P(a, a)); return l.contains(new P(a, a)); }",
    })
    void records(String members) {
        same(members);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "record R(BigInteger a, long b, int c, boolean d, Boolean e, String f, byte[] g, PlutusData h) { } static R m(BigInteger x, byte[] y, boolean z, String s, PlutusData d) { return new R(x, 2, 3, z, !z, s, y, d); }",
            "record R(JulcList<BigInteger> a, JulcMap<BigInteger, String> b, Optional<BigInteger> c) { } static R m(JulcList<BigInteger> l, JulcMap<BigInteger, String> o) { return new R(l, o, Optional.of(BigInteger.ONE)); }",
            "record R(N a, B b) { } static R m(BigInteger x, byte[] y) { return new R(new N(x), new B(y)); }",
            "record R(S a, T b, L c) { } static R m(BigInteger x) { return new R(new S.C(), new Z(x), new L.Nil()); }",
            "record R(PubKeyHash a, TokenName b) { } static R m(byte[] x) { return new R(new PubKeyHash(x), new TokenName(x)); }",
            "record R(boolean a) { } static boolean m(boolean x) { var r = new R(x); return r.a(); }",
            "record R(String a) { } static String m(String x) { var r = new R(x); return r.a(); }",
            "record R(byte[] a) { } static byte[] m(byte[] x) { var r = new R(x); return r.a(); }",
            "record R(JulcList<BigInteger> a) { } static BigInteger m(JulcList<BigInteger> x) { var r = new R(x); return r.a().head(); }",
            "record R(JulcMap<BigInteger, BigInteger> a) { } static long m(JulcMap<BigInteger, BigInteger> x) { var r = new R(x); return r.a().size(); }",
    })
    void everyFieldEncoding(String members) {
        same(members);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "static S m(BigInteger a) { return new S.A(a); }",
            "static S m(BigInteger a) { return new S.B(\"s\", a); }",
            "static S m() { return new S.C(); }",
            "static S.B m(BigInteger a) { return new S.B(\"s\", a); }",
            "static Main.S m(BigInteger a) { return new Main.S.A(a); }",
            "static T m(BigInteger a) { return new Z(a); }",
            "static T m(byte[] a) { return new Y(a); }",
            "static H m(BigInteger a) { return new H(new S.A(a), new Z(a)); }",
            "static BigInteger m(BigInteger a) { var b = new S.B(\"s\", a); return b.w(); }",
            "static BigInteger m(BigInteger a) { S s = new S.A(a); if (s instanceof S.A x) { return x.v(); } return a; }",
            "static S m(boolean c, BigInteger a) { return c ? new S.A(a) : new S.C(); }",
            "static L m(BigInteger a) { return new L.Cons(a, new L.Cons(a, new L.Nil())); }",
            "static Credential m(PubKeyHash a) { return new Credential.PubKeyCredential(a); }",
            "static OutputDatum m() { return new OutputDatum.NoOutputDatum(); }",
            "sealed interface G<V> { record I<V>(BigInteger a) implements G<V> { } } static G<String> m(BigInteger a) { return new G.I<>(a); }",
    })
    void variants(String members) {
        same(members);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "static N m(BigInteger a) { return new N(a); }",
            "static BigInteger m(BigInteger a) { var n = new N(a); return n.value(); }",
            "static byte[] m(byte[] a) { var n = new B(a); return n.value(); }",
            "static long m(byte[] a) { var n = new B(a); return n.value().length; }",
            "static PubKeyHash m(byte[] a) { return new PubKeyHash(a); }",
            "static TxId m(byte[] a) { return new TxId(a); }",
            "static PolicyId m(byte[] a) { return new PolicyId(a); }",
            "static BigInteger m() { return new BigInteger(\"123\"); }",
            "static BigInteger m() { return new BigInteger(\"-5\"); }",
            "static BigInteger m() { return new BigInteger(\"123456789012345678901234567890\"); }",
            "static BigInteger m(BigInteger a) { return new BigInteger(\"5\").add(a); }",
            "static Address m(Credential c) { return new Address(c, Optional.empty()); }",
            "static TxOutRef m(byte[] a, BigInteger n) { return new TxOutRef(new TxId(a), n); }",
            "static long m(byte[] a) { return a.length; }",
            "static long m() { return new byte[]{1, 2}.length; }",
            "record R(byte[] a) { } static long m(R r) { return r.a().length; }",
            "static long m(byte[] a) { return (a).length; }",
    })
    void identitiesAndLedgerRecords(String members) {
        same(members);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "static Tuple2<BigInteger, String> m(BigInteger a) { return new Tuple2<BigInteger, String>(a, \"s\"); }",
            "static Tuple2<BigInteger, String> m(BigInteger a) { return new Tuple2<>(a, \"s\"); }",
            "static Tuple3<BigInteger, String, Boolean> m(BigInteger a) { return new Tuple3<>(a, \"s\", true); }",
            "static BigInteger m(BigInteger a) { var t = new Tuple2<>(a, \"s\"); return t.first(); }",
            "static String m(BigInteger a) { var t = new Tuple2<>(a, \"s\"); return t.second(); }",
            "static boolean m(BigInteger a) { var t = new Tuple3<>(a, \"s\", a.signum() > 0); return t.third(); }",
            "static BigInteger m(BigInteger a) { Tuple2<BigInteger, BigInteger> t = new Tuple2<>(a, a); return t.second(); }",
            "static int m(int a) { var t = new Tuple2<>(a, 2); return t.first(); }",
            "static Tuple2 m(PlutusData a) { return new Tuple2(a, a); }",
            "static Tuple2 m(PlutusData a) { return new Tuple2<>(a, a); }",
            "static boolean m(boolean c, PlutusData a) { Tuple2 t = c ? new Tuple2<>(a, a) : (new Tuple2<>(a, a)); return true; }",
            "static long h(Tuple2 t) { return 1; } static long m(PlutusData a) { return h(new Tuple2<>(a, a)); }",
            "static boolean m(PlutusData a) { Tuple2 t = new Tuple2<>(a, a); return true; }",
            "static Tuple2 m(P a, S b) { return new Tuple2(a, b); }",
            "static Tuple2<Boolean, String> m(boolean c) { return c ? new Tuple2<>(c, \"a\") : new Tuple2<>(!c, \"b\"); }",
            "static Tuple2<Boolean, String> m(boolean c) { return (new Tuple2<>(c, \"a\")); }",
            "static Tuple2<BigInteger, BigInteger> h(Tuple2<BigInteger, BigInteger> t) { return t; } static Tuple2<BigInteger, BigInteger> m(BigInteger a) { return h(new Tuple2<>(a, a)); }",
            "static final Tuple2<BigInteger, BigInteger> K = new Tuple2<>(BigInteger.ONE, BigInteger.TWO); static BigInteger m() { return K.second(); }",
            "static BigInteger m(PlutusData d) { var t = new Tuple2<>(Builtins.tailList(Builtins.unListData(d)), BigInteger.ONE); return t.second(); }",
            "static PlutusData m(PlutusData d) { var t = new Tuple2<>(Builtins.tailList(Builtins.unListData(d)), BigInteger.ONE); return t.first(); }",
            "static P m(BigInteger a) { var t = new Tuple2<>(new P(a, a), a); return t.first(); }",
            "static BigInteger m(BigInteger a) { var t = new Tuple2<>(new P(a, a), a); return t.first().y(); }",
            "static boolean m(BigInteger a) { return new Tuple2<>(a, a).equals(new Tuple2<>(a, a)); }",
    })
    void tuples(String members) {
        same(members);
    }

    /** What javac wrote into a constructor is not source: neither {@code super();} nor a compact constructor's assignments. */
    @ParameterizedTest
    @ValueSource(strings = {
            "record R(BigInteger a) { R { } } static R m(BigInteger x) { return new R(x); }",
            "static class D { D() { } } static long m() { return 1; }",
            "; static long m() { return 1; } ;",
    })
    void whatJavacWroteIsNotSource(String members) {
        same(members);
    }

    /** A {@code ;} after a type is no declaration, which javac keeps among a unit's types. */
    @Test
    void aSemicolonAfterAType() {
        LoweringFixture.assertSameMethodAsReference("class Main { static long m() { return 1; } };\n;\nclass Other { }\n", "m");
    }

    /** A static method's parameter of a component's name is the parameter, and a component is read through its accessor alone. */
    @ParameterizedTest
    @ValueSource(strings = {
            "record R(BigInteger a) { } static BigInteger m(R r, BigInteger a) { return r.a().add(a); }",
            "record R(BigInteger a) { } static BigInteger m(BigInteger a) { var r = new R(a.add(a)); return r.a().add(a); }",
            "record R(BigInteger a) { } static R h(BigInteger a) { return new R(a); } static BigInteger m(BigInteger a) { var r = h(a); return r.a(); }",
    })
    void aParameterShadowsAComponent(String members) {
        same(members);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "static BigInteger m(P p) { return p.x; }",
            "static BigInteger m(BigInteger a) { return new P(a, a).x(); }",
            "static BigInteger m(BigInteger a) { return (new P(a, a)).x(); }",
            "static BigInteger m(P p) { return (p).x(); }",
            "static BigInteger m(BigInteger a) { var p = (new P(a, a)); return p.x(); }",
            "static BigInteger m(boolean c, BigInteger a) { var p = c ? new P(a, a) : new P(a, a); return p.x(); }",
            "static BigInteger m(BigInteger a) { return new Tuple2<>(a, a).second(); }",
            "static BigInteger m(BigInteger a) { return new Tuple2<BigInteger, BigInteger>(a, a).second(); }",
            "static BigInteger h(BigInteger a) { return a; } static BigInteger m(P p) { return h(p.x); }",
            "static BigInteger m(BigInteger a) { var n = new N(a); return n.abs(); }",
            "static BigInteger m() { return new BigInteger(\"ff\", 16); }",
            "static BigInteger m(String s) { return new BigInteger(s); }",
            "static BigInteger m() { return new BigInteger((\"5\")); }",
            "static BigInteger m() { return new BigInteger(\"1\" + \"2\"); }",
            "static BigInteger m() { return new BigInteger(\"\"\"\n    5\"\"\"); }",
            "static BigInteger m(byte[] b) { return new BigInteger(b); }",
            "static AssetEntry m(byte[] a, BigInteger c) { return new AssetEntry(a, a, c); }",
            "static PlutusData m(BigInteger a) { return new PlutusData.IntData(a); }",
            "static PlutusData m(byte[] a) { return new PlutusData.BytesData(a); }",
            "static class D { } static boolean m() { var d = new D(); return true; }",
            "static Tuple2 m(BigInteger a) { return new Tuple2(a, a); }",
            "static Tuple2 m(PlutusData d, BigInteger a) { return new Tuple2(d, a); }",
            "static boolean m(BigInteger a) { Tuple2 t = new Tuple2<>(a, a); return true; }",
            "static boolean m(PlutusData d) { Tuple2<PlutusData.ListData, BigInteger> t = new Tuple2<>(Builtins.tailList(Builtins.unListData(d)), BigInteger.ONE); return true; }",
            "static boolean m(boolean c, BigInteger a) { Tuple2 t = c ? new Tuple2<>(a, a) : new Tuple2<>(a, a); return true; }",
            "static boolean m(BigInteger a) { Tuple2 t = (new Tuple2<>(a, a)); return true; }",
            "static Tuple2 m(BigInteger a) { return new Tuple2<>(a, a); }",
            "static Tuple2<PlutusData.ListData, BigInteger> m(PlutusData d) { return new Tuple2<>(Builtins.tailList(Builtins.unListData(d)), BigInteger.ONE); }",
            "static long h(Tuple2 t) { return 1; } static long m(BigInteger a) { return h(new Tuple2<>(a, a)); }",
            "static final Tuple2 K = new Tuple2<>(BigInteger.ONE, BigInteger.TWO); static long m() { return 1; }",
            "record R(JulcValue v) { } static boolean m(PlutusData d) { var r = new R(Builtins.unValueData(d)); return true; }",
            "static boolean m(PlutusData d) { var t = new Tuple2<>(Builtins.unValueData(d), d); return true; }",
            "static boolean m(PlutusData d) { var t = new Tuple2<JulcValue, PlutusData>(Builtins.unValueData(d), d); return true; }",
            "sealed interface W { record I(JulcValue v) implements W { } } static boolean m(PlutusData d) { var r = new W.I(Builtins.unValueData(d)); return true; }",
            "static boolean m(PlutusData d) { var a = JulcArray.of(BigInteger.ONE); var t = new Tuple2<>(a, a); return true; }",
            "record R(BigInteger a) { R { a = BigInteger.ONE; } } static long m() { return 1; }",
            "record R(BigInteger a, BigInteger b) { R(BigInteger a) { this(a, a); } } static R m(BigInteger x) { return new R(x); }",
    })
    void rejections(String members) {
        rejected(members);
    }

    /**
     * A varargs component's Java type is an array. The reference ignores the {@code ...}: it registers one integer, and
     * the same word for {@code byte...} is its rejection. On javac each is the array or the {@code byte} it is.
     */
    @Test
    void aVarargsComponentIsAnArray() {
        LoweringFixture.assertReferenceAcceptsMethod(source("record R(BigInteger... a) { } static long m() { return 1; }"), "m");
        assertTrue(javac("record R(BigInteger... a) { } static long m() { return 1; }").getMessage().contains("Arrays are not supported"));
        assertTrue(javac("record R(long a, byte... b) { } static long m() { return 1; }").getMessage().contains("Unsupported primitive type: byte"));
        assertTrue(javac("@NewType record V(byte... b) { } static long m() { return 1; }").getMessage().contains("field type 'byte' is not supported"));
    }

    /**
     * F909: {@code @Param} is read on the compiled class alone. On a nested type it is no parameter, and nothing can read it:
     * a read through the type is {@code Undefined variable}, as in the reference, so the field has no value to be wrong about.
     */
    @Test
    void aParamOutsideTheCompiledClassIsInert() {
        String source = """
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;
                import org.julclang.stdlib.annotation.*;
                @SpendingValidator
                class V {
                    static class Nested { @Param static BigInteger P; }
                    @Entrypoint static boolean validate(PlutusData d, PlutusData r, PlutusData c) { %s }
                }
                """;
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions().setFrontend(CompilerFrontend.JAVAC));
        assertTrue(compiler.compile(source.formatted("return true;"), List.of()).params().isEmpty());
        var read = assertThrows(CompilerException.class,
                () -> compiler.compile(source.formatted("return Nested.P.signum() > 0;"), List.of()));
        assertTrue(read.getMessage().contains("Undefined variable: Nested"), read::getMessage);
    }
}
