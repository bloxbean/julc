package com.bloxbean.cardano.julc.testkit;

import com.bloxbean.cardano.julc.compiler.CompileResult;
import com.bloxbean.cardano.julc.compiler.CompilerOptions;
import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.source.SourceLocation;
import com.bloxbean.cardano.julc.core.source.SourceMap;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.ExBudget;
import com.bloxbean.cardano.julc.vm.JulcVm;
import com.bloxbean.cardano.julc.vm.trace.BuiltinExecution;
import com.bloxbean.cardano.julc.vm.trace.ExecutionTraceEntry;
import com.bloxbean.cardano.julc.vm.trace.FailureReport;
import com.bloxbean.cardano.julc.vm.trace.FailureReportBuilder;
import com.bloxbean.cardano.julc.vm.trace.FailureReportFormatter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Core test utilities for evaluating Plutus validators.
 * <p>
 * Provides static methods for compiling Java source to UPLC, evaluating programs
 * with arguments, and asserting evaluation outcomes.
 * <p>
 * Usage:
 * <pre>{@code
 * // Compile a validator class with auto-discovered deps
 * CompileResult result = ValidatorTest.compileValidator(MyValidator.class);
 *
 * // Evaluate a compiled program with data arguments
 * EvalResult result = ValidatorTest.evaluate(program, datum, redeemer, ctx);
 *
 * // Assert that a validator accepts
 * ValidatorTest.assertValidates(program, datum, redeemer, ctx);
 * }</pre>
 */
public final class ValidatorTest {

    private ValidatorTest() {
        // utility class
    }

    /**
     * Evaluate a UPLC program with the given PlutusData arguments using unlimited budget.
     * <p>
     * The program is applied to each argument in order. Arguments are wrapped as
     * Data constants. A JulcVm is auto-discovered via ServiceLoader.
     *
     * @param program the UPLC program to evaluate
     * @param args    the arguments to apply (as PlutusData)
     * @return the evaluation result
     * @throws IllegalStateException if no VM provider is found on the classpath
     */
    public static EvalResult evaluate(Program program, PlutusData... args) {
        Objects.requireNonNull(program, "program must not be null");
        var vm = JulcVm.create();
        if (args.length == 0) {
            return vm.evaluate(program);
        }
        return vm.evaluateWithArgs(program, List.of(args));
    }

    /**
     * Evaluate a UPLC program with a budget limit.
     *
     * @param program the UPLC program to evaluate
     * @param budget  the maximum allowed budget
     * @param args    the arguments to apply (as PlutusData)
     * @return the evaluation result
     */
    public static EvalResult evaluate(Program program, ExBudget budget, PlutusData... args) {
        Objects.requireNonNull(program, "program must not be null");
        Objects.requireNonNull(budget, "budget must not be null");
        var vm = JulcVm.create();
        if (args.length == 0) {
            return vm.evaluate(program, budget);
        }
        return vm.evaluateWithArgs(program, List.of(args), budget);
    }

    /**
     * Compile Java source to a UPLC program, then evaluate with the given arguments.
     *
     * @param javaSource the Java source code containing a @Validator or @MintingPolicy class
     * @param args       the arguments to apply (as PlutusData)
     * @return the evaluation result
     * @throws com.bloxbean.cardano.julc.compiler.CompilerException if compilation fails
     */
    public static EvalResult evaluate(String javaSource, PlutusData... args) {
        Objects.requireNonNull(javaSource, "javaSource must not be null");
        var program = compile(javaSource);
        return evaluate(program, args);
    }

    /**
     * Compile Java source to a UPLC Program.
     *
     * @param javaSource the Java source code
     * @return the compiled Program
     * @throws com.bloxbean.cardano.julc.compiler.CompilerException if compilation fails
     */
    public static Program compile(String javaSource) {
        Objects.requireNonNull(javaSource, "javaSource must not be null");
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry());
        CompileResult result = compiler.compile(javaSource);
        if (result.hasErrors()) {
            throw new AssertionError("Compilation produced errors: " + result.diagnostics());
        }
        return result.program();
    }

    /**
     * Compile Java source to a UPLC Program with stdlib support.
     *
     * @param javaSource    the Java source code
     * @param stdlibLookup  the stdlib lookup for resolving stdlib calls
     * @return the compiled Program
     * @throws com.bloxbean.cardano.julc.compiler.CompilerException if compilation fails
     */
    public static Program compile(String javaSource,
                                  com.bloxbean.cardano.julc.compiler.pir.StdlibLookup stdlibLookup) {
        Objects.requireNonNull(javaSource, "javaSource must not be null");
        var compiler = new JulcCompiler(stdlibLookup);
        CompileResult result = compiler.compile(javaSource);
        if (result.hasErrors()) {
            throw new AssertionError("Compilation produced errors: " + result.diagnostics());
        }
        return result.program();
    }

    /**
     * Compile Java source and evaluate with the given arguments, with stdlib support.
     *
     * @param javaSource    the Java source code
     * @param stdlibLookup  the stdlib lookup for resolving stdlib calls
     * @param args          the arguments to apply (as PlutusData)
     * @return the evaluation result
     */
    public static EvalResult evaluate(String javaSource,
                                      com.bloxbean.cardano.julc.compiler.pir.StdlibLookup stdlibLookup,
                                      PlutusData... args) {
        Objects.requireNonNull(javaSource, "javaSource must not be null");
        var program = compile(javaSource, stdlibLookup);
        return evaluate(program, args);
    }

    /**
     * Assert that the program evaluates successfully with the given arguments.
     * <p>
     * Throws {@link AssertionError} if evaluation fails or the budget is exhausted.
     *
     * @param program the UPLC program to evaluate
     * @param args    the arguments to apply
     */
    public static void assertValidates(Program program, PlutusData... args) {
        var result = evaluate(program, args);
        if (!result.isSuccess()) {
            throw new AssertionError("Expected validator to succeed, but got: " + formatResult(result));
        }
    }

    /**
     * Assert that the program evaluation fails (error term or budget exhaustion).
     * <p>
     * Throws {@link AssertionError} if evaluation succeeds.
     *
     * @param program the UPLC program to evaluate
     * @param args    the arguments to apply
     */
    public static void assertRejects(Program program, PlutusData... args) {
        var result = evaluate(program, args);
        if (result.isSuccess()) {
            throw new AssertionError("Expected validator to reject, but it succeeded: " + formatResult(result));
        }
    }

    /**
     * Assert that compiling and evaluating Java source succeeds.
     *
     * @param javaSource the Java source code
     * @param args       the arguments to apply
     */
    public static void assertValidates(String javaSource, PlutusData... args) {
        var program = compile(javaSource);
        assertValidates(program, args);
    }

    /**
     * Assert that compiling and evaluating Java source fails.
     *
     * @param javaSource the Java source code
     * @param args       the arguments to apply
     */
    public static void assertRejects(String javaSource, PlutusData... args) {
        var program = compile(javaSource);
        assertRejects(program, args);
    }

    // --- Detailed compilation (with PIR/UPLC inspection) ---

    /**
     * Compile Java source to a CompileResult with PIR and UPLC terms captured.
     *
     * @param javaSource the Java source code
     * @return the compile result with non-null pirTerm() and uplcTerm()
     */
    public static CompileResult compileWithDetails(String javaSource) {
        Objects.requireNonNull(javaSource, "javaSource must not be null");
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry());
        CompileResult result = compiler.compileWithDetails(javaSource);
        if (result.hasErrors()) {
            throw new AssertionError("Compilation produced errors: " + result.diagnostics());
        }
        return result;
    }

    /**
     * Compile Java source to a CompileResult with PIR and UPLC terms captured, with stdlib support.
     *
     * @param javaSource    the Java source code
     * @param stdlibLookup  the stdlib lookup for resolving stdlib calls
     * @return the compile result with non-null pirTerm() and uplcTerm()
     */
    public static CompileResult compileWithDetails(String javaSource,
                                                    com.bloxbean.cardano.julc.compiler.pir.StdlibLookup stdlibLookup) {
        Objects.requireNonNull(javaSource, "javaSource must not be null");
        var compiler = new JulcCompiler(stdlibLookup);
        CompileResult result = compiler.compileWithDetails(javaSource);
        if (result.hasErrors()) {
            throw new AssertionError("Compilation produced errors: " + result.diagnostics());
        }
        return result;
    }

    // --- Multi-file compilation ---

    /**
     * Compile a validator with library sources to a UPLC Program.
     *
     * @param validatorSource the validator Java source code (must contain @Validator or @MintingPolicy)
     * @param librarySources  library Java source files (must NOT contain @Validator/@MintingPolicy)
     * @return the compiled Program
     * @throws com.bloxbean.cardano.julc.compiler.CompilerException if compilation fails
     */
    public static Program compile(String validatorSource, String... librarySources) {
        Objects.requireNonNull(validatorSource, "validatorSource must not be null");
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry());
        CompileResult result = compiler.compile(validatorSource, List.of(librarySources));
        if (result.hasErrors()) {
            throw new AssertionError("Compilation produced errors: " + result.diagnostics());
        }
        return result.program();
    }

    /**
     * Compile a validator from a source file on disk.
     *
     * @param sourceFile path to the validator Java source file
     * @return the compiled Program
     * @throws IOException if the file cannot be read
     * @throws com.bloxbean.cardano.julc.compiler.CompilerException if compilation fails
     */
    public static Program compile(Path sourceFile) throws IOException {
        Objects.requireNonNull(sourceFile, "sourceFile must not be null");
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry());
        CompileResult result = compiler.compile(sourceFile);
        if (result.hasErrors()) {
            throw new AssertionError("Compilation produced errors: " + result.diagnostics());
        }
        return result.program();
    }

    /**
     * Compile a validator with library sources and evaluate with the given arguments.
     *
     * @param validatorSource the validator Java source code
     * @param librarySources  library Java source files
     * @param args            the arguments to apply (as PlutusData)
     * @return the evaluation result
     * @throws com.bloxbean.cardano.julc.compiler.CompilerException if compilation fails
     */
    public static EvalResult evaluate(String validatorSource, List<String> librarySources, PlutusData... args) {
        Objects.requireNonNull(validatorSource, "validatorSource must not be null");
        Objects.requireNonNull(librarySources, "librarySources must not be null");
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry());
        CompileResult result = compiler.compile(validatorSource, librarySources);
        if (result.hasErrors()) {
            throw new AssertionError("Compilation produced errors: " + result.diagnostics());
        }
        return evaluate(result.program(), args);
    }

    // --- Class-based compilation ---

    /**
     * Compile a validator class with auto-discovered library dependencies.
     * Uses the default source root ({@code src/main/java}).
     *
     * @param validatorClass the validator class to compile
     * @return the compilation result
     */
    public static CompileResult compileValidator(Class<?> validatorClass) {
        return SourceDiscovery.compile(validatorClass, java.nio.file.Path.of("src/main/java"));
    }

    /**
     * Compile a validator class with auto-discovered library dependencies.
     *
     * @param validatorClass the validator class to compile
     * @param sourceRoot     the root of the source tree
     * @return the compilation result
     */
    public static CompileResult compileValidator(Class<?> validatorClass, java.nio.file.Path sourceRoot) {
        return SourceDiscovery.compile(validatorClass, sourceRoot);
    }

    /**
     * Compile a validator by fully-qualified class name with auto-discovered library dependencies.
     * Uses the default source root ({@code src/main/java}).
     * <p>
     * Useful when {@code -proc:only} prevents {@code .class} file generation.
     *
     * @param fqcn the fully-qualified class name (e.g., "com.example.MyValidator")
     * @return the compilation result
     */
    public static CompileResult compileValidatorByName(String fqcn) {
        return SourceDiscovery.compile(fqcn);
    }

    /**
     * Compile a validator by fully-qualified class name with auto-discovered library dependencies.
     *
     * @param fqcn       the fully-qualified class name
     * @param sourceRoot the root of the source tree
     * @return the compilation result
     */
    public static CompileResult compileValidatorByName(String fqcn, java.nio.file.Path sourceRoot) {
        return SourceDiscovery.compile(fqcn, sourceRoot);
    }

    // --- Method evaluation (delegates to MethodEvaluator) ---

    /**
     * Compile and evaluate a single static method, returning the raw evaluation result.
     *
     * @param javaSource the Java source containing the method
     * @param methodName the static method name to compile and evaluate
     * @param args       arguments to apply (as PlutusData)
     * @return the evaluation result
     * @see MethodEvaluator#evaluateRaw
     */
    public static EvalResult evaluateMethod(String javaSource, String methodName, PlutusData... args) {
        return MethodEvaluator.evaluateRaw(javaSource, methodName, args);
    }

    /**
     * Compile and evaluate a static method, returning the result as a BigInteger.
     *
     * @see MethodEvaluator#evaluateInteger
     */
    public static java.math.BigInteger evaluateInteger(String javaSource, String methodName, PlutusData... args) {
        return MethodEvaluator.evaluateInteger(javaSource, methodName, args);
    }

    /**
     * Compile and evaluate a static method, returning the result as a boolean.
     *
     * @see MethodEvaluator#evaluateBoolean
     */
    public static boolean evaluateBoolean(String javaSource, String methodName, PlutusData... args) {
        return MethodEvaluator.evaluateBoolean(javaSource, methodName, args);
    }

    /**
     * Compile and evaluate a static method, returning the result as PlutusData.
     *
     * @see MethodEvaluator#evaluateData
     */
    public static PlutusData evaluateData(String javaSource, String methodName, PlutusData... args) {
        return MethodEvaluator.evaluateData(javaSource, methodName, args);
    }

    // --- File-based method evaluation (Class<?>) ---

    /**
     * Compile and evaluate a static method from a source file, returning the raw evaluation result.
     *
     * @param sourceClass the class containing the method
     * @param methodName  the static method name to compile and evaluate
     * @param args        arguments to apply (as PlutusData)
     * @return the evaluation result
     * @see MethodEvaluator#evaluateRaw(Class, String, PlutusData...)
     */
    public static EvalResult evaluateMethod(Class<?> sourceClass, String methodName, PlutusData... args) {
        return MethodEvaluator.evaluateRaw(sourceClass, methodName, args);
    }

    /**
     * Compile and evaluate a static method from a source file, returning the result as a BigInteger.
     *
     * @see MethodEvaluator#evaluateInteger(Class, String, PlutusData...)
     */
    public static java.math.BigInteger evaluateInteger(Class<?> sourceClass, String methodName, PlutusData... args) {
        return MethodEvaluator.evaluateInteger(sourceClass, methodName, args);
    }

    /**
     * Compile and evaluate a static method from a source file, returning the result as a boolean.
     *
     * @see MethodEvaluator#evaluateBoolean(Class, String, PlutusData...)
     */
    public static boolean evaluateBoolean(Class<?> sourceClass, String methodName, PlutusData... args) {
        return MethodEvaluator.evaluateBoolean(sourceClass, methodName, args);
    }

    /**
     * Compile and evaluate a static method from a source file, returning the result as PlutusData.
     *
     * @see MethodEvaluator#evaluateData(Class, String, PlutusData...)
     */
    public static PlutusData evaluateData(Class<?> sourceClass, String methodName, PlutusData... args) {
        return MethodEvaluator.evaluateData(sourceClass, methodName, args);
    }

    // --- Source map support ---

    /**
     * Compile a validator class with source map generation enabled.
     * This is the primary way to get source-mapped error locations in tests.
     * <p>
     * Usage:
     * <pre>{@code
     * var compiled = ValidatorTest.compileValidatorWithSourceMap(MyValidator.class);
     * var result = ValidatorTest.evaluate(compiled.program(), datum, redeemer, ctx);
     * var location = ValidatorTest.resolveErrorLocation(result, compiled.sourceMap());
     * // location = "MyValidator.java:42 (amount < 0)"
     * }</pre>
     *
     * @param validatorClass the validator class to compile
     * @return the compile result with source map
     */
    public static CompileResult compileValidatorWithSourceMap(Class<?> validatorClass) {
        return compileValidatorWithSourceMap(validatorClass, java.nio.file.Path.of("src/main/java"));
    }

    /**
     * Compile a validator class with source map generation enabled.
     *
     * @param validatorClass the validator class to compile
     * @param sourceRoot     the root of the source tree
     * @return the compile result with source map
     */
    public static CompileResult compileValidatorWithSourceMap(Class<?> validatorClass,
                                                               java.nio.file.Path sourceRoot) {
        var options = new CompilerOptions().setSourceMapEnabled(true);
        return SourceDiscovery.compile(validatorClass, sourceRoot, options);
    }

    /**
     * Compile Java source with source map generation enabled.
     *
     * @param javaSource the Java source code
     * @return the compile result with a non-null sourceMap
     */
    public static CompileResult compileWithSourceMap(String javaSource) {
        Objects.requireNonNull(javaSource, "javaSource must not be null");
        var options = new CompilerOptions().setSourceMapEnabled(true);
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(), options);
        CompileResult result = compiler.compile(javaSource);
        if (result.hasErrors()) {
            throw new AssertionError("Compilation produced errors: " + result.diagnostics());
        }
        return result;
    }

    /**
     * Compile Java source with source map, then evaluate with the given arguments.
     * On failure, the returned {@link EvalResult} includes source location information.
     *
     * @param javaSource the Java source code
     * @param args       the arguments to apply (as PlutusData)
     * @return the evaluation result
     */
    public static EvalResult evaluateWithSourceMap(String javaSource, PlutusData... args) {
        var compiled = compileWithSourceMap(javaSource);
        return evaluate(compiled.program(), args);
    }

    /**
     * Resolve the source location of a failed evaluation result using a source map.
     *
     * @param result    the evaluation result (Failure or BudgetExhausted)
     * @param sourceMap the source map from compilation
     * @return the source location, or null if not resolvable
     */
    public static SourceLocation resolveErrorLocation(EvalResult result, SourceMap sourceMap) {
        if (sourceMap == null) return null;
        var failedTerm = switch (result) {
            case EvalResult.Failure f -> f.failedTerm();
            case EvalResult.BudgetExhausted b -> b.failedTerm();
            case EvalResult.Success _ -> null;
        };
        return sourceMap.lookup(failedTerm);
    }

    /**
     * Assert that the program evaluates successfully, with source map error reporting on failure.
     *
     * @param compileResult the compile result (must have source map)
     * @param args          the arguments to apply
     */
    public static void assertValidatesWithSourceMap(CompileResult compileResult, PlutusData... args) {
        var result = evaluate(compileResult.program(), args);
        BudgetAssertions.assertSuccess(result, compileResult.sourceMap());
    }

    /**
     * Assert that the program evaluation fails, with source map location in the message.
     *
     * @param compileResult the compile result (must have source map)
     * @param args          the arguments to apply
     */
    public static void assertRejectsWithSourceMap(CompileResult compileResult, PlutusData... args) {
        var result = evaluate(compileResult.program(), args);
        BudgetAssertions.assertFailure(result, compileResult.sourceMap());
    }

    // --- Builtin trace (lightweight) ---

    /**
     * Evaluate a compiled program with source map but WITHOUT execution tracing.
     * Retrieves the builtin trace (always collected by the VM) for lightweight diagnostics.
     * <p>
     * This is cheaper than {@link #evaluateWithTrace} because it does not enable
     * per-step execution tracing.
     *
     * @param compiled the compile result (with source map)
     * @param args     the PlutusData arguments
     * @return the result with empty execution trace and populated builtin trace
     */
    public static EvalWithTrace evaluateWithBuiltinTrace(CompileResult compiled, PlutusData... args) {
        return doEvaluate(compiled, false, args);
    }

    /**
     * Evaluate a compiled program with builtin-only diagnostics (no execution tracing).
     * Returns a {@link FailureReport} if the evaluation fails, or null on success.
     * <p>
     * Lighter-weight alternative to {@link #evaluateWithDiagnostics} — useful for
     * diagnosing validator failures without the overhead of full execution tracing.
     *
     * @param compiled the compile result (with source map)
     * @param args     the PlutusData arguments
     * @return a FailureReport on failure, null on success
     */
    public static FailureReport evaluateWithBuiltinDiagnostics(CompileResult compiled, PlutusData... args) {
        return buildReportIfFailed(evaluateWithBuiltinTrace(compiled, args), compiled.sourceMap());
    }

    // --- Execution tracing ---

    /**
     * Evaluate a compiled program with source map and execution tracing enabled.
     * Returns an {@link EvalWithTrace} containing both the result and the trace.
     *
     * @param compiled the compile result (with source map)
     * @param args     the PlutusData arguments
     * @return the result and execution trace
     */
    public static EvalWithTrace evaluateWithTrace(CompileResult compiled, PlutusData... args) {
        return doEvaluate(compiled, true, args);
    }

    /**
     * Holds an evaluation result together with its execution trace and builtin trace.
     */
    public record EvalWithTrace(EvalResult result, List<ExecutionTraceEntry> trace,
                                 List<BuiltinExecution> builtinTrace) {
        /** Backward-compatible constructor (no builtin trace). */
        public EvalWithTrace(EvalResult result, List<ExecutionTraceEntry> trace) {
            this(result, trace, List.of());
        }

        /** Format the trace as a readable multi-line string. */
        public String formatTrace() {
            return ExecutionTraceEntry.format(trace);
        }

        /** Format a per-file/line budget summary with visit counts. */
        public String formatBudgetSummary() {
            return ExecutionTraceEntry.formatSummary(trace);
        }
    }

    // --- Diagnostic evaluation ---

    /**
     * Evaluate a compiled program with full diagnostics (source map, execution trace, builtin trace).
     * Returns a {@link FailureReport} if the evaluation fails, or null on success.
     *
     * @param compiled the compile result (with source map)
     * @param args     the PlutusData arguments
     * @return a FailureReport on failure, null on success
     */
    public static FailureReport evaluateWithDiagnostics(CompileResult compiled, PlutusData... args) {
        return buildReportIfFailed(evaluateWithTrace(compiled, args), compiled.sourceMap());
    }

    /**
     * Assert that the program evaluates successfully, with rich diagnostics on failure.
     * <p>
     * On failure, the assertion error includes source location, last builtin executions,
     * and budget consumed — the full {@link FailureReport}.
     *
     * @param compiled the compile result (with source map)
     * @param args     the arguments to apply
     */
    public static void assertValidatesWithDiagnostics(CompileResult compiled, PlutusData... args) {
        var report = evaluateWithDiagnostics(compiled, args);
        if (report != null) {
            throw new AssertionError("Expected validator to succeed, but got:\n"
                    + FailureReportFormatter.format(report));
        }
    }

    /**
     * Shared evaluate helper — creates a VM, sets source map, optionally enables tracing,
     * evaluates, and collects traces. The VM is throwaway (no cleanup needed).
     */
    private static EvalWithTrace doEvaluate(CompileResult compiled, boolean tracing, PlutusData... args) {
        var vm = JulcVm.create();
        vm.setSourceMap(compiled.sourceMap());
        if (tracing) vm.setTracingEnabled(true);
        EvalResult result;
        if (args.length == 0) {
            result = vm.evaluate(compiled.program());
        } else {
            result = vm.evaluateWithArgs(compiled.program(), List.of(args));
        }
        var executionTrace = tracing ? vm.getLastExecutionTrace() : List.<ExecutionTraceEntry>of();
        var builtinTrace = vm.getLastBuiltinTrace();
        return new EvalWithTrace(result, executionTrace, builtinTrace);
    }

    /**
     * Build a FailureReport if evaluation failed, null otherwise.
     */
    private static FailureReport buildReportIfFailed(EvalWithTrace traced, SourceMap sourceMap) {
        if (traced.result().isSuccess()) return null;
        return FailureReportBuilder.build(traced.result(), sourceMap,
                traced.trace(), traced.builtinTrace());
    }

    private static String formatResult(EvalResult result) {
        return switch (result) {
            case EvalResult.Success s ->
                    "Success{term=" + s.resultTerm() + ", budget=" + s.consumed() + ", traces=" + s.traces() + "}";
            case EvalResult.Failure f ->
                    "Failure{error=" + f.error() + ", budget=" + f.consumed() + ", traces=" + f.traces() + "}";
            case EvalResult.BudgetExhausted b ->
                    "BudgetExhausted{budget=" + b.consumed() + ", traces=" + b.traces() + "}";
        };
    }
}
