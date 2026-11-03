package org.julclang.cli.mcp.lint;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every {@link LintEngineTest} case read by javac's parser (ADR-068 M6): the findings, positions included, are the ones
 * JavaParser's reading gives, and the tests' assertions hold on them. Then the shapes where the two trees differ.
 */
class LintEngineOnJavacTest extends LintEngineTest {

    private final LintEngine javaParser = new LintEngine(LintEngine.DEFAULT_RULES, LintEngine.Reader.JAVAPARSER);
    private final LintEngine javac = new LintEngine(LintEngine.DEFAULT_RULES, LintEngine.Reader.JAVAC);

    @Override
    List<LintFinding> lint(String source) {
        var expected = javaParser.lint(source);
        var actual = javac.lint(source);
        assertEquals(expected, actual, source);
        return actual;
    }

    @Test
    void tabsCountOneColumnAndEscapesKeepTheirWrittenPositions() {
        var findings = lint("class T {\n\tstatic int f(int i) {\n\t\ti++; int \\u0078; return i;\n\t}\n}\n");
        assertEquals(2, findings.size(), findings.toString());
        assertEquals(3, findings.getFirst().line());
        assertEquals(3, findings.getFirst().column());
    }

    @Test
    void declaratorsAfterTheFirstAreFoundAfterTheirComma() {
        var findings = lint("""
                class T {
                    static boolean f() {
                        int a = 1, /* b */ b, c[];
                        for (int i = 0, j; ; ) { }
                        try (var r = open(); ) { }
                        return true;
                    }
                }
                """);
        assertEquals(3, findings.stream().filter(f -> f.ruleId().equals("JULC-LINT-UNINITIALIZED-VAR")).count(),
                findings.toString());
    }

    @Test
    void namesInTypesAndCallsAreNotReferencesOfAParameter() {
        var findings = lint("""
                class T {
                    sealed interface I permits A {}
                    record A(java.math.BigInteger time) implements I {}
                    static boolean f(I i, java.math.BigInteger time, java.math.BigInteger time2) {
                        return switch (i) {
                            case A a -> { time2 x = null; time(); yield a.time().equals(java.math.BigInteger.ONE); }
                        };
                    }
                    static boolean g(I i, java.math.BigInteger time) {
                        return switch (i) {
                            case A a -> switch (i) { case A b -> time.signum() > 0; };
                        };
                    }
                }
                """);
        assertEquals(2, findings.size(), findings.toString());
    }

    @Test
    void varargsLabelsAndNestedSwitchesMatchJavaParser() {
        var findings = lint("""
                import org.julclang.stdlib.annotation.Entrypoint;
                class T {
                    @Entrypoint static boolean v(java.math.BigInteger... xs) { return true; }
                    static boolean f(Object o) {
                        return switch (o) {
                            case Tuple2(var a, var b) -> switch (o) { case Tuple3 t -> true; default -> false; };
                            case final Tuple2 t -> false;
                            default -> new PlutusData.ConstrData(0, null) != null;
                        };
                    }
                    static int g() { while (true) { Runnable r = () -> { return; }; return 1; } }
                }
                """);
        assertTrue(findings.size() >= 5, findings.toString());
    }

    @Test
    void aSourceJavacRejectsHasNoFindings() {
        assertEquals(List.of(), javac.lint("class { not valid Java"));
    }
}
