package org.julclang.compiler;

import org.julclang.compiler.error.CompilerDiagnostic;
import org.julclang.compiler.error.DiagnosticInfo;
import org.julclang.core.source.SourceLocation;

/**
 * Coded diagnostics for {@code switch} expressions over sealed interfaces (ADR-067 D4, D7). Each check runs on the
 * source before the switch is lowered, so a verdict never depends on the optimization level.
 */
public final class SwitchDiagnostics {

    private SwitchDiagnostics() {
    }

    /** A diagnostic from the catalogue, formatted with {@code args}, as a compiler error at {@code location}. */
    public static CompilerException error(DiagnosticInfo info, SourceLocation location, Object... args) {
        String message = info.format(args);
        var diagnostic = new CompilerDiagnostic(
                info.level(),
                message,
                location != null && location.fileName() != null ? location.fileName() : "<compiler>",
                location != null ? location.line() : 0,
                location != null ? location.column() : 0,
                info.fix(),
                info.code());
        return new CompilerException(message, diagnostic);
    }
}
