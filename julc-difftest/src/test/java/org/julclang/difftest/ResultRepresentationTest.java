package org.julclang.difftest;

import org.julclang.compiler.OptimizationLevel;
import org.julclang.core.Constant;
import org.julclang.core.PlutusData;
import org.julclang.core.Term;
import org.julclang.core.types.JulcList;
import org.julclang.difftest.RecordMappingTest.Shapes;
import org.julclang.difftest.ValueMapping.Representation;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A UPLC result must have the representation of the entry method's declared return type (ADR-066 D4, G1-I10):
 * a result that maps to the right Data in the wrong representation is a crash, not an agreement.
 */
class ResultRepresentationTest {

    private static final PlutusData RECORD = new PlutusData.ConstrData(1, List.of(new PlutusData.IntData(BigInteger.TWO)));

    private static Term integer(long value) {
        return new Term.Const(Constant.integer(BigInteger.valueOf(value)));
    }

    private static Term data(PlutusData value) {
        return new Term.Const(new Constant.DataConst(value));
    }

    @SuppressWarnings("unused")
    private static JulcList<BigInteger> list() {
        return null;
    }

    @Test
    void theDeclaredTypeDecidesTheRepresentation() throws Exception {
        assertEquals(Representation.INTEGER, Representation.of(BigInteger.class));
        assertEquals(Representation.BOOL, Representation.of(boolean.class));
        assertEquals(Representation.CONSTR_DATA, Representation.of(Shapes.Point.class));
        assertEquals(Representation.CONSTR_DATA, Representation.of(Shapes.Unit.class));
        assertEquals(Representation.CONSTR_DATA, Representation.of(Shapes.Shape.class));
        assertEquals(Representation.CONSTR_DATA, Representation.of(Shapes.Circle.class));
        // Unchecked: types outside ADR-066 D4's list, and library records with their own encodings.
        for (var type : List.of(long.class, int.class, Boolean.class, byte[].class, String.class, PlutusData.class,
                PlutusData.ConstrData.class, ResultRepresentationTest.class.getDeclaredMethod("list").getGenericReturnType())) {
            assertEquals(Representation.ANY, Representation.of(type), type.getTypeName());
        }
    }

    @Test
    void aResultInItsRepresentationMaps() {
        assertEquals(new PlutusData.IntData(BigInteger.valueOf(-3)), ValueMapping.fromTerm(integer(-3), Representation.INTEGER));
        assertEquals(new PlutusData.ConstrData(1, List.of()), ValueMapping.fromTerm(new Term.Const(Constant.bool(true)), Representation.BOOL));
        assertEquals(RECORD, ValueMapping.fromTerm(data(RECORD), Representation.CONSTR_DATA));
        // ANY keeps ADR-063's lenient mapping.
        assertEquals(RECORD, ValueMapping.fromTerm(new Term.Constr(1, List.of(integer(2))), Representation.ANY));
        assertEquals(new PlutusData.IntData(BigInteger.ONE), ValueMapping.fromTerm(data(new PlutusData.IntData(BigInteger.ONE)),
                Representation.ANY));
    }

    @Test
    void aResultInAnotherRepresentationIsRejected() {
        // The same Data, in the wrong representation: an integer as Data, a bool as a Constr, a record as a native
        // Constr, and Data that holds no Constr.
        assertRejected(data(new PlutusData.IntData(BigInteger.ONE)), Representation.INTEGER);
        assertRejected(new Term.Const(Constant.bool(true)), Representation.INTEGER);
        assertRejected(data(new PlutusData.ConstrData(1, List.of())), Representation.BOOL);
        assertRejected(new Term.Constr(1, List.of()), Representation.BOOL);
        assertRejected(integer(1), Representation.BOOL);
        assertRejected(new Term.Constr(1, List.of(integer(2))), Representation.CONSTR_DATA);
        assertRejected(data(new PlutusData.IntData(BigInteger.TWO)), Representation.CONSTR_DATA);
        assertRejected(data(new PlutusData.ListData(List.of())), Representation.CONSTR_DATA);
        assertRejected(integer(0), Representation.CONSTR_DATA);
    }

    private static void assertRejected(Term term, Representation representation) {
        var e = assertThrows(HarnessException.class, () -> ValueMapping.fromTerm(term, representation));
        assertTrue(e.getMessage().startsWith("representation"), e.getMessage());
    }

    /** The executor reports a violation as {@code Crash("result: representation …")}, which fails the run. */
    @Test
    void theExecutorReportsAViolationAsACrash() {
        String source = """
                import java.math.BigInteger;

                public class Probe {
                    public static BigInteger m(BigInteger a) {
                        return a.add(BigInteger.ONE);
                    }
                }
                """;
        var diffCase = new DiffCase("representation", source, "Probe", "m", List.of(new PlutusData.IntData(BigInteger.TWO)));
        var executor = new UplcExecutor();
        var config = new DiffConfig(OptimizationLevel.BASELINE, "Java");
        assertEquals(new Observation.Value(new PlutusData.IntData(BigInteger.valueOf(3)), List.of()),
                executor.run(diffCase, config, UplcExecutor.BUDGET, Representation.INTEGER).observation());
        var crash = assertInstanceOf(Observation.Crash.class,
                executor.run(diffCase, config, UplcExecutor.BUDGET, Representation.CONSTR_DATA).observation());
        assertTrue(crash.detail().startsWith("result: representation"), crash.detail());
        assertEquals(Verdict.Kind.CRASH, Verdict.compare(new Observation.Value(new PlutusData.IntData(BigInteger.valueOf(3)), List.of()),
                crash).kind());
    }

    /** The runner takes the declared type from javac's class: G0's {@code BigInteger} and other results still agree. */
    @Test
    void theRunnerChecksTheDeclaredReturnType() {
        var runner = DiffRunner.standard();
        for (String type : List.of("BigInteger", "boolean", "long")) {
            String body = switch (type) {
                case "BigInteger" -> "return a.add(BigInteger.ONE);";
                case "boolean" -> "return a.compareTo(BigInteger.ZERO) > 0;";
                default -> "return 7L;";
            };
            String source = """
                    import java.math.BigInteger;

                    public class Probe {
                        public static %s m(BigInteger a) {
                            %s
                        }
                    }
                    """.formatted(type, body);
            var report = runner.run(new DiffCase(type, source, "Probe", "m", List.of(new PlutusData.IntData(BigInteger.TWO))));
            assertTrue(report.all(Verdict.Kind.AGREE), report.describe());
        }
    }
}
