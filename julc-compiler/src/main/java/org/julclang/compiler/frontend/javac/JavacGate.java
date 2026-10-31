package org.julclang.compiler.frontend.javac;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.error.DiagnosticCodes;
import org.julclang.core.corpus.CorpusFiles;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.security.DigestOutputStream;
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
 * ({@code off}, {@code report} or {@code enforce}), else {@link JavacGateMode#ENFORCE} (M1b rewrote every in-repo
 * source to Java, D6). {@code report} never fails a compilation: a would-be rejection is written under the directory the system
 * property {@value #REPORT_PROPERTY} names, if it names one ({@link JavacGateReport}).
 * <p>
 * Results are cached by the digest of the inputs within the frontend's immutable environment (see {@link #cached}),
 * so the same sources in the same environment (one test compiled at four optimization levels) give the same result.
 */
public final class JavacGate {

    public static final String MODE_PROPERTY = "julc.javacGate";
    public static final String REPORT_PROPERTY = "julc.javacGate.report";

    private JavacGate() {
    }

    /** The gate's mode for a compilation with {@code options}. */
    public static JavacGateMode mode(CompilerOptions options) {
        if (options != null && options.getJavacGate() != null) return options.getJavacGate();
        String value = System.getProperty(MODE_PROPERTY);
        if (value == null || value.isBlank()) return JavacGateMode.ENFORCE;
        try {
            return JavacGateMode.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(MODE_PROPERTY + " must be off, report or enforce, not " + value);
        }
    }

    /**
     * Runs the gate on one compilation: {@code roots} are the units it compiles, {@code pool} the library sources it
     * is given (D8). In {@code enforce} mode a rejection throws {@link CompilerException} with the javac frontend's
     * diagnostics; in {@code report} mode it is only recorded, best-effort.
     * <p>
     * A runtime without the {@code java.compiler}/{@code jdk.compiler} modules is {@code JULC0089}. A JuLC build
     * without its javac resources is {@link BrokenJulcBuildException}, an internal error, never {@code JULC0089}.
     *
     * @param entry the entry point, for the report (such as {@code compile} or {@code JavaLibraryProvider})
     * @return the result, or null when the gate is off (or, in report mode, when the gate itself failed)
     */
    public static JavacResult check(String entry, List<JavacFrontend.Unit> roots, List<JavacFrontend.Unit> pool,
                                    CompilerOptions options) {
        JavacGateMode mode = mode(options);
        if (mode == JavacGateMode.OFF) return null;
        JavacResult result;
        try {
            result = cached(roots, pool);
        } catch (LinkageError e) {
            if (!isMissingCompiler(e)) return failed(mode, entry, roots, pool, e);
            result = noCompiler(e);
        } catch (RuntimeException | StackOverflowError e) {
            // In report mode the gate never fails a compilation, even when javac itself fails (a deep tree).
            return failed(mode, entry, roots, pool, e);
        }
        if (result.isAccepted()) return result;
        if (mode == JavacGateMode.ENFORCE) throw new CompilerException(result.compilerDiagnostics());
        JavacGateReport.record(entry, roots, pool, result);
        return result;
    }

    private static JavacResult failed(JavacGateMode mode, String entry, List<JavacFrontend.Unit> roots,
                                      List<JavacFrontend.Unit> pool, Throwable e) {
        if (mode == JavacGateMode.ENFORCE) {
            if (e instanceof RuntimeException runtime) throw runtime;
            if (e instanceof Error error) throw error;
            throw new IllegalStateException(e);
        }
        JavacGateReport.recordFailure(entry, roots, pool, e);
        return null;
    }

    /** Whether {@code e} says that the runtime has no Java compiler API (I6), as opposed to another linkage fault. */
    public static boolean isMissingCompiler(LinkageError e) {
        if (!(e instanceof NoClassDefFoundError)) return false;
        String message = String.valueOf(e.getMessage()).replace('.', '/');
        return message.startsWith("javax/tools/") || message.startsWith("com/sun/source/")
                || message.startsWith("javax/lang/model/");
    }

    /** {@code JULC0089} for a runtime whose compiler API cannot link; it references no compiler-API class. */
    public static JavacResult noCompiler(LinkageError e) {
        // No reference to JavacFrontend here: it cannot link without the compiler API.
        String version = Runtime.version().toString();
        return new JavacResult(List.of(JavacDiagnostic.of(DiagnosticCodes.JAVAC_UNSUPPORTED, null,
                DiagnosticCodes.JAVAC_UNSUPPORTED.format(version, "this runtime has no Java compiler (" + e + ")"),
                null, 0, 0, -1, -1, null)), version, null, null);
    }

    private static volatile JavacMetadata metadata;

    /**
     * The empty {@code JavaLibraryProvider}'s metadata-only task (D8): built over the shipped stubs, which must define
     * the ledger's {@code ScriptContext}, once per process. Null when the gate is off; in {@code report} mode a
     * failure is recorded and the result is null. The instance synchronizes its own queries ({@link JavacMetadata#read}).
     */
    public static JavacMetadata metadata(CompilerOptions options) {
        JavacGateMode mode = mode(options);
        if (mode == JavacGateMode.OFF) return null;
        JavacMetadata cached = metadata;
        if (cached != null) return cached;
        try {
            var support = JavacFrontend.shared().supported();
            if (!support.isAccepted()) {
                if (mode == JavacGateMode.ENFORCE) throw new CompilerException(support.compilerDiagnostics());
                JavacGateReport.record("JavaLibraryProvider", List.of(), List.of(), support);
                return null;
            }
            synchronized (JavacGate.class) {
                if (metadata == null) {
                    var built = JavacFrontend.shared().metadata();
                    if (built.read((elements, types) -> elements.getTypeElement(SCRIPT_CONTEXT) == null)) {
                        throw new BrokenJulcBuildException("the javac stubs do not define " + SCRIPT_CONTEXT);
                    }
                    metadata = built;
                }
                return metadata;
            }
        } catch (LinkageError e) {
            if (!isMissingCompiler(e)) return failedMetadata(mode, e);
            if (mode == JavacGateMode.ENFORCE) throw new CompilerException(noCompiler(e).compilerDiagnostics());
            JavacGateReport.record("JavaLibraryProvider", List.of(), List.of(), noCompiler(e));
            return null;
        } catch (RuntimeException e) {
            return failedMetadata(mode, e);
        }
    }

    private static JavacMetadata failedMetadata(JavacGateMode mode, Throwable e) {
        failed(mode, "JavaLibraryProvider(empty)", List.of(), List.of(), e);
        return null;
    }

    private static final String SCRIPT_CONTEXT = "org.julclang.ledger.ScriptContext";

    /**
     * The result for these inputs in the current environment. The frontend is selected first and holds its own
     * cache, so a result is only ever reused within the immutable environment (platform, stubs, bundled set) that
     * produced it; an analysis still running in a replaced environment fills only that environment's cache (F510).
     */
    private static JavacResult cached(List<JavacFrontend.Unit> roots, List<JavacFrontend.Unit> pool) {
        var frontend = JavacFrontend.shared();
        return frontend.cached(key(roots, pool), () -> frontend.analyze(roots, pool));
    }

    /**
     * The digest of an unambiguous encoding of the inputs: the number of groups, then per group its number of units,
     * then per unit a tag for whether it has a path, the length-prefixed injective stored form of the path
     * ({@link CorpusFiles#bytes}), that of the text, and its origin. No two different inputs share an encoding, so a cached result is always the result of these inputs.
     */
    static String key(List<JavacFrontend.Unit> roots, List<JavacFrontend.Unit> pool) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var groups = List.of(roots, pool);
            var out = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest));
            out.writeInt(groups.size());
            for (var group : groups) {
                out.writeInt(group.size());
                for (var unit : group) {
                    Path path = unit.path();
                    out.writeBoolean(path != null);
                    if (path != null) writeChars(out, path.toString());
                    writeChars(out, unit.text());
                    out.writeByte(unit.origin().ordinal());
                }
            }
            out.flush();
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The string's injective stored form ({@link CorpusFiles#bytes}), length-prefixed. */
    private static void writeChars(DataOutputStream out, String text) throws IOException {
        byte[] bytes = CorpusFiles.bytes(text);
        out.writeInt(bytes.length);
        out.write(bytes);
    }
}
