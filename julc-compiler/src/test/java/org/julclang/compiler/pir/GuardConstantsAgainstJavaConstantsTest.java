package org.julclang.compiler.pir;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.VariableTree;
import org.julclang.compiler.frontend.javac.JavacFrontend;
import org.julclang.compiler.frontend.javac.JavacProgram;
import org.julclang.compiler.frontend.javac.subset.GuardConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ported evaluator (ADR-068 I9) against the two answers it must meet, neither derived from it: the frozen
 * {@code JavaConstants} on the same text, and javac's own folding, which is what {@code getConstantValue()} reads off a
 * constant field. So a value here is Java's, and what the reference classifies is what the port does.
 */
class GuardConstantsAgainstJavaConstantsTest {

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

    /** The javac side of one expression, as a field's initializer. */
    private record Read(GuardConstants.Result port, Object folded) {
    }

    private static Read javac(String expression) {
        String source = "class Probe {\n" + FIELDS + "static final Object PROBE_BOX = 0;\nstatic final " + typed(expression) + " PROBE = " + expression + ";\n}\n";
        var program = JavacProgram.analyze(List.of(JavacFrontend.Unit.of(source)), List.of());
        return program.read(facts -> {
            CompilationUnitTree unit = program.roots().getFirst();
            for (var member : ((ClassTree) unit.getTypeDecls().getFirst()).getMembers()) {
                if (member instanceof VariableTree field && field.getName().contentEquals("PROBE")) {
                    var element = (javax.lang.model.element.VariableElement) facts.element(field);
                    return new Read(new GuardConstants(facts).evaluate(field.getInitializer()), element.getConstantValue());
                }
            }
            throw new IllegalStateException();
        });
    }

    /** What declares an expression of each kind without a conversion. */
    private static String typed(String expression) {
        var result = JavaConstants.evaluate(new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21))
                .parse("class Probe {\n" + FIELDS + "static final Object PROBE = " + expression + ";\n}\n").getResult().orElseThrow()
                .findAll(FieldDeclaration.class).getLast().getVariable(0).getInitializer().orElseThrow());
        if (!result.constant() || result.unclassifiable()) return "Object";
        return switch (result.value().kind()) {
            case BOOLEAN -> "boolean";
            case INT -> "int";
            case LONG -> "long";
            case CHAR -> "char";
            case STRING -> "String";
        };
    }

    private static JavaConstants.Result reference(String expression) {
        var unit = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21))
                .parse("class Probe {\n" + FIELDS + "static final Object PROBE = " + expression + ";\n}\n").getResult().orElseThrow();
        return JavaConstants.evaluate(unit.findAll(FieldDeclaration.class).getLast().getVariable(0).getInitializer().orElseThrow());
    }

    private int compared;

    private void sameAnswers(String expression) {
        var reference = reference(expression);
        var read = javac(expression);
        var port = read.port();
        assertEquals(reference.constant(), port.constant(), expression);
        assertEquals(reference.unclassifiable(), port.unclassifiable(), expression);
        if (reference.constant() && !reference.unclassifiable()) {
            assertEquals(reference.value().value(), port.value().value(), expression);
            assertEquals(reference.value().kind().name(), port.value().kind().name(), expression);
            // javac 25 leaves a shift by a long unfolded, a constant to JLS 15.29 and to the reference: it runs to the same value.
            if (read.folded() != null || !expression.contains(">")) {
                assertEquals(port.value().value(), read.folded(), () -> "javac folds " + expression + " otherwise");
                compared++;
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "true", "false", "1", "1L", "\"a\"", "'c'", "1.5", "1.5f", "null",
            "(1)", "((1L))", "-1", "- 1", "+1", "~1", "!true", "-2147483648", "-2_147_483_648", "-9223372036854775808L", "-9223372036854775808l",
            "0xFFFFFFFF", "0x80000000", "-0x80000000", "017", "0b11", "1_000", "0xFFFFFFFFFFFFFFFFL",
            "2147483647 + 1", "2147483647L + 1", "-2147483648 - 1", "65536 * 65536", "7 / 2", "-7 / 2", "7 % 3", "-7 % 3", "1 / 0", "1 % 0", "1L / 0L",
            "1 << 31", "1 << 32", "1L << 32", "-1 >> 1", "-1 >>> 28", "-1L >>> 60", "1 << 33L", "5 & 3", "5 | 3", "5 ^ 3", "5L & 3",
            "1 == 1", "1 != 1", "1 < 2", "1 <= 1", "2 > 1", "1 >= 2", "1 == 1L", "1L < 2",
            "true && false", "true || false", "true & false", "true | false", "true ^ true", "true == true", "true != true",
            "\"a\" + \"b\"", "\"a\" + 1", "1 + \"a\"", "\"a\" + true", "\"a\" + 1L", "\"a\" == \"a\"", "\"a\" != \"a\"", "\"a\" + \"b\" == \"ab\"", "\"a\" + (2147483647 + 1)",
            "true ? 1 : 2", "false ? 1 : 2", "true ? 2147483647 : 0L", "(true ? 2147483647 : 0L) + 1", "true ? \"a\" : \"b\"", "true ? true : false", "true ? 1 : \"a\"", "true ? true : 0",
            "(int) 2147483648L", "(int) 1", "(long) 1", "(long) 2147483647 + 1", "(boolean) true", "(String) \"a\"", "(byte) 1", "(short) 1", "(char) 65", "(double) 1", "(float) 1", "(Object) 1",
            "N", "N + 1", "W << 32", "M + 1", "ON", "!ON", "T", "U", "T + \"v\"", "A", "A + 1", "B", "C", "H", "B + 1", "X", "Y", "Z", "NF", "Probe.N", "Probe.W << 32",
            "mutable", "mutable + 1", "Integer.MAX_VALUE", "Long.MAX_VALUE", "Math.abs(1)",
    })
    void eachForm(String expression) {
        sameAnswers(expression);
    }

    @Test
    void generatedExpressions() {
        var random = new Random(68);
        var constants = new ArrayList<String>();
        for (int i = 0; i < 400; i++) {
            String expression = number(random, 3);
            sameAnswers(expression);
            if (reference(expression).constant()) constants.add(expression);
        }
        assertTrue(constants.size() >= 300, "too few constants: " + constants.size());
        assertTrue(compared >= 200, "too few values met javac's: " + compared);
    }

    private static final String[] OPERATORS = {"+", "-", "*", "/", "%", "&", "|", "^", "<<", ">>", ">>>"};
    private static final String[] LEAVES = {"0", "1", "2", "-1", "31", "33", "2147483647", "-2147483648", "1L", "-1L", "9223372036854775807L", "N", "W", "M", "0xFFFFFFFF"};

    private static String number(Random random, int depth) {
        if (depth == 0 || random.nextInt(4) == 0) return LEAVES[random.nextInt(LEAVES.length)];
        return switch (random.nextInt(6)) {
            case 0 -> "(- " + number(random, depth - 1) + ")";
            case 1 -> "(~" + number(random, depth - 1) + ")";
            case 2 -> "((int) " + number(random, depth - 1) + ")";
            case 3 -> "(" + number(random, depth - 1) + " > " + number(random, depth - 1) + " ? " + number(random, depth - 1) + " : " + number(random, depth - 1) + ")";
            default -> "(" + number(random, depth - 1) + " " + OPERATORS[random.nextInt(OPERATORS.length)] + " " + number(random, depth - 1) + ")";
        };
    }
}
