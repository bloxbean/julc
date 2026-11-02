package org.julclang.difftest;

import org.julclang.compiler.CompilerFrontend;
import org.julclang.core.PlutusData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Member reads against the reference, in both directions: what it rejects stays rejected though javac knows the type (I2), and
 * what it lowers keeps lowering, through a {@code var}, parentheses and a conditional, and agrees with the JVM. Then the
 * declarations a record compiles past: a declared {@code toPlutusData} or accessor is no script's Data, so it is rejected
 * where it is declared, on both frontends, and a record with neither keeps lowering.
 */
class Adr068ReadsHarnessTest {

    private static final DiffRunner RUNNER = new DiffRunner(configs(), 10_000);

    private static List<DiffConfig> configs() {
        var configs = new ArrayList<>(DiffConfig.allLevels());
        configs.addAll(DiffConfig.allLevels(CompilerFrontend.JAVAC));
        return configs;
    }

    private static String program(String members) {
        return """
                import java.math.BigInteger;
                import java.util.Optional;
                import org.julclang.core.PlutusData;
                import org.julclang.core.types.JulcList;
                import org.julclang.core.types.JulcMap;
                import org.julclang.core.types.Tuple2;
                import org.julclang.ledger.PlutusDataConvertible;
                import org.julclang.ledger.PubKeyHash;
                import org.julclang.ledger.Value;
                import org.julclang.stdlib.Builtins;
                import org.julclang.stdlib.annotation.NewType;

                public class Case {
                    public record P(BigInteger y) { }
                    public record W(P p, JulcList<P> l, Optional<P> o, JulcMap<BigInteger, P> mp) { }
                    %s
                }
                """.formatted(members);
    }

    private static DiffRunner.Report run(String members, PlutusData... args) {
        return RUNNER.run(new DiffCase("reads", program(members), "Case", "m", List.of(args)));
    }

    private static PlutusData i(long value) {
        return new PlutusData.IntData(BigInteger.valueOf(value));
    }

    private static final PlutusData TRUE = new PlutusData.ConstrData(1, List.of());

    private static final String A = "public static BigInteger m(BigInteger a) { ";
    private static final String BOOLEAN = "public static boolean m(BigInteger a) { ";
    private static final String C = "public static BigInteger m(boolean c, BigInteger a) { ";

    /** JULC0055 on both frontends, and the JVM's value where javac's type would have lowered it. */
    @ParameterizedTest
    @ValueSource(strings = {
            BOOLEAN + "var o = Optional.of(a); return o.isPresent(); }",
            BOOLEAN + "var o = (Optional.of(a)); return o.isPresent(); }",
            BOOLEAN + "var o = Optional.of(a); var p = o; return p.isPresent(); }",
            BOOLEAN + "return (Optional.of(a)).isPresent(); }",
            A + "return (Optional.of(a)).get(); }",
            A + "return Optional.of(a).get().add(a); }",
            A + "return JulcList.of(a).head().add(a); }",
            A + "var q = JulcList.of(a); return q.head().add(a); }",
            A + "var xs = JulcList.of(a, a); var h = xs.head(); return h.add(BigInteger.ONE); }",
            A + "var xs = (JulcList.of(a, a)); return xs.head().add(BigInteger.ONE); }",
            A + "var xs = JulcList.of(JulcList.of(a)); var y = xs.head(); return y.head().add(a); }",
            A + "return (JulcList.of(a)).head().add(a); }",
            A + "return JulcList.of(a).tail().prepend(a).head().add(a); }",
            A + "return JulcList.of(new P(a)).head().y(); }",
            A + "var l = JulcList.of(new P(a)); return l.head().y(); }",
            A + "var x = (new Tuple2<BigInteger, Boolean>(a, true)); return x.first(); }",
            A + "var x = (new P(a)); return x.y(); }",
    })
    void whatTheReferenceRejectsStaysRejected(String members) {
        var report = run(members, i(1));
        report.results().forEach((config, result) -> {
            assertEquals(Verdict.Kind.REJECTED, result.verdict().kind(), report::describe);
            assertTrue(result.uplc().toString().contains("JULC0055"), report::describe);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            C + "var o = c ? Optional.of(a) : Optional.of(a); return o.get(); }",
            "public static boolean m(boolean c, BigInteger a) { return (c ? Optional.of(a) : Optional.of(a)).isPresent(); }",
            C + "var x = c ? new Tuple2<BigInteger, Boolean>(a, true) : new Tuple2<BigInteger, Boolean>(a, false); return x.first(); }",
    })
    void aConditionalReadsTheTerm(String members) {
        var report = run(members, TRUE, i(1));
        report.results().forEach((config, result) -> {
            assertEquals(Verdict.Kind.REJECTED, result.verdict().kind(), report::describe);
            assertTrue(result.uplc().toString().contains("JULC0055"), report::describe);
        });
    }

    private static final PlutusData P_VALUE = new PlutusData.ConstrData(0, List.of(i(2)));
    private static final PlutusData W_VALUE = new PlutusData.ConstrData(0, List.of(P_VALUE, new PlutusData.ListData(List.of(P_VALUE)),
            new PlutusData.ConstrData(0, List.of(P_VALUE)), new PlutusData.MapData(List.of(new PlutusData.Pair(i(1), P_VALUE)))));

    /** The reference keeps a registry method's declared result with its term, so these read a record through it. */
    @ParameterizedTest
    @ValueSource(strings = {
            "var x = (w.l().head()); return x.y();",
            "var x = c ? w.l().head() : w.l().head(); return x.y();",
            "var x = (w.o().get()); return x.y();",
            "var x = c ? w.o().get() : w.o().get(); return x.y();",
            "var x = (w.mp().get(BigInteger.ONE)); return x.y();",
            "var x = c ? w.mp().get(BigInteger.ONE) : w.mp().get(BigInteger.ONE); return x.y();",
            "JulcList<P> l = w.l(); var x = (l.head()); return x.y();",
            "JulcList<P> l = w.l(); var x = l.head(); return x.y();",
            "JulcList<P> l = w.l(); return l.head().y();",
            "var x = w.p(); return x.y();",
            "return w.l().head().y();",
            "return w.o().get().y();",
    })
    void whatTheReferenceLowersKeepsLowering(String body) {
        var report = run("public static BigInteger m(boolean c, W w) { " + body + " }", TRUE, W_VALUE);
        assertTrue(report.all(Verdict.Kind.AGREE), report::describe);
        report.results().values().forEach(result -> assertInstanceOf(Observation.Value.class, result.uplc(), report::describe));
    }

    @ParameterizedTest
    @ValueSource(strings = {"var x = (w.p()); return x.y();", "var x = (w.l()); return x.head().y();", "return (w.l()).head().y();"})
    void parenthesesAroundAnAccessorReadTheTerm(String body) {
        var report = run("public static BigInteger m(boolean c, W w) { " + body + " }", TRUE, W_VALUE);
        report.results().forEach((config, result) -> {
            assertEquals(Verdict.Kind.REJECTED, result.verdict().kind(), report::describe);
            assertTrue(result.uplc().toString().contains("JULC0055"), report::describe);
        });
    }

    // --- A builtin value in Data: the follow-ups' rows, which fail closed ---

    private static final PlutusData LIST = new PlutusData.ListData(List.of(i(1), i(2)));
    private static final String UN_LIST = "Builtins.unListData(d)";

    /** The JVM returns and the script fails, at every level: pinned, so that a fix or a value here shows. */
    @ParameterizedTest
    @ValueSource(strings = {
            "public static boolean m(PlutusData d) { PlutusData p = " + UN_LIST + "; return Builtins.equalsData(p, d); }",
            "public static boolean m(PlutusData d) { var p = " + UN_LIST + "; return Builtins.equalsData(p, d); }",
            "public static boolean m(PlutusData d) { PlutusData.ListData p = " + UN_LIST + "; return Builtins.equalsData(p, d); }",
            "public static boolean m(PlutusData d) { return Builtins.equalsData(" + UN_LIST + ", d); }",
            "static PlutusData w(PlutusData d) { return " + UN_LIST + "; } public static boolean m(PlutusData d) { return Builtins.equalsData(w(d), d); }",
            "public static byte[] m(PlutusData d) { PlutusData p = " + UN_LIST + "; return Builtins.serialiseData(p); }",
            "public static PlutusData m(PlutusData d) { PlutusData p = " + UN_LIST + "; return Builtins.unListData(p); }",
    })
    void aBuiltinListInDataFailsClosed(String members) {
        var report = run(members, LIST);
        report.results().forEach((config, result) -> {
            if (config.frontend() == CompilerFrontend.JAVAC) assertInstanceOf(Observation.Failure.class, result.uplc(), report::describe);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "public static byte[] m(PlutusData d) { return Builtins.encodeUtf8(d); }",
            "public static boolean m(PlutusData d) { return Builtins.nullList(d); }",
            "public static PlutusData m(PlutusData a) { return Builtins.mkCons(a, a); }",
    })
    void dataWhereABuiltinTakesAListOrTextFailsClosed(String members) {
        var report = run(members, i(1));
        report.results().forEach((config, result) -> {
            if (config.frontend() == CompilerFrontend.JAVAC) assertInstanceOf(Observation.Failure.class, result.uplc(), report::describe);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "public static boolean m(PlutusData d) { PlutusData p = " + UN_LIST + "; return Builtins.nullList(p); }",
            "public static PlutusData m(PlutusData d) { PlutusData p = " + UN_LIST + "; return Builtins.headList(p); }",
    })
    void aListInDataStaysLegitimate(String members) {
        var report = run(members, LIST);
        assertTrue(report.all(Verdict.Kind.AGREE), report::describe);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "public record R(PlutusData d) { } static PlutusData.ListData h(PlutusData z) { return Builtins.constrFields(z); } public static R m(PlutusData z) { return new R(h(z)); }",
    })
    void aHelpersReturnIsAFollowUp(String members) {
        var report = run(members, new PlutusData.ConstrData(0, List.of(i(1))));
        report.results().forEach((config, result) -> {
            if (config.frontend() == CompilerFrontend.JAVAC) assertInstanceOf(Observation.Failure.class, result.uplc(), report::describe);
        });
    }

    // --- Declarations ---

    private static final String DECLARES_DATA = "public record D(PlutusData i) implements PlutusDataConvertible { public PlutusData toPlutusData() { return i; } } ";
    private static final String DECLARES_UNIT = "public record U(BigInteger a) implements PlutusDataConvertible { public PlutusData toPlutusData() { return PlutusData.UNIT; } } ";
    private static final String DECLARES_ACCESSOR = "public record R(BigInteger a) { public BigInteger a() { return BigInteger.TEN; } } ";
    private static final String SWAPS = "public record Pr(BigInteger a, BigInteger b) { public BigInteger a() { return b; } public BigInteger b() { return a; } } ";

    static Stream<String> declarations() {
        return Stream.of(
                DECLARES_DATA + "public static D m(PlutusData d) { return new D(d); }",
                DECLARES_DATA + "public static PlutusData m(PlutusData d) { JulcList<D> l = JulcList.of(new D(d)); return l.toPlutusData(); }",
                DECLARES_DATA + "public static PlutusData m(PlutusData d) { JulcMap<BigInteger, D> l = JulcMap.empty(); return l.insert(BigInteger.ONE, new D(d)).toPlutusData(); }",
                DECLARES_UNIT + "public static U m(BigInteger a) { return new U(a); }",
                DECLARES_UNIT + "public record H(U u) { } public static H m(BigInteger a) { return new H(new U(a)); }",
                DECLARES_ACCESSOR + "public static R m(BigInteger a) { return new R(a); }",
                SWAPS + "public static Pr m(BigInteger a) { return new Pr(a, a.add(a)); }",
                SWAPS + "public record H(Pr p) { } public static H m(BigInteger a) { return new H(new Pr(a, a)); }",
                "@NewType public record N(BigInteger value) { public BigInteger value() { return BigInteger.TEN; } } public static N m(BigInteger a) { return new N(a); }",
                DECLARES_ACCESSOR + "public static BigInteger m(BigInteger a) { var r = new R(a); return r.a(); }");
    }

    @ParameterizedTest
    @MethodSource("declarations")
    void aDeclarationThatCompilesPastItsBodyIsRejected(String members) {
        var report = run(members, i(1));
        assertEquals(8, report.results().size(), report::describe);
        report.results().forEach((config, result) -> {
            assertEquals(Verdict.Kind.REJECTED, result.verdict().kind(), report::describe);
            assertTrue(result.uplc().toString().contains("JULC0101"), report::describe);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "public record R(BigInteger a) { } public static R m(BigInteger a) { return new R(a); }",
            "public record R(BigInteger a) { } public static BigInteger m(BigInteger a) { return new R(a).equals(new R(a)) ? a : BigInteger.ZERO; }",
            "public record R(BigInteger a, BigInteger b) { } public static BigInteger m(BigInteger a) { var r = new R(a, a); return r.b(); }",
            "@NewType public record N(BigInteger value) { } public static BigInteger m(BigInteger a) { var n = new N(a); return n.value(); }",
    })
    void aRecordWithNeitherKeepsLowering(String members) {
        var report = run(members, i(1));
        assertTrue(report.all(Verdict.Kind.AGREE), report::describe);
    }

    // --- What no tree says: a record's members that javac generates or inherits ---

    /** Asserts every run of {@code frontend} is rejected with {@code code}. */
    private static void assertRejects(DiffRunner.Report report, CompilerFrontend frontend, String code) {
        report.results().forEach((config, result) -> {
            if (config.frontend() != frontend) return;
            assertEquals(Verdict.Kind.REJECTED, result.verdict().kind(), report::describe);
            assertTrue(result.uplc().toString().contains(code), report::describe);
        });
    }

    /** Asserts every run of {@code frontend} evaluated and agrees with the JVM: a rejection does not pass this. */
    private static void assertAgrees(DiffRunner.Report report, CompilerFrontend frontend) {
        assertEquals(8, report.results().size(), report::describe);
        report.results().forEach((config, result) -> {
            if (config.frontend() == frontend) assertEquals(Verdict.Kind.AGREE, result.verdict().kind(), report::describe);
        });
    }

    private static final TagTable SEALED = new TagTable(List.of(new TagTable.Entry("Case$S$A", "Case$S", 0),
            new TagTable.Entry("Case$S$B", "Case$S", 1)));

    private static DiffRunner.Report tagged(String members, PlutusData... args) {
        var tags = members.contains("sealed interface S") ? SEALED : TagTable.EMPTY;
        return RUNNER.run(new DiffCase("reads", program(members), "Case", "m", List.of(args), tags, null));
    }

    private static final String GENERATED_DATA = "public record X(PlutusData toPlutusData) implements PlutusDataConvertible { } ";

    static List<String> generatedToPlutusData() {
        return List.of(
                GENERATED_DATA + "public static X m(PlutusData d) { return new X(d); }",
                GENERATED_DATA + "public static PlutusData m(PlutusData d) { JulcList<X> l = JulcList.of(new X(d)); return l.toPlutusData(); }",
                GENERATED_DATA + "public static PlutusData m(PlutusData d) { JulcMap<BigInteger, X> l = JulcMap.empty(); return l.insert(BigInteger.ONE, new X(d)).toPlutusData(); }",
                GENERATED_DATA + "public record O(X w) { } public static O m(PlutusData d) { return new O(new X(d)); }",
                GENERATED_DATA + "public static Optional<X> m(PlutusData d) { return Optional.of(new X(d)); }",
                GENERATED_DATA + "public static boolean m(PlutusData d) { return new X(d).equals(new X(d)); }",
                GENERATED_DATA + "public static PlutusData m(PlutusData d) { var w = new X(d); return w.toPlutusData(); }",
                GENERATED_DATA + "public static PlutusData m(PlutusData d) { return d; }",
                "public sealed interface S { record A(PlutusData toPlutusData) implements S, PlutusDataConvertible { } record B() implements S { } } public static S m(PlutusData d) { return new S.A(d); }",
                "public sealed interface S extends PlutusDataConvertible { record A(PlutusData toPlutusData) implements S { } record B(PlutusData b, PlutusData toPlutusData) implements S { } } public static S m(PlutusData d) { return new S.A(d); }",
                "public record X(PlutusData.IntData toPlutusData) implements PlutusDataConvertible { } public static X m(PlutusData d) { return new X(new PlutusData.IntData(BigInteger.ONE)); }",
                "public record X(PlutusData toPlutusData, BigInteger b) implements PlutusDataConvertible { } public static X m(PlutusData d) { return new X(d, BigInteger.ONE); }",
                "public record X(PlutusData toPlutusData) implements org.julclang.core.ToPlutusData { } public static PlutusData m(PlutusData d) { JulcList<X> l = JulcList.of(new X(d)); return l.toPlutusData(); }",
                "public interface D extends PlutusDataConvertible { } public record X(PlutusData toPlutusData) implements D { } public static X m(PlutusData d) { return new X(d); }",
                "public static PlutusData m(PlutusData d) { record X(PlutusData toPlutusData) implements PlutusDataConvertible { } return new X(d).toPlutusData(); }");
    }

    @ParameterizedTest
    @MethodSource("generatedToPlutusData")
    void aGeneratedToPlutusDataIsRejectedAtTheRecord(String members) {
        var report = tagged(members, i(5));
        assertEquals(8, report.results().size(), report::describe);
        assertRejects(report, CompilerFrontend.JAVAC, "JULC0101");
        assertRejects(report, CompilerFrontend.JAVAPARSER, "JULC0101");
    }

    /** The reference reads these through the accessor, and the rule is the javac frontend's: its table says so. */
    @ParameterizedTest
    @ValueSource(strings = {
            "public interface I { BigInteger a(); } public record X(BigInteger a) implements I { } public static BigInteger m(BigInteger v) { var w = new X(v); return w.a(); }",
            "public interface I { default BigInteger a() { return BigInteger.ONE; } } public record X(BigInteger a) implements I { } public static BigInteger m(BigInteger v) { var w = new X(v); return w.a(); }",
            "public interface I<T> { T a(); } public record X(BigInteger a) implements I<BigInteger> { } public static BigInteger m(BigInteger v) { var w = new X(v); return w.a(); }",
            "public sealed interface S { BigInteger a(); record A(BigInteger a) implements S { } record B(BigInteger a) implements S { } } public static BigInteger m(BigInteger v) { var x = new S.A(v); return x.a(); }",
    })
    void aGeneratedAccessorThatImplementsAnInterfaceIsRejected(String members) {
        var report = tagged(members, i(5));
        assertRejects(report, CompilerFrontend.JAVAC, "JULC0101");
        assertAgrees(report, CompilerFrontend.JAVAPARSER);
    }

    @Test
    void aParameterOfAGeneratedToPlutusDataIsRejected() {
        var report = run(GENERATED_DATA + "public static X m(X d) { return d; }", new PlutusData.ConstrData(0, List.of(i(5))));
        assertRejects(report, CompilerFrontend.JAVAC, "JULC0101");
        assertRejects(report, CompilerFrontend.JAVAPARSER, "JULC0101");
    }

    @Test
    void anInterfaceAtAnyDepthIsRead() {
        var report = run("public interface B { BigInteger a(); } public interface I extends B { } public record X(BigInteger a) implements I { }"
                + " public static BigInteger m(BigInteger v) { var w = new X(v); return w.a(); }", i(5));
        assertRejects(report, CompilerFrontend.JAVAC, "JULC0101");
    }

    /** Neither names a method of an interface, so each keeps lowering, on both frontends where the reference lowers it. */
    @ParameterizedTest
    @ValueSource(strings = {
            "public interface I { BigInteger b(); } public record X(BigInteger a) { } public static BigInteger m(BigInteger v) { var w = new X(v); return w.a(); }",
            "public interface I { BigInteger b(BigInteger x); } public record X(BigInteger b) implements Marker { } public interface Marker { } public static BigInteger m(BigInteger v) { var w = new X(v); return w.b(); }",
            "public sealed interface S { record A(BigInteger a) implements S { } record B(BigInteger b) implements S { } } public static BigInteger m(BigInteger v) { var x = new S.A(v); return x.a(); }",
            "public record X(BigInteger a) implements Comparable<X> { public int compareTo(X o) { return 0; } } public static BigInteger m(BigInteger v) { var w = new X(v); return w.a(); }",
    })
    void aRecordWhoseAccessorsImplementNothingKeepsLowering(String members) {
        var report = tagged(members, i(5));
        assertAgrees(report, CompilerFrontend.JAVAC);
        assertAgrees(report, CompilerFrontend.JAVAPARSER);
    }

    // --- Every member a type decides is read through CallTargets.seen ---

    private static PlutusData list(long... values) {
        var elements = new ArrayList<PlutusData>();
        for (long value : values) elements.add(i(value));
        return new PlutusData.ListData(elements);
    }

    private static PlutusData map(long key, long value) {
        return new PlutusData.MapData(List.of(new PlutusData.Pair(i(key), i(value))));
    }

    private static PlutusData bytes(int... values) {
        var bytes = new byte[values.length];
        for (int k = 0; k < values.length; k++) bytes[k] = (byte) values[k];
        return new PlutusData.BytesData(bytes);
    }

    private static final String EQUALS_V = "var y = %s; return y.equals(v);";

    private static Stream<Arguments> equalsOnAValueWithNoEquals() {
        var rows = new ArrayList<Arguments>();
        record Sample(String parameter, PlutusData argument, String map) {
        }
        var values = List.of(new Sample("boolean", new PlutusData.ConstrData(1, List.of()), "Boolean"),
                new Sample("JulcList<BigInteger>", list(1, 2), "JulcList<BigInteger>"),
                new Sample("JulcMap<BigInteger,BigInteger>", map(1, 2), "JulcMap<BigInteger,BigInteger>"));
        for (var value : values) {
            String mapOf = "JulcMap.<BigInteger," + value.map() + ">empty().insert(BigInteger.ONE, v)";
            for (String source : List.of("JulcList.of(v).head()", "JulcList.of(v).get(0)", "JulcList.of(v, v).tail().head()",
                    "JulcList.of(v).reverse().head()", "JulcList.of(v).take(1).head()", "JulcList.of(v).drop(0).head()",
                    "JulcList.of(v).prepend(v).head()", "JulcList.of(v).concat(JulcList.of(v)).head()", mapOf + ".get(BigInteger.ONE)",
                    mapOf + ".values().head()")) {
                for (String body : List.of(EQUALS_V, "var y = (%s); return y.equals(v);", "var z = %s; var y = z; return y.equals(v);",
                        "return (%s).equals(v);")) {
                    rows.add(Arguments.of("public static boolean m(" + value.parameter() + " v) { " + body.formatted(source) + " }",
                            value.argument()));
                }
            }
        }
        return rows.stream();
    }

    /**
     * The term holds a builtin value, which {@code EqualsData} fails on, and the registry has no {@code equals} for it. The
     * reference holds the same read undecoded, as Data, so it evaluates: this is the javac frontend's narrower answer.
     */
    @ParameterizedTest
    @MethodSource("equalsOnAValueWithNoEquals")
    void equalsOnADecodedValueWithNoEqualsIsAnUnresolvedMember(String members, PlutusData argument) {
        var report = run(members, argument);
        assertRejects(report, CompilerFrontend.JAVAC, "JULC0055");
    }

    private static Stream<Arguments> equalsThatDispatches() {
        return Stream.of(
                Arguments.of("public static boolean m(BigInteger v) { " + EQUALS_V.formatted("JulcList.of(v).head()") + " }", i(5)),
                Arguments.of("public static boolean m(String v) { " + EQUALS_V.formatted("JulcList.of(v).head()") + " }", bytes(0x78)),
                Arguments.of("public static boolean m(PubKeyHash v) { " + EQUALS_V.formatted("JulcList.of(v).head()") + " }", bytes(1)),
                Arguments.of("public static boolean m(P v) { " + EQUALS_V.formatted("JulcList.of(v).head()") + " }",
                        new PlutusData.ConstrData(0, List.of(i(1)))),
                Arguments.of("public static boolean m(PlutusData v) { " + EQUALS_V.formatted("JulcList.of(v).head()") + " }", i(5)),
                Arguments.of("public static boolean m(Optional<BigInteger> v) { " + EQUALS_V.formatted("JulcList.of(v).head()") + " }",
                        new PlutusData.ConstrData(0, List.of(i(5)))),
                Arguments.of("public static boolean m(Tuple2<BigInteger, BigInteger> v) { " + EQUALS_V.formatted("JulcList.of(v).head()") + " }",
                        new PlutusData.ConstrData(0, List.of(i(1), i(2)))),
                Arguments.of("public static boolean m(Value v) { " + EQUALS_V.formatted("JulcList.of(v).head()") + " }",
                        new PlutusData.MapData(List.of(new PlutusData.Pair(bytes(), new PlutusData.MapData(
                                List.of(new PlutusData.Pair(bytes(), i(5)))))))),
                Arguments.of("public static boolean m(BigInteger v) { return (JulcList.of(v).head()).equals(v); }", i(5)),
                Arguments.of("public static boolean m(BigInteger v) { var y = v; return v.equals(y); }", i(5)));
    }

    @ParameterizedTest
    @MethodSource("equalsThatDispatches")
    void equalsOnADecodedValueKeepsItsRow(String members, PlutusData argument) {
        var report = run(members, argument);
        assertAgrees(report, CompilerFrontend.JAVAC);
        assertAgrees(report, CompilerFrontend.JAVAPARSER);
    }

    private static Stream<Arguments> membersTheReferenceDoesNotSee() {
        return Stream.of(
                Arguments.of("public static BigInteger m(byte[] v) { var y = JulcList.of(v).head(); return BigInteger.valueOf(y.length); }", bytes(1)),
                Arguments.of("public static BigInteger m(byte[] v) { var y = (JulcList.of(v).head()); return BigInteger.valueOf(y.length); }", bytes(1)),
                Arguments.of("public static BigInteger m(byte[] v) { var z = JulcList.of(v).head(); var y = z; return BigInteger.valueOf(y.length); }", bytes(1)),
                Arguments.of("public static BigInteger m(byte[] v) { return BigInteger.valueOf(JulcList.of(v).head().length); }", bytes(1)),
                Arguments.of("public static BigInteger m(byte[] v) { return BigInteger.valueOf(JulcList.of(v).get(0).length); }", bytes(1)),
                Arguments.of("public static BigInteger m(byte[] v) { return BigInteger.valueOf(JulcList.of(v, v).tail().head().length); }", bytes(1)),
                Arguments.of("public static boolean m(BigInteger v) { var y = JulcList.of(v).head(); return y.compareTo(v) < 0; }", i(5)),
                Arguments.of("public static boolean m(BigInteger v) { var y = (JulcList.of(v).head()); return y.compareTo(v) >= 0; }", i(5)),
                Arguments.of("public static boolean m(BigInteger v) { var z = JulcList.of(v).head(); var y = z; return y.compareTo(v) == 0; }", i(5)),
                Arguments.of("public static boolean m(BigInteger v) { var y = JulcList.of(v).head(); return 0 > y.compareTo(v); }", i(5)),
                Arguments.of("public static boolean m(BigInteger v) { return JulcList.of(v).head().compareTo(v) < 0; }", i(5)),
                Arguments.of("public static boolean m(BigInteger v) { return JulcList.of(v, v).tail().head().compareTo(v) != 0; }", i(5)),
                Arguments.of("public static BigInteger m(BigInteger v) { var y = JulcList.of(v).head(); return BigInteger.valueOf(y.compareTo(v)); }", i(5)),
                Arguments.of("public static byte[] m(PubKeyHash h) { return JulcList.of(h).head().hash(); }", bytes(1, 2)));
    }

    @ParameterizedTest
    @MethodSource("membersTheReferenceDoesNotSee")
    void aMemberOfAReadTheReferenceDoesNotTypeIsUnresolved(String members, PlutusData argument) {
        var report = run(members, argument);
        assertEquals(8, report.results().size(), report::describe);
        assertRejects(report, CompilerFrontend.JAVAC, "JULC0055");
        assertRejects(report, CompilerFrontend.JAVAPARSER, "JULC0055");
    }

    private static Stream<Arguments> membersTheReferenceSees() {
        return Stream.of(
                Arguments.of("public static BigInteger m(JulcList<byte[]> v) { var y = v.head(); return BigInteger.valueOf(y.length); }", new PlutusData.ListData(List.of(bytes(1)))),
                Arguments.of("public static BigInteger m(JulcList<byte[]> v) { return BigInteger.valueOf(v.head().length); }", new PlutusData.ListData(List.of(bytes(1)))),
                Arguments.of("static byte[] h(byte[] x) { return x; } public static BigInteger m(byte[] v) { return BigInteger.valueOf(h(v).length); }", bytes(1)),
                Arguments.of("public static BigInteger m(byte[] v) { return BigInteger.valueOf((v).length); }", bytes(1, 2)),
                Arguments.of("public static BigInteger m(byte[] v) { return BigInteger.valueOf(Builtins.appendByteString(v, v).length); }", bytes(1)),
                Arguments.of("public record R(byte[] b) { } public static BigInteger m(byte[] v) { var r = new R(v); return BigInteger.valueOf(r.b().length); }", bytes(1)),
                Arguments.of("static final byte[] K = new byte[]{1, 2}; public static BigInteger m(byte[] v) { return BigInteger.valueOf(K.length); }", bytes(1)),
                Arguments.of("public static BigInteger m(String s) { return BigInteger.valueOf(s.getBytes().length); }", bytes(0x78)),
                Arguments.of("public static boolean m(JulcList<BigInteger> v) { var y = v.head(); return y.compareTo(BigInteger.ONE) < 0; }", list(5)),
                Arguments.of("public static boolean m(BigInteger v) { return BigInteger.ONE.compareTo(v) < 0; }", i(5)),
                Arguments.of("public static boolean m(BigInteger v) { return BigInteger.valueOf(1).compareTo(v) < 0; }", i(5)),
                Arguments.of("public static boolean m(BigInteger v) { return v.add(v).compareTo(v) < 0; }", i(5)),
                Arguments.of("public static boolean m(BigInteger v) { return v.abs().compareTo(v) < 0; }", i(5)),
                Arguments.of("static BigInteger h(BigInteger x) { return x; } public static boolean m(BigInteger v) { return h(v).compareTo(v) < 0; }", i(5)),
                Arguments.of("public static boolean m(BigInteger v) { return (v).compareTo(v) < 0; }", i(5)),
                Arguments.of("public record R(BigInteger b) { } public static boolean m(BigInteger v) { var r = new R(v); return r.b().compareTo(BigInteger.ONE) < 0; }", i(1)),
                Arguments.of("public static boolean m(JulcMap<BigInteger, BigInteger> o) { return o.get(BigInteger.ONE).compareTo(BigInteger.ONE) < 0; }", map(1, 1)),
                Arguments.of("public static boolean m(PlutusData o) { return Builtins.unIData(o).compareTo(BigInteger.ONE) < 0; }", i(1)),
                Arguments.of("public record R(PubKeyHash h) { } public static byte[] m(PubKeyHash h) { var r = new R(h); return r.h().hash(); }", bytes(1, 2)));
    }

    @ParameterizedTest
    @MethodSource("membersTheReferenceSees")
    void aMemberOfAReadTheReferenceTypesKeepsLowering(String members, PlutusData argument) {
        var report = run(members, argument);
        assertAgrees(report, CompilerFrontend.JAVAC);
        assertAgrees(report, CompilerFrontend.JAVAPARSER);
    }
}
