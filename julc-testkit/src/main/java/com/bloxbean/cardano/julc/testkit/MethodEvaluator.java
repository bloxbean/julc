package com.bloxbean.cardano.julc.testkit;

import com.bloxbean.cardano.julc.compiler.CompileResult;
import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.JulcVm;
import com.bloxbean.cardano.julc.vm.TermExtractor;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Evaluates individual static methods compiled to UPLC.
 * <p>
 * Bridges compilation ({@link JulcCompiler#compileMethod}) with VM evaluation
 * ({@link JulcVm}) and value extraction ({@link TermExtractor}).
 * <p>
 * Usage:
 * <pre>{@code
 * static final String SOURCE = """
 *     class MathUtils {
 *         static BigInteger doubleIt(BigInteger x) { return x * 2; }
 *     }
 *     """;
 *
 * BigInteger result = MethodEvaluator.evaluateInteger(SOURCE, "doubleIt",
 *     PlutusData.integer(21));
 * // result == 42
 * }</pre>
 */
public final class MethodEvaluator {

    private MethodEvaluator() {}

    /**
     * Compile and evaluate a static method, returning the raw evaluation result.
     *
     * @param javaSource the Java source containing the method
     * @param methodName the name of the static method to compile and evaluate
     * @param args       arguments to apply (as PlutusData, passed as Data to the method)
     * @return the evaluation result
     */
    public static EvalResult evaluateRaw(String javaSource, String methodName, PlutusData... args) {
        Objects.requireNonNull(javaSource, "javaSource must not be null");
        Objects.requireNonNull(methodName, "methodName must not be null");
        var program = compileMethod(javaSource, methodName);
        var vm = JulcVm.create();
        if (args.length == 0) {
            return vm.evaluate(program);
        }
        return vm.evaluateWithArgs(program, List.of(args));
    }

    /**
     * Compile and evaluate a static method, returning the result Term.
     *
     * @throws TermExtractor.ExtractionException if evaluation failed
     */
    public static Term evaluateTerm(String javaSource, String methodName, PlutusData... args) {
        var result = evaluateRaw(javaSource, methodName, args);
        return TermExtractor.extractResultTerm(result);
    }

    /**
     * Compile and evaluate a static method, returning the result as a BigInteger.
     *
     * @throws TermExtractor.ExtractionException if the result is not an integer
     */
    public static BigInteger evaluateInteger(String javaSource, String methodName, PlutusData... args) {
        return TermExtractor.extractInteger(evaluateTerm(javaSource, methodName, args));
    }

    /**
     * Compile and evaluate a static method, returning the result as a byte array.
     *
     * @throws TermExtractor.ExtractionException if the result is not a byte string
     */
    public static byte[] evaluateByteString(String javaSource, String methodName, PlutusData... args) {
        return TermExtractor.extractByteString(evaluateTerm(javaSource, methodName, args));
    }

    /**
     * Compile and evaluate a static method, returning the result as a boolean.
     *
     * @throws TermExtractor.ExtractionException if the result is not a boolean
     */
    public static boolean evaluateBoolean(String javaSource, String methodName, PlutusData... args) {
        return TermExtractor.extractBoolean(evaluateTerm(javaSource, methodName, args));
    }

    /**
     * Compile and evaluate a static method, returning the result as a String.
     *
     * @throws TermExtractor.ExtractionException if the result is not a string
     */
    public static String evaluateString(String javaSource, String methodName, PlutusData... args) {
        return TermExtractor.extractString(evaluateTerm(javaSource, methodName, args));
    }

    /**
     * Compile and evaluate a static method, returning the result as PlutusData.
     *
     * @throws TermExtractor.ExtractionException if the result cannot be converted to PlutusData
     */
    public static PlutusData evaluateData(String javaSource, String methodName, PlutusData... args) {
        return TermExtractor.extractData(evaluateTerm(javaSource, methodName, args));
    }

    /**
     * Compile and evaluate a static method, returning the result as an Optional.
     *
     * @throws TermExtractor.ExtractionException if the result is not an Optional (Constr 0/1)
     */
    public static Optional<PlutusData> evaluateOptional(String javaSource, String methodName, PlutusData... args) {
        return TermExtractor.extractOptional(evaluateTerm(javaSource, methodName, args));
    }

    /**
     * Compile and evaluate a static method, returning the result as a list of PlutusData.
     *
     * @throws TermExtractor.ExtractionException if the result is not a list
     */
    public static List<PlutusData> evaluateList(String javaSource, String methodName, PlutusData... args) {
        return TermExtractor.extractList(evaluateTerm(javaSource, methodName, args));
    }

    /**
     * Compile and evaluate a static method, auto-detecting the return type.
     *
     * @return BigInteger, byte[], Boolean, String, PlutusData, Optional, List, or the raw Term
     */
    public static Object evaluate(String javaSource, String methodName, PlutusData... args) {
        return TermExtractor.extract(evaluateTerm(javaSource, methodName, args));
    }

    /**
     * Compile a single static method to a UPLC Program.
     */
    public static Program compileMethod(String javaSource, String methodName) {
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry());
        CompileResult result = compiler.compileMethod(javaSource, methodName);
        if (result.hasErrors()) {
            throw new AssertionError("Compilation produced errors: " + result.diagnostics());
        }
        return result.program();
    }

    // --- File-based overloads (Class<?>) ---

    /**
     * Compile and evaluate a static method from a source file, returning the raw evaluation result.
     * Uses the default source root ({@code src/main/java}).
     *
     * @param sourceClass the class containing the method
     * @param methodName  the name of the static method to compile and evaluate
     * @param args        arguments to apply (as PlutusData)
     * @return the evaluation result
     */
    public static EvalResult evaluateRaw(Class<?> sourceClass, String methodName, PlutusData... args) {
        return evaluateRaw(sourceClass, Path.of("src/main/java"), methodName, args);
    }

    /**
     * Compile and evaluate a static method from a source file, returning the raw evaluation result.
     *
     * @param sourceClass the class containing the method
     * @param sourceRoot  the root of the source tree
     * @param methodName  the name of the static method to compile and evaluate
     * @param args        arguments to apply (as PlutusData)
     * @return the evaluation result
     */
    public static EvalResult evaluateRaw(Class<?> sourceClass, Path sourceRoot, String methodName, PlutusData... args) {
        Objects.requireNonNull(sourceClass, "sourceClass must not be null");
        Objects.requireNonNull(sourceRoot, "sourceRoot must not be null");
        Objects.requireNonNull(methodName, "methodName must not be null");
        var program = compileMethod(sourceClass, sourceRoot, methodName);
        var vm = JulcVm.create();
        if (args.length == 0) {
            return vm.evaluate(program);
        }
        return vm.evaluateWithArgs(program, List.of(args));
    }

    /**
     * Compile and evaluate a static method from a source file, returning the result Term.
     * Uses the default source root ({@code src/main/java}).
     */
    public static Term evaluateTerm(Class<?> sourceClass, String methodName, PlutusData... args) {
        return evaluateTerm(sourceClass, Path.of("src/main/java"), methodName, args);
    }

    /**
     * Compile and evaluate a static method from a source file, returning the result Term.
     */
    public static Term evaluateTerm(Class<?> sourceClass, Path sourceRoot, String methodName, PlutusData... args) {
        var result = evaluateRaw(sourceClass, sourceRoot, methodName, args);
        return TermExtractor.extractResultTerm(result);
    }

    /**
     * Compile and evaluate a static method from a source file, returning the result as a BigInteger.
     * Uses the default source root ({@code src/main/java}).
     */
    public static BigInteger evaluateInteger(Class<?> sourceClass, String methodName, PlutusData... args) {
        return evaluateInteger(sourceClass, Path.of("src/main/java"), methodName, args);
    }

    /**
     * Compile and evaluate a static method from a source file, returning the result as a BigInteger.
     */
    public static BigInteger evaluateInteger(Class<?> sourceClass, Path sourceRoot, String methodName, PlutusData... args) {
        return TermExtractor.extractInteger(evaluateTerm(sourceClass, sourceRoot, methodName, args));
    }

    /**
     * Compile and evaluate a static method from a source file, returning the result as a byte array.
     * Uses the default source root ({@code src/main/java}).
     */
    public static byte[] evaluateByteString(Class<?> sourceClass, String methodName, PlutusData... args) {
        return evaluateByteString(sourceClass, Path.of("src/main/java"), methodName, args);
    }

    /**
     * Compile and evaluate a static method from a source file, returning the result as a byte array.
     */
    public static byte[] evaluateByteString(Class<?> sourceClass, Path sourceRoot, String methodName, PlutusData... args) {
        return TermExtractor.extractByteString(evaluateTerm(sourceClass, sourceRoot, methodName, args));
    }

    /**
     * Compile and evaluate a static method from a source file, returning the result as a boolean.
     * Uses the default source root ({@code src/main/java}).
     */
    public static boolean evaluateBoolean(Class<?> sourceClass, String methodName, PlutusData... args) {
        return evaluateBoolean(sourceClass, Path.of("src/main/java"), methodName, args);
    }

    /**
     * Compile and evaluate a static method from a source file, returning the result as a boolean.
     */
    public static boolean evaluateBoolean(Class<?> sourceClass, Path sourceRoot, String methodName, PlutusData... args) {
        return TermExtractor.extractBoolean(evaluateTerm(sourceClass, sourceRoot, methodName, args));
    }

    /**
     * Compile and evaluate a static method from a source file, returning the result as a String.
     * Uses the default source root ({@code src/main/java}).
     */
    public static String evaluateString(Class<?> sourceClass, String methodName, PlutusData... args) {
        return evaluateString(sourceClass, Path.of("src/main/java"), methodName, args);
    }

    /**
     * Compile and evaluate a static method from a source file, returning the result as a String.
     */
    public static String evaluateString(Class<?> sourceClass, Path sourceRoot, String methodName, PlutusData... args) {
        return TermExtractor.extractString(evaluateTerm(sourceClass, sourceRoot, methodName, args));
    }

    /**
     * Compile and evaluate a static method from a source file, returning the result as PlutusData.
     * Uses the default source root ({@code src/main/java}).
     */
    public static PlutusData evaluateData(Class<?> sourceClass, String methodName, PlutusData... args) {
        return evaluateData(sourceClass, Path.of("src/main/java"), methodName, args);
    }

    /**
     * Compile and evaluate a static method from a source file, returning the result as PlutusData.
     */
    public static PlutusData evaluateData(Class<?> sourceClass, Path sourceRoot, String methodName, PlutusData... args) {
        return TermExtractor.extractData(evaluateTerm(sourceClass, sourceRoot, methodName, args));
    }

    /**
     * Compile and evaluate a static method from a source file, returning the result as an Optional.
     * Uses the default source root ({@code src/main/java}).
     */
    public static Optional<PlutusData> evaluateOptional(Class<?> sourceClass, String methodName, PlutusData... args) {
        return evaluateOptional(sourceClass, Path.of("src/main/java"), methodName, args);
    }

    /**
     * Compile and evaluate a static method from a source file, returning the result as an Optional.
     */
    public static Optional<PlutusData> evaluateOptional(Class<?> sourceClass, Path sourceRoot, String methodName, PlutusData... args) {
        return TermExtractor.extractOptional(evaluateTerm(sourceClass, sourceRoot, methodName, args));
    }

    /**
     * Compile and evaluate a static method from a source file, returning the result as a list.
     * Uses the default source root ({@code src/main/java}).
     */
    public static List<PlutusData> evaluateList(Class<?> sourceClass, String methodName, PlutusData... args) {
        return evaluateList(sourceClass, Path.of("src/main/java"), methodName, args);
    }

    /**
     * Compile and evaluate a static method from a source file, returning the result as a list.
     */
    public static List<PlutusData> evaluateList(Class<?> sourceClass, Path sourceRoot, String methodName, PlutusData... args) {
        return TermExtractor.extractList(evaluateTerm(sourceClass, sourceRoot, methodName, args));
    }

    /**
     * Compile and evaluate a static method from a source file, auto-detecting the return type.
     * Uses the default source root ({@code src/main/java}).
     *
     * @return BigInteger, byte[], Boolean, String, PlutusData, Optional, List, or the raw Term
     */
    public static Object evaluate(Class<?> sourceClass, String methodName, PlutusData... args) {
        return evaluate(sourceClass, Path.of("src/main/java"), methodName, args);
    }

    /**
     * Compile and evaluate a static method from a source file, auto-detecting the return type.
     *
     * @return BigInteger, byte[], Boolean, String, PlutusData, Optional, List, or the raw Term
     */
    public static Object evaluate(Class<?> sourceClass, Path sourceRoot, String methodName, PlutusData... args) {
        return TermExtractor.extract(evaluateTerm(sourceClass, sourceRoot, methodName, args));
    }

    /**
     * Compile a single static method from a source file to a UPLC Program.
     * Uses the default source root ({@code src/main/java}).
     *
     * @param sourceClass the class containing the method
     * @param methodName  the name of the static method to compile
     * @return the compiled Program
     */
    public static Program compileMethod(Class<?> sourceClass, String methodName) {
        return compileMethod(sourceClass, Path.of("src/main/java"), methodName);
    }

    /**
     * Compile a single static method from a source file to a UPLC Program.
     *
     * @param sourceClass the class containing the method
     * @param sourceRoot  the root of the source tree
     * @param methodName  the name of the static method to compile
     * @return the compiled Program
     */
    public static Program compileMethod(Class<?> sourceClass, Path sourceRoot, String methodName) {
        CompileResult result = SourceDiscovery.compileMethod(sourceClass, sourceRoot, methodName);
        return result.program();
    }
}
