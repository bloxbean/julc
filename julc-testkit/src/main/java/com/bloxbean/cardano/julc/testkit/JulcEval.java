package com.bloxbean.cardano.julc.testkit;

import com.bloxbean.cardano.julc.compiler.CompileResult;
import com.bloxbean.cardano.julc.compiler.CompilerOptions;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.core.source.SourceLocation;
import com.bloxbean.cardano.julc.core.source.SourceMap;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.trace.ExecutionTraceEntry;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Type-safe evaluator for JuLC methods with natural Java syntax.
 * <p>
 * Provides two usage modes:
 * <ul>
 *   <li><b>Interface proxy</b> — define an interface matching the on-chain methods,
 *       and call them with Java types. Arguments are auto-converted to PlutusData,
 *       and results are auto-extracted to the declared return type.</li>
 *   <li><b>Fluent call</b> — one-off calls with string method names and
 *       explicit result extraction via {@link CallResult}.</li>
 * </ul>
 * <p>
 * Example (interface proxy):
 * <pre>{@code
 * interface MathProxy {
 *     BigInteger doubleIt(long x);
 *     boolean isPositive(long x);
 * }
 * var proxy = JulcEval.forClass(SampleValidator.class, sourceRoot)
 *                      .create(MathProxy.class);
 * assertEquals(BigInteger.valueOf(42), proxy.doubleIt(21));
 * }</pre>
 * <p>
 * Example (fluent call):
 * <pre>{@code
 * var eval = JulcEval.forClass(SampleValidator.class, sourceRoot);
 * assertEquals(BigInteger.valueOf(42), eval.call("doubleIt", 21).asInteger());
 * }</pre>
 */
public final class JulcEval {

    private final String javaSource;
    private final Class<?> sourceClass;
    private final Path sourceRoot;
    private final com.bloxbean.cardano.julc.core.PlutusData[] params;
    private boolean sourceMapEnabled;
    private boolean tracingEnabled;
    private volatile List<ExecutionTraceEntry> lastExecutionTrace = List.of();

    private JulcEval(String javaSource, Class<?> sourceClass, Path sourceRoot,
                     com.bloxbean.cardano.julc.core.PlutusData[] params) {
        this.javaSource = javaSource;
        this.sourceClass = sourceClass;
        this.sourceRoot = sourceRoot;
        this.params = params;
    }

    // --- Factory methods ---

    /**
     * Create an evaluator for a source class using the default source root ({@code src/main/java}).
     */
    public static JulcEval forClass(Class<?> sourceClass) {
        return forClass(sourceClass, Path.of("src/main/java"));
    }

    /**
     * Create an evaluator for a source class with a specific source root.
     */
    public static JulcEval forClass(Class<?> sourceClass, Path sourceRoot) {
        Objects.requireNonNull(sourceClass, "sourceClass must not be null");
        Objects.requireNonNull(sourceRoot, "sourceRoot must not be null");
        return new JulcEval(null, sourceClass, sourceRoot, null);
    }

    /**
     * Create an evaluator for a parameterized source class using the default source root.
     * The params are applied as outermost lambda arguments (for {@code @Param} fields).
     *
     * @param sourceClass the class containing the method(s)
     * @param params      @Param values to apply (auto-converted via ArgConverter)
     */
    public static JulcEval forClass(Class<?> sourceClass, Object... params) {
        return forClass(sourceClass, Path.of("src/main/java"), params);
    }

    /**
     * Create an evaluator for a parameterized source class with a specific source root.
     * The params are applied as outermost lambda arguments (for {@code @Param} fields).
     *
     * @param sourceClass the class containing the method(s)
     * @param sourceRoot  the root of the source tree
     * @param params      @Param values to apply (auto-converted via ArgConverter)
     */
    public static JulcEval forClass(Class<?> sourceClass, Path sourceRoot, Object... params) {
        Objects.requireNonNull(sourceClass, "sourceClass must not be null");
        Objects.requireNonNull(sourceRoot, "sourceRoot must not be null");
        var converted = ArgConverter.convert(params);
        return new JulcEval(null, sourceClass, sourceRoot, converted.length > 0 ? converted : null);
    }

    /**
     * Create an evaluator for inline Java source code.
     */
    public static JulcEval forSource(String javaSource) {
        Objects.requireNonNull(javaSource, "javaSource must not be null");
        return new JulcEval(javaSource, null, null, null);
    }

    /**
     * Enable source map generation. When a method call fails, the error message
     * will include the Java source file and line number where the error occurred.
     * <p>
     * Example:
     * <pre>{@code
     * var eval = JulcEval.forClass(MyValidator.class).sourceMap();
     * eval.call("validate", data).asBoolean();
     * // On failure: "Evaluation failed: Error term encountered
     * //   at MyValidator.java:42 (Builtins.error())"
     * }</pre>
     */
    public JulcEval sourceMap() {
        this.sourceMapEnabled = true;
        return this;
    }

    /**
     * Enable execution tracing. Implies {@link #sourceMap()}.
     * After each call, the execution trace is accessible via {@link #getLastExecutionTrace()}
     * and {@link #formatLastTrace()}.
     * On failure, the error message includes the full execution trace.
     * <p>
     * Example:
     * <pre>{@code
     * var eval = JulcEval.forClass(MyValidator.class).trace();
     * eval.call("validate", data).asBoolean();
     * System.out.print(eval.formatLastTrace());
     * }</pre>
     */
    public JulcEval trace() {
        this.sourceMapEnabled = true;
        this.tracingEnabled = true;
        return this;
    }

    /**
     * Returns the execution trace from the most recent call.
     * Empty if tracing was not enabled.
     */
    public List<ExecutionTraceEntry> getLastExecutionTrace() {
        return lastExecutionTrace;
    }

    /**
     * Format the last execution trace as a readable multi-line string.
     */
    public String formatLastTrace() {
        return ExecutionTraceEntry.format(lastExecutionTrace);
    }

    /**
     * Format a per-file/line budget summary for the last execution trace.
     */
    public String formatLastBudgetSummary() {
        return ExecutionTraceEntry.formatSummary(lastExecutionTrace);
    }

    // --- Interface proxy ---

    /**
     * Create a type-safe proxy implementing the given interface.
     * <p>
     * Each method on the interface is mapped to a JuLC method with the same name.
     * Arguments are auto-converted via {@link ArgConverter} and results are
     * auto-extracted via {@link ResultConverter}.
     *
     * @param proxyInterface the interface to implement
     * @param <T>            the interface type
     * @return a proxy instance
     * @throws IllegalArgumentException if the argument is not an interface
     */
    @SuppressWarnings("unchecked")
    public <T> T create(Class<T> proxyInterface) {
        Objects.requireNonNull(proxyInterface, "proxyInterface must not be null");
        if (!proxyInterface.isInterface()) {
            throw new IllegalArgumentException(
                    proxyInterface.getName() + " is not an interface. "
                    + "The create() method requires an interface type.");
        }

        InvocationHandler handler = (proxy, method, args) -> {
            // Delegate Object methods to sensible defaults
            if (method.getDeclaringClass() == Object.class) {
                return handleObjectMethod(method, proxy, args);
            }

            String methodName = method.getName();
            var plutusArgs = ArgConverter.convert(args);
            Term term = evaluateTerm(methodName, plutusArgs);
            return ResultConverter.convert(term, method.getReturnType());
        };

        return (T) Proxy.newProxyInstance(
                proxyInterface.getClassLoader(),
                new Class<?>[]{proxyInterface},
                handler);
    }

    // --- Fluent call API ---

    /**
     * Call a method by name with auto-converted arguments.
     *
     * @param methodName the method name
     * @param args       Java arguments (auto-converted to PlutusData)
     * @return a {@link CallResult} for fluent extraction
     */
    public CallResult call(String methodName, Object... args) {
        Objects.requireNonNull(methodName, "methodName must not be null");
        var plutusArgs = ArgConverter.convert(args);
        Term term = evaluateTerm(methodName, plutusArgs);
        return new CallResult(term);
    }

    // --- Internal ---

    private Term evaluateTerm(String methodName, com.bloxbean.cardano.julc.core.PlutusData[] args) {
        if (sourceMapEnabled) {
            return evaluateTermWithSourceMap(methodName, args);
        }
        if (javaSource != null) {
            return MethodEvaluator.evaluateTerm(javaSource, methodName, params, args);
        }
        return MethodEvaluator.evaluateTerm(sourceClass, sourceRoot, methodName, params, args);
    }

    private Term evaluateTermWithSourceMap(String methodName, com.bloxbean.cardano.julc.core.PlutusData[] args) {
        var options = new CompilerOptions().setSourceMapEnabled(true);
        var compiler = new com.bloxbean.cardano.julc.compiler.JulcCompiler(
                com.bloxbean.cardano.julc.stdlib.StdlibRegistry.defaultRegistry(), options);

        CompileResult compiled;
        if (javaSource != null) {
            compiled = compiler.compileMethod(javaSource, methodName);
        } else {
            // Read source file and compile method with source maps
            var sourceFile = SourceDiscovery.sourceFileFor(sourceClass, sourceRoot);
            String source;
            try {
                source = java.nio.file.Files.readString(sourceFile);
            } catch (java.io.IOException e) {
                throw new com.bloxbean.cardano.julc.vm.TermExtractor.ExtractionException(
                        "Cannot read source: " + sourceFile);
            }
            compiled = compiler.compileMethod(source, methodName);
        }
        if (compiled.hasErrors()) {
            throw new com.bloxbean.cardano.julc.vm.TermExtractor.ExtractionException(
                    "Compilation failed: " + compiled.diagnostics());
        }

        var vm = com.bloxbean.cardano.julc.vm.JulcVm.create();
        vm.setSourceMap(compiled.sourceMap());
        if (tracingEnabled) {
            vm.setTracingEnabled(true);
        }
        var allArgs = MethodEvaluator.buildAllArgs(params, args);
        EvalResult result;
        try {
            if (allArgs.isEmpty()) {
                result = vm.evaluate(compiled.program());
            } else {
                result = vm.evaluateWithArgs(compiled.program(), allArgs);
            }
        } finally {
            this.lastExecutionTrace = vm.getLastExecutionTrace();
            vm.setTracingEnabled(false);
            vm.setSourceMap(null);
        }

        if (result instanceof EvalResult.Success s) {
            return s.resultTerm();
        }

        // Resolve source location for error message
        var location = ValidatorTest.resolveErrorLocation(result, compiled.sourceMap());
        var errorMsg = switch (result) {
            case EvalResult.Failure f -> "Evaluation failed: " + f.error();
            case EvalResult.BudgetExhausted b -> "Budget exhausted: " + b.consumed();
            default -> "Evaluation failed: " + result;
        };
        if (location != null) {
            errorMsg += "\n  at " + location;
        }
        if (tracingEnabled && !lastExecutionTrace.isEmpty()) {
            errorMsg += "\n" + ExecutionTraceEntry.format(lastExecutionTrace);
        }
        throw new com.bloxbean.cardano.julc.vm.TermExtractor.ExtractionException(errorMsg);
    }

    private static Object handleObjectMethod(Method method, Object proxy, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> "JulcEval$" + System.identityHashCode(proxy);
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
    }
}
