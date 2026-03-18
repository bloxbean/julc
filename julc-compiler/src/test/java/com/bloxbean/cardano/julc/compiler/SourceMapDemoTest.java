package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.core.source.SourceLocation;
import com.bloxbean.cardano.julc.core.source.SourceMap;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import com.bloxbean.cardano.julc.testkit.ValidatorTest;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.ExBudget;
import com.bloxbean.cardano.julc.vm.JulcVm;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Demo test — run this to see source map error reporting in action.
 */
class SourceMapDemoTest {

    @Test
    void demo_errorTermWithSourceLocation() {
        var options = new CompilerOptions().setSourceMapEnabled(true);
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(), options);

        String source = """
                import com.bloxbean.cardano.julc.stdlib.Builtins;
                import java.math.BigInteger;

                class AmountChecker {
                    public static BigInteger checkAmount(BigInteger amount) {
                        if (amount.compareTo(BigInteger.ZERO) < 0) {
                            Builtins.error();
                        }
                        return amount;
                    }
                }
                """;

        var compiled = compiler.compileMethod(source, "checkAmount");
        var sourceMap = compiled.sourceMap();

        System.out.println("=== Source Map Stats ===");
        System.out.println("Entries: " + sourceMap.size());
        System.out.println();

        // --- Case 1: Positive value succeeds ---
        var vm = JulcVm.create();
        var successResult = vm.evaluateWithArgs(compiled.program(), List.of(PlutusData.integer(42)));
        System.out.println("=== Case 1: checkAmount(42) ===");
        System.out.println("Result: " + (successResult.isSuccess() ? "SUCCESS" : "FAILURE"));
        System.out.println("Budget: " + successResult.budgetConsumed());
        System.out.println();

        // --- Case 2: Negative value fails with source location ---
        var failResult = vm.evaluateWithArgs(compiled.program(), List.of(PlutusData.integer(-5)));
        System.out.println("=== Case 2: checkAmount(-5) ===");
        System.out.println("Result: " + formatResult(failResult));
        var location = ValidatorTest.resolveErrorLocation(failResult, sourceMap);
        System.out.println("Error location: " + (location != null ? location : "<unknown>"));
        System.out.println();

        // --- Case 3: Show all mapped source locations in the UPLC tree ---
        System.out.println("=== All Source Map Entries (walk UPLC tree) ===");
        walkAndPrint(compiled.uplcTerm(), sourceMap, 0);
    }

    @Test
    void demo_budgetExhaustedWithSourceLocation() {
        var options = new CompilerOptions().setSourceMapEnabled(true);
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(), options);

        String source = """
                import java.math.BigInteger;

                class Summer {
                    public static BigInteger sum(BigInteger n) {
                        var result = BigInteger.ZERO;
                        while (n.compareTo(BigInteger.ZERO) > 0) {
                            result = result.add(n);
                            n = n.subtract(BigInteger.ONE);
                        }
                        return result;
                    }
                }
                """;

        var compiled = compiler.compileMethod(source, "sum");
        var sourceMap = compiled.sourceMap();

        var vm = JulcVm.create();
        var result = vm.evaluateWithArgs(compiled.program(),
                List.of(PlutusData.integer(999999)),
                new ExBudget(5000, 5000)); // very tight budget

        System.out.println("=== Budget Exhaustion Demo: sum(999999) with tiny budget ===");
        System.out.println("Result: " + formatResult(result));
        var location = ValidatorTest.resolveErrorLocation(result, sourceMap);
        System.out.println("Exhaustion location: " + (location != null ? location : "<unknown>"));
    }

    @Test
    void demo_multipleErrorPaths() {
        var options = new CompilerOptions().setSourceMapEnabled(true);
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(), options);

        String source = """
                import com.bloxbean.cardano.julc.stdlib.Builtins;
                import java.math.BigInteger;

                class MultiCheck {
                    public static BigInteger validate(BigInteger x) {
                        if (x.compareTo(BigInteger.ZERO) < 0) {
                            Builtins.error();
                        }
                        if (x.compareTo(BigInteger.valueOf(100)) > 0) {
                            Builtins.error();
                        }
                        return x;
                    }
                }
                """;

        var compiled = compiler.compileMethod(source, "validate");
        var sourceMap = compiled.sourceMap();
        var vm = JulcVm.create();

        // Fails on first check (negative)
        var r1 = vm.evaluateWithArgs(compiled.program(), List.of(PlutusData.integer(-1)));
        var loc1 = ValidatorTest.resolveErrorLocation(r1, sourceMap);
        System.out.println("=== validate(-1) ===");
        System.out.println("Result: " + formatResult(r1));
        System.out.println("Error location: " + (loc1 != null ? loc1 : "<unknown>"));
        System.out.println();

        // Fails on second check (too large)
        var r2 = vm.evaluateWithArgs(compiled.program(), List.of(PlutusData.integer(200)));
        var loc2 = ValidatorTest.resolveErrorLocation(r2, sourceMap);
        System.out.println("=== validate(200) ===");
        System.out.println("Result: " + formatResult(r2));
        System.out.println("Error location: " + (loc2 != null ? loc2 : "<unknown>"));
        System.out.println();

        // Succeeds
        var r3 = vm.evaluateWithArgs(compiled.program(), List.of(PlutusData.integer(50)));
        System.out.println("=== validate(50) ===");
        System.out.println("Result: " + (r3.isSuccess() ? "SUCCESS" : "FAILURE"));
    }

    // --- Helpers ---

    private static String formatResult(EvalResult result) {
        return switch (result) {
            case EvalResult.Success s ->
                    "Success{budget=" + s.consumed() + "}";
            case EvalResult.Failure f ->
                    "Failure{error=\"" + f.error() + "\", budget=" + f.consumed() + "}";
            case EvalResult.BudgetExhausted b ->
                    "BudgetExhausted{budget=" + b.consumed() + "}";
        };
    }

    /** Walk the UPLC tree and print any term that has a source map entry. */
    private static void walkAndPrint(Term term, SourceMap sourceMap, int depth) {
        if (term == null) return;
        var loc = sourceMap.lookup(term);
        if (loc != null) {
            var indent = "  ".repeat(depth);
            var termKind = term.getClass().getSimpleName();
            System.out.println(indent + termKind + " -> " + loc);
        }
        // Recurse into children
        switch (term) {
            case Term.Lam lam -> walkAndPrint(lam.body(), sourceMap, depth + 1);
            case Term.Apply app -> {
                walkAndPrint(app.function(), sourceMap, depth + 1);
                walkAndPrint(app.argument(), sourceMap, depth + 1);
            }
            case Term.Force f -> walkAndPrint(f.term(), sourceMap, depth + 1);
            case Term.Delay d -> walkAndPrint(d.term(), sourceMap, depth + 1);
            case Term.Constr c -> c.fields().forEach(f -> walkAndPrint(f, sourceMap, depth + 1));
            case Term.Case cs -> {
                walkAndPrint(cs.scrutinee(), sourceMap, depth + 1);
                cs.branches().forEach(b -> walkAndPrint(b, sourceMap, depth + 1));
            }
            default -> {} // Var, Const, Builtin, Error — no children
        }
    }
}
