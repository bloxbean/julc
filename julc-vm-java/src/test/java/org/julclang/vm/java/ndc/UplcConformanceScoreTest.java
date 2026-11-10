package org.julclang.vm.java.ndc;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UplcConformanceScoreTest {

    private static JsonNode json(String text) throws IOException {
        return NdcReports.JSON.readTree(text.replace('\'', '"'));
    }

    private static String entry(String id, String plutus, String protocol, String evaluation, String budget) {
        return "{'id': '" + id + "', 'group': 'term/a', 'plutus_version': '" + plutus + "', 'protocol_version': '"
                + protocol + "', 'evaluation': '" + evaluation + "', 'budget': " + budget + "}";
    }

    @Test
    void ignoresPrettyPrinterWhitespaceButNotStringContents() {
        assertEquals("(program 1.0.0 [(lam x x) (con integer 1)])",
                UplcConformanceScore.normalize("(program 1.0.0\n  [ (lam x x)\n (con integer 1) ]\n)"));
        assertEquals(UplcConformanceScore.normalize("(con (list integer) [1, 2,3])"),
                UplcConformanceScore.normalize("(con (list integer) [ 1 ,2 , 3 ])"));
        assertTrue(UplcConformanceScore.normalize("(con string \"a  b\")").contains("\"a  b\""));
        assertFalse(UplcConformanceScore.normalize("(con string \"a b\")")
                .equals(UplcConformanceScore.normalize("(con string \"a  b\")")));
    }

    @Test
    void scoresEvaluationsAndExpectedBudgets() throws IOException {
        JsonNode expectations = json("[" + String.join(",",
                entry("a_v3", "V3", "11.0", "(program 1.0.0 (con integer 1))", "{'cpu': 10, 'mem': 2}"),
                entry("b_v3", "V3", "11.0", "evaluation failure", "null"),
                entry("c_v3", "V3", "11.0", "(program 1.0.0 (con integer 1))", "{'cpu': 10, 'mem': 2}"),
                entry("d_v3", "V3", "11.0", "(program 1.0.0 (con integer 1))", "null"),
                entry("e_v3", "V3", "11.0", "out of budget", "null"),
                entry("a_v3", "V3", "10.0", "parse/decode error", "null")) + "]");
        JsonNode report = json("[" + String.join(",",
                entry("a_v3", "V3", "11.0", "(program 1.0.0\\n  (con integer 1))", "{'cpu': 10, 'mem': 2}"),
                entry("b_v3", "V3", "11.0", "evaluation failure", "null"),
                entry("c_v3", "V3", "11.0", "(program 1.0.0 (con integer 1))", "{'cpu': 11, 'mem': 2}"),
                entry("d_v3", "V3", "11.0", "(program 1.0.0 (con integer 1))", "{'cpu': 1, 'mem': 1}"),
                entry("e_v3", "V3", "11.0", "evaluation failure", "null"),
                entry("a_v3", "V3", "10.0", "parse/decode error", "null")) + "]");

        UplcConformanceScore.Result result = UplcConformanceScore.score(report, expectations);

        assertEquals(6, result.total());
        assertEquals(4, result.passed());
        assertEquals(2, result.budgeted());
        assertEquals(1, result.budgetPassed());
        assertEquals(2, result.mismatches().size());
        UplcConformanceScore.Mismatch budget = result.mismatches().get(0);
        assertEquals("c_v3 V3/11.0", budget.key());
        assertTrue(budget.evaluationPassed());
        assertFalse(budget.budgetPassed());
        UplcConformanceScore.Mismatch evaluation = result.mismatches().get(1);
        assertEquals("e_v3 V3/11.0", evaluation.key());
        assertEquals("evaluation failure", evaluation.actualEvaluation());
    }

    @Test
    void aMissingEntryOrAnotherVersionFails() throws IOException {
        JsonNode expectations = json("[" + entry("a_v3", "V3", "11.0", "evaluation failure", "null") + "]");

        UplcConformanceScore.Result missing = UplcConformanceScore.score(json("[]"), expectations);
        assertEquals(0, missing.passed());
        assertNull(missing.mismatches().getFirst().actualEvaluation());

        UplcConformanceScore.Result otherProtocol = UplcConformanceScore.score(
                json("[" + entry("a_v3", "V3", "10.0", "evaluation failure", "null") + "]"), expectations);
        assertEquals(0, otherProtocol.passed());
    }

    @Test
    void refusesTwoEntriesForOneVector() throws IOException {
        String a = entry("a_v3", "V3", "11.0", "evaluation failure", "null");
        assertThrows(IllegalStateException.class,
                () -> UplcConformanceScore.score(json("[" + a + "," + a + "]"), json("[" + a + "]")));
    }
}
