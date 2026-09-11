package org.julclang.cli.check;

import org.julclang.compiler.CompileResult;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.LibrarySourceResolver;
import org.julclang.core.Constant;
import org.julclang.core.Term;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalOptions;
import org.julclang.vm.EvalResult;
import org.julclang.vm.ExBudget;
import org.julclang.vm.JulcVm;

import java.util.List;
import java.util.Map;

/**
 * Compiles and evaluates test methods using compileMethod + JulcVm.
 */
public final class TestRunner {

    private final JulcVm vm;
    private final Map<String, ?> libraryPool;

    public TestRunner(Map<String, ?> libraryPool) {
        this.vm = JulcVm.create();
        this.libraryPool = libraryPool;
    }

    /**
     * Run a single test method.
     */
    public TestResult run(TestDiscovery.TestMethod test) {
        try {
            // Resolve libraries for the test source
            var resolvedLibs = LibrarySourceResolver.resolve(test.source(), libraryPool);

            // Compile the test method
            var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry());
            CompileResult result = compiler.compileMethod(test.source(), test.methodName(), resolvedLibs);

            if (result.hasErrors()) {
                return TestResult.fail(test.className(), test.methodName(),
                        new ExBudget(0, 0), List.of(),
                        "Compilation error: " + result.diagnostics().getFirst().message());
            }

            // Evaluate the compiled program
            EvalResult evalResult = vm.evaluate(
                    result.program(), result.target().ledgerTarget(),
                    null, EvalOptions.DEFAULT);

            return switch (evalResult) {
                case EvalResult.Success s -> {
                    boolean passed = isTrueResult(s.resultTerm());
                    if (passed) {
                        yield TestResult.pass(test.className(), test.methodName(),
                                s.consumed(), s.traces());
                    } else {
                        yield TestResult.fail(test.className(), test.methodName(),
                                s.consumed(), s.traces(), "Test returned false");
                    }
                }
                case EvalResult.Failure f ->
                        TestResult.fail(test.className(), test.methodName(),
                                f.consumed(), f.traces(), f.error());
                case EvalResult.BudgetExhausted b ->
                        TestResult.fail(test.className(), test.methodName(),
                                b.consumed(), b.traces(), "Budget exhausted");
            };
        } catch (CompilerException e) {
            return TestResult.fail(test.className(), test.methodName(),
                    new ExBudget(0, 0), List.of(),
                    "Compilation failed: " + e.getMessage());
        } catch (Exception e) {
            return TestResult.fail(test.className(), test.methodName(),
                    new ExBudget(0, 0), List.of(),
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Check if a UPLC result term is Plutus True (Constr 1 []).
     */
    private static boolean isTrueResult(Term term) {
        if (term instanceof Term.Const c && c.value() instanceof Constant.BoolConst b) {
            return b.value();
        }
        if (term instanceof Term.Constr constr) {
            return constr.tag() == 1;
        }
        return false;
    }
}
