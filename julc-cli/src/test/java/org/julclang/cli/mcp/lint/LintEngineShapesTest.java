package org.julclang.cli.mcp.lint;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shapes where javac's syntax tree and the tree the rules were first written on differ (ADR-068 M6). The counts are the
 * findings both readings gave while both ran.
 */
class LintEngineShapesTest {

    private final LintEngine engine = new LintEngine();

    private List<LintFinding> lint(String source) {
        return engine.lint(source);
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
        // The inner switch's case is reported twice, once under each switch it is in, as the rules always have.
        assertEquals(3, findings.size(), findings.toString());
    }

    @Test
    void varargsLabelsAndNestedSwitches() {
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
        assertEquals(List.of(), engine.lint("class { not valid Java"));
    }

    @Test
    void shapesAnIndependentReviewFound() {
        // `var` without an initializer, a C-style array after an array type, and many declarators.
        lint("class T { void m() { var x; final var y; int[] a[]; int b0, b1, b2, b3, b4, b5; } }");
        // A method reference's qualifier reads as a type, whatever its case.
        lint("""
                class T {
                    sealed interface I permits A {}
                    record A(java.math.BigInteger time) implements I {}
                    static boolean f(I i, java.math.BigInteger time, JulcList<java.math.BigInteger> xs) {
                        return switch (i) { case A a -> xs.any(time::equals); };
                    }
                }
                """);
        // An enum constant is not a field.
        lint("""
                class T {
                    static int RED;
                    static void m() { enum Color { RED } RED = 1; }
                }
                """);
        lint("enum E { A, B; @Param static PlutusData.BytesData p; }");
    }
}
