package org.julclang.vm.java.ndc;

import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.core.text.UplcParser;
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
        UplcEvaluation.Outcome outcome = UplcEvaluation.evaluate(null,
                "(program 1.0.0 [(lam x (lam y x)) (con integer 1)])", expected, 3, 11, DEFAULTS);

        assertEquals(expected, outcome.evaluation());
        assertNotNull(outcome.budget());
        assertTrue(outcome.budget().cpuSteps() > 0);
    }

    @Test
    void anotherResultIsReportedAsTheVmPrintsIt() {
        UplcEvaluation.Outcome outcome = UplcEvaluation.evaluate(null, "(program 1.0.0 (con integer 2))",
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
        UplcEvaluation.Outcome outcome = UplcEvaluation.evaluate(flat("(program 1.0.0 (lam x x))"),
                "(program 1.0.0 (con integer 1))", "(program 1.0.0 (lam x x))", 3, 11, DEFAULTS);

        assertEquals("(program 1.0.0 (lam x x))", outcome.evaluation());
    }

    @Test
    void aSuccessfulFlatEvaluationStandsEvenWhenTheTextDoesNotParse() {
        // The corpus's legacy-alias-test-vector-25: the text names a builtin by an alias the parser does not know.
        assertEquals("(program 1.0.0 (lam x x))", UplcEvaluation.evaluate(flat("(program 1.0.0 (lam x x))"),
                "(program 1.0.0 (builtin verifySignature))", "(program 1.0.0 (lam x x))", 3, 11, DEFAULTS)
                .evaluation());
    }

    @Test
    void aFailedFlatEvaluationFallsBackToTheText() {
        // The corpus's constr-07: its Flat program decodes (tag 2^64 - 5) but fails; its text, with a negative tag,
        // does not parse.
        String flat = flat("(program 1.1.0 (constr 18446744073709551611 (con integer 1) (error)))");
        assertEquals(UplcEvaluation.PARSE_ERROR, UplcEvaluation.evaluate(flat,
                "(program 1.1.0 (constr -5 (con integer 1) (error)))", "parse/decode error", 3, 11, DEFAULTS)
                .evaluation());
        assertEquals(UplcEvaluation.EVALUATION_FAILURE, UplcEvaluation.evaluate(flat, "(program 1.1.0 (error))",
                "evaluation failure", 3, 11, DEFAULTS).evaluation());
    }

    @Test
    void aFlatProgramThatDoesNotDecodeFallsBackToTheText() {
        assertEquals("(program 1.0.0 (con integer 1))", UplcEvaluation.evaluate("ff", "(program 1.0.0 (con integer 1))",
                "(program 1.0.0 (con integer 1))", 3, 11, DEFAULTS).evaluation());
    }

    @Test
    void errorLabels() {
        assertEquals(UplcEvaluation.EVALUATION_FAILURE,
                UplcEvaluation.evaluate(null, "(program 1.0.0 (error))", "evaluation failure", 3, 11, DEFAULTS)
                        .evaluation());
        assertEquals(UplcEvaluation.OUT_OF_BUDGET, UplcEvaluation.evaluate(null,
                "(program 1.0.0 [(lam x [x x]) (lam x [x x])])", "out of budget", 3, 11, DEFAULTS).evaluation());
        assertEquals(UplcEvaluation.PARSE_ERROR,
                UplcEvaluation.evaluate(null, "(program 1.0.0 (con integer", "parse/decode error", 3, 11, DEFAULTS)
                        .evaluation());
        // case and constr need program version 1.1.0, which PlutusV1 gets only from protocol version 11
        UplcEvaluation.Outcome gated = UplcEvaluation.evaluate(null, "(program 1.1.0 (constr 0))",
                "parse/decode error", 1, 10, DEFAULTS);
        assertEquals(UplcEvaluation.PARSE_ERROR, gated.evaluation());
        assertNull(gated.budget());
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
