package org.julclang.difftest;

import org.julclang.compiler.CompilerFrontend;
import org.julclang.core.PlutusData;
import org.junit.jupiter.params.ParameterizedTest;
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
}
