package org.julclang.difftest;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * The JVM side of the harness (ADR-063 D2): javac compiles the source against the harness's own
 * classpath, and every evaluation loads the class in a fresh class loader, so static fields start
 * from their initializers (H3). This side never sees JuLC's output (H1).
 * <p>
 * Off-chain {@code Builtins.trace} prints {@code [TRACE] message} with one {@code println} call. The
 * executor replaces standard output with a {@link TraceSink} that records each such call, made
 * directly by {@code Builtins.trace}, as one trace event, so a message keeps its line breaks and
 * anything else written to standard output is never mistaken for a trace. Evaluations are
 * serialized because standard output is process-wide. A timed-out evaluation is abandoned on a
 * daemon thread, not stopped; generated programs terminate by construction (D5), so the timeout
 * is only a safety net.
 */
public final class JvmExecutor {

    private static final String TRACE_PREFIX = "[TRACE] ";
    private static final String TRACE_CLASS = "org.julclang.stdlib.Builtins";

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
        var out = new TraceSink();
        PrintStream original = System.out;
        var executor = Executors.newSingleThreadExecutor(runnable -> {
            var thread = new Thread(runnable, "difftest-jvm");
            thread.setDaemon(true);
            return thread;
        });
        System.setOut(out);
        try {
            var future = executor.submit(() -> method.invoke(null, args));
            try {
                Object result = future.get(timeoutMillis, TimeUnit.MILLISECONDS);
                return new Observation.Value(ValueMapping.toData(result), out.traces());
            } catch (TimeoutException e) {
                future.cancel(true);
                return new Observation.Inconclusive("JVM timeout after " + timeoutMillis + " ms");
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() instanceof InvocationTargetException ite ? ite.getCause() : e.getCause();
                if (cause instanceof StackOverflowError) return new Observation.Inconclusive("JVM stack overflow");
                return new Observation.Failure(String.valueOf(cause), out.traces());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Observation.Inconclusive("interrupted");
            }
        } finally {
            System.setOut(original);
            executor.shutdownNow();
        }
    }

    /** Standard output during one evaluation: keeps the trace events, discards everything else. */
    private static final class TraceSink extends PrintStream {
        private final List<String> traces = Collections.synchronizedList(new ArrayList<>());

        TraceSink() {
            super(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8);
        }

        @Override
        public void println(String line) {
            var caller = StackWalker.getInstance().walk(frames -> frames.skip(1).findFirst()).orElseThrow();
            if (caller.getClassName().equals(TRACE_CLASS) && caller.getMethodName().equals("trace")
                    && line != null && line.startsWith(TRACE_PREFIX)) {
                traces.add(line.substring(TRACE_PREFIX.length()));
            }
        }

        List<String> traces() {
            return List.copyOf(traces);
        }
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

    /**
     * Compiles many sources with one javac run, so that their later evaluations need no compilation.
     * Their classes must have distinct names.
     */
    public synchronized void precompile(List<DiffCase> cases) {
        var pending = cases.stream().filter(c -> !compiled.containsKey(c.source()))
                .collect(Collectors.toMap(DiffCase::source, c -> c, (first, second) -> first,
                        LinkedHashMap::new));
        if (pending.isEmpty()) return;
        Path dir = compile(List.copyOf(pending.values()), "cases " + pending.size());
        pending.keySet().forEach(source -> compiled.put(source, dir));
    }

    private static Path compile(DiffCase diffCase) {
        return compile(List.of(diffCase), diffCase.name());
    }

    /** javac must accept every case; a rejection is a harness or generator bug (ADR-063 H6). */
    private static Path compile(List<DiffCase> cases, String label) {
        try {
            Path dir = Files.createTempDirectory("julc-difftest-");
            var arguments = new ArrayList<>(List.of("-proc:none", "-classpath", System.getProperty("java.class.path"),
                    "-d", dir.toString()));
            for (DiffCase diffCase : cases) {
                Path file = dir.resolve(diffCase.className() + ".java");
                if (Files.exists(file)) throw new HarnessException(label + ": two sources declare " + diffCase.className());
                Files.writeString(file, diffCase.source());
                arguments.add(file.toString());
            }
            var errors = new ByteArrayOutputStream();
            int status = ToolProvider.getSystemJavaCompiler().run(null, null, errors, arguments.toArray(String[]::new));
            if (status != 0) {
                throw new HarnessException(label + ": javac rejected the source\n" + errors
                        + (cases.size() == 1 ? "\n" + cases.getFirst().source() : ""));
            }
            return dir;
        } catch (IOException e) {
            throw new HarnessException(label + ": cannot write the source", e);
        }
    }
}
