package org.julclang.verification;

import org.julclang.core.source.SourceLocation;

/** A source-local, fail-closed verification specification error. */
public final class VerificationPropertyException extends IllegalArgumentException {
    private final SourceLocation sourceLocation;

    public VerificationPropertyException(String message, SourceLocation sourceLocation) {
        super(message + (sourceLocation == null ? "" : System.lineSeparator() + sourceLocation));
        this.sourceLocation = sourceLocation;
    }

    public SourceLocation sourceLocation() {
        return sourceLocation;
    }
}
