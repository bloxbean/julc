package org.julclang.difftest;

import org.julclang.compiler.CompilerFrontend;
import org.julclang.core.PlutusData;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-068 M5, the review of M5a (F1, F2): a conditional or a {@code switch} whose arms do not have one Java type is
 * {@code JULC0012} on {@code frontend=JAVAC}, wherever it stands: bound to a {@code var}, nested, in a lambda, a list, a map, an
 * {@code Optional}, a diamond {@code Tuple2} or {@code Tuple3}, a comparison. The programs are the review's, each run against the
 * JVM at NONE, BASELINE, PV11_SAFE and PV11_COSTED for every input: the JVM answers, and {@code JAVAC} rejects at every level.
 * The reference's answers are the review's (it fails or answers {@code true} where Java answers {@code false} on these); a
 * control of arms of one type agrees with the JVM on both frontends.
 */
class Adr068MixedArmsHarnessTest {

    private static final DiffRunner BOTH = new DiffRunner(configs(), 20_000);

    private static List<DiffConfig> configs() {
        var configs = new ArrayList<>(DiffConfig.allLevels());
        configs.addAll(DiffConfig.allLevels(CompilerFrontend.JAVAC));
        return configs;
    }

    static final String HEADER = """
            import java.math.BigInteger;
            import java.util.Optional;
            import org.julclang.core.PlutusData;
            import org.julclang.core.types.JulcList;
            import org.julclang.core.types.JulcMap;
            import org.julclang.core.types.Tuple2;
            import org.julclang.core.types.Tuple3;
            import org.julclang.stdlib.Builtins;
            import org.julclang.stdlib.annotation.NewType;
            import org.julclang.ledger.PubKeyHash;
            import org.julclang.ledger.TxId;

            public class Case {
            """;

    static final String S = "sealed interface S permits A, B { } record A(BigInteger v) implements S { } record B(BigInteger v) implements S { }\n";
    static final TagTable TAGS = new TagTable(List.of(new TagTable.Entry("Case$A", "Case$S", 0), new TagTable.Entry("Case$B", "Case$S", 1)));

    static PlutusData i(long v) { return new PlutusData.IntData(BigInteger.valueOf(v)); }
    static PlutusData b(boolean v) { return new PlutusData.ConstrData(v ? 1 : 0, List.of()); }
    static PlutusData bs(int... v) { var a = new byte[v.length]; for (int k = 0; k < v.length; k++) a[k] = (byte) v[k]; return new PlutusData.BytesData(a); }

    record P(String name, String members, List<List<PlutusData>> inputs, TagTable tags) { }

    static final List<List<PlutusData>> AC = List.of(List.of(i(1), b(true)), List.of(i(1), b(false)), List.of(i(-3), b(true)), List.of(i(-3), b(false)));
    static final List<List<PlutusData>> ACD = List.of(List.of(i(1), b(true), b(true)), List.of(i(1), b(true), b(false)),
            List.of(i(1), b(false), b(true)), List.of(i(1), b(false), b(false)));
    static final List<List<PlutusData>> ACB = List.of(List.of(i(1), b(true), bs(1, 2)), List.of(i(1), b(false), bs(1, 2)));

    static List<P> mixed() {
        var p = new ArrayList<P>();
        String cd = "Builtins.constrData(0, Builtins.mkCons(Builtins.iData(a), Builtins.mkNilData()))";
        String amt = "@NewType record Amt(BigInteger v) { }\n";
        p.add(new P("S05 s.equals(c?A:B)", S + "public static boolean m(BigInteger a, boolean c) { S s = new A(a); return s.equals(c ? new A(a) : new B(a)); }", AC, TAGS));
        p.add(new P("S06 b.equals(c?1:a)", "public static boolean m(BigInteger a, boolean c) { BigInteger q = a; return q.equals(c ? 1 : a); }", AC, TagTable.EMPTY));
        p.add(new P("S07 (c?1:a).equals(a)", "public static boolean m(BigInteger a, boolean c) { return (c ? 1 : a).equals(a); }", AC, TagTable.EMPTY));
        p.add(new P("S08 tuple<int|BigInteger>.equals", "public static boolean m(BigInteger a, boolean c) { var t = new Tuple2<>(c ? 1 : a, a); return t.equals(new Tuple2<>(a, a)); }", AC, TagTable.EMPTY));
        p.add(new P("S09 list<int|BigInteger>.contains", "public static boolean m(BigInteger a, boolean c) { var l = JulcList.of(c ? 1 : a); return l.contains(a); }", AC, TagTable.EMPTY));
        p.add(new P("S09b list<int|BigInteger>.size", "public static long m(BigInteger a, boolean c) { var l = JulcList.of(c ? 1 : a); return l.size(); }", AC, TagTable.EMPTY));
        p.add(new P("S10 tuple<Amt|BigInteger>.equals", "@NewType record Amt(BigInteger v) { }\npublic static boolean m(BigInteger a, boolean c) { var t = new Tuple2<>(c ? new Amt(a) : a, a); return t.equals(new Tuple2<>(a, a)); }", AC, TagTable.EMPTY));
        p.add(new P("S11 list<Amt|BigInteger>.size", "@NewType record Amt(BigInteger v) { }\npublic static long m(BigInteger a, boolean c) { var l = JulcList.of(c ? new Amt(a) : a); return l.size(); }", AC, TagTable.EMPTY));
        p.add(new P("S26 var unknown -> list", "public static long m(BigInteger a, boolean c) { var v = c ? a : Builtins.iData(a); return JulcList.of(v).size(); }", AC, TagTable.EMPTY));
        p.add(new P("S27 var unknown -> tuple", "public static BigInteger m(BigInteger a, boolean c) { var v = c ? a : Builtins.iData(a); var t = new Tuple2<>(v, a); return t.second(); }", AC, TagTable.EMPTY));
        p.add(new P("S27b var unknown switch -> list", S + "public static long m(BigInteger a, boolean c) { S s = c ? new A(a) : new B(a); var v = switch (s) { case A q -> q.v(); case B q -> Builtins.iData(q.v()); }; return JulcList.of(v).size(); }", AC, TAGS));
        p.add(new P("S28 nested data-first -> list", "public static long m(BigInteger a, boolean c, boolean d) { return JulcList.of(c ? Builtins.iData(a) : (d ? a : Builtins.iData(a))).size(); }", ACD, TagTable.EMPTY));
        p.add(new P("S28b nested unknown-first -> list", "public static long m(BigInteger a, boolean c, boolean d) { return JulcList.of(c ? (d ? a : Builtins.iData(a)) : Builtins.iData(a)).size(); }", ACD, TagTable.EMPTY));
        p.add(new P("S29 nested data-first -> tuple", "public static BigInteger m(BigInteger a, boolean c, boolean d) { var t = new Tuple2<>(c ? Builtins.iData(a) : (d ? a : Builtins.iData(a)), a); return t.second(); }", ACD, TagTable.EMPTY));
        p.add(new P("S30 nested record+unknown -> list", S + "public static long m(BigInteger a, boolean c, boolean d) { return JulcList.of(c ? new A(a) : (d ? a : Builtins.iData(a))).size(); }", ACD, TAGS));
        p.add(new P("S30b nested record+unknown -> tuple", S + "public static BigInteger m(BigInteger a, boolean c, boolean d) { var t = new Tuple2<>(c ? new A(a) : (d ? a : Builtins.iData(a)), a); return t.second(); }", ACD, TAGS));
        p.add(new P("S31 switch arm holds unknown conditional -> list", S + "public static long m(BigInteger a, boolean c, boolean d) { S s = c ? new A(a) : new B(a); return JulcList.of(switch (s) { case A q -> q; case B q -> (d ? a : Builtins.iData(a)); }).size(); }", ACD, TAGS));
        p.add(new P("S31b switch arm data then unknown -> tuple", S + "public static BigInteger m(BigInteger a, boolean c, boolean d) { S s = c ? new A(a) : new B(a); var t = new Tuple2<>(switch (s) { case A q -> Builtins.iData(q.v()); case B q -> (d ? a : Builtins.iData(a)); }, a); return t.second(); }", ACD, TAGS));
        p.add(new P("S32 var unknown nested via var", "public static long m(BigInteger a, boolean c, boolean d) { var v = d ? a : Builtins.iData(a); return JulcList.of(c ? Builtins.iData(a) : v).size(); }", ACD, TagTable.EMPTY));
        p.add(new P("S38 conditional data-first with mixed switch -> list", S + "public static long m(BigInteger a, boolean c, boolean d) { S s = d ? new A(a) : new B(a); return JulcList.of(c ? Builtins.iData(a) : switch (s) { case A q -> q.v(); case B q -> Builtins.iData(q.v()); }).size(); }", ACD, TAGS));
        p.add(new P("S39 var data-first nested -> tuple", "public static BigInteger m(BigInteger a, boolean c, boolean d) { var v = c ? Builtins.iData(a) : (d ? a : Builtins.iData(a)); var t = new Tuple2<>(v, a); return t.second(); }", ACD, TagTable.EMPTY));
        p.add(new P("S40 map lambda unknown", "public static long m(BigInteger a, boolean c) { JulcList<BigInteger> xs = JulcList.of(a); return xs.map(x -> c ? x : Builtins.iData(x)).size(); }", AC, TagTable.EMPTY));
        p.add(new P("S41 map lambda nested data-first", "public static long m(BigInteger a, boolean c, boolean d) { JulcList<BigInteger> xs = JulcList.of(a); return xs.map(x -> c ? Builtins.iData(x) : (d ? x : Builtins.iData(x))).size(); }", ACD, TagTable.EMPTY));
        p.add(new P("S42 Optional.of unknown", "public static boolean m(BigInteger a, boolean c) { return Optional.of(c ? a : Builtins.iData(a)).isPresent(); }", AC, TagTable.EMPTY));
        p.add(new P("S42b Optional.of nested data-first", "public static boolean m(BigInteger a, boolean c, boolean d) { return Optional.of(c ? Builtins.iData(a) : (d ? a : Builtins.iData(a))).isPresent(); }", ACD, TagTable.EMPTY));
        p.add(new P("S43 map insert unknown key", "public static long m(BigInteger a, boolean c) { var mp = JulcMap.empty().insert(c ? a : Builtins.iData(a), a); return mp.size(); }", AC, TagTable.EMPTY));
        p.add(new P("S43b map insert nested data-first key", "public static long m(BigInteger a, boolean c, boolean d) { var mp = JulcMap.empty().insert(c ? Builtins.iData(a) : (d ? a : Builtins.iData(a)), a); return mp.size(); }", ACD, TagTable.EMPTY));
        p.add(new P("S44 tuple3 nested record-first", S + "public static BigInteger m(BigInteger a, boolean c, boolean d) { var t = new Tuple3<>(a, c ? new A(a) : (d ? a : Builtins.iData(a)), a); return t.third(); }", ACD, TAGS));
        p.add(new P("S33 conditional of Optional|A", S + "public static long m(BigInteger a, boolean c) { return JulcList.of(c ? Optional.of(a) : new A(a)).size(); }", AC, TAGS));
        p.add(new P("X1 list<BigInteger>.equals(list.of(Amt|BigInteger))", amt + "public static boolean m(BigInteger a, boolean c) { JulcList<BigInteger> l = JulcList.of(a); return l.equals(JulcList.of(c ? new Amt(a) : a)); }", AC, TagTable.EMPTY));
        p.add(new P("X2 list<PKH>.equals(list.of(PKH|byte[]))", "public static boolean m(BigInteger a, boolean c, byte[] h) { JulcList<PubKeyHash> l = JulcList.of(new PubKeyHash(h)); return l.equals(JulcList.of(c ? new PubKeyHash(h) : h)); }", ACB, TagTable.EMPTY));
        p.add(new P("X3 tuple.equals(tuple(Amt|BigInteger))", amt + "public static boolean m(BigInteger a, boolean c) { return new Tuple2<>(a, a).equals(new Tuple2<>(c ? new Amt(a) : a, a)); }", AC, TagTable.EMPTY));
        p.add(new P("X4 list<A>.equals(list.of(A|constrData))", S + "public static boolean m(BigInteger a, boolean c) { JulcList<A> l = JulcList.of(new A(a)); return l.equals(JulcList.of(c ? new A(a) : " + cd + ")); }", AC, TAGS));
        p.add(new P("X5 tuple<A>.equals(tuple(A|constrData))", S + "public static boolean m(BigInteger a, boolean c) { return new Tuple2<>(new A(a), a).equals(new Tuple2<>(c ? new A(a) : " + cd + ", a)); }", AC, TAGS));
        p.add(new P("X6 list.of(Amt|BigInteger).equals(l)", amt + "public static boolean m(BigInteger a, boolean c) { JulcList<BigInteger> l = JulcList.of(a); return JulcList.of(c ? new Amt(a) : a).equals(l); }", AC, TagTable.EMPTY));
        p.add(new P("X7 list.equals(list.of(int|BigInteger))", "public static boolean m(BigInteger a, boolean c) { JulcList<BigInteger> l = JulcList.of(a); return l.equals(JulcList.of(c ? 1 : a)); }", AC, TagTable.EMPTY));
        p.add(new P("X8 tuple.equals(tuple(PKH|byte[]))", "public static boolean m(BigInteger a, boolean c, byte[] h) { PubKeyHash k = new PubKeyHash(h); return new Tuple2<>(k, a).equals(new Tuple2<>(c ? k : h, a)); }", ACB, TagTable.EMPTY));
        p.add(new P("X9 tuple.equals(tuple(A|B)) diff families", S + "record P1(BigInteger v) { }\npublic static boolean m(BigInteger a, boolean c) { return new Tuple2<>(new P1(a), a).equals(new Tuple2<>(c ? new P1(a) : new A(a), a)); }", AC, TAGS));
        return p;
    }

    @Test
    void armsOfTwoTypesAreRejectedWhereverTheyStand() {
        var probes = mixed();
        assertTrue(probes.size() >= 38, "the review's routes: " + probes.size());
        for (var probe : probes) {
            for (var args : probe.inputs()) {
                var report = BOTH.run(new DiffCase(probe.name(), HEADER + probe.members() + "\n}\n", "Case", "m", args, probe.tags(), null));
                assertInstanceOf(Observation.Value.class, report.jvm(), () -> probe.name() + ": " + report.describe());
                report.results().forEach((config, result) -> {
                    if (config.frontend() != CompilerFrontend.JAVAC) return;
                    assertEquals(Verdict.Kind.REJECTED, result.verdict().kind(), () -> probe.name() + ": " + report.describe());
                    assertEquals("JULC0012", assertInstanceOf(Observation.Rejected.class, result.uplc()).code(), () -> probe.name() + ": " + report.describe());
                });
            }
        }
    }

    /** Arms of one type keep it: a conditional, a nested one, a switch, a var of one, in a list and a tuple, agree everywhere. */
    @Test
    void armsOfOneTypeAgreeOnBothFrontends() {
        for (var members : List.of(
                "public static long m(BigInteger a, boolean c) { var v = c ? a : a.negate(); return JulcList.of(v, (c ? a : BigInteger.ONE)).size() + v.longValue(); }",
                S + "public static BigInteger m(BigInteger a, boolean c) { S s = c ? new A(a) : new B(a); S t = c ? s : (S) new B(a.negate()); var u = new Tuple2<>(t, a); return switch (u.first()) { case A q -> q.v(); case B q -> q.v(); }; }",
                "public static long m(BigInteger a, boolean c) { return c ? 1 : a.longValue(); }")) {
            for (var args : AC) {
                var report = BOTH.run(new DiffCase("one-type", HEADER + members + "\n}\n", "Case", "m", args, members.startsWith("sealed") ? TAGS : TagTable.EMPTY, null));
                assertTrue(report.all(Verdict.Kind.AGREE), report::describe);
            }
        }
    }
}
