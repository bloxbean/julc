package org.julclang.vm.java.ndc;

import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.core.text.UplcParser;
import org.julclang.vm.EvalResult;
import org.julclang.vm.java.JavaVmProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UplcEvaluationTest {

    /** The cost model: the VM's defaults. */
    private static final long[] DEFAULTS = null;

    private static String flat(String program) {
        return HexFormat.of().formatHex(UplcFlatEncoder.encodeProgram(UplcParser.parseProgram(program)));
    }

    @Test
    void anAlphaEquivalentResultIsReportedAsTheExpectedText() {
        String expected = "(program 1.0.0 (lam z (con integer 1)))";
        UplcEvaluation.Outcome outcome = UplcEvaluation.evaluate(null, null,
                "(program 1.0.0 [(lam x (lam y x)) (con integer 1)])", expected, 3, 11, DEFAULTS);

        assertEquals(expected, outcome.evaluation());
        assertNotNull(outcome.budget());
        assertTrue(outcome.budget().cpuSteps() > 0);
    }

    @Test
    void anotherResultIsReportedAsTheVmPrintsIt() {
        UplcEvaluation.Outcome outcome = UplcEvaluation.evaluate(null, null, "(program 1.0.0 (con integer 2))",
                "(program 1.0.0 (con integer 1))", 3, 11, DEFAULTS);

        assertEquals("(program 1.0.0 (con integer 2))", outcome.evaluation());
    }

    @Test
    void aFreeVariableIsNotEquivalentToABoundOne() {
        assertFalse(UplcEvaluation.alphaEquivalent(UplcParser.parseProgram("(program 1.0.0 (lam x (lam y x)))"),
                UplcParser.parseProgram("(program 1.0.0 (lam x (lam y y)))")));
        assertFalse(UplcEvaluation.alphaEquivalent(UplcParser.parseProgram("(program 1.0.0 (lam x x))"),
                UplcParser.parseProgram("(program 1.1.0 (lam x x))")));
    }

    @Test
    void theFlatProgramIsEvaluatedWhenPresent() {
        UplcEvaluation.Outcome outcome = UplcEvaluation.evaluate(flat("(program 1.0.0 (lam x x))"), null,
                "(program 1.0.0 (con integer 1))", "(program 1.0.0 (lam x x))", 3, 11, DEFAULTS);

        assertEquals("(program 1.0.0 (lam x x))", outcome.evaluation());
    }

    @Test
    void aSuccessfulFlatEvaluationStandsEvenWhenTheTextDoesNotParse() {
        // The corpus's legacy-alias-test-vector-25: the text names a builtin by an alias the parser does not know.
        assertEquals("(program 1.0.0 (lam x x))", UplcEvaluation.evaluate(flat("(program 1.0.0 (lam x x))"), null,
                "(program 1.0.0 (builtin verifySignature))", "(program 1.0.0 (lam x x))", 3, 11, DEFAULTS)
                .evaluation());
    }

    @Test
    void aFailedFlatEvaluationFallsBackToTheText() {
        // The corpus's constr-07: its Flat program decodes (tag 2^64 - 5) but fails; its text, with a negative tag,
        // does not parse.
        String flat = flat("(program 1.1.0 (constr 18446744073709551611 (con integer 1) (error)))");
        assertEquals(UplcEvaluation.PARSE_ERROR, UplcEvaluation.evaluate(flat, null,
                "(program 1.1.0 (constr -5 (con integer 1) (error)))", "parse/decode error", 3, 11, DEFAULTS)
                .evaluation());
        assertEquals(UplcEvaluation.EVALUATION_FAILURE, UplcEvaluation.evaluate(flat, null, "(program 1.1.0 (error))",
                "evaluation failure", 3, 11, DEFAULTS).evaluation());
    }

    @Test
    void aFlatProgramThatDoesNotDecodeFallsBackToTheText() {
        assertEquals("(program 1.0.0 (con integer 1))", UplcEvaluation.evaluate("ff", null,
                "(program 1.0.0 (con integer 1))", "(program 1.0.0 (con integer 1))", 3, 11, DEFAULTS).evaluation());
    }

    @Test
    void errorLabels() {
        assertEquals(UplcEvaluation.EVALUATION_FAILURE,
                UplcEvaluation.evaluate(null, null, "(program 1.0.0 (error))", "evaluation failure", 3, 11, DEFAULTS)
                        .evaluation());
        assertEquals(UplcEvaluation.OUT_OF_BUDGET, UplcEvaluation.evaluate(null, null,
                "(program 1.0.0 [(lam x [x x]) (lam x [x x])])", "out of budget", 3, 11, DEFAULTS).evaluation());
        assertEquals(UplcEvaluation.PARSE_ERROR,
                UplcEvaluation.evaluate(null, null, "(program 1.0.0 (con integer", "parse/decode error", 3, 11,
                        DEFAULTS).evaluation());
        // case and constr need program version 1.1.0, which PlutusV1 gets only from protocol version 11
        UplcEvaluation.Outcome gated = UplcEvaluation.evaluate(null, null, "(program 1.1.0 (constr 0))",
                "parse/decode error", 1, 10, DEFAULTS);
        assertEquals(UplcEvaluation.PARSE_ERROR, gated.evaluation());
        assertNull(gated.budget());
    }

    @Test
    void theFlatProgramAloneMustGiveWhatTheVectorSays() {
        String flat = flat("(program 1.0.0 [(lam x x) (con integer 7)])");
        String seven = flat("(program 1.0.0 (con integer 7))");
        String text = "(program 1.0.0 (con integer 7))";
        String expected = "(program 1.0.0 (con integer 7))";

        assertTrue(UplcEvaluation.evaluate(flat, seven, text, expected, 3, 11, DEFAULTS).problems().isEmpty());
        // a Flat program that does not decode: the reported outcome comes from the text and still matches, but the
        // Flat check reports it
        UplcEvaluation.Outcome undecoded = UplcEvaluation.evaluate("ff", seven, text, expected, 3, 11, DEFAULTS);
        assertEquals(expected, undecoded.evaluation());
        assertEquals(1, undecoded.problems().size());
        assertTrue(undecoded.problems().getFirst().contains("parse/decode error"), undecoded.problems()::toString);
        // another result
        String eight = flat("(program 1.0.0 (con integer 8))");
        assertTrue(UplcEvaluation.evaluate(flat, eight, text, expected, 3, 11, DEFAULTS).problems().getFirst()
                .contains("differs"));
        // an error label
        assertTrue(UplcEvaluation.evaluate(flat("(program 1.0.0 (error))"), "evaluation failure\n",
                "(program 1.0.0 (error))", "evaluation failure", 3, 11, DEFAULTS).problems().isEmpty());
        assertEquals(1, UplcEvaluation.evaluate(flat, "evaluation failure", text, expected, 3, 11, DEFAULTS)
                .problems().size());
        // a vector that does not say what its Flat program gives is not checked
        assertTrue(UplcEvaluation.evaluate("ff", null, text, expected, 3, 11, DEFAULTS).problems().isEmpty());
    }

    @Test
    void theScenarioBudgetDecidesWhenTheScenarioExpectsOutOfBudget() {
        // .flat.expected is computed without the scenario's budget: a program that needs more than 10B CPU has a result
        // there, and runs out of budget here
        String loop = flat("(program 1.0.0 [(lam x [x x]) (lam x [x x])])");
        String text = "(program 1.0.0 [(lam x [x x]) (lam x [x x])])";
        String result = flat("(program 1.0.0 (con integer 1))");

        assertEquals(1, UplcEvaluation.evaluate(loop, result, text, "out of budget", null, 3, 11, DEFAULTS)
                .problems().size());
        assertTrue(UplcEvaluation.evaluate(loop, result, text, "out of budget", "out of budget", 3, 11, DEFAULTS)
                .problems().isEmpty());
    }

    @Test
    void anUndecodableExpectedFlatResultIsAProblem() {
        UplcEvaluation.Run success = new UplcEvaluation.Run(UplcEvaluation.Kind.SUCCESS,
                UplcParser.parseProgram("(program 1.0.0 (con integer 7))"), null, null);
        assertTrue(UplcEvaluation.flatProblem(success, "ff").contains("does not decode"));
    }

    @Test
    void anEvaluationErrorIsNotACrash() {
        var program = UplcParser.parseProgram(
                "(program 1.0.0 [(builtin divideInteger) (con integer 1) (con integer 0)])");
        var target = UplcEvaluation.target(3, 11);
        var provider = new JavaVmProvider();
        var result = provider.evaluate(program, target, UplcEvaluation.BUDGET, UplcEvaluation.OPTIONS);
        assertTrue(result instanceof EvalResult.Failure);
        assertNull(UplcEvaluation.crashOf(provider, program, target, (EvalResult.Failure) result));
        assertTrue(UplcEvaluation.evaluate(null, null, "(program 1.0.0 (error))", "evaluation failure", 3, 11,
                DEFAULTS).problems().isEmpty());
    }

    @Test
    void readsACostModelFile(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("v3.json"), "[100788, 420,\n 1]");
        assertArrayEquals(new long[]{100788, 420, 1}, UplcEvaluation.costModel(file));

        Path notArray = Files.writeString(dir.resolve("bad.json"), "{\"a\": 1}");
        assertThrows(IllegalArgumentException.class, () -> UplcEvaluation.costModel(notArray));
        Path notInteger = Files.writeString(dir.resolve("bad2.json"), "[1, \"x\"]");
        assertThrows(IllegalArgumentException.class, () -> UplcEvaluation.costModel(notInteger));
    }

    @Test
    void targets() {
        assertEquals("PLUTUS_V2", UplcEvaluation.target(2, 9).ledgerLanguage().name());
        assertEquals(9, UplcEvaluation.target(2, 9).protocolVersion().major());
        assertThrows(IllegalArgumentException.class, () -> UplcEvaluation.target(4, 11));
    }
}
