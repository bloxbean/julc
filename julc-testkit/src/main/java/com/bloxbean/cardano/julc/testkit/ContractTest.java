package com.bloxbean.cardano.julc.testkit;

import com.bloxbean.cardano.julc.compiler.CompileResult;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.source.SourceLocation;
import com.bloxbean.cardano.julc.core.source.SourceMap;
import com.bloxbean.cardano.julc.ledger.Credential;
import com.bloxbean.cardano.julc.ledger.PolicyId;
import com.bloxbean.cardano.julc.ledger.ProposalProcedure;
import com.bloxbean.cardano.julc.ledger.TxCert;
import com.bloxbean.cardano.julc.ledger.TxOutRef;
import com.bloxbean.cardano.julc.ledger.Voter;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.JulcVm;
import com.bloxbean.cardano.julc.vm.trace.BuiltinExecution;
import com.bloxbean.cardano.julc.vm.trace.ExecutionTraceEntry;

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

    /** The result from the most recent evaluation. */
    private EvalResult lastResult;

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

    /**
     * Create a ScriptContextTestBuilder for a rewarding (withdraw) script context.
     *
     * @param credential the staking credential
     * @return a new ScriptContextTestBuilder
     */
    protected ScriptContextTestBuilder rewardingContext(Credential credential) {
        return ScriptContextTestBuilder.rewarding(credential);
    }

    /**
     * Create a ScriptContextTestBuilder for a certifying script context.
     *
     * @param index the certificate index
     * @param cert  the transaction certificate
     * @return a new ScriptContextTestBuilder
     */
    protected ScriptContextTestBuilder certifyingContext(java.math.BigInteger index, TxCert cert) {
        return ScriptContextTestBuilder.certifying(index, cert);
    }

    /**
     * Create a ScriptContextTestBuilder for a voting script context.
     *
     * @param voter the governance voter
     * @return a new ScriptContextTestBuilder
     */
    protected ScriptContextTestBuilder votingContext(Voter voter) {
        return ScriptContextTestBuilder.voting(voter);
    }

    /**
     * Create a ScriptContextTestBuilder for a proposing script context.
     *
     * @param index     the proposal index
     * @param procedure the proposal procedure
     * @return a new ScriptContextTestBuilder
     */
    protected ScriptContextTestBuilder proposingContext(java.math.BigInteger index, ProposalProcedure procedure) {
        return ScriptContextTestBuilder.proposing(index, procedure);
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

    // --- Source map support ---

    /**
     * Compile a validator class with source map generation enabled.
     * Uses the default source root ({@code src/main/java}).
     * <p>
     * Usage:
     * <pre>{@code
     * var compiled = compileValidatorWithSourceMap(SwapOrder.class);
     * var result = evaluate(compiled.program(), ctx);
     * assertFailure(result, compiled.sourceMap());
     * }</pre>
     *
     * @param validatorClass the validator class to compile
     * @return the compile result with source map
     */
    protected CompileResult compileValidatorWithSourceMap(Class<?> validatorClass) {
        return compileValidatorWithSourceMap(validatorClass, Path.of("src/main/java"));
    }

    /**
     * Compile a validator class with source map generation enabled.
     *
     * @param validatorClass the validator class to compile
     * @param sourceRoot     the root of the source tree
     * @return the compile result with source map
     */
    protected CompileResult compileValidatorWithSourceMap(Class<?> validatorClass, Path sourceRoot) {
        return ValidatorTest.compileValidatorWithSourceMap(validatorClass, sourceRoot);
    }

    /**
     * Assert that the result is a success, with source map error reporting on failure.
     *
     * @param result    the evaluation result
     * @param sourceMap the source map from compilation
     */
    protected void assertSuccess(EvalResult result, SourceMap sourceMap) {
        BudgetAssertions.assertSuccess(result, sourceMap);
    }

    /**
     * Assert that the result is a failure, with source map location in the message.
     *
     * @param result    the evaluation result
     * @param sourceMap the source map from compilation
     */
    protected void assertFailure(EvalResult result, SourceMap sourceMap) {
        BudgetAssertions.assertFailure(result, sourceMap);
    }

    /**
     * Resolve the source location of a failed evaluation result using a source map.
     *
     * @param result    the evaluation result
     * @param sourceMap the source map from compilation
     * @return the source location, or null if not resolvable
     */
    protected SourceLocation resolveErrorLocation(EvalResult result, SourceMap sourceMap) {
        return ValidatorTest.resolveErrorLocation(result, sourceMap);
    }

    /**
     * Log the evaluation result with budget and source location on failure.
     *
     * @param testName  a label for the test (e.g., method name)
     * @param result    the evaluation result
     * @param sourceMap the source map from compilation
     */
    protected void logResult(String testName, EvalResult result, SourceMap sourceMap) {
        var budget = result.budgetConsumed();
        var sb = new StringBuilder();
        sb.append("[").append(testName).append("] ");
        sb.append("CPU: ").append(budget.cpuSteps());
        sb.append(", Mem: ").append(budget.memoryUnits());
        if (!result.isSuccess()) {
            var location = ValidatorTest.resolveErrorLocation(result, sourceMap);
            if (location != null) {
                sb.append(" | Error at: ").append(location);
            }
        }
        System.out.println(sb);
    }

    // --- Execution tracing ---

    /**
     * Evaluate a compiled program with source map and execution tracing enabled.
     * After evaluation, retrieve the trace via {@link #getLastExecutionTrace()}.
     *
     * @param compiled the compile result (with source map)
     * @param args     the PlutusData arguments
     * @return the evaluation result
     */
    protected EvalResult evaluateWithTrace(CompileResult compiled, PlutusData... args) {
        return doEvaluate(compiled, true, args);
    }

    /**
     * Evaluate a compiled program with source map but WITHOUT execution tracing.
     * Retrieves the builtin trace (always collected by the VM) for lightweight diagnostics.
     *
     * @param compiled the compile result (with source map)
     * @param args     the PlutusData arguments
     * @return the evaluation result
     */
    protected EvalResult evaluateWithBuiltinTrace(CompileResult compiled, PlutusData... args) {
        return doEvaluate(compiled, false, args);
    }

    /**
     * Shared evaluate helper — sets source map, optionally enables tracing,
     * evaluates, and cleans up the shared VM state.
     */
    private EvalResult doEvaluate(CompileResult compiled, boolean tracing, PlutusData... args) {
        var v = vm();
        var options = com.bloxbean.cardano.julc.vm.EvalOptions.DEFAULT
                .withSourceMap(compiled.sourceMap())
                .withTracing(tracing);
        EvalResult result;
        if (args.length == 0) {
            result = v.evaluate(compiled.program(), options);
        } else {
            result = v.evaluateWithArgs(compiled.program(), java.util.List.of(args), options);
        }
        this.lastResult = result;
        return result;
    }

    /**
     * Returns the builtin trace from the most recent evaluation.
     * Builtin tracing is always on by default — no opt-in required.
     */
    protected java.util.List<BuiltinExecution> getLastBuiltinTrace() {
        var r = lastResult;
        return r != null ? r.builtinTrace() : java.util.List.of();
    }

    /**
     * Returns the execution trace from the most recent evaluation.
     * Call after {@link #evaluateWithTrace}.
     */
    protected java.util.List<ExecutionTraceEntry> getLastExecutionTrace() {
        var r = lastResult;
        return r != null ? r.executionTrace() : java.util.List.of();
    }

    /**
     * Format the last execution trace as a readable multi-line string.
     */
    protected String formatExecutionTrace() {
        return ExecutionTraceEntry.format(getLastExecutionTrace());
    }

    /**
     * Format a per-file/line budget summary for the last execution trace.
     * Aggregates CPU/memory costs by source location with visit counts.
     */
    protected String formatBudgetSummary() {
        return ExecutionTraceEntry.formatSummary(getLastExecutionTrace());
    }
}
