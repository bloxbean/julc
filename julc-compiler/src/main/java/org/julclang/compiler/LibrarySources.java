package org.julclang.compiler;

import org.julclang.compiler.frontend.javac.JavacFrontend;
import org.julclang.compiler.frontend.javac.JavacGate;
import org.julclang.compiler.frontend.javac.JavacGateMode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarFile;

/** Deterministic source-only input. No classes or service providers are loaded from user JARs. */
public final class LibrarySources {
    private static final String PREFIX = "META-INF/plutus-sources/";
    private LibrarySources() {}

    /** Resolve bundled dependencies with the same source resolver as the Java compiler. */
    public static List<String> resolve(List<String> modules, Map<String, String> supplied,
                                       ClassLoader loader) {
        var pool = new TreeMap<String, LibrarySource>(LibrarySourceResolver.scanClasspathSources(loader));
        var selected = new TreeMap<String, LibrarySource>();
        supplied.forEach((name, source) -> {
            if (pool.containsKey(name))
                throw new IllegalArgumentException("Custom library conflicts with bundled source: " + name);
            var descriptor = LibrarySourceResolver.librarySource(source);
            if (!name.equals(descriptor.fqcn()))
                throw new IllegalArgumentException("Library source path does not match declaration: " + name + " / " + descriptor.fqcn());
            pool.put(name, descriptor);
            selected.put(name, descriptor);
        });
        for (String module : modules) {
            var source = pool.get(module);
            if (source == null) throw new IllegalArgumentException("On-chain Java source not found: " + module);
            selected.put(module, source);
        }
        for (var source : List.copyOf(selected.values()))
            for (var dependency : LibrarySourceResolver.resolveLibrarySources(source.source(), pool))
                selected.putIfAbsent(dependency.fqcn(), dependency);
        return selected.values().stream().map(LibrarySource::source).toList();
    }

    public static Map<String, String> readJars(List<Path> jars) throws IOException {
        var result = new LinkedHashMap<String, String>();
        var origins = new LinkedHashMap<String, String>();
        for (Path path : jars) {
            try (var jar = new JarFile(path.toFile())) {
                var entries = jar.stream().filter(e -> !e.isDirectory()
                        && e.getName().startsWith(PREFIX) && e.getName().endsWith(".java"))
                        .sorted(Comparator.comparing(java.util.jar.JarEntry::getName)).toList();
                if (entries.isEmpty()) throw new IllegalArgumentException(
                        "No on-chain Java sources in " + path + "; bundle sources under " + PREFIX);
                for (var entry : entries) {
                    String relative = entry.getName().substring(PREFIX.length());
                    if (relative.contains("..") || relative.startsWith("/") || relative.contains("\\"))
                        throw new IllegalArgumentException("Invalid library source path: " + entry.getName());
                    String identity = relative.substring(0, relative.length() - 5).replace('/', '.');
                    try (var stream = jar.getInputStream(entry)) {
                        if (result.putIfAbsent(identity, new String(stream.readAllBytes(), StandardCharsets.UTF_8)) != null)
                            throw new IllegalArgumentException("Duplicate library source ownership: " + identity + " in " + path);
                        origins.put(identity, path + "!/" + entry.getName());
                    }
                }
            }
        }
        checkDeclarations(result, origins);
        return Collections.unmodifiableMap(result);
    }

    private static final AtomicBoolean SKIP_WARNED = new AtomicBoolean();

    /**
     * ADR-068 D8: two JAR sources declaring one qualified name are an error, over every top-level declaration, not
     * only the entry paths, read as the javac gate reads them. The check needs a supported javac: without one it
     * fails with {@code JULC0089} when the gate is enforced; otherwise (report, off) only the entry paths are checked,
     * as at B0, and the skipped check is reported once per process on standard error.
     */
    private static void checkDeclarations(Map<String, String> sources, Map<String, String> origins) {
        var identities = List.copyOf(sources.keySet());
        Optional<List<List<String>>> names;
        CompilerException unsupported;
        try {
            var frontend = JavacFrontend.shared();
            names = frontend.declaredNames(identities.stream().map(sources::get).toList());
            unsupported = names.isEmpty() ? new CompilerException(frontend.supported().compilerDiagnostics()) : null;
        } catch (LinkageError e) {
            if (!JavacGate.isMissingCompiler(e)) throw e;
            names = Optional.empty();
            unsupported = new CompilerException(JavacGate.noCompiler(e).compilerDiagnostics());
        }
        if (names.isEmpty()) {
            if (JavacGate.mode(null) == JavacGateMode.ENFORCE) throw unsupported;
            if (SKIP_WARNED.compareAndSet(false, true)) {
                System.err.println("[julc] WARN: LibrarySources.readJars checked entry paths only: without a supported"
                        + " javac the duplicate check over declared names (ADR-068 D8) was skipped ("
                        + unsupported.diagnostics().getFirst().message() + ")");
            }
            return;
        }
        var declarers = new HashMap<String, String>();
        for (int i = 0; i < identities.size(); i++) {
            for (String name : names.get().get(i)) {
                String first = declarers.putIfAbsent(name, identities.get(i));
                if (first != null) {
                    throw new IllegalArgumentException("Duplicate library declaration: " + name + " is declared in "
                            + origins.get(first) + " and " + origins.get(identities.get(i)));
                }
            }
        }
    }
}
