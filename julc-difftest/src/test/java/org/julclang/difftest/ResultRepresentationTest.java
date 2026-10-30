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

    /** The same Data in another representation: a bool as a native {@code Constr}, an integer or a record as Data. */
    private static Term otherRepresentation(Term term) {
        if (!(term instanceof Term.Const c)) return term;
        return switch (c.value()) {
            case Constant.BoolConst b -> new Term.Constr(b.value() ? 1 : 0, List.of());
            case Constant.IntegerConst i -> data(new PlutusData.IntData(i.value()));
            case Constant.DataConst d when d.value() instanceof PlutusData.ConstrData r ->
                    new Term.Constr(r.tag(), r.fields().stream().map(ResultRepresentationTest::data).toList());
            default -> term;
        };
    }

    /**
     * Review F47: the runner passes the declared type to every UPLC run and confirmation rerun. Through the test
     * seam, each evaluated result keeps its Data and changes its representation; for a checked declared type every
     * configuration crashes, and for an unchecked one ({@code long}) the lenient mapping still agrees.
     */
    @Test
    void theRunnerRejectsTheRightDataInTheWrongRepresentation() {
        var runner = new DiffRunner(DiffConfig.allLevels(), 10_000, new UplcExecutor(ResultRepresentationTest::otherRepresentation));
        var cases = List.of(
                new DiffCase("boolean", """
                        import java.math.BigInteger;

                        public class Probe {
                            public static boolean m(BigInteger a) {
                                return a.compareTo(BigInteger.ZERO) > 0;
                            }
                        }
                        """, "Probe", "m", List.of(new PlutusData.IntData(BigInteger.TWO))),
                new DiffCase("BigInteger", """
                        import java.math.BigInteger;

                        public class Probe {
                            public static BigInteger m(BigInteger a) {
                                return a.add(BigInteger.ONE);
                            }
                        }
                        """, "Probe", "m", List.of(new PlutusData.IntData(BigInteger.TWO))),
                new DiffCase("record", """
                        import java.math.BigInteger;

                        public class Probe {
                            sealed interface S permits B, A {}
                            record A(BigInteger v) implements S {}
                            record B() implements S {}

                            public static S m(BigInteger a) {
                                return new A(a);
                            }
                        }
                        """, "Probe", "m", List.of(new PlutusData.IntData(BigInteger.TWO)),
                        TagTable.of(new TagTable.Entry("Probe$A", "Probe$S", 1), new TagTable.Entry("Probe$B", "Probe$S", 0)), null));
        for (var diffCase : cases) {
            var report = runner.run(diffCase);
            assertTrue(report.all(Verdict.Kind.CRASH), report.describe());
            for (var config : DiffConfig.allLevels()) {
                var crash = assertInstanceOf(Observation.Crash.class, report.results().get(config).uplc(), report.describe());
                assertTrue(crash.detail().startsWith("result: representation"), crash.detail());
                var rerun = assertInstanceOf(Observation.Crash.class, runner.rerun(diffCase, config, 2).observation(), config.toString());
                assertTrue(rerun.detail().startsWith("result: representation"), rerun.detail());
            }
            assertTrue(DiffRunner.standard().run(diffCase).all(Verdict.Kind.AGREE), "without the seam: " + diffCase.name());
        }
        var unchecked = runner.run(new DiffCase("long", """
                public class Probe {
                    public static long m(long a) {
                        return a + 1;
                    }
                }
                """, "Probe", "m", List.of(new PlutusData.IntData(BigInteger.TWO))));
        assertTrue(unchecked.all(Verdict.Kind.AGREE), unchecked.describe());
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
