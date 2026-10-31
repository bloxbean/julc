package org.julclang.compiler;

import org.julclang.core.Constant;
import org.julclang.core.PlutusData;
import org.julclang.core.Term;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalOptions;
import org.julclang.vm.EvalResult;
import org.julclang.vm.ExBudget;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-068 D6 (D4 exception 1) on the JavaParser frontend: the lowering of {@code a.compareTo(b) OP 0} and of the
 * relational operators. ComparisonOrderDiffTest (julc-difftest) checks the semantics against the JVM; this test
 * pins the minimal binding, the forms outside the rule, the dialect, and dispatch decisions taken before the rule.
 */
class ComparisonLoweringTest {

    private static final String HEADER = """
            import java.math.BigInteger;
            import org.julclang.stdlib.Builtins;
            class P {
                static BigInteger t(String k, BigInteger v) { return Builtins.unIData(Builtins.trace(k, Builtins.iData(v))); }
            """;

    private static String source(String body) {
        return HEADER + "static boolean m(BigInteger a, BigInteger b) { " + body + " } }";
    }

    private static String pir(String body) {
        return new JulcCompiler(StdlibRegistry.defaultRegistry(),
                new CompilerOptions().setOptimizationLevel(OptimizationLevel.NONE)).compileMethod(source(body), "m")
                .pirFormatted();
    }

    private static int count(String text, String needle) {
        return text.split(Pattern.quote(needle), -1).length - 1;
    }

    private static final List<String> OPERATORS = List.of("<", "<=", ">", ">=", "==", "!=");

    @Test
    void theRuleLowersEveryFormToOneBuiltinWithoutBinding() {
        for (String op : OPERATORS) {
            for (String form : List.of("a.compareTo(b) " + op + " 0", "0 " + op + " a.compareTo(b)")) {
                String pir = pir("return " + form + ";");
                assertEquals(0, count(pir, "(con integer -1)"), form + ": the three-way result is gone\n" + pir);
                assertEquals(0, count(pir, "(let #lhs"), form + "\n" + pir);
                assertEquals(1, count(pir, "Integer) "), form + ": one integer comparison builtin\n" + pir);
            }
        }
    }

    @Test
    void zeroLeftFormsReverseTheOperator() {
        // 0 < c is c > 0: lessThanInteger b a; 0 > c is c < 0: lessThanInteger a b.
        assertTrue(pir("return 0 < a.compareTo(b);").contains("[[(builtin lessThanInteger) b] a]"));
        assertTrue(pir("return 0 > a.compareTo(b);").contains("[[(builtin lessThanInteger) a] b]"));
        assertTrue(pir("return 0 <= a.compareTo(b);").contains("[[(builtin lessThanEqualsInteger) b] a]"));
        assertTrue(pir("return 0 >= a.compareTo(b);").contains("[[(builtin lessThanEqualsInteger) a] b]"));
        assertTrue(pir("return 0 != a.compareTo(b);").contains(
                "(if [[(builtin equalsInteger) a] b] then (con bool False) else (con bool True))"));
    }

    @Test
    void onlyGreaterThanBindsAndOnlyWhenBothOperandsAreNonTrivial() {
        String x = "t(\"A\", a)";
        String y = "t(\"B\", b)";
        for (String op : OPERATORS) {
            boolean swaps = op.startsWith(">");  // > and >=
            String both = pir("return " + x + ".compareTo(" + y + ") " + op + " 0;");
            assertEquals(swaps ? 1 : 0, count(both, "(let #lhs = [[P.t (con string \"A\")] a] in"), op + "\n" + both);
            assertEquals(0, count(pir("return a.compareTo(" + y + ") " + op + " 0;"), "(let #lhs"), op);
            assertEquals(0, count(pir("return " + x + ".compareTo(BigInteger.ONE) " + op + " 0;"), "(let #lhs"), op);
        }
        // The relational operators (#295): the same binding for > and >=, none for < and <=.
        for (String op : List.of("<", "<=", ">", ">=")) {
            String both = pir("return " + x + ".intValue() " + op + " " + y + ".intValue();");
            assertEquals(op.startsWith(">") ? 1 : 0, count(both, "(let #lhs"), op + "\n" + both);
            assertEquals(0, count(pir("return " + x + ".intValue() " + op + " b.intValue();"), "(let #lhs"), op);
            assertEquals(0, count(pir("return 7 " + op + " " + y + ".intValue();"), "(let #lhs"), op);
        }
    }

    @Test
    void nestedComparisonsBindApart() {
        String pir = pir("return t(\"A\", a).add(t(\"D\", b).compareTo(t(\"C\", a)) > 0 ? a : b).compareTo(t(\"B\", b)) > 0;");
        assertEquals(2, count(pir, "(let #lhs = "), pir);
    }

    @Test
    void formsOutsideTheRuleKeepTodaysLowering() {
        // The match is syntactic and sees through no parentheses (ADR-068 D6, review F600).
        for (String form : List.of("a.compareTo(b) > 0L", "(a.compareTo(b)) > 0", "a.compareTo(b) > (0)",
                "(0) < a.compareTo(b)", "0 < (a.compareTo(b))", "a.compareTo(b) < 1",
                "a.compareTo(b) == -1", "0L == a.compareTo(b)", "a.compareTo(b) + 0 > 0")) {
            String pir = pir("return " + form + ";");
            assertEquals(1, count(pir, "(con integer -1)"), form + "\n" + pir);
        }
    }

    /** JuLC's BigInteger operator dialect (rejected by javac, so outside the harness) evaluates left to right too (#295). */
    @Test
    void dialectRelationalOperatorsTraceInSourceOrder() {
        for (String op : List.of("<", "<=", ">", ">=")) {
            for (var level : OptimizationLevel.values()) {
                var program = new JulcCompiler(StdlibRegistry.defaultRegistry(),
                        new CompilerOptions().setOptimizationLevel(level))
                        .compileMethod(source("return t(\"X\", a) " + op + " t(\"Y\", b);"), "m").program();
                var vm = CompilerTestVm.pv11("Java");
                var result = vm.evaluateWithArgs(program, CompilerTarget.PLUTUS_V3_PV11.ledgerTarget(),
                        List.of(PlutusData.integer(3), PlutusData.integer(1)),
                        new ExBudget(1_000_000_000L, 5_000_000L), EvalOptions.DEFAULT);
                var success = assertInstanceOf(EvalResult.Success.class, result, op + " " + level);
                assertEquals(List.of("X", "Y"), success.traces(), op + " " + level);
                assertEquals(new Term.Const(Constant.bool(op.startsWith(">"))), success.resultTerm(), op + " " + level);
            }
        }
    }

    /**
     * Static-library dispatch runs before the rule and is unchanged: a local {@code BigInteger Shadow} next to an
     * {@code @OnchainLibrary class Shadow} still calls {@code Shadow.compareTo} (T33's kind, accepted until M5).
     */
    @Test
    void staticLibraryDispatchIsUnchanged() {
        String library = """
                import java.math.BigInteger;
                import org.julclang.stdlib.annotation.OnchainLibrary;
                @OnchainLibrary
                public class Shadow {
                    public static BigInteger compareTo(BigInteger y) { return BigInteger.valueOf(-7); }
                }
                """;
        String body = "BigInteger Shadow = a; return Shadow.compareTo(b) > 0;";
        var result = new JulcCompiler(StdlibRegistry.defaultRegistry(),
                new CompilerOptions().setOptimizationLevel(OptimizationLevel.NONE))
                .compileMethod(source(body), "m", List.of(library));
        String pir = result.pirFormatted();
        assertTrue(pir.contains("Shadow.compareTo"), pir);
        assertTrue(pir.contains("[[(builtin lessThanInteger) (con integer 0)] [Shadow.compareTo b]]"), pir);
        var vm = CompilerTestVm.pv11("Java");
        var eval = vm.evaluateWithArgs(result.program(), CompilerTarget.PLUTUS_V3_PV11.ledgerTarget(),
                List.of(PlutusData.integer(3), PlutusData.integer(1)), new ExBudget(1_000_000_000L, 5_000_000L),
                EvalOptions.DEFAULT);
        // The library returns -7, so the comparison is false although a > b.
        assertEquals(new Term.Const(Constant.bool(false)), assertInstanceOf(EvalResult.Success.class, eval).resultTerm());
    }
}
