package org.julclang.compiler;

import org.julclang.compiler.frontend.javac.JavacGate;

/**
 * ADR-068 M1b: turns the javac gate off for tests of a JuLC dialect construct that has no Java equivalent with
 * today's API, such as a for-each over a {@code JulcMap} (not {@code Iterable} in Java) or a {@code ListsLib}
 * higher-order function whose lambda JuLC types from a typed list. javac rejects those sources, so the frozen
 * JavaParser frontend's behaviour is tested with the gate off until the API changes; M1b's report lists each use.
 * For entry points that take no {@link CompilerOptions} (the testkit), through the gate's system property.
 */
public final class DialectGate {

    private static String saved;

    private DialectGate() {
    }

    /** Sets {@code julc.javacGate=off}, keeping the previous value for {@link #restore()}. */
    public static void off() {
        saved = System.getProperty(JavacGate.MODE_PROPERTY);
        System.setProperty(JavacGate.MODE_PROPERTY, "off");
    }

    /** Restores the value {@link #off()} replaced. */
    public static void restore() {
        if (saved == null) System.clearProperty(JavacGate.MODE_PROPERTY);
        else System.setProperty(JavacGate.MODE_PROPERTY, saved);
        saved = null;
    }
}
