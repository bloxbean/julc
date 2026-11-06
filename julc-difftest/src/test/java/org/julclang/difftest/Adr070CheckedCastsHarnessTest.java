package org.julclang.difftest;

import org.julclang.core.PlutusData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-070: a cast to a record variant of a sealed interface. Each program runs on the JVM and, at every level, on the VM;
 * the JVM is the oracle. {@code S} has variants of different layouts ({@code A(v)}, {@code B(v, w)}), a nullary one
 * ({@code D}) and one with {@code A}'s layout ({@code C(v)}), so a cast that is not checked reads another variant's fields.
 * <p>
 * Before ADR-070 a downcast agreed only on its own variant and on the nullary {@code D}, where the unchecked read failed
 * too, and was a MISMATCH on a variant with fields, read through the target's layout (M0). Now a downcast tests the tag
 * (D1), so it agrees on every variant: the value on its own, a failure, with the JVM's traces, on any other.
 */
class Adr070CheckedCastsHarnessTest {

    private static final DiffRunner RUNNER = new DiffRunner(DiffConfig.allLevels(), 10_000);

    private static String program(String members) {
        return """
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;
                import org.julclang.core.types.JulcList;
                import org.julclang.ledger.*;
                import org.julclang.stdlib.Builtins;

                public class Case {
                    static BigInteger said(String m, BigInteger v) { return Builtins.unIData(Builtins.trace(m, Builtins.iData(v))); }
                    static S echo(String m, S s) { Builtins.trace(m, Builtins.iData(BigInteger.ZERO)); return s; }
                    public sealed interface S {
                        record A(BigInteger v) implements S { }
                        record B(BigInteger v, BigInteger w) implements S { }
                        record D() implements S { }
                        record C(BigInteger v) implements S { }
                    }
                    record Box(S value) { }
                    static S make(BigInteger v) { return v.signum() > 0 ? new S.A(v) : new S.B(v, v); }
                    static long ignore(S.A a) { return 1; }
                    static long value(S.A a) { return a.v().longValue(); }
                    %s
                }
                """.formatted(members);
    }

    private static final TagTable TAGS = new TagTable(List.of(
            new TagTable.Entry("Case$S$A", "Case$S", 0), new TagTable.Entry("Case$S$B", "Case$S", 1), new TagTable.Entry("Case$S$D", "Case$S", 2),
            new TagTable.Entry("Case$S$C", "Case$S", 3)));

    private static PlutusData integer(long value) {
        return new PlutusData.IntData(BigInteger.valueOf(value));
    }

    private static PlutusData constr(int tag, PlutusData... fields) {
        return new PlutusData.ConstrData(tag, List.of(fields));
    }

    /** One of each variant: A first, then the others a cast to A must refuse. */
    private static final List<PlutusData> EVERY_S = List.of(constr(0, integer(7)), constr(1, integer(10), integer(4)), constr(2), constr(3, integer(9)));

    private static DiffRunner.Report run(String members, PlutusData... args) {
        return RUNNER.run(new DiffCase("adr070", program(members), "Case", "m", List.of(args), TAGS, null));
    }

    /** A downcast to A (or to C), in each position: it agrees with the JVM on every variant, a failure on the others. */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiterString = " ~ ", textBlock = """
            local                   ~ public static long m(S s) { S.A a = (S.A) s; return a.v().longValue(); }
            argument                ~ static long f(S.A a) { return a.v().longValue(); } public static long m(S s) { return f((S.A) s); }
            return                  ~ static S.A g(S s) { return (S.A) s; } public static long m(S s) { S.A a = g(s); return a.v().longValue(); }
            traced operand          ~ public static long m(S s) { S.A a = (S.A) echo("op", s); return said("v", a.v()).longValue(); }
            in a loop               ~ public static long m(S s) { long t = 0; for (S x : JulcList.of(s, s)) { S.A a = (S.A) x; t = t + a.v().longValue(); } return t; }
            in a lambda             ~ public static long m(S s) { return JulcList.of(s).map(x -> { S.A a = (S.A) x; return a.v(); }).size(); }
            same layout as A        ~ public static long m(S s) { S.C c = (S.C) s; return c.v().longValue(); }
            unused result           ~ public static long m(S s) { S.A unused = (S.A) s; return 1; }
            ignored argument        ~ public static long m(S s) { return ignore((S.A) s); }
            between two traces      ~ public static long m(S s) { said("before", BigInteger.ONE); S.A a = (S.A) s; said("after", BigInteger.TWO); return a.v().longValue(); }
            in a ternary arm        ~ public static long m(S s) { long v = s instanceof S.D ? 0 : value((S.A) s); return v; }
            through the interface   ~ public static long m(S s) { S.A a = (S.A) (S) s; return a.v().longValue(); }
            a component typed S     ~ public static long m(S s) { Box box = new Box(s); S.A a = (S.A) box.value(); return a.v().longValue(); }
            a list head typed S     ~ public static long m(S s) { S.A a = (S.A) JulcList.of(s).head(); return a.v().longValue(); }
            a pattern operand       ~ public static long m(S s) { if ((S.A) s instanceof S.A a) { return a.v().longValue(); } return -1; }
            an early-exit rest      ~ public static long m(S s) { if (!(s instanceof S.B b)) { S.A a = (S.A) s; return a.v().longValue(); } return b.w().longValue(); }
            """)
    void aDowncastAgrees(String shape, String members) {
        for (var s : EVERY_S) {
            var report = run(members, s);
            assertEquals(4, report.results().size());
            assertTrue(report.all(Verdict.Kind.AGREE), report::describe);
        }
    }

    /** A ternary of two variants is {@code Record & S}: its cast is checked (review F1), with its operand evaluated once. */
    @Test
    void aTernaryOperandIsChecked() {
        for (long n : new long[]{-3, 0, 4}) {
            var report = run("public static long m(long n) { S.A a = (S.A) (n > 0 ? new S.A(BigInteger.valueOf(n)) : new S.B(BigInteger.ONE, BigInteger.TWO)); "
                    + "return a.v().longValue(); }", integer(n));
            assertTrue(report.all(Verdict.Kind.AGREE), report::describe);
            var inferred = run("public static long m(long n) { var x = n > 0 ? new S.A(BigInteger.valueOf(n)) : new S.D(); S.A a = (S.A) x; return a.v().longValue(); }",
                    integer(n));
            assertTrue(inferred.all(Verdict.Kind.AGREE), inferred::describe);
        }
    }

    /** A cast that &&, || or a ?: arm skips does not run, as on the JVM. */
    @Test
    void aSkippedCastDoesNotRun() {
        for (var s : EVERY_S) {
            for (String body : new String[]{"return s instanceof S.A && value((S.A) s) > 0 ? 1 : 0;",
                    "return !(s instanceof S.A) || value((S.A) s) > 0 ? 1 : 0;",
                    "return s instanceof S.A ? value((S.A) s) : 9;"}) {
                var report = run("public static long m(S s) { " + body + " }", s);
                assertTrue(report.all(Verdict.Kind.AGREE), report::describe);
            }
        }
    }

    /** #288's reproducer: a B cast to A fails, where it returned B's field. */
    @Test
    void issue288() {
        var report = run("public static BigInteger m(BigInteger a) { S s = new S.B(a, a); S.A x = (S.A) s; return x.v(); }", integer(3));
        assertTrue(report.all(Verdict.Kind.AGREE), report::describe);
    }

    /**
     * A ledger sum: a validator's default handler casting the ScriptInfo it is given. A minting script info passes; a
     * rewarding one fails, as the JVM throws.
     */
    @Test
    void aLedgerScriptInfoCast() {
        String members = "public static byte[] m(long n) { ScriptInfo info = n == 0 ? new ScriptInfo.MintingScript(new PolicyId(new byte[]{1, 2})) "
                + ": new ScriptInfo.RewardingScript(new Credential.PubKeyCredential(new PubKeyHash(new byte[]{3}))); "
                + "ScriptInfo.MintingScript mint = (ScriptInfo.MintingScript) info; return mint.policyId().hash(); }";
        for (long n : new long[]{0, 1}) {
            var report = run(members, integer(n));
            assertTrue(report.all(Verdict.Kind.AGREE), report::describe);
        }
    }

    /**
     * Risk (ADR-070, Q6): JuLC's {@code any} runs the predicate on every element, where the JVM stops at the first true. A
     * checked cast in the predicate fails on an element the JVM never reaches: true on the JVM, a failure on-chain (before
     * ADR-070, true, with B read as A). Pinned until any and all stop early.
     */
    @Test
    void anyRunsTheCastOnEveryElement() {
        var report = run("public static boolean m(S s) { return JulcList.of(s, (S) new S.B(BigInteger.ONE, BigInteger.TWO)).any(x -> value((S.A) x) > 0); }",
                constr(0, integer(5)));
        assertEquals(new Observation.Value(new PlutusData.ConstrData(1, List.of()), List.of()), report.jvm(), report::describe);
        report.results().values().forEach(result -> assertTrue(result.uplc() instanceof Observation.Failure, report::describe));
    }

    /** Casts that need no check: each agrees on every variant, today and after M1 (D2). */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiterString = " ~ ", textBlock = """
            upcast                  ~ public static long m(S s) { S t = (S) new S.A(BigInteger.ONE); return t instanceof S.A ? 1 : 0; }
            same type               ~ public static long m(S s) { S t = (S) s; return t instanceof S.A ? 1 : 0; }
            guarded downcast        ~ public static long m(S s) { if (s instanceof S.A) { S.A a = (S.A) s; return a.v().longValue(); } return -1; }
            pattern instead         ~ public static long m(S s) { if (s instanceof S.A a) { return a.v().longValue(); } return -1; }
            """)
    void aCastThatNeedsNoCheckAgrees(String shape, String members) {
        for (var s : EVERY_S) {
            var report = run(members, s);
            assertTrue(report.all(Verdict.Kind.AGREE), report::describe);
        }
    }
}
