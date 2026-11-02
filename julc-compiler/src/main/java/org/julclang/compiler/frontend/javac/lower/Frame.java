package org.julclang.compiler.frontend.javac.lower;

import org.julclang.compiler.pir.PirType;

/**
 * What lowering needs of the point it is at, passed down and never stored, so no state outlives the construct that set
 * it or leaks on an exception. {@link Exit} is what a {@code return} here does, {@link PatternScope} what the {@code switch}
 * arms around here hold, and {@code inGuard} says this is a {@code switch} guard, an arm of a {@code switch} in one
 * included, where a constant has Java's width and {@code instanceof} is a total dispatch (ADR-067).
 */
record Frame(Scope scope, Exit exit, PatternScope patterns, boolean inGuard) {

    /**
     * @param returnType   the declared return type; null in a {@code void} method and in a field initializer
     * @param guardBoolean a boolean {@code return} fails instead of returning false, at this {@code return}: the
     *                     entrypoint's source-map lowering
     */
    record Exit(PirType returnType, boolean guardBoolean) {
    }

    /** The frame of a method's body. */
    static Frame body(Scope scope, Exit exit) {
        return new Frame(scope, exit, PatternScope.NONE, false);
    }

    /** The frame of a field initializer, which no {@code return} can leave. */
    static Frame initializer(Scope scope) {
        return body(scope, new Exit(null, false));
    }

    /** The frame of a lambda's body. A {@code return} there leaves the lambda, so it is no {@code return} of the method (ADR-047). */
    static Frame lambda(Scope scope) {
        return new Frame(scope, new Exit(null, false));
    }

    /** This frame with {@code inner} visible: a binder's scope, which ends where the term it was built for does. */
    Frame in(Scope inner) {
        return new Frame(inner, exit, patterns, inGuard);
    }

    /** This frame in a {@code switch} arm. */
    Frame in(Scope inner, PatternScope arm) {
        return new Frame(inner, exit, arm, inGuard);
    }

    /** This frame in a {@code switch} guard. */
    Frame guard() {
        return new Frame(scope, exit, patterns, true);
    }
}
