package org.julclang.compiler;

/**
 * Experimental (ADR-068 D4): which Java frontend reads a compilation's sources. The choice is explicit: the javac
 * frontend never hands a program, or part of one, to the JavaParser frontend (I8). A construct the javac frontend has
 * not ported yet is {@code JULC0084}. {@link #JAVAC} is the default from ADR-068 M5; {@link #JAVAPARSER} stays selectable
 * explicitly until M6, which removes it and the option (D4, D12).
 */
public enum CompilerFrontend {
    /** The frozen reference frontend (ADR-068 D4), selected explicitly only; removed at M6. */
    JAVAPARSER,
    /** javac reads, attributes and types the sources (ADR-068 D1). */
    JAVAC;

    /** The frontend a compilation uses when none is selected: {@link #JAVAC}, from ADR-068 M5. */
    public static final CompilerFrontend DEFAULT = JAVAC;

    /** The frontend a host's option names: {@code javaparser} or {@code javac}, in either case. */
    public static CompilerFrontend parse(String name) {
        for (var frontend : values()) {
            if (frontend.name().equalsIgnoreCase(name)) return frontend;
        }
        throw new CompilerException("Unknown frontend '" + name + "'. Supported: javaparser, javac.");
    }
}
