package org.julclang.compiler.frontend.javac.subset;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.VariableTree;
import org.julclang.compiler.frontend.javac.JavacFrontend;
import org.julclang.compiler.frontend.javac.JavacProgram;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.VariableElement;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The constants a guard may use (ADR-068 I9), against two answers neither derived from the class: {@code guard-constants.tsv},
 * what the reference's {@code JavaConstants} classified on each text before it was removed, and javac's own folding, which is
 * what {@code getConstantValue()} reads off a constant field. So a value here is Java's. A constant outside the subset is
 * unclassifiable, never non-constant or guessed.
 */
class GuardConstantsTest {

    private static final String FIELDS = """
            static final int N = 2147483647;
            static final long W = 1;
            static final long M = 2147483647;
            static final boolean ON = true;
            static final String T = "t";
            static final String U = T + "u";
            static final int A = N;
            static final byte B = 1;
            static final char C = 'c';
            static final short H = 2;
            static final int X = 'c';
            static final int Y = (short) 5;
            static final int Z = 1 / 0;
            static final long NF = 5;
            static int mutable = 5;
            final int instance = 5;
            """;

    /** The class's answer on one expression, and javac's folding of it. */
    private record Read(GuardConstants.Result result, Object folded) {
    }

    private static Read read(String fields, String type, String expression) {
        String source = "import org.julclang.stdlib.annotation.Param;\nclass Probe {\n" + fields + "static final " + type + " PROBE = "
                + expression + ";\n}\n";
        var program = JavacProgram.analyze(List.of(JavacFrontend.Unit.of(source)), List.of());
        return program.read(facts -> {
            var unit = program.roots().getFirst();
            for (var member : ((ClassTree) unit.getTypeDecls().getLast()).getMembers()) {
                if (member instanceof VariableTree field && field.getName().contentEquals("PROBE")) {
                    var element = (VariableElement) facts.element(field);
                    return new Read(new GuardConstants(facts).evaluate(field.getInitializer()), element.getConstantValue());
                }
            }
            throw new IllegalStateException();
        });
    }

    private static GuardConstants.Result evaluate(String fields, String expression) {
        return read(fields + "\n", "Object", expression).result();
    }

    private static void constant(String fields, String expression, Object value) {
        var result = evaluate(fields, expression);
        assertTrue(result.constant() && !result.unclassifiable(), expression + ": " + result);
        assertEquals(value, result.value().value(), expression);
    }

    private static String declaredType(String kind) {
        return switch (kind) {
            case "BOOLEAN" -> "boolean";
            case "INT" -> "int";
            case "LONG" -> "long";
            case "STRING" -> "String";
            default -> "Object";
        };
    }

    private static Object valueOf(String kind, String text) {
        return switch (kind) {
            case "BOOLEAN" -> Boolean.valueOf(text);
            case "INT" -> Integer.valueOf(text);
            case "LONG" -> Long.valueOf(text);
            case "STRING" -> text;
            default -> throw new IllegalArgumentException(kind);
        };
    }

    /** Every form the frozen table holds: its classification, its kind and value, and, where javac folds it, javac's value. */
    @Test
    void everyFormTakesTheFrozenAnswerAndJavacsValue() throws IOException {
        String table;
        try (InputStream in = GuardConstantsTest.class.getResourceAsStream("/adr068/guard-constants.tsv")) {
            table = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int forms = 0;
        int constants = 0;
        int folded = 0;
        for (String row : table.split("\n")) {
            var cells = row.split("\t", -1);
            String expression = cells[0];
            boolean constant = Boolean.parseBoolean(cells[1]);
            boolean unclassifiable = Boolean.parseBoolean(cells[2]);
            String kind = cells[3];
            var read = read(FIELDS, declaredType(kind), expression);
            forms++;
            assertEquals(constant, read.result().constant(), expression);
            assertEquals(unclassifiable, read.result().unclassifiable(), expression);
            if (constant && !unclassifiable) {
                constants++;
                assertEquals(kind, read.result().value().kind().name(), expression);
                assertEquals(valueOf(kind, cells[4]), read.result().value().value(), expression);
                // javac 25 leaves a shift by a long unfolded, a constant to JLS 15.29: it runs to the same value elsewhere.
                if (read.folded() != null || !expression.contains(">")) {
                    assertEquals(read.result().value().value(), read.folded(), () -> "javac folds " + expression + " otherwise");
                    folded++;
                }
            }
        }
        assertTrue(forms >= 400, "too few forms: " + forms);
        assertTrue(constants >= 300, "too few constants: " + constants);
        assertTrue(folded >= 200, "too few values met javac's: " + folded);
    }

    @Test
    void fixedWidthIntAndLong() {
        constant("", "(2147483647 + 1) < 0", true);
        constant("", "((int) 2147483648L) < 0", true);
        constant("static final int N = 2147483647;", "N + 1 < 0", true);
        // The declared long type, not the initializer's int, governs the shift.
        constant("static final long N = 1;", "(N << 32) == 4294967296L", true);
        constant("static final long N = 2147483647;", "N + 1 > 0", true);
        constant("", "((true ? 2147483647 : 0L) + 1) > 0", true);
        // A hex int literal is a 32-bit pattern; a long constant initialized by an int overflow keeps the wrapped value.
        constant("", "0xFFFFFFFF == -1", true);
        constant("static final long L = 2147483647 + 1;", "L == -2147483648L", true);
    }

    @Test
    void minimumValueLiterals() {
        constant("", "-2147483648 < 0", true);
        constant("", "-2147483648 == 0x80000000", true);
        constant("", "-9223372036854775808L < 0", true);
        constant("", "-2_147_483_648 == -2147483647 - 1", true);
        constant("", "-9_223_372_036_854_775_808L == -9223372036854775807L - 1", true);
        constant("", "-9223372036854775808l < 0", true);
    }

    @Test
    void paramFieldsAreNeverConstant() {
        // A @Param field is the applied parameter on-chain, so it is not folded, directly or through another constant.
        assertFalse(evaluate("@Param static final boolean ON = true;", "ON").constant());
        assertFalse(evaluate("@org.julclang.stdlib.annotation.Param static final long LIMIT = 5;", "LIMIT > 0").constant());
        assertFalse(evaluate("@Param static final long LIMIT = 5; static final long M = LIMIT;", "M > 0").constant());
        assertFalse(evaluate("@Param static final long LIMIT = 5;", "Probe.LIMIT > 0").constant());
    }

    @Test
    void stringsAndBooleans() {
        constant("", "\"a\" + \"b\" == \"ab\"", true);
        constant("static final boolean ON = true;", "!ON", false);
        constant("static final String K = \"x\";", "K == \"x\"", true);
    }

    @Test
    void outsideTheSubsetIsUnclassifiable() {
        assertTrue(evaluate("", "'a' < 'b'").unclassifiable());
        assertTrue(evaluate("", "(byte) 200 == -56").unclassifiable());
        assertTrue(evaluate("", "1.5 > 1.0").unclassifiable());
        assertTrue(evaluate("static final byte B = 3;", "B > 0").unclassifiable());
        assertTrue(evaluate("", "1 / 0 == 0").unclassifiable());
    }

    @Test
    void notConstant() {
        assertFalse(evaluate("static int n = 1;", "n > 0").constant());
        // An instance field and a non-final field are not constant variables.
        assertFalse(evaluate("static boolean ON = true;", "ON").constant());
    }
}
