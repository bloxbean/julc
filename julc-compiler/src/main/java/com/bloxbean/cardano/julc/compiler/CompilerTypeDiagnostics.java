package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.compiler.error.CompilerDiagnostic;
import com.bloxbean.cardano.julc.compiler.error.DiagnosticCodes;
import com.bloxbean.cardano.julc.compiler.pir.PirType;
import com.bloxbean.cardano.julc.compiler.resolve.LibraryMethodRegistry;
import com.bloxbean.cardano.julc.core.source.SourceLocation;

/** Stable diagnostics for correctness-critical compiler type boundaries. */
public final class CompilerTypeDiagnostics {

    private CompilerTypeDiagnostics() {
    }

    public static CompilerException nativeTypeMismatch(
            String operation,
            PirType actual,
            PirType expected,
            SourceLocation location) {
        var info = DiagnosticCodes.NATIVE_TYPE_MISMATCH;
        var message = info.format(
                operation,
                LibraryMethodRegistry.pirTypeName(actual),
                LibraryMethodRegistry.pirTypeName(expected));
        return exception(info.code(), info.level(), info.fix(), message, location);
    }

    public static CompilerException nativeTypeAtDataBoundary(
            String parameterName,
            PirType type,
            SourceLocation location) {
        var info = DiagnosticCodes.NATIVE_TYPE_AT_DATA_BOUNDARY;
        var message = info.format(
                parameterName, LibraryMethodRegistry.pirTypeName(type));
        return exception(info.code(), info.level(), info.fix(), message, location);
    }

    private static CompilerException exception(
            String code,
            CompilerDiagnostic.Level level,
            String fix,
            String message,
            SourceLocation location) {
        var diagnostic = new CompilerDiagnostic(
                level,
                message,
                location != null && location.fileName() != null
                        ? location.fileName() : "<compiler>",
                location != null ? location.line() : 0,
                location != null ? location.column() : 0,
                fix,
                code);
        return new CompilerException(message, diagnostic);
    }
}
