package org.julclang.compiler;

import org.julclang.compiler.frontend.javac.JavacGateMode;
import org.julclang.vm.OptimizationCostProfile;

import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Configuration options for the JuLC compiler.
 * <p>
 * Use {@link #setVerbose(boolean)} to enable verbose output that logs
 * each compilation stage and internal decisions.
 */
public class CompilerOptions {
    private boolean verbose = false;
    private boolean sourceMapEnabled = false;
    private CompilerTarget target = CompilerTarget.PLUTUS_V3_PV11;
    private OptimizationLevel optimizationLevel = OptimizationLevel.DEFAULT;
    private OptimizationCostProfile optimizationCostProfile;
    private final Set<String> disabledOptimizationRules = new LinkedHashSet<>();
    private Consumer<String> logger = System.out::println;
    private JavacGateMode javacGate;
    private Set<String> jarLibrarySources = Set.of();
    private CompilerFrontend frontend = CompilerFrontend.JAVAPARSER;

    /**
     * Select the ledger target for this compilation.
     *
     * <p>JuLC currently supports only {@link CompilerTarget#PLUTUS_V3_PV11}.
     * Unsupported targets fail explicitly when compilation begins; they never
     * fall back to this default.
     */
    public CompilerOptions setTarget(CompilerTarget target) {
        this.target = Objects.requireNonNull(target, "target");
        return this;
    }

    /** Return the requested compiler target. */
    public CompilerTarget getTarget() {
        return target;
    }

    /** Select optimizer rollout independently from compiler-target legality. */
    public CompilerOptions setOptimizationLevel(OptimizationLevel optimizationLevel) {
        this.optimizationLevel = Objects.requireNonNull(
                optimizationLevel, "optimizationLevel");
        return this;
    }

    public OptimizationLevel getOptimizationLevel() {
        return optimizationLevel;
    }

    /**
     * Retain an optional pinned profile for configuration compatibility.
     * Current compiler rules do not read its numeric parameters; it is not required
     * by any optimization level and does not configure runtime evaluation.
     * Supplying a profile does not enable cost-directed rules by itself.
     */
    public CompilerOptions setOptimizationCostProfile(
            OptimizationCostProfile optimizationCostProfile) {
        this.optimizationCostProfile = Objects.requireNonNull(
                optimizationCostProfile, "optimizationCostProfile");
        return this;
    }

    public OptimizationCostProfile getOptimizationCostProfile() {
        return optimizationCostProfile;
    }

    /**
     * Keep one optimization rule off at every level, identified by the id it reports in the
     * optimization report (for example {@code pv11.o8.value-sharing}). Only the PIR-to-PIR
     * passes listed by {@link CompilationContext#switchableOptimizationRules()} can be switched
     * off individually; any other identifier fails when compilation begins rather than being
     * ignored. The switch exists so that each rule can be reviewed, measured and, if necessary,
     * excluded on its own (ADR-044); the level remains the supported way to choose a rollout.
     */
    public CompilerOptions disableOptimizationRule(String ruleId) {
        Objects.requireNonNull(ruleId, "ruleId");
        if (ruleId.isBlank()) throw new IllegalArgumentException("ruleId must not be blank");
        disabledOptimizationRules.add(ruleId);
        return this;
    }

    /** The rule ids passed to {@link #disableOptimizationRule(String)}, in insertion order. */
    public Set<String> getDisabledOptimizationRules() {
        return Collections.unmodifiableSet(disabledOptimizationRules);
    }

    public CompilerOptions setVerbose(boolean verbose) {
        this.verbose = verbose;
        return this;
    }

    public boolean isVerbose() {
        return verbose;
    }

    /**
     * Enable source map generation. When enabled, the compiler captures source positions
     * for UPLC terms and skips optimization to preserve Term identity for runtime lookup.
     */
    public CompilerOptions setSourceMapEnabled(boolean sourceMapEnabled) {
        this.sourceMapEnabled = sourceMapEnabled;
        return this;
    }

    public boolean isSourceMapEnabled() {
        return sourceMapEnabled;
    }

    /**
     * Experimental (ADR-068 M1): how the javac gate acts, which has javac analyze every compilation's sources before
     * the JavaParser frontend compiles them. {@code null} (the default) defers to the system property
     * {@code julc.javacGate} ({@code off}, {@code report} or {@code enforce}), and then to {@code enforce}, which
     * fails a compilation javac rejects. {@code report} never fails one.
     */
    public CompilerOptions setJavacGate(JavacGateMode javacGate) {
        this.javacGate = javacGate;
        return this;
    }

    /** The javac gate's mode, or {@code null} to use the system property. */
    public JavacGateMode getJavacGate() {
        return javacGate;
    }

    /**
     * Experimental (ADR-068 M1, D8): the library sources of this compilation that came from JARs (a class-path scan),
     * by provenance: the {@code String} objects the scan returned, compared by identity, never by text, so a project
     * source whose text equals a JAR copy stays a project source. The javac gate ranks JAR sources below project
     * sources (a JAR unit declaring a project unit's name is shadowed); every other library source is a project
     * source. In {@code enforce} mode the JavaParser frontend compiles the same effective pool; in {@code report} and
     * {@code off} modes it compiles the libraries as given.
     */
    public CompilerOptions setJarLibrarySources(Collection<String> sources) {
        var identities = Collections.newSetFromMap(new IdentityHashMap<String, Boolean>());
        identities.addAll(sources);
        this.jarLibrarySources = Collections.unmodifiableSet(identities);
        return this;
    }

    /** The library sources marked as JAR sources, an identity set ({@link #setJarLibrarySources}). */
    public Set<String> getJarLibrarySources() {
        return jarLibrarySources;
    }

    /**
     * Experimental (ADR-068 D4): the Java frontend of this compilation, {@link CompilerFrontend#JAVAPARSER} by default.
     * {@link CompilerFrontend#JAVAC} has javac read and type the sources and never falls back: until ADR-068 M4 a
     * construct it has not ported is {@code JULC0084}.
     */
    public CompilerOptions setFrontend(CompilerFrontend frontend) {
        this.frontend = Objects.requireNonNull(frontend, "frontend");
        return this;
    }

    /** The Java frontend of this compilation. */
    public CompilerFrontend getFrontend() {
        return frontend;
    }

    public CompilerOptions setLogger(Consumer<String> logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
        return this;
    }

    Consumer<String> getLogger() {
        return logger;
    }

    /**
     * Log a message if verbose mode is enabled.
     */
    public void log(String msg) {
        if (verbose) logger.accept("[julc] " + msg);
    }

    /**
     * Log a formatted message if verbose mode is enabled.
     */
    public void logf(String fmt, Object... args) {
        if (verbose) logger.accept("[julc] " + String.format(fmt, args));
    }

    /**
     * Log a warning unconditionally (not gated by verbose mode).
     */
    public void warnf(String fmt, Object... args) {
        logger.accept("[julc] WARN: " + String.format(fmt, args));
    }
}
