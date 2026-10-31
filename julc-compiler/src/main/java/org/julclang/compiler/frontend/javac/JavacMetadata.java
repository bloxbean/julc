package org.julclang.compiler.frontend.javac;

import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * A javac task with no compilation units, over the same file manager and stubs as every other task (ADR-068 D8: the
 * empty {@code JavaLibraryProvider}). Only its {@code Elements} and {@code Types} are exposed, so {@code parse()} and
 * {@code analyze()} cannot be called on it.
 */
public final class JavacMetadata {

    private final Elements elements;
    private final Types types;

    JavacMetadata(Elements elements, Types types) {
        this.elements = elements;
        this.types = types;
    }

    public Elements elements() {
        return elements;
    }

    public Types types() {
        return types;
    }
}
