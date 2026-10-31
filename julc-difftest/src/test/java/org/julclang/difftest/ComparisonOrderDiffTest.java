package org.julclang.difftest;

import org.julclang.core.PlutusData;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-068 D6 and D4 exception 1, on the JavaParser frontend at every optimization level against the JVM: the
 * {@code compareTo} rule ({@code a.compareTo(b) OP 0} and {@code 0 OP a.compareTo(b)} evaluate {@code a}, then
 * {@code b}, once each, #146) and left-to-right relational operators (#295). Every expected value, trace and failure
 * is the JVM's.
 */
class ComparisonOrderDiffTest {

    private static final DiffRunner RUNNER = DiffRunner.standard();
    private static final List<String> OPERATORS = List.of("<", "<=", ">", ">=", "==", "!=");
    /** {@code a > b}, {@code a = b}, {@code a < b}. */
    private static final List<long[]> INPUTS = List.of(new long[]{3, 1}, new long[]{2, 2}, new long[]{1, 3});

    private static String source(String decls, String returnType, String body) {
        return """
                import java.math.BigInteger;
                import org.julclang.core.types.JulcList;
                import org.julclang.stdlib.Builtins;
                import org.julclang.stdlib.annotation.NewType;

                public class Probe {
                    %s
                    static BigInteger t(String k, BigInteger v) {
                        return Builtins.unIData(Builtins.trace(k, Builtins.iData(v)));
                    }

                    static BigInteger fail(BigInteger z) {
                        return Builtins.unIData(Builtins.error());
                    }

                    public static %s m(BigInteger a, BigInteger b) {
                        %s
                    }
                }
                """.formatted(decls, returnType, body);
    }

    private static PlutusData integer(long value) {
        return new PlutusData.IntData(BigInteger.valueOf(value));
    }

    private static DiffRunner.Report run(String decls, String returnType, String body, long a, long b) {
        return RUNNER.run(new DiffCase(body + " [" + a + ", " + b + "]", source(decls, returnType, body), "Probe", "m",
                List.of(integer(a), integer(b))));
    }

    private static DiffRunner.Report agrees(String decls, String body, long a, long b) {
        var report = run(decls, "boolean", body, a, b);
        assertTrue(report.all(Verdict.Kind.AGREE), report.describe());
        return report;
    }

    /** The twelve forms: six operators, call on the left and zero on the left. */
    private static List<String> forms(String receiver, String argument) {
        var forms = new ArrayList<String>();
        for (String op : OPERATORS) {
            forms.add(receiver + ".compareTo(" + argument + ") " + op + " 0");
            forms.add("0 " + op + " " + receiver + ".compareTo(" + argument + ")");
        }
        return forms;
    }

    @Test
    void twelveFormsAgreeOnEveryOrdering() {
        for (String form : forms("a", "b")) {
            for (long[] in : INPUTS) agrees("", "return " + form + ";", in[0], in[1]);
        }
    }

    @Test
    void tracedOperandsTraceOnceEachInSourceOrder() {
        for (String form : forms("t(\"A\", a)", "t(\"B\", b)")) {
            for (long[] in : INPUTS) {
                var report = agrees("", "return " + form + ";", in[0], in[1]);
                assertEquals(List.of("A", "B"), ((Observation.Value) report.jvm()).traces(), form);
            }
        }
    }

    @Test
    void aFailingOperandFailsAfterTheEarlierOperandsTraces() {
        for (String form : forms("fail(a)", "t(\"B\", b)")) {
            var report = run("", "boolean", "return " + form + ";", 3, 1);
            assertTrue(report.all(Verdict.Kind.AGREE), report.describe());
            assertEquals(List.of(), assertInstanceOf(Observation.Failure.class, report.jvm()).traces(), form);
        }
        for (String form : forms("t(\"A\", a)", "fail(b)")) {
            var report = run("", "boolean", "return " + form + ";", 3, 1);
            assertTrue(report.all(Verdict.Kind.AGREE), report.describe());
            assertEquals(List.of("A"), assertInstanceOf(Observation.Failure.class, report.jvm()).traces(), form);
        }
    }

    @Test
    void nonTrivialOperandsOfEveryShape() {
        String decls = "record P(BigInteger amt) {}";
        for (long[] in : INPUTS) {
            // An accessor receiver, a method-call receiver, arithmetic on both sides (the bound let of > and >=).
            agrees(decls, "P p = new P(a); return p.amt().compareTo(t(\"B\", b)) > 0;", in[0], in[1]);
            agrees(decls, "P p = new P(t(\"A\", a)); return p.amt().compareTo(t(\"B\", b)) >= 0;", in[0], in[1]);
            agrees("", "return t(\"A\", a).add(b).compareTo(t(\"B\", b).multiply(b)) > 0;", in[0], in[1]);
            agrees("", "return 0 < t(\"A\", a).subtract(b).compareTo(t(\"B\", a));", in[0], in[1]);
            agrees("", "boolean x = t(\"A\", a).compareTo(t(\"B\", b)) > 0 && t(\"C\", b).compareTo(t(\"D\", a)) <= 0; return x;",
                    in[0], in[1]);
        }
    }

    /**
     * #295: {@code >} and {@code >=} on {@code int} and {@code long} evaluate the left operand first; {@code <} and
     * {@code <=} already did.
     */
    @Test
    void relationalOperatorsEvaluateLeftToRight() {
        for (String op : List.of("<", "<=", ">", ">=")) {
            for (String width : List.of("intValue", "longValue")) {
                String form = "t(\"X\", a)." + width + "() " + op + " t(\"Y\", b)." + width + "()";
                for (long[] in : INPUTS) {
                    var report = agrees("", "return " + form + ";", in[0], in[1]);
                    assertEquals(List.of("X", "Y"), ((Observation.Value) report.jvm()).traces(), form);
                }
                var failsRight = run("", "boolean", "return t(\"X\", a)." + width + "() " + op + " fail(b)." + width + "();", 3, 1);
                assertTrue(failsRight.all(Verdict.Kind.AGREE), failsRight.describe());
                var failsLeft = run("", "boolean", "return fail(a)." + width + "() " + op + " t(\"Y\", b)." + width + "();", 3, 1);
                assertTrue(failsLeft.all(Verdict.Kind.AGREE), failsLeft.describe());
            }
        }
    }

    /**
     * A {@code @NewType} that declares {@code compareTo}: the frozen frontend's dispatch selects the integer handler
     * and ignores the declared method (T31, accepted until M5), before and after the rule. With an order-preserving
     * declaration both sides agree; with a reversed one UPLC still makes the builtin comparison.
     */
    @Test
    void newTypeWithADeclaredCompareToKeepsTheBuiltinComparison() {
        String natural = "@NewType record Amt(BigInteger v) { public int compareTo(Amt o) { return v.compareTo(o.v()); } }";
        String reversed = "@NewType record Amt(BigInteger v) { public int compareTo(Amt o) { return o.v().compareTo(v); } }";
        for (long[] in : INPUTS) {
            agrees(natural, "Amt x = new Amt(t(\"A\", a)); Amt y = new Amt(b); return x.compareTo(y) > 0;", in[0], in[1]);
            agrees(natural, "return new Amt(t(\"A\", a)).compareTo(new Amt(t(\"B\", b))) >= 0;", in[0], in[1]);
            var report = run(reversed, "boolean", "return new Amt(t(\"A\", a)).compareTo(new Amt(t(\"B\", b))) > 0;", in[0], in[1]);
            var builtin = new Observation.Value(new PlutusData.ConstrData(in[0] > in[1] ? 1 : 0, List.of()), List.of("A", "B"));
            report.results().values().forEach(result -> assertEquals(builtin, result.uplc(), report.describe()));
        }
    }

    /** The frozen frontend rejects a compareTo on a chained accessor of a call; the rule does not change that. */
    @Test
    void chainedAccessorOfACallStaysRejected() {
        String decls = "record P(BigInteger amt) {} static P box(String k, BigInteger v) { return new P(t(k, v)); }";
        var report = run(decls, "boolean", "return box(\"L\", a).amt().compareTo(b) > 0;", 3, 1);
        assertTrue(report.all(Verdict.Kind.REJECTED), report.describe());
        report.results().values().forEach(result ->
                assertEquals("JULC0055", ((Observation.Rejected) result.uplc()).code(), report.describe()));
    }

    /**
     * Outside the rule ({@code OP 0L}, a parenthesized call or zero, a comparison with 1) {@code compareTo} keeps today's
     * lowering, which evaluates a traced operand more than once (T13, #146): those stay open.
     */
    @Test
    void otherComparisonsKeepTodaysLowering() {
        for (String form : List.of("t(\"A\", a).compareTo(t(\"B\", b)) > 0L", "(t(\"A\", a).compareTo(t(\"B\", b))) > 0",
                "t(\"A\", a).compareTo(t(\"B\", b)) > (0)",
                "t(\"A\", a).compareTo(t(\"B\", b)) < 1")) {
            var report = run("", "boolean", "return " + form + ";", 3, 1);
            assertTrue(report.all(Verdict.Kind.MISMATCH), report.describe());
            report.results().values().forEach(result ->
                    assertEquals(List.of("A", "B", "A", "B"), ((Observation.Value) result.uplc()).traces(), report.describe()));
        }
    }
}
