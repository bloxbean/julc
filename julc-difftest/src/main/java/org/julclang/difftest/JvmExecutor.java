package org.julclang.difftest;

import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
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
    private final Map<String, Compiled> compiled = new ConcurrentHashMap<>();

    /** Where a source's classes are, and the binary names of the top-level classes javac generated from it. */
    private record Compiled(Path dir, List<String> classes) {
    }

    public JvmExecutor(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * Validates the case's tag table against the compiled source (ADR-066 D3), maps the arguments, runs the
     * entry method and maps its result at its declared return type. The classes are loaded, linked and verified
     * first, without being initialized; initialization belongs to the run, so a failing initializer is the
     * program's failure.
     */
    public synchronized Observation run(DiffCase diffCase) {
        Compiled classes = compiled(diffCase);
        try (var loader = new URLClassLoader(new URL[]{classes.dir().toUri().toURL()}, JvmExecutor.class.getClassLoader())) {
            Method method = linked(diffCase, classes, loader);
            int params = method.getParameterCount();
            if (params != diffCase.args().size()) {
                throw new HarnessException(diffCase.name() + ": " + method.getName() + " takes " + params
                        + " arguments, the case has " + diffCase.args().size());
            }
            return invoke(diffCase, classes, method);
        } catch (IOException | ClassNotFoundException e) {
            throw new HarnessException(diffCase.name() + ": cannot load " + diffCase.className(), e);
        }
    }

    /**
     * The representation the UPLC result must have (ADR-066 D4), from the declared return type of the entry
     * method as javac compiled it: the oracle's reading of the source, never JuLC's. It also validates the case's
     * tag table, so that a case with an invalid table fails before any run.
     */
    public synchronized ValueMapping.Representation resultRepresentation(DiffCase diffCase) {
        Compiled classes = compiled(diffCase);
        try (var loader = new URLClassLoader(new URL[]{classes.dir().toUri().toURL()}, JvmExecutor.class.getClassLoader())) {
            return ValueMapping.Representation.of(linked(diffCase, classes, loader).getGenericReturnType());
        } catch (IOException | ClassNotFoundException e) {
            throw new HarnessException(diffCase.name() + ": cannot load " + diffCase.className(), e);
        }
    }

    private Compiled compiled(DiffCase diffCase) {
        return compiled.computeIfAbsent(diffCase.source(), source -> compile(diffCase));
    }

    /**
     * Loads, links and verifies the case's top-level classes and their member classes, recursively, without
     * initializing them; validates the tag table over them (ADR-066 D3); and returns the entry method. A class the
     * JVM rejects here, such as a {@code VerifyError}, is javac's fault, not an observation of the program: an
     * oracle fault (ADR-063 H6). Local and anonymous classes are not visited.
     */
    private static Method linked(DiffCase diffCase, Compiled classes, ClassLoader loader) throws ClassNotFoundException {
        var types = new ArrayList<Class<?>>();
        try {
            for (String name : classes.classes()) types.add(Class.forName(name, false, loader));
            // Reflecting on a class's methods links it, which verifies it, and does not initialize it.
            for (Class<?> type : types) link(type);
        } catch (LinkageError e) {
            throw new HarnessException(diffCase.name() + ": the JVM rejects javac's class: " + e, e);
        }
        try {
            diffCase.tags().validate(types);
        } catch (HarnessException e) {
            throw new HarnessException(diffCase.name() + ": " + e.getMessage(), e);
        }
        return entryMethod(Class.forName(diffCase.className(), false, loader), diffCase.method());
    }

    private static void link(Class<?> type) {
        type.getDeclaredMethods();
        for (Class<?> member : type.getDeclaredClasses()) link(member);
    }

    /**
     * What a failure while the program ran reports: the exception a class initializer threw, for an
     * {@code ExceptionInInitializerError} or the {@code NoClassDefFoundError} of a later use of that class; else the
     * throwable itself.
     */
    private static Throwable reported(Throwable thrown) {
        Throwable error = thrown instanceof NoClassDefFoundError && thrown.getCause() instanceof ExceptionInInitializerError first
                ? first : thrown;
        return error instanceof ExceptionInInitializerError && error.getCause() != null ? error.getCause() : error;
    }

    /**
     * Whether the JVM rejected a class javac compiled from this case when it loaded lazily, such as a local or
     * anonymous class, which the preflight does not visit: a {@code VerifyError} or {@code ClassFormatError} whose
     * message names one of the case's compiled classes. Such an error thrown by the program itself would be read the
     * same way; generated programs throw none.
     */
    private static boolean rejectsOwnClass(Throwable thrown, Compiled classes) {
        if (!(thrown instanceof VerifyError || thrown instanceof ClassFormatError) || thrown.getMessage() == null) return false;
        String message = thrown.getMessage().replace('/', '.');
        try (var files = Files.walk(classes.dir())) {
            return files.map(file -> classes.dir().relativize(file).toString())
                    .filter(file -> file.endsWith(".class"))
                    .map(file -> file.substring(0, file.length() - ".class".length()).replace(File.separatorChar, '.'))
                    .filter(name -> classes.classes().stream().anyMatch(top -> name.equals(top) || name.startsWith(top + "$")))
                    .anyMatch(message::contains);
        } catch (IOException e) {
            throw new HarnessException("cannot list the classes in " + classes.dir(), e);
        }
    }

    private static Object[] arguments(DiffCase diffCase, Method method) {
        var params = method.getGenericParameterTypes();
        Object[] args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            try {
                args[i] = ValueMapping.fromData(diffCase.args().get(i), params[i], diffCase.tags());
            } catch (HarnessException e) {
                throw new HarnessException(diffCase.name() + ": argument " + i + ": " + e.getMessage(), e);
            }
        }
        return args;
    }

    /**
     * Maps the arguments and invokes the entry method with standard output captured. Both may initialize classes:
     * that is part of the program's run. So whatever the run throws, from an initializer or the method, a
     * {@code LinkageError} included, is the program's {@code Failure} with its traces; the exceptions are a stack
     * overflow (inconclusive) and a class of the case that the JVM rejects while loading it lazily (an oracle fault).
     */
    private Observation invoke(DiffCase diffCase, Compiled classes, Method method) {
        var out = new TraceSink();
        PrintStream original = System.out;
        var executor = Executors.newSingleThreadExecutor(runnable -> {
            var thread = new Thread(runnable, "difftest-jvm");
            thread.setDaemon(true);
            return thread;
        });
        System.setOut(out);
        try {
            var future = executor.submit(() -> method.invoke(null, arguments(diffCase, method)));
            try {
                Object result = future.get(timeoutMillis, TimeUnit.MILLISECONDS);
                return new Observation.Value(ValueMapping.toData(result, method.getGenericReturnType(), diffCase.tags()),
                        out.traces());
            } catch (TimeoutException e) {
                future.cancel(true);
                return new Observation.Inconclusive("JVM timeout after " + timeoutMillis + " ms");
            } catch (ExecutionException e) {
                if (e.getCause() instanceof HarnessException harness) throw harness;
                Throwable cause = e.getCause() instanceof InvocationTargetException ite ? ite.getCause() : e.getCause();
                if (cause instanceof StackOverflowError) return new Observation.Inconclusive("JVM stack overflow");
                if (rejectsOwnClass(cause, classes)) {
                    throw new HarnessException(diffCase.name() + ": the JVM rejects javac's class: " + cause, cause);
                }
                return new Observation.Failure(String.valueOf(reported(cause)), out.traces());
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
        compiled.putAll(compile(List.copyOf(pending.values()), "cases " + pending.size()));
    }

    private static Compiled compile(DiffCase diffCase) {
        return compile(List.of(diffCase), diffCase.name()).get(diffCase.source());
    }

    /**
     * javac must accept every case; a rejection is a harness or generator bug (ADR-063 H6). Returns each source's
     * classes; a listener records which top-level classes javac analyzed in which source file.
     */
    private static Map<String, Compiled> compile(List<DiffCase> cases, String label) {
        try {
            Path dir = Files.createTempDirectory("julc-difftest-");
            var options = List.of("-proc:none", "-classpath", System.getProperty("java.class.path"), "-d", dir.toString());
            var sources = new LinkedHashMap<URI, String>();
            for (DiffCase diffCase : cases) {
                Path file = dir.resolve(diffCase.className() + ".java");
                if (Files.exists(file)) throw new HarnessException(label + ": two sources declare " + diffCase.className());
                Files.writeString(file, diffCase.source());
                sources.put(file.toUri(), diffCase.source());
            }
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            var errors = new StringWriter();
            var emitted = new LinkedHashMap<URI, List<String>>();
            try (var files = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
                var units = files.getJavaFileObjectsFromPaths(sources.keySet().stream().map(Path::of).toList());
                var task = (JavacTask) compiler.getTask(errors, files, null, options, null, units);
                task.addTaskListener(new TaskListener() {
                    @Override
                    public void finished(TaskEvent event) {
                        if (event.getKind() == TaskEvent.Kind.ANALYZE && event.getTypeElement() != null) {
                            emitted.computeIfAbsent(event.getSourceFile().toUri(), uri -> new ArrayList<>())
                                    .add(task.getElements().getBinaryName(event.getTypeElement()).toString());
                        }
                    }
                });
                if (!task.call()) {
                    throw new HarnessException(label + ": javac rejected the source\n" + errors
                            + (cases.size() == 1 ? "\n" + cases.getFirst().source() : ""));
                }
            }
            var result = new LinkedHashMap<String, Compiled>();
            sources.forEach((uri, source) -> result.put(source, new Compiled(dir, List.copyOf(emitted.getOrDefault(uri, List.of())))));
            return result;
        } catch (IOException e) {
            throw new HarnessException(label + ": cannot write the source", e);
        }
    }
}
