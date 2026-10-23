package org.julclang.difftest;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The JVM side of the harness (ADR-063 D2): javac compiles the source against the harness's own
 * classpath, and every evaluation loads the class in a fresh class loader, so static fields start
 * from their initializers (H3). This side never sees JuLC's output (H1).
 * <p>
 * Off-chain {@code Builtins.trace} prints {@code [TRACE] message} to standard output; those lines
 * are captured as the trace log, in order. Evaluations are serialized because standard output is
 * process-wide. A timed-out evaluation is abandoned on a daemon thread, not stopped; generated
 * programs terminate by construction (D5), so the timeout is only a safety net.
 */
public final class JvmExecutor {

    private static final String TRACE_PREFIX = "[TRACE] ";

    private final long timeoutMillis;
    private final Map<String, Path> compiled = new ConcurrentHashMap<>();

    public JvmExecutor(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    public synchronized Observation run(DiffCase diffCase) {
        Path classes = compiled.computeIfAbsent(diffCase.source(), source -> compile(diffCase));
        try (var loader = new URLClassLoader(new URL[]{classes.toUri().toURL()}, JvmExecutor.class.getClassLoader())) {
            Method method = entryMethod(loader.loadClass(diffCase.className()), diffCase.method());
            var params = method.getGenericParameterTypes();
            if (params.length != diffCase.args().size()) {
                throw new HarnessException(diffCase.name() + ": " + method.getName() + " takes " + params.length
                        + " arguments, the case has " + diffCase.args().size());
            }
            Object[] args = new Object[params.length];
            for (int i = 0; i < params.length; i++) args[i] = ValueMapping.fromData(diffCase.args().get(i), params[i]);
            return invoke(method, args);
        } catch (IOException | ClassNotFoundException e) {
            throw new HarnessException(diffCase.name() + ": cannot load " + diffCase.className(), e);
        }
    }

    private Observation invoke(Method method, Object[] args) {
        var out = new ByteArrayOutputStream();
        PrintStream original = System.out;
        var executor = Executors.newSingleThreadExecutor(runnable -> {
            var thread = new Thread(runnable, "difftest-jvm");
            thread.setDaemon(true);
            return thread;
        });
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            var future = executor.submit(() -> method.invoke(null, args));
            try {
                Object result = future.get(timeoutMillis, TimeUnit.MILLISECONDS);
                return new Observation.Value(ValueMapping.toData(result), traces(out));
            } catch (TimeoutException e) {
                future.cancel(true);
                return new Observation.Inconclusive("JVM timeout after " + timeoutMillis + " ms");
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() instanceof InvocationTargetException ite ? ite.getCause() : e.getCause();
                if (cause instanceof StackOverflowError) return new Observation.Inconclusive("JVM stack overflow");
                return new Observation.Failure(String.valueOf(cause), traces(out));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Observation.Inconclusive("interrupted");
            }
        } finally {
            System.setOut(original);
            executor.shutdownNow();
        }
    }

    private static List<String> traces(ByteArrayOutputStream out) {
        var traces = new ArrayList<String>();
        for (String line : out.toString(StandardCharsets.UTF_8).split("\\R")) {
            if (line.startsWith(TRACE_PREFIX)) traces.add(line.substring(TRACE_PREFIX.length()));
        }
        return traces;
    }

    private static Method entryMethod(Class<?> type, String name) {
        var methods = Arrays.stream(type.getDeclaredMethods()).filter(m -> m.getName().equals(name)).toList();
        if (methods.size() != 1 || !Modifier.isStatic(methods.getFirst().getModifiers())) {
            throw new HarnessException(type.getName() + " must declare exactly one static method " + name);
        }
        Method method = methods.getFirst();
        method.setAccessible(true);
        return method;
    }

    /** javac must accept every case; a rejection is a harness or generator bug (ADR-063 H6). */
    private static Path compile(DiffCase diffCase) {
        try {
            Path dir = Files.createTempDirectory("julc-difftest-");
            Path file = dir.resolve(diffCase.className() + ".java");
            Files.writeString(file, diffCase.source());
            var errors = new ByteArrayOutputStream();
            int status = ToolProvider.getSystemJavaCompiler().run(null, null, errors,
                    "-proc:none", "-classpath", System.getProperty("java.class.path"),
                    "-d", dir.toString(), file.toString());
            if (status != 0) {
                throw new HarnessException(diffCase.name() + ": javac rejected the source\n" + errors + "\n" + diffCase.source());
            }
            return dir;
        } catch (IOException e) {
            throw new HarnessException(diffCase.name() + ": cannot write the source", e);
        }
    }
}
