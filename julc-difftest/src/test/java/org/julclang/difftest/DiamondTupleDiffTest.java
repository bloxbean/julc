package org.julclang.difftest;

import org.julclang.core.PlutusData;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #299 (ADR-068 D4 exception 2): a diamond {@code new Tuple2<>(…)} or {@code new Tuple3<>(…)} takes the component
 * types javac infers, from a declared target type or else from the arguments, and encodes each component by its
 * type, so it agrees with the JVM at every optimization level. A raw tuple keeps working when its components are
 * already Data (StdlibCompileEvalTest's {@code rawTuple2StillWorks}, MathLib); with any other component it is
 * rejected with JULC0012.
 */
class DiamondTupleDiffTest {

    private static final DiffRunner RUNNER = DiffRunner.standard();

    private static DiffRunner.Report run(String decls, String returnType, String body) {
        String source = """
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;
                import org.julclang.core.types.JulcList;
                import org.julclang.core.types.Tuple2;
                import org.julclang.core.types.Tuple3;
                import org.julclang.stdlib.Builtins;

                public class Probe {
                    %s
                    public static %s m(BigInteger x, BigInteger y) {
                        %s
                    }
                }
                """.formatted(decls, returnType, body);
        var tags = decls.contains(SEALED) ? new TagTable(List.of(new TagTable.Entry("Probe$A", "Probe$S", 0),
                new TagTable.Entry("Probe$B", "Probe$S", 1))) : TagTable.EMPTY;
        return RUNNER.run(new DiffCase(body, source, "Probe", "m",
                List.of(new PlutusData.IntData(BigInteger.valueOf(3)), new PlutusData.IntData(BigInteger.valueOf(4))), tags, null));
    }

    private static void agrees(String decls, String returnType, String body) {
        var report = run(decls, returnType, body);
        assertTrue(report.all(Verdict.Kind.AGREE), report.describe());
    }

    private static final String RECORD = "record P(BigInteger a, byte[] b) {}";
    private static final String SEALED = "sealed interface S permits A, B {} record A(BigInteger n) implements S {}"
            + " record B(BigInteger n) implements S {}";

    private static void rejected(String decls, String returnType, String body, String code) {
        var report = run(decls, returnType, body);
        assertTrue(report.all(Verdict.Kind.REJECTED), report.describe());
        report.results().values().forEach(r -> assertEquals(code, ((Observation.Rejected) r.uplc()).code(), body));
    }

    @Test
    void componentsInferredFromTheArguments() {
        agrees("", "BigInteger", "var t = new Tuple2<>(x, y); BigInteger s = t.second(); return s;");
        agrees("", "byte[]", "var t = new Tuple2<>(x, new byte[]{1, 2}); return t.second();");
        agrees("", "boolean", "var t = new Tuple2<>(x.compareTo(y) < 0, y); return t.first();");
        agrees(RECORD, "BigInteger", "var t = new Tuple2<>(new P(x, new byte[]{3}), y); P p = t.first(); return p.a();");
        agrees("", "BigInteger", "var t = new Tuple2<>(new Tuple2<>(x, y), y); var inner = t.first(); BigInteger s = inner.second(); return s;");
        agrees("", "byte[]", "var t = new Tuple3<>(x, new byte[]{7}, y.compareTo(x) > 0); return t.third() ? t.second() : new byte[]{};");
    }

    @Test
    void componentsTakenFromADeclaredTarget() {
        agrees("", "BigInteger", "Tuple2<BigInteger, BigInteger> t = new Tuple2<>(x, y); return t.second();");
        agrees("", "byte[]", "Tuple3<BigInteger, byte[], Boolean> t = new Tuple3<>(y, new byte[]{1, 2}, x.compareTo(y) < 0);"
                + " return t.third() ? t.second() : new byte[]{};");
        agrees(RECORD, "byte[]", "Tuple2<P, Tuple2<BigInteger, byte[]>> t = new Tuple2<>(new P(x, new byte[]{3}), new Tuple2<>(y, new byte[]{4}));"
                + " return t.second().second();");
        // A return statement and an assignment in a loop are targets too; an argument position takes the arguments' types.
        agrees("static Tuple2<BigInteger, BigInteger> mk(BigInteger a, BigInteger b) { return new Tuple2<>(a, b); }",
                "BigInteger", "var t = mk(x, y); BigInteger s = t.first(); return s;");
        agrees("", "BigInteger", "Tuple2<BigInteger, BigInteger> t = new Tuple2<>(x, y); for (var i : JulcList.of(x)) { t = new Tuple2<>(t.second(), t.first()); } return t.first();");
        agrees("static BigInteger snd(Tuple2<BigInteger, BigInteger> t) { return t.second(); }", "BigInteger",
                "return snd(new Tuple2<>(x, y));");
    }

    /** Review F710: a switch expression's result keeps the type its arms agree on, for a diamond's component. */
    @Test
    void switchComponentsKeepTheirType() {
        String sw = "switch (s) { case A a -> a.n(); case B b -> b.n(); }";
        agrees(SEALED, "BigInteger", "S s = new A(x); var t = new Tuple2<>(" + sw + ", y); return t.second();");
        agrees(SEALED, "BigInteger", "S s = new A(x); var t = new Tuple2<>(" + sw + ", y); BigInteger f = t.first(); return f;");
        agrees(SEALED, "BigInteger", "S s = new A(x); var t = new Tuple3<>(new Tuple2<>(" + sw + ", y), y, x);"
                + " var inner = t.first(); BigInteger f = inner.first(); return f;");
        agrees(SEALED, "BigInteger", "S s = new A(x); var v = " + sw + "; return v.add(y);");
    }

    /**
     * Review F710: a switch whose arms have different types (here an integer and Data) has no type the lowering
     * knows, so encoding it, or taking it as a diamond's component, is JULC0012 rather than an encoding as Data.
     */
    @Test
    void switchesOfUnknownTypeAreNotEncoded() {
        String decls = SEALED + " static PlutusData d(BigInteger v) { return Builtins.iData(v); }";
        String sw = "switch (s) { case A a -> a.n(); case B b -> d(b.n()); }";
        rejected(decls, "long", "S s = new A(x); var v = " + sw + "; return JulcList.of(v).size();", "JULC0012");
        rejected(decls, "long", "S s = new A(x); var t = new Tuple2<>(" + sw + ", y); return 1;", "JULC0012");
    }

    /** Review F711: a raw destination is a raw destination through parentheses, in a loop and as a parameter. */
    @Test
    void rawDestinationsInEveryContext() {
        rejected("", "BigInteger", "Tuple2 t = (new Tuple2<>(x, y)); BigInteger b = (BigInteger) t.second(); return b.add(x);",
                "JULC0012");
        rejected("", "BigInteger", "Tuple2 t = new Tuple2(Builtins.iData(x), Builtins.iData(y));"
                + " for (var i : JulcList.of(x)) { t = new Tuple2<>(x, y); } BigInteger b = (BigInteger) t.second(); return b.add(x);",
                "JULC0012");
        rejected("static BigInteger snd(Tuple2 t) { BigInteger b = (BigInteger) t.second(); return b; }", "BigInteger",
                "return snd(new Tuple2<>(x, y)).add(x);", "JULC0012");
        rejected("static Tuple2 mk(BigInteger a, BigInteger b) { return new Tuple2<>(a, b); }", "long",
                "var t = mk(x, y); return 1;", "JULC0012");
        // Data components stay accepted at a raw destination, as at B0.
        agrees("", "BigInteger", "Tuple2 t = (new Tuple2<>(Builtins.iData(x), Builtins.iData(y)));"
                + " return Builtins.unIData((PlutusData) t.second());");
    }

    /** Review F712: an explicit constructor keeps its written component types, whatever its destination. */
    @Test
    void explicitConstructorsAtRawDestinationsKeepTheirTypes() {
        agrees("", "BigInteger", "Tuple2 raw = new Tuple2<BigInteger, BigInteger>(x, y); Tuple2<BigInteger, BigInteger> t = raw;"
                + " return t.second();");
    }

    @Test
    void rawTuplesWithNonDataComponentsAreRejected() {
        for (String body : List.of("Tuple2 t = new Tuple2(x, y); return (BigInteger) t.second();",
                "Tuple2 t = new Tuple2<>(x, y); return (BigInteger) t.second();")) {
            var report = run("", "BigInteger", body);
            assertTrue(report.all(Verdict.Kind.REJECTED), report.describe());
            report.results().values().forEach(r -> assertEquals("JULC0012", ((Observation.Rejected) r.uplc()).code(), body));
        }
    }
}
