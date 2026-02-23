package com.bloxbean.cardano.julc.testkit;

import com.bloxbean.cardano.julc.compiler.CompileResult;
import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.ExBudget;
import com.bloxbean.cardano.julc.vm.JulcVm;

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
