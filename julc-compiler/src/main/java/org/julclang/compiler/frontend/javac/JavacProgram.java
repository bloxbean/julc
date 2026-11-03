package org.julclang.compiler.frontend.javac;

import com.sun.source.tree.CompilationUnitTree;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.resolve.TypeRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * A program the javac frontend has read: attributed units and the types registered from them (ADR-068 D7). javac
 * attributes the roots and, through the source path, the pool units they reference (D8); the library units are in
 * qualified-name order (I4).
 * <p>
 * <b>Thread and lifetime discipline (F847).</b> A program owns one javac task, and javac's model is not thread-safe: it
 * completes classes lazily, so even a call on an element obtained earlier reads and writes the task. So:
 * <ul>
 *   <li>Outside this package, the model is reached only through {@link #read}, which runs a whole query under this
 *       program's lock, as {@link JavacMetadata#read} does. The lock is reentrant, and a query takes no other lock.</li>
 *   <li>A query returns JuLC's own values ({@code PirTerm}, {@code PirType}, diagnostics, strings), which hold no javac
 *       object. An {@code Element}, {@code TypeMirror} or {@code Tree} must not be kept past the query that read it.</li>
 *   <li>A compilation does all its work in one query, on the thread that called it, and drops the program when it
 *       returns. A holder that lowers later, as {@code JavaLibraryProvider.specialize} does at {@code materialize()},
 *       keeps the program and makes a query then, from any thread. Attribution and type registration finish on the
 *       creating thread, before anyone has the program, so a query needs only this lock. The javac task stays in memory
 *       for as long as the holder does.</li>
 * </ul>
 * These are rules for the code in this module: {@link #read} returns whatever its query does, so nothing stops a query
 * from returning an element, and {@link #roots()} hands out trees.
 * <p>
 * A construct not ported yet is {@link NotPorted}: nothing here hands a program to the JavaParser frontend (I8).
 */
public final class JavacProgram {

    private final JavacFrontend frontend;
    private final JavacResult result;
    private final JavacFacts facts;
    private final List<CompilationUnitTree> roots;
    private final List<CompilationUnitTree> libraries;
    private final List<CompilationUnitTree> offered;
    private final List<CompilationUnitTree> offeredByName;
    private JavacTypeRegistrar declarations;

    private JavacProgram(JavacFrontend frontend, JavacFrontend.Attribution attribution) {
        this.frontend = frontend;
        this.result = attribution.result();
        this.facts = JavacFacts.of(attribution, new TypeRegistry());
        this.roots = attribution.roots();
        this.libraries = attribution.libraries();
        this.offered = attribution.offered();
        this.offeredByName = attribution.offeredByName();
    }

    /**
     * Reads {@code roots} with {@code pool} on the source path, and registers the ledger and the attributed units'
     * types. A javac error, or a rejected type, is a {@link CompilerException}.
     */
    public static JavacProgram analyze(List<JavacFrontend.Unit> roots, List<JavacFrontend.Unit> pool) {
        return analyze(JavacFrontend.shared(), roots, pool);
    }

    static JavacProgram analyze(JavacFrontend frontend, List<JavacFrontend.Unit> roots, List<JavacFrontend.Unit> pool) {
        var program = attribute(frontend, roots, pool);
        program.registerTypes();
        return program;
    }

    /** {@link #analyze} without the types, for a compilation with rejections to make first, in the reference's order. */
    public static JavacProgram attribute(List<JavacFrontend.Unit> roots, List<JavacFrontend.Unit> pool) {
        return attribute(JavacFrontend.shared(), roots, pool);
    }

    private static JavacProgram attribute(JavacFrontend frontend, List<JavacFrontend.Unit> roots,
                                          List<JavacFrontend.Unit> pool) {
        var attribution = frontend.attribute(roots, pool);
        if (!attribution.result().isAccepted()) throw new CompilerException(attribution.result().compilerDiagnostics());
        return new JavacProgram(frontend, attribution);
    }

    /** The ledger's and the attributed units' types: library units first, in qualified-name order, then the roots. */
    static JavacProgram read(JavacFrontend frontend, JavacFrontend.Attribution attribution) {
        var program = new JavacProgram(frontend, attribution);
        program.registerTypes();
        return program;
    }

    /** Registers the ledger's and the units' types; a rejected type is a {@link CompilerException}. */
    void registerTypes() {
        var registrar = new JavacTypeRegistrar(facts, frontend);
        registrar.registerLedger();
        var units = new ArrayList<>(libraries);
        units.addAll(roots);
        registrar.registerSources(units);
        declarations = registrar;
    }

    /** Runs {@code query} over this program's model, under its lock: the only way in from outside this package. */
    public synchronized <T> T read(Function<JavacFacts, T> query) {
        return query.apply(facts);
    }

    /** The model, for this package's code that runs before the program is shared, or inside {@link #read}. */
    JavacFacts facts() {
        return facts;
    }

    /** What javac said of the program: accepted, with its environment (D3). */
    JavacResult result() {
        return result;
    }

    /** The registry, filled from javac's model. It holds no javac object. */
    public TypeRegistry registry() {
        return facts.registry();
    }

    /** What was registered, in registration order: the ledger's declarations, then the units'. */
    JavacTypeRegistrar declarations() {
        return declarations;
    }

    public List<CompilationUnitTree> roots() {
        return roots;
    }

    /** The pool units javac read, in qualified-name order. */
    public List<CompilationUnitTree> libraries() {
        return libraries;
    }

    /**
     * What the reference compiles as libraries, of the units javac read, in the order the host offered them: each pool
     * unit it offered, and a root whose text it offered in the pool too. The bundled sources it did not offer are read for
     * their types alone (D8).
     */
    public List<CompilationUnitTree> offered() {
        return offered;
    }

    /** {@link #offered}, in qualified-name order (I4): by the public type's name, or the first type's. */
    public List<CompilationUnitTree> offeredByName() {
        return offeredByName;
    }
}
