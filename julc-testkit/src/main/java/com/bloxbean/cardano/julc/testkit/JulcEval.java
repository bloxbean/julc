package com.bloxbean.cardano.julc.testkit;

import com.bloxbean.cardano.julc.core.Term;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
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

    private JulcEval(String javaSource, Class<?> sourceClass, Path sourceRoot) {
        this.javaSource = javaSource;
        this.sourceClass = sourceClass;
        this.sourceRoot = sourceRoot;
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
        return new JulcEval(null, sourceClass, sourceRoot);
    }

    /**
     * Create an evaluator for inline Java source code.
     */
    public static JulcEval forSource(String javaSource) {
        Objects.requireNonNull(javaSource, "javaSource must not be null");
        return new JulcEval(javaSource, null, null);
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
        if (javaSource != null) {
            return MethodEvaluator.evaluateTerm(javaSource, methodName, args);
        }
        return MethodEvaluator.evaluateTerm(sourceClass, sourceRoot, methodName, args);
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
