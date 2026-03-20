package com.bloxbean.julc.cli.check;

import com.bloxbean.cardano.julc.compiler.CompileResult;
import com.bloxbean.cardano.julc.compiler.CompilerException;
import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.compiler.LibrarySourceResolver;
import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.ExBudget;
import com.bloxbean.cardano.julc.vm.JulcVm;

import java.util.List;
import java.util.Map;

/**
 * Compiles and evaluates test methods using compileMethod + JulcVm.
 */
public final class TestRunner {

    private final JulcVm vm;
    private final Map<String, String> libraryPool;

    public TestRunner(Map<String, String> libraryPool) {
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
            EvalResult evalResult = vm.evaluate(result.program());

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
