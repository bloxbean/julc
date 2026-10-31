package org.julclang.compiler.frontend.javac;

import org.julclang.compiler.corpus.CorpusRecorder;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.error.DiagnosticCodes;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ADR-068 M1's javac gate: before the JavaParser frontend lowers anything, every {@code JulcCompiler} entry point
 * and {@code JavaLibraryProvider} has javac analyze the same sources (D3, D5). Nothing else changes: the gate never
 * alters what the JavaParser frontend compiles or produces.
 * <p>
 * The mode comes from {@link CompilerOptions#getJavacGate()}, else the system property {@value #MODE_PROPERTY}
 * ({@code off}, {@code report} or {@code enforce}), else {@link JavacGateMode#REPORT} (until M1's dialect rewrite
 * lands). {@code report} never fails a compilation: a would-be rejection is written under the directory the system
 * property {@value #REPORT_PROPERTY} names, if it names one ({@link JavacGateReport}).
 * <p>
 * Results are cached per process by the digest of the inputs: javac's environment is fixed for a process, so the same
 * sources (one test compiled at four optimization levels) give the same result.
 */
public final class JavacGate {

    public static final String MODE_PROPERTY = "julc.javacGate";
    public static final String REPORT_PROPERTY = "julc.javacGate.report";

    private static final int CACHE_LIMIT = 512;
    private static final Map<String, JavacResult> CACHE = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, JavacResult> eldest) {
            return size() > CACHE_LIMIT;
        }
    };

    private JavacGate() {
    }

    /** The gate's mode for a compilation with {@code options}. */
    public static JavacGateMode mode(CompilerOptions options) {
        if (options != null && options.getJavacGate() != null) return options.getJavacGate();
        String value = System.getProperty(MODE_PROPERTY);
        if (value == null || value.isBlank()) return JavacGateMode.REPORT;
        try {
            return JavacGateMode.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(MODE_PROPERTY + " must be off, report or enforce, not " + value);
        }
    }

    /**
     * Runs the gate on one compilation: {@code roots} are the units it compiles, {@code pool} the library sources it
     * is given (D8). In {@code enforce} mode a rejection throws {@link CompilerException} with the javac frontend's
     * diagnostics; in {@code report} mode it is only recorded.
     *
     * @param entry the entry point, for the report (such as {@code compile} or {@code JavaLibraryProvider})
     * @return the result, or null when the gate is off
     */
    public static JavacResult check(String entry, List<JavacFrontend.Unit> roots, List<JavacFrontend.Unit> pool,
                                    CompilerOptions options) {
        JavacGateMode mode = mode(options);
        if (mode == JavacGateMode.OFF) return null;
        JavacResult result;
        try {
            result = cached(roots, pool);
        } catch (LinkageError e) {
            // No java.compiler/jdk.compiler module at all: the frontend's own classes cannot link (I6).
            result = new JavacResult(List.of(JavacDiagnostic.of(DiagnosticCodes.JAVAC_UNSUPPORTED, null,
                    DiagnosticCodes.JAVAC_UNSUPPORTED.format(JavacFrontend.javacVersion(),
                            "this runtime has no Java compiler (" + e + ")"), null, 0, 0, -1, -1, null)),
                    JavacFrontend.javacVersion(), null, null);
        } catch (RuntimeException | StackOverflowError e) {
            // In report mode the gate never fails a compilation, even when javac itself fails (a deep tree).
            if (mode == JavacGateMode.ENFORCE) throw e;
            JavacGateReport.recordFailure(entry, roots, pool, e);
            return null;
        }
        if (result.isAccepted()) return result;
        if (mode == JavacGateMode.ENFORCE) throw new CompilerException(result.compilerDiagnostics());
        JavacGateReport.record(entry, roots, pool, result);
        return result;
    }

    /**
     * The empty {@code JavaLibraryProvider}'s metadata-only task (D8): built over the shipped stubs, which must define
     * the ledger's {@code ScriptContext}. Null when the gate is off; in {@code report} mode a failure is recorded and
     * the result is null.
     */
    public static JavacMetadata metadata(CompilerOptions options) {
        JavacGateMode mode = mode(options);
        if (mode == JavacGateMode.OFF) return null;
        try {
            var support = JavacFrontend.shared().supported();
            if (!support.isAccepted()) {
                if (mode == JavacGateMode.ENFORCE) throw new CompilerException(support.compilerDiagnostics());
                JavacGateReport.record("JavaLibraryProvider", List.of(), List.of(), support);
                return null;
            }
            var metadata = JavacFrontend.shared().metadata();
            if (metadata.elements().getTypeElement(SCRIPT_CONTEXT) == null) {
                throw new IllegalStateException("the javac stubs do not define " + SCRIPT_CONTEXT);
            }
            return metadata;
        } catch (LinkageError | RuntimeException e) {
            if (mode == JavacGateMode.ENFORCE) {
                if (e instanceof RuntimeException runtime) throw runtime;
                throw new CompilerException(List.of(JavacDiagnostic.of(DiagnosticCodes.JAVAC_UNSUPPORTED, null,
                        DiagnosticCodes.JAVAC_UNSUPPORTED.format(JavacFrontend.javacVersion(),
                                "this runtime has no Java compiler (" + e + ")"), null, 0, 0, -1, -1, null)
                        .toCompilerDiagnostic()));
            }
            JavacGateReport.recordFailure("JavaLibraryProvider(empty)", List.of(), List.of(), e);
            return null;
        }
    }

    private static final String SCRIPT_CONTEXT = "org.julclang.ledger.ScriptContext";

    private static JavacResult cached(List<JavacFrontend.Unit> roots, List<JavacFrontend.Unit> pool) {
        String key = key(roots, pool);
        synchronized (CACHE) {
            JavacResult hit = CACHE.get(key);
            if (hit != null) return hit;
        }
        JavacResult result = JavacFrontend.shared().analyze(roots, pool);
        synchronized (CACHE) {
            CACHE.put(key, result);
        }
        return result;
    }

    /** The digest of the inputs: each unit's role, path and text. */
    static String key(List<JavacFrontend.Unit> roots, List<JavacFrontend.Unit> pool) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var group : List.of(roots, pool)) {
                digest.update((byte) 0xff);
                for (var unit : group) {
                    Path path = unit.path();
                    digest.update((path == null ? "-" : path.toString()).getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                    digest.update(CorpusRecorder.bytes(unit.text()));
                    digest.update((byte) 0);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
