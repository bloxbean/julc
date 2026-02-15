package com.bloxbean.cardano.julc.testkit;

import com.bloxbean.cardano.julc.compiler.CompileResult;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.ledger.PolicyId;
import com.bloxbean.cardano.julc.ledger.TxOutRef;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.JulcVm;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Abstract base class for validator contract tests.
 * <p>
 * Provides pre-configured JulcVm instance and convenience methods
 * for building script contexts and evaluating validators.
 * <p>
 * Extend this class to write tests for your validators:
 * <pre>{@code
 * class MyValidatorTest extends ContractTest {
 *     @Test
 *     void testAccepts() {
 *         var ctx = scriptContext(PlutusData.integer(42));
 *         var result = evaluate(mySource, ctx);
 *         assertSuccess(result);
 *     }
 * }
 * }</pre>
 */
public abstract class ContractTest {

    /**
     * The JulcVm instance, lazily initialized on first access.
     */
    private JulcVm vm;

    /**
     * Returns the shared JulcVm instance, creating it on first access.
     */
    protected JulcVm vm() {
        if (vm == null) {
            vm = JulcVm.create();
        }
        return vm;
    }

    /**
     * Build a minimal mock ScriptContext as PlutusData.
     * <p>
     * ScriptContext = Constr(0, [txInfo, redeemer, scriptInfo])
     * <p>
     * Uses a dummy txInfo (integer 0) and dummy scriptInfo (integer 0).
     * The redeemer is placed at field index 1.
     *
     * @param redeemer the redeemer PlutusData
     * @return a mock ScriptContext PlutusData
     */
    protected PlutusData scriptContext(PlutusData redeemer) {
        return PlutusData.constr(0,
                PlutusData.integer(0),
                redeemer,
                PlutusData.integer(0));
    }

    /**
     * Compile Java source and evaluate with the given arguments.
     *
     * @param javaSource the validator Java source code
     * @param args       the PlutusData arguments
     * @return the evaluation result
     */
    protected EvalResult evaluate(String javaSource, PlutusData... args) {
        return ValidatorTest.evaluate(javaSource, args);
    }

    /**
     * Evaluate a compiled program with the given arguments.
     *
     * @param program the compiled UPLC program
     * @param args    the PlutusData arguments
     * @return the evaluation result
     */
    protected EvalResult evaluate(Program program, PlutusData... args) {
        return ValidatorTest.evaluate(program, args);
    }

    /**
     * Compile Java source to a UPLC program.
     *
     * @param javaSource the validator Java source code
     * @return the compiled Program
     */
    protected Program compile(String javaSource) {
        return ValidatorTest.compile(javaSource);
    }

    /**
     * Assert that the result is a success.
     */
    protected void assertSuccess(EvalResult result) {
        BudgetAssertions.assertSuccess(result);
    }

    /**
     * Assert that the result is a failure.
     */
    protected void assertFailure(EvalResult result) {
        BudgetAssertions.assertFailure(result);
    }

    /**
     * Assert that the budget is under the given limits.
     */
    protected void assertBudgetUnder(EvalResult result, long maxCpu, long maxMem) {
        BudgetAssertions.assertBudgetUnder(result, maxCpu, maxMem);
    }

    // --- ScriptContext builder shortcuts ---

    /**
     * Create a ScriptContextTestBuilder for a spending script context.
     *
     * @param ref the transaction output reference being spent
     * @return a new ScriptContextTestBuilder
     */
    protected ScriptContextTestBuilder spendingContext(TxOutRef ref) {
        return ScriptContextTestBuilder.spending(ref);
    }

    /**
     * Create a ScriptContextTestBuilder for a spending script context with a datum.
     *
     * @param ref   the transaction output reference being spent
     * @param datum the datum attached to the spent output
     * @return a new ScriptContextTestBuilder
     */
    protected ScriptContextTestBuilder spendingContext(TxOutRef ref, PlutusData datum) {
        return ScriptContextTestBuilder.spending(ref, datum);
    }

    /**
     * Create a ScriptContextTestBuilder for a minting script context.
     *
     * @param policyId the minting policy ID
     * @return a new ScriptContextTestBuilder
     */
    protected ScriptContextTestBuilder mintingContext(PolicyId policyId) {
        return ScriptContextTestBuilder.minting(policyId);
    }

    // --- Multi-file compilation ---

    /**
     * Compile a validator from a source file on disk.
     *
     * @param sourceFile path to the validator Java source file
     * @return the compiled Program
     * @throws RuntimeException if the file cannot be read
     */
    protected Program compile(Path sourceFile) {
        try {
            return ValidatorTest.compile(sourceFile);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read source file: " + sourceFile, e);
        }
    }

    /**
     * Compile a validator with library sources.
     *
     * @param validatorSource  the validator Java source code
     * @param librarySources   the library Java source files
     * @return the compiled Program
     */
    protected Program compile(String validatorSource, String... librarySources) {
        return ValidatorTest.compile(validatorSource, librarySources);
    }

    /**
     * Compile a validator with library sources and evaluate with the given arguments.
     *
     * @param validatorSource  the validator Java source code
     * @param librarySources   the library Java source files
     * @param args             the PlutusData arguments
     * @return the evaluation result
     */
    protected EvalResult evaluate(String validatorSource, List<String> librarySources, PlutusData... args) {
        return ValidatorTest.evaluate(validatorSource, librarySources, args);
    }

    /**
     * Assert that a compiled program evaluates successfully with the given arguments.
     *
     * @param program the compiled UPLC program
     * @param args    the PlutusData arguments
     */
    protected void assertValidates(Program program, PlutusData... args) {
        ValidatorTest.assertValidates(program, args);
    }

    // --- Crypto initialization ---

    /**
     * Initialize the CryptoLib with the JVM-based crypto provider.
     * <p>
     * Call this in a {@code @BeforeAll} method in your test class, or invoke it
     * directly before any test that uses CryptoLib functions.
     */
    protected static void initCrypto() {
        Builtins.setCryptoProvider(new JvmCryptoProvider());
    }

    // --- Class-based compilation ---

    /**
     * Compile a validator class with auto-discovered library dependencies.
     * Uses the default source root ({@code src/main/java}).
     *
     * @param validatorClass the validator class to compile
     * @return the compilation result
     */
    protected CompileResult compileValidator(Class<?> validatorClass) {
        return SourceDiscovery.compile(validatorClass, Path.of("src/main/java"));
    }

    /**
     * Compile a validator class with auto-discovered library dependencies.
     *
     * @param validatorClass the validator class to compile
     * @param sourceRoot     the root of the source tree
     * @return the compilation result
     */
    protected CompileResult compileValidator(Class<?> validatorClass, Path sourceRoot) {
        return SourceDiscovery.compile(validatorClass, sourceRoot);
    }
}
