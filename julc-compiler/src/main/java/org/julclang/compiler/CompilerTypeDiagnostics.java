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

    /** JULC0052: a compound assignment whose Java meaning the lowering does not implement (ADR-060). */
    public static CompilerException compoundAssignmentUnsupported(String operator, SourceLocation location) {
        var info = DiagnosticCodes.COMPOUND_ASSIGNMENT_UNSUPPORTED;
        return exception(info.code(), info.level(), info.fix(), info.format(operator), location);
    }

    /** JULC0053: a local declaration with more than one variable (ADR-060). */
    public static CompilerException multipleDeclarators(String declaration, SourceLocation location) {
        var info = DiagnosticCodes.MULTIPLE_DECLARATORS_UNSUPPORTED;
        return exception(info.code(), info.level(), info.fix(), info.format(declaration), location);
    }

    /**
     * JULC0054: two static methods with one name that are not interchangeable on-chain. Methods
     * are bound by name, so every call would run one of them (ADR-060).
     */
    public static CompilerException methodOverload(String method, String owner, SourceLocation location) {
        var info = DiagnosticCodes.METHOD_OVERLOAD_UNSUPPORTED;
        return exception(info.code(), info.level(), info.fix(), info.format(method, owner), location);
    }

    /**
     * JULC0055: {@code x.name()} or {@code x.name} whose receiver type is unknown survived to code
     * generation. It used to bind to whatever binder was named {@code name} (ADR-060).
     */
    public static CompilerException unresolvedMember(String member, SourceLocation location) {
        var info = DiagnosticCodes.UNRESOLVED_MEMBER_ACCESS;
        return exception(info.code(), info.level(), info.fix(), info.format(member), location);
    }

    /**
     * JULC0056: a loop assignment to a final static field, or to a static field or {@code @Param}
     * another method reads. The loop lowering rebinds the name only inside the method (ADR-060).
     */
    public static CompilerException fieldAssignment(String field, SourceLocation location) {
        var info = DiagnosticCodes.FIELD_ASSIGNMENT_UNSUPPORTED;
        return exception(info.code(), info.level(), info.fix(), info.format(field), location);
    }

    /** JULC0057: a static field initializer that calls a method of its class. */
    public static CompilerException staticInitializerCallsMethod(String field, String method, SourceLocation location) {
        var info = DiagnosticCodes.STATIC_INITIALIZER_CALLS_METHOD;
        return exception(info.code(), info.level(), info.fix(), info.format(field, method), location);
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
