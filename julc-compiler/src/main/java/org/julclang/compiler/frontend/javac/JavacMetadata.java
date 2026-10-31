package org.julclang.compiler.frontend.javac;

import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.function.BiFunction;

/**
 * A javac task with no compilation units, over the same file manager and stubs as every other task (ADR-068 D8: the
 * empty {@code JavaLibraryProvider}). Only its {@code Elements} and {@code Types} are exposed, so {@code parse()} and
 * {@code analyze()} cannot be called on it.
 * <p>
 * One instance is shared by a process ({@link JavacGate#metadata}), and javac's model is not thread-safe: it
 * completes classes lazily, so even a call on an element obtained earlier reads the task. The instance therefore
 * synchronizes itself: the task is reached only through {@link #read}, which runs a whole query under the instance's
 * lock. Callers take no lock of their own, and must not keep model objects to use after the query returns.
 */
public final class JavacMetadata {

    private final Elements elements;
    private final Types types;

    JavacMetadata(Elements elements, Types types) {
        this.elements = elements;
        this.types = types;
    }

    /** Runs {@code query} over the task's {@code Elements} and {@code Types}, under this metadata's lock. */
    public synchronized <T> T read(BiFunction<Elements, Types, T> query) {
        return query.apply(elements, types);
    }
}
