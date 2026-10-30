package org.julclang.difftest;

import org.julclang.core.PlutusData;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Record and sealed arguments and results end to end (ADR-066 D3, D4): hand-written programs whose
 * declaration order, {@code permits} order and switch-arm order are permuted independently agree at every
 * level when the tag table follows the {@code permits} clause, and disagree when it follows another order.
 */
class RecordDiffTest {

    private static final DiffRunner RUNNER = DiffRunner.standard();

    private static final Map<String, String> RECORDS = Map.of(
            "X", "record X(BigInteger v) implements S {}",
            "Y", "record Y(BigInteger v, boolean f) implements S {}",
            "Z", "record Z() implements S {}");
    private static final Map<String, String> ARMS = Map.of(
            "X", "case X q -> new Y(q.v().add(a), true);",
            "Y", "case Y q -> q.f() ? new Z() : new X(q.v().subtract(a));",
            "Z", "case Z q -> new X(a);");

    /** Declaration, {@code permits} and switch-arm orders: no two of a triple are equal. */
    private static final List<List<List<String>>> ORDERS = List.of(
            List.of(List.of("X", "Y", "Z"), List.of("Y", "Z", "X"), List.of("Z", "X", "Y")),
            List.of(List.of("X", "Y", "Z"), List.of("Z", "Y", "X"), List.of("Y", "X", "Z")),
            List.of(List.of("Y", "X", "Z"), List.of("X", "Z", "Y"), List.of("Z", "Y", "X")),
            List.of(List.of("Z", "Y", "X"), List.of("Y", "X", "Z"), List.of("X", "Z", "Y")),
            List.of(List.of("Z", "X", "Y"), List.of("X", "Y", "Z"), List.of("Y", "Z", "X")),
            List.of(List.of("Y", "Z", "X"), List.of("Z", "X", "Y"), List.of("X", "Y", "Z")));

    /**
     * {@code P(S s, BigInteger n)} in and out: a standalone record argument with a sealed component, and a record
     * result whose component is another variant than the argument's.
     */
    private static String source(List<String> declared, List<String> permits, List<String> arms) {
        var text = new StringBuilder("""
                import java.math.BigInteger;

                public class Probe {
                    sealed interface S permits %s {}
                """.formatted(String.join(", ", permits)));
        for (String variant : declared) text.append("    ").append(RECORDS.get(variant)).append('\n');
        text.append("""
                    record P(S s, BigInteger n) {}

                    public static P m(P p, BigInteger a) {
                        S r = switch (p.s()) {
                """);
        for (String variant : arms) text.append("            ").append(ARMS.get(variant)).append('\n');
        text.append("""
                        };
                        return new P(r, p.n().add(BigInteger.ONE));
                    }
                }
                """);
        return text.toString();
    }

    /** The tags of X, Y and Z under S in the given order. */
    private static TagTable tags(List<String> order) {
        var entries = new ArrayList<TagTable.Entry>();
        for (int i = 0; i < order.size(); i++) entries.add(new TagTable.Entry("Probe$" + order.get(i), "Probe$S", i));
        return new TagTable(entries);
    }

    private static PlutusData integer(long value) {
        return new PlutusData.IntData(BigInteger.valueOf(value));
    }

    private static PlutusData constr(int tag, PlutusData... fields) {
        return new PlutusData.ConstrData(tag, List.of(fields));
    }

    private static final PlutusData TRUE = constr(1);
    private static final PlutusData FALSE = constr(0);

    /** The arguments {@code P(s, n)} and {@code a} for each variant of S, encoded under the tag order. */
    private static List<List<PlutusData>> inputs(List<String> order) {
        int x = order.indexOf("X"), y = order.indexOf("Y"), z = order.indexOf("Z");
        return List.of(
                List.of(constr(0, constr(x, integer(2)), integer(5)), integer(4)),
                List.of(constr(0, constr(y, integer(3), TRUE), integer(0)), integer(4)),
                List.of(constr(0, constr(y, integer(3), FALSE), integer(-1)), integer(4)),
                List.of(constr(0, constr(z), integer(7)), integer(-6)));
    }

    @Test
    void permutedFamiliesAgreeUnderThePermitsOrder() {
        for (var orders : ORDERS) {
            var permits = orders.get(1);
            String source = source(orders.get(0), permits, orders.get(2));
            for (var args : inputs(permits)) {
                var report = RUNNER.run(new DiffCase("permuted " + orders, source, "Probe", "m", args, tags(permits), null));
                assertTrue(report.all(Verdict.Kind.AGREE), report.describe());
            }
        }
    }

    /** The expected results, written from the documented encoding for the first permutation. */
    @Test
    void theResultsAreTheDocumentedData() {
        var orders = ORDERS.getFirst();
        var permits = orders.get(1);
        int x = permits.indexOf("X"), y = permits.indexOf("Y"), z = permits.indexOf("Z");
        var expected = List.of(
                constr(0, constr(y, integer(6), TRUE), integer(6)),
                constr(0, constr(z), integer(1)),
                constr(0, constr(x, integer(-1)), integer(0)),
                constr(0, constr(x, integer(-6)), integer(8)));
        var inputs = inputs(permits);
        for (int i = 0; i < inputs.size(); i++) {
            var report = RUNNER.run(new DiffCase("documented", source(orders.get(0), permits, orders.get(2)), "Probe", "m",
                    inputs.get(i), tags(permits), null));
            assertEquals(new Observation.Value(expected.get(i), List.of()), report.jvm(), report.describe());
            assertTrue(report.all(Verdict.Kind.AGREE), report.describe());
        }
    }

    /** A table that follows the declaration order instead of {@code permits} is a mismatch: the harness sees it. */
    @Test
    void aTableInAnotherOrderDisagrees() {
        var orders = ORDERS.getFirst();
        var declared = orders.get(0);
        String source = source(declared, orders.get(1), orders.get(2));
        boolean mismatch = false;
        for (var args : inputs(declared)) {
            var report = RUNNER.run(new DiffCase("declaration order", source, "Probe", "m", args, tags(declared), null));
            mismatch |= report.results().values().stream().anyMatch(r -> r.verdict().kind() == Verdict.Kind.MISMATCH);
        }
        assertTrue(mismatch, "a wrong tag table must show as a mismatch");
    }

    /** A sealed result typed as the sealed interface, and a variant result typed as itself. */
    @Test
    void sealedAndVariantResultsAgree() {
        String source = """
                import java.math.BigInteger;

                public class Probe {
                    sealed interface S permits B, A {}
                    record A(BigInteger v) implements S {}
                    record B(boolean f, BigInteger w) implements S {}

                    public static S m(BigInteger a) {
                        return a.compareTo(BigInteger.ZERO) > 0 ? new A(a) : new B(true, a);
                    }
                }
                """;
        var tags = TagTable.of(new TagTable.Entry("Probe$A", "Probe$S", 1), new TagTable.Entry("Probe$B", "Probe$S", 0));
        var positive = RUNNER.run(new DiffCase("sealed result", source, "Probe", "m", List.of(integer(3)), tags, null));
        assertEquals(new Observation.Value(constr(1, integer(3)), List.of()), positive.jvm());
        assertTrue(positive.all(Verdict.Kind.AGREE), positive.describe());
        var negative = RUNNER.run(new DiffCase("sealed result", source, "Probe", "m", List.of(integer(-2)), tags, null));
        assertEquals(new Observation.Value(constr(0, TRUE, integer(-2)), List.of()), negative.jvm());
        assertTrue(negative.all(Verdict.Kind.AGREE), negative.describe());

        String variant = source.replace("public static S m(BigInteger a) {\n        return a.compareTo(BigInteger.ZERO) > 0 ? new A(a) : new B(true, a);",
                "public static A m(BigInteger a) {\n        return new A(a.add(BigInteger.ONE));");
        var typedAsItself = RUNNER.run(new DiffCase("variant result", variant, "Probe", "m", List.of(integer(3)), tags, null));
        assertEquals(new Observation.Value(constr(1, integer(4)), List.of()), typedAsItself.jvm());
        assertTrue(typedAsItself.all(Verdict.Kind.AGREE), typedAsItself.describe());
    }

    /** Source validation runs before any run: a case whose table misses a variant fails as a harness error. */
    @Test
    void anInvalidTableFailsBeforeAnyRun() {
        var orders = ORDERS.getFirst();
        String source = source(orders.get(0), orders.get(1), orders.get(2));
        var partial = TagTable.of(new TagTable.Entry("Probe$Y", "Probe$S", 0), new TagTable.Entry("Probe$Z", "Probe$S", 1));
        var args = inputs(orders.get(1)).getFirst();
        assertThrows(HarnessException.class, () -> RUNNER.run(new DiffCase("missing", source, "Probe", "m", args, partial, null)));
        assertThrows(HarnessException.class, () -> RUNNER.run(new DiffCase("none", source, "Probe", "m", args)));
    }
}
