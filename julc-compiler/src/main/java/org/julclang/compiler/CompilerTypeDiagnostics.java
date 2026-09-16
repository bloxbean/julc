package org.julclang.compiler;

import org.julclang.compiler.error.CompilerDiagnostic;
import org.julclang.compiler.error.DiagnosticCodes;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.resolve.LibraryMethodRegistry;
import org.julclang.core.source.SourceLocation;

import java.util.List;
import java.util.stream.Collectors;

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

    /**
     * JULC0012: {@code var a = JulcArray.of(...)} whose elements have different types. javac
     * would type the local by a common supertype the on-chain subset cannot represent, and the
     * access needs one element type to choose its decode.
     */
    public static CompilerException arrayLiteralElementTypesDiffer(
            List<PirType> elementTypes,
            SourceLocation location) {
        var info = DiagnosticCodes.TYPE_RESOLUTION_FAILED;
        var names = elementTypes.stream().map(LibraryMethodRegistry::pirTypeName).distinct()
                .collect(Collectors.joining(", "));
        var message = info.format("the element type of JulcArray.of(...) under var: the elements have different types ("
                + names + "). Declare the array type, JulcArray<T> a = JulcArray.of(...)");
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
