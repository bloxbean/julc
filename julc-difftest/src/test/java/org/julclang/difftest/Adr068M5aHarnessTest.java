package org.julclang.difftest;

import org.julclang.compiler.CompilerFrontend;
import org.julclang.core.PlutusData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-068 M5's entry gate, part 1 ({@code adr/evidence/068-m5a.md}): the shapes that stopped the corpus at {@code JULC0084}
 * and now lower on {@code frontend=JAVAC} answer as the JVM, on both frontends, at all four levels, for every input below.
 */
class Adr068M5aHarnessTest {

    private static final DiffRunner BOTH = new DiffRunner(configs(), 20_000);

    private static List<DiffConfig> configs() {
        var configs = new ArrayList<>(DiffConfig.allLevels());
        return configs;
    }

    private static final String HEADER = """
            import java.math.BigInteger;
            import org.julclang.core.PlutusData;
            import org.julclang.core.types.JulcList;
            import org.julclang.core.types.Tuple2;
            import org.julclang.core.types.Tuple3;
            import org.julclang.stdlib.Builtins;

            public class Case {
            """;

    /** The variants the programs below declare, by their place in what {@code S} permits. */
    private static final TagTable TAGS = new TagTable(List.of(new TagTable.Entry("Case$A", "Case$S", 0),
            new TagTable.Entry("Case$B", "Case$S", 1)));

    private static PlutusData integer(long v) {
        return new PlutusData.IntData(BigInteger.valueOf(v));
    }

    private static PlutusData bool(boolean b) {
        return new PlutusData.ConstrData(b ? 1 : 0, List.of());
    }

    private static int agreeOnEveryInput(String name, String members, TagTable tags, List<List<PlutusData>> inputs) {
        int runs = 0;
        for (var args : inputs) {
            var report = BOTH.run(new DiffCase(name, HEADER + members + "\n}\n", "Case", "m", args, tags, null));
            assertInstanceOf(Observation.Value.class, report.jvm(), report::describe);
            assertTrue(report.all(Verdict.Kind.AGREE), report::describe);
            runs += report.results().size();
        }
        return runs;
    }

    /** A raw {@code Tuple2} or {@code Tuple3}: each component is Data, as the reference reads it (StdlibCompileEvalTest's). */
    @ParameterizedTest
    @ValueSource(strings = {
            "public static BigInteger m(BigInteger a, boolean c) { Tuple2 t = new Tuple2(Builtins.iData(a), Builtins.iData(BigInteger.TEN)); return Builtins.unIData(t.first()).add(Builtins.unIData(t.second())); }",
            "public static boolean m(BigInteger a, boolean c) { Tuple2 t = new Tuple2(Builtins.iData(a), Builtins.iData(BigInteger.TEN)); return Builtins.unIData(t.second()).equals(a); }",
            "public static BigInteger m(BigInteger a, boolean c) { Tuple3 t = new Tuple3(Builtins.iData(a), Builtins.iData(BigInteger.TWO), Builtins.iData(BigInteger.TEN)); return Builtins.unIData(t.third()).subtract(Builtins.unIData(t.first())); }"})
    void aRawTuplesComponentIsData(String members) {
        var inputs = List.of(List.of(integer(10), bool(true)), List.of(integer(-3), bool(false)));
        assertEquals(8, agreeOnEveryInput("raw-tuple-component", members, TagTable.EMPTY, inputs));
    }

    /**
     * Arms that are variants of one sealed interface, compared through {@code equals}: the argument is javac's {@code Object},
     * so arms of two types, {@code JULC0012} since the review of M5a (F1, F2), where the JVM answers.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "sealed interface S permits A, B { } record A(BigInteger v) implements S { } record B(BigInteger v) implements S { }\n"
                    + "public static boolean m(BigInteger a, boolean c) { S s = c ? new A(a) : new B(a); return s.equals(switch (s) { case A x -> new A(a); default -> s; }); }",
            "sealed interface S permits A, B { } record A(BigInteger v) implements S { } record B(BigInteger v) implements S { }\n"
                    + "public static boolean m(BigInteger a, boolean c) { S s = new B(a); A x = new A(a); return s.equals(c ? s : x); }"})
    void armsOfTwoTypesComparedAreRejected(String members) {
        for (var args : List.of(List.of(integer(4), bool(true)), List.of(integer(4), bool(false)))) {
            var report = BOTH.run(new DiffCase("two-types", HEADER + members + "\n}\n", "Case", "m", args, TAGS, null));
            assertInstanceOf(Observation.Value.class, report.jvm(), report::describe);
            report.results().forEach((config, result) -> {
                    assertEquals("JULC0012", assertInstanceOf(Observation.Rejected.class, result.uplc(), report::describe).code());
                
            });
        }
    }

}
