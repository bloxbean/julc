package org.julclang.compiler.frontend.javac.lower;

import org.julclang.compiler.pir.PirType;

/**
 * What lowering needs of the point it is at, passed down and never stored, so no state outlives the construct that set
 * it or leaks on an exception. {@link Exit} is what a {@code return} here does.
 */
record Frame(Scope scope, Exit exit) {

    /**
     * @param returnType   the declared return type; null in a {@code void} method and in a field initializer
     * @param guardBoolean a boolean {@code return} fails instead of returning false, at this {@code return}: the
     *                     entrypoint's source-map lowering
     */
    record Exit(PirType returnType, boolean guardBoolean) {
    }

    /** The frame of a field initializer, which no {@code return} can leave. */
    static Frame initializer(Scope scope) {
        return new Frame(scope, new Exit(null, false));
    }
}
