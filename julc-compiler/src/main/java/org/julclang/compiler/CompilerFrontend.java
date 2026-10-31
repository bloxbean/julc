package org.julclang.compiler;

/**
 * Experimental (ADR-068 D4): which Java frontend reads a compilation's sources. The choice is explicit: the javac
 * frontend never hands a program, or part of one, to the JavaParser frontend (I8). A construct the javac frontend has
 * not ported yet is {@code JULC0084}. {@link #JAVAPARSER} stays the default until ADR-068 M5, and the option goes at M6.
 */
public enum CompilerFrontend {
    /** The frozen reference frontend (ADR-068 D4). */
    JAVAPARSER,
    /** javac reads, attributes and types the sources (ADR-068 D1). */
    JAVAC
}
