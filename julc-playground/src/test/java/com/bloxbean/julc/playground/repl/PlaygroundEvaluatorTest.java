package com.bloxbean.julc.playground.repl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaygroundEvaluatorTest {

    @Test
    void evaluatesDefaultPv11CaseBoolLoweringUnderCompiledTarget() {
        var result = new PlaygroundEvaluator().evaluate("MathLib.abs(-5)");

        assertTrue(result.success(), result::error);
        assertEquals("5", result.result());
        assertTrue(result.uplc().contains("(case"), result::uplc);
    }
}
