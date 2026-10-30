package org.julclang.difftest;

import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;

import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
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
import java.util.Collection;
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
    private final Path workDir;
    private final Map<String, Compiled> compiled = new ConcurrentHashMap<>();

    /**
     * Where a source's classes are, the binary names of the top-level classes javac generated from it, and the binary
     * names of every class file javac wrote for that source (by the source javac passed with each output), member,
     * local and anonymous classes included.
     */
    private record Compiled(Path dir, List<String> classes, List<String> emitted) {
    }

    public JvmExecutor(long timeoutMillis) {
        this(timeoutMillis, null);
    }

    /** An executor that compiles into fresh directories under {@code workDir}, or the system's if it is null. */
    JvmExecutor(long timeoutMillis, Path workDir) {
        this.timeoutMillis = timeoutMillis;
        this.workDir = workDir;
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
            return invoke(diffCase, method);
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
     * Loads, links and verifies every class javac wrote for the case's source, local and anonymous classes included,
     * without initializing them; validates the tag table over the top-level classes and their members (ADR-066 D3);
     * and returns the entry method. A class the JVM rejects here, such as a {@code VerifyError}, is javac's fault, not an
     * observation of the program: an oracle fault (ADR-063 H6). Afterwards every class the program can load from its
     * own source is verified, so nothing the run throws is read as one.
     */
    private static Method linked(DiffCase diffCase, Compiled classes, ClassLoader loader) throws ClassNotFoundException {
        var types = new ArrayList<Class<?>>();
        try {
            for (String name : classes.classes()) types.add(Class.forName(name, false, loader));
            // Reflecting on a class's methods links it, which verifies it, and does not initialize it.
            for (String name : classes.emitted()) Class.forName(name, false, loader).getDeclaredMethods();
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
     * that is part of the program's run. So whatever the run throws, from an initializer or the method, is the
     * program's {@code Failure} with its traces, whatever its class: a {@code VerifyError} or another
     * {@code LinkageError} the program throws, and also one the toolchain raises while the program runs, such as a
     * {@code BootstrapMethodError} from a pattern switch's bootstrap. Only a stack overflow is inconclusive.
     */
    private Observation invoke(DiffCase diffCase, Method method) {
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

    private Compiled compile(DiffCase diffCase) {
        return compile(List.of(diffCase), diffCase.name()).get(diffCase.source());
    }

    /** The source written to the file javac names, compared as files, not by the spelling of their paths. */
    private static String sourceOf(Path unit, Map<Path, String> files) throws IOException {
        for (var file : files.entrySet()) {
            if (Files.isSameFile(unit, file.getKey())) return file.getValue();
        }
        throw new HarnessException("javac compiles " + unit + ", which is not a source of the batch");
    }

    /**
     * The class files javac writes in one compilation, by the source each was compiled from: the sibling javac
     * passes with the output. A class file without a sibling, or with one that is not a source of the batch, is
     * recorded as unowned, and {@link #check} fails then.
     */
    static final class Outputs {
        private final Map<URI, List<String>> owned = new LinkedHashMap<>();
        private final List<String> unowned = new ArrayList<>();

        Outputs(Collection<URI> sources) {
            for (URI source : sources) owned.put(source, new ArrayList<>());
        }

        synchronized void record(String className, FileObject sibling) {
            var classes = sibling == null ? null : owned.get(sibling.toUri());
            if (classes == null) unowned.add(className + " (" + (sibling == null ? "no source" : sibling.toUri()) + ")");
            else classes.add(className);
        }

        synchronized void check(String label) {
            if (!unowned.isEmpty()) throw new HarnessException(label + ": javac wrote class files for no source of the batch: " + unowned);
        }

        synchronized List<String> of(URI source) {
            return List.copyOf(owned.getOrDefault(source, List.of()));
        }
    }

    /**
     * javac must accept every case; a rejection is a harness or generator bug (ADR-063 H6). Returns each source's
     * classes: a listener records which top-level classes javac analyzed in which source file, and the file manager
     * which class files it wrote for which source. Both are keyed by the compilation units' own URIs, as javac
     * reports them. Ownership fails closed: a class file for no source of the batch, or a source whose top-level
     * classes are not all among its class files, is a harness error, so the preflight never verifies less than the
     * case's classes.
     */
    private Map<String, Compiled> compile(List<DiffCase> cases, String label) {
        try {
            Path dir = workDir == null ? Files.createTempDirectory("julc-difftest-") : Files.createTempDirectory(workDir, "julc-difftest-");
            var options = List.of("-proc:none", "-classpath", System.getProperty("java.class.path"), "-d", dir.toString());
            var files = new LinkedHashMap<Path, String>();
            for (DiffCase diffCase : cases) {
                Path file = dir.resolve(diffCase.className() + ".java");
                if (Files.exists(file)) throw new HarnessException(label + ": two sources declare " + diffCase.className());
                Files.writeString(file, diffCase.source());
                files.put(file, diffCase.source());
            }
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            var errors = new StringWriter();
            var emitted = new LinkedHashMap<URI, List<String>>();
            try (var standard = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
                var units = new ArrayList<JavaFileObject>();
                standard.getJavaFileObjectsFromPaths(files.keySet()).forEach(units::add);
                var sources = new LinkedHashMap<URI, String>();
                for (JavaFileObject unit : units) sources.put(unit.toUri(), sourceOf(Path.of(unit.toUri()), files));
                var outputs = new Outputs(sources.keySet());
                var manager = new ForwardingJavaFileManager<>(standard) {
                    @Override
                    public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind,
                                                               FileObject sibling) throws IOException {
                        if (kind == JavaFileObject.Kind.CLASS) outputs.record(className, sibling);
                        return super.getJavaFileForOutput(location, className, kind, sibling);
                    }
                };
                var task = (JavacTask) compiler.getTask(errors, manager, null, options, null, units);
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
                outputs.check(label);
                var result = new LinkedHashMap<String, Compiled>();
                sources.forEach((uri, source) -> {
                    var classes = List.copyOf(emitted.getOrDefault(uri, List.of()));
                    var written = outputs.of(uri);
                    if (classes.isEmpty() || !written.containsAll(classes)) {
                        throw new HarnessException(label + ": javac's classes " + classes + " for " + uri
                                + " are not all among the class files written for it, " + written);
                    }
                    result.put(source, new Compiled(dir, classes, written));
                });
                return result;
            }
        } catch (IOException e) {
            throw new HarnessException(label + ": cannot write the source", e);
        }
    }
}
