package org.julclang.compiler.frontend.javac;

/**
 * An internal error: this JuLC build lacks part of its javac environment (the platform blob, the API stubs or the
 * bundled-source manifest). It is a packaging fault of JuLC, never a property of the user's source or runtime, so it
 * is not reported as {@code JULC0089} (ADR-068 D3).
 */
public final class BrokenJulcBuildException extends IllegalStateException {
    public BrokenJulcBuildException(String message) {
        super("broken JuLC build: " + message);
    }
}
