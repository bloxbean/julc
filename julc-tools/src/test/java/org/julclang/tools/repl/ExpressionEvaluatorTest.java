package org.julclang.tools.repl;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpressionEvaluatorTest {

    @Test
    void aCollectionJavacInfersFromAPrimitiveEvaluates() {
        var control = new ExpressionEvaluator().evaluate("JulcList.of(BigInteger.ONE)");
        assertTrue(control.success(), control::error);
        for (String expression : List.of("JulcList.of(1)", "JulcList.of(1L)")) {
            var result = new ExpressionEvaluator().evaluate(expression);
            assertTrue(result.success(), () -> expression + ": " + result.error());
            assertEquals(control.result(), result.result(), expression);
        }
    }

    @Test
    void evaluatesDefaultPv11CaseBoolLoweringUnderCompiledTarget() {
        var result = new ExpressionEvaluator().evaluate("MathLib.abs(BigInteger.valueOf(-5))");

        assertTrue(result.success(), result::error);
        assertEquals("5", result.result());
        assertTrue(result.uplc().contains("(case"), result::uplc);
    }
}
