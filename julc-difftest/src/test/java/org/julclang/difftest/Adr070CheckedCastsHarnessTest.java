package org.julclang.difftest;

import org.julclang.core.PlutusData;
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
 * M0 pins what {@code main} does before the change: a downcast agrees on its own variant and on the nullary {@code D} (the
 * unchecked read fails there too, and a failure agrees with the JVM's exception when the traces do), and is a MISMATCH on
 * a variant with fields of another type, read through the target's layout. Every other cast agrees. M1 makes the downcasts
 * agree on every variant.
 */
class Adr070CheckedCastsHarnessTest {

    private static final DiffRunner RUNNER = new DiffRunner(DiffConfig.allLevels(), 10_000);

    private static String program(String members) {
        return """
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;
                import org.julclang.core.types.JulcList;
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

    /** A downcast to A, in each position: the JVM's result on an A, a MISMATCH on every other variant (today). */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiterString = " ~ ", textBlock = """
            local                   ~ public static long m(S s) { S.A a = (S.A) s; return a.v().longValue(); }
            argument                ~ static long f(S.A a) { return a.v().longValue(); } public static long m(S s) { return f((S.A) s); }
            return                  ~ static S.A g(S s) { return (S.A) s; } public static long m(S s) { S.A a = g(s); return a.v().longValue(); }
            traced operand          ~ public static long m(S s) { S.A a = (S.A) echo("op", s); return said("v", a.v()).longValue(); }
            in a loop               ~ public static long m(S s) { long t = 0; for (S x : JulcList.of(s, s)) { S.A a = (S.A) x; t = t + a.v().longValue(); } return t; }
            in a lambda             ~ public static long m(S s) { return JulcList.of(s).map(x -> { S.A a = (S.A) x; return a.v(); }).size(); }
            same layout as A        ~ public static long m(S s) { S.C c = (S.C) s; return c.v().longValue(); }
            """)
    void aDowncastToday(String shape, String members) {
        for (int i = 0; i < EVERY_S.size(); i++) {
            var s = EVERY_S.get(i);
            var report = run(members, s);
            assertEquals(4, report.results().size());
            boolean own = shape.startsWith("same layout") ? i == 3 : i == 0;
            boolean nullary = i == 2;
            if (own || nullary) {
                assertTrue(report.all(Verdict.Kind.AGREE), report::describe);
            } else {
                assertTrue(report.all(Verdict.Kind.MISMATCH), report::describe);
            }
        }
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
