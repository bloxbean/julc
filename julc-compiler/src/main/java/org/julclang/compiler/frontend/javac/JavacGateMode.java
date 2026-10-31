package org.julclang.compiler.frontend.javac;

/** How the javac gate of ADR-068 M1 acts on a rejection ({@link JavacGate}). */
public enum JavacGateMode {
    /** javac does not run. */
    OFF,
    /** javac runs; a rejection is recorded ({@link JavacGateReport}) and the compilation goes on. */
    REPORT,
    /** javac runs; a rejection fails the compilation. */
    ENFORCE
}
