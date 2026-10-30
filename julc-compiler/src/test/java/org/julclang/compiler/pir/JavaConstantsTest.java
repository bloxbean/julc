package org.julclang.compiler.pir;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.stmt.ReturnStmt;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-067 M1 review round 3: the verified constant subset (boolean, int, long, String with JLS fixed-width semantics).
 * Each expression is the return value of {@code m} in a class with the given fields; a constant outside the subset is
 * unclassifiable, never non-constant or guessed.
 */
class JavaConstantsTest {

    private static JavaConstants.Result evaluate(String fields, String expression) {
        String source = "class Probe {\n" + fields + "\n static boolean m(long n) { return " + expression + "; }\n}\n";
        var unit = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21))
                .parse(source).getResult().orElseThrow();
        Expression e = unit.findFirst(ReturnStmt.class).orElseThrow().getExpression().orElseThrow();
        return JavaConstants.evaluate(e);
    }

    private static void constant(String fields, String expression, Object value) {
        var result = evaluate(fields, expression);
        assertTrue(result.constant() && !result.unclassifiable(), expression + ": " + result);
        assertEquals(value, result.value().value(), expression);
    }

    @Test
    void fixedWidthIntAndLong() {
        constant("", "(2147483647 + 1) < 0", true);
        constant("", "((int) 2147483648L) < 0", true);
        constant("static final int N = 2147483647;", "N + 1 < 0", true);
        // F70: the declared long type, not the initializer's int, governs the shift.
        constant("static final long N = 1;", "(N << 32) == 4294967296L", true);
        constant("static final long N = 2147483647;", "N + 1 > 0", true);
        constant("", "((true ? 2147483647 : 0L) + 1) > 0", true);
        // A hex int literal is a 32-bit pattern; a long constant initialized by an int overflow keeps the wrapped value.
        constant("", "0xFFFFFFFF == -1", true);
        constant("static final long L = 2147483647 + 1;", "L == -2147483648L", true);
    }

    @Test
    void minimumValueLiteralsAndOutOfRangeLiterals() {
        // F82: the decimal literals 2147483648 and 9223372036854775808L are legal only as the direct operand of unary
        // minus (JLS 3.10.1); elsewhere javac rejects them, and they are unclassifiable.
        constant("", "-2147483648 < 0", true);
        constant("", "-2147483648 == 0x80000000", true);
        constant("", "-9223372036854775808L < 0", true);
        constant("", "-2_147_483_648 == -2147483647 - 1", true);
        assertTrue(evaluate("", "-(2147483648) < 0").unclassifiable());
        assertTrue(evaluate("", "2147483648 > 0").unclassifiable());
        assertTrue(evaluate("", "9223372036854775808L > 0").unclassifiable());
        assertTrue(evaluate("static final int N = 2147483648;", "N > 0").unclassifiable());
        assertTrue(invalid("static final int N = 2147483648;", "N > n").contains("'N = 2147483648' cannot be evaluated exactly"));
    }

    @Test
    void paramFieldsAreNeverConstant() {
        // F41/F80: a @Param field is the applied parameter on-chain, so it is not folded, directly or through another
        // constant (#293 for its JVM meaning).
        assertFalse(evaluate("@Param static final boolean ON = true;", "ON").constant());
        assertFalse(evaluate("@org.julclang.stdlib.annotation.Param static final long LIMIT = 5;", "LIMIT > 0").constant());
        assertFalse(evaluate("@Param static final long LIMIT = 5; static final long M = LIMIT;", "M > 0").constant());
        assertFalse(evaluate("@Param static final long LIMIT = 5;", "Probe.LIMIT > 0").constant());
    }

    @Test
    void declarationsAGuardReadsAreValidated() {
        // F71: simple, qualified and transitive references to a declaration javac rejects; a legal chain passes.
        assertTrue(invalid("static final int N = 4294967296L;", "N > n").contains("'N = 4294967296L' narrows"));
        assertTrue(invalid("static final int N = 4294967296L;", "Probe.N > n").contains("'N = 4294967296L' narrows"));
        assertTrue(invalid("static final int N = 4294967296L; static final int M = N;", "M > n").contains("'N = 4294967296L' narrows"));
        assertNull(invalid("static final int N = 4; static final long M = N;", "M > n"));
    }

    private static String invalid(String fields, String expression) {
        String source = "class Probe {\n" + fields + "\n static boolean m(long n) { return " + expression + "; }\n}\n";
        var unit = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21))
                .parse(source).getResult().orElseThrow();
        return JavaConstants.invalidDeclaration(unit.findFirst(ReturnStmt.class).orElseThrow().getExpression().orElseThrow());
    }

    @Test
    void stringsAndBooleans() {
        constant("", "\"a\" + \"b\" == \"ab\"", true);
        constant("static final boolean ON = true;", "!ON", false);
        constant("static final String K = \"x\";", "K == \"x\"", true);
    }

    @Test
    void outsideTheSubsetIsUnclassifiable() {
        // F66: a narrowing initializer is a declaration javac rejects.
        assertTrue(evaluate("static final int N = 4294967296L;", "N > 0").unclassifiable());
        // F63: char, byte, short and floating point.
        assertTrue(evaluate("", "'a' < 'b'").unclassifiable());
        assertTrue(evaluate("", "(byte) 200 == -56").unclassifiable());
        assertTrue(evaluate("", "1.5 > 1.0").unclassifiable());
        assertTrue(evaluate("static final byte B = 3;", "B > 0").unclassifiable());
        assertTrue(evaluate("", "1 / 0 == 0").unclassifiable());
    }

    @Test
    void notConstant() {
        assertFalse(evaluate("", "n > 0").constant());
        // An instance field is not a constant variable readable from the static method.
        assertFalse(evaluate("final boolean ON = true;", "ON").constant());
        assertFalse(evaluate("static boolean ON = true;", "ON").constant());
    }
}
