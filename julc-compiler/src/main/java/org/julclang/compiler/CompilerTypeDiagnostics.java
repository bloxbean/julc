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
     * JULC0091 (ADR-068 D6, #298): a native array in a Data-encoded position. It has no Data
     * encoding, so the script would fail where the Java code succeeds.
     */
    public static CompilerException nativeArrayNotDataEncodable(
            String position,
            PirType array,
            SourceLocation location) {
        var info = DiagnosticCodes.NATIVE_ARRAY_NOT_DATA_ENCODABLE;
        var message = info.format(position, LibraryMethodRegistry.pirTypeName(array));
        return exception(info.code(), info.level(), info.fix(), message, location);
    }

    /**
     * JULC0012 (#299): a diamond {@code new Tuple2<>(...)} whose component types neither a declared
     * target nor the arguments give. Its components could not be Data-encoded by type.
     */
    public static CompilerException diamondTypeArgumentsUnknown(String typeName, SourceLocation location) {
        var info = DiagnosticCodes.TYPE_RESOLUTION_FAILED;
        var message = info.format("the type arguments of new " + typeName + "<>(...): neither a declared target type nor "
                + "the arguments give them. Write them, as in new " + typeName + "<BigInteger, byte[]>(a, b)");
        return exception(info.code(), info.level(), info.fix(), message, location);
    }

    /**
     * JULC0012 (#299): a non-Data component of a raw {@code Tuple2} or {@code Tuple3}. javac types
     * its components {@code Object}, so it has no on-chain encoding and its reads no on-chain type.
     */
    public static CompilerException rawTupleUnsupported(String typeName, SourceLocation location) {
        var info = DiagnosticCodes.TYPE_RESOLUTION_FAILED;
        var message = info.format("a component of a raw " + typeName + ", which Java types as Object and JuLC cannot encode"
                + " unless it is already PlutusData. Write the type arguments, as in " + typeName + "<BigInteger, byte[]>");
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
     * JULC0056: a loop assignment to a static field or {@code @Param} whose result would differ
     * from Java; {@code reason} says why. The loop lowering rebinds the name only in the rest of
     * the assigning method's current run (ADR-060).
     */
    public static CompilerException fieldAssignment(String field, String reason, SourceLocation location) {
        var info = DiagnosticCodes.FIELD_ASSIGNMENT_UNSUPPORTED;
        return exception(info.code(), info.level(), info.fix(), info.format(field, reason), location);
    }

    /** JULC0070 (ADR-062 T25): an integer literal javac rejects; {@code problem} says why. */
    public static CompilerException invalidIntegerLiteral(String literal, String problem, SourceLocation location) {
        var info = DiagnosticCodes.INTEGER_LITERAL_INVALID;
        return exception(info.code(), info.level(), info.fix(), info.format(literal, problem), location);
    }

    /**
     * JULC0058: a for-each loop with no accumulator reassigns its loop variable or a body local;
     * {@code kind} is {@code "loop variable"} or {@code "local variable"}.
     */
    public static CompilerException loopReassignmentWithoutAccumulator(String kind, String name, SourceLocation location) {
        var info = DiagnosticCodes.LOOP_REASSIGNMENT_WITHOUT_ACCUMULATOR;
        return exception(info.code(), info.level(), info.fix(), info.format(kind, name), location);
    }

    /** JULC0003: a {@code return} directly in a while loop body; {@code location} is the return (ADR-062 T20). */
    public static CompilerException returnInsideWhile(SourceLocation location) {
        var info = DiagnosticCodes.RETURN_INSIDE_WHILE;
        return exception(info.code(), info.level(), info.fix(), info.format(), location);
    }

    /** JULC0062: {@code JulcMap.of(...)}, which exists off-chain only (ADR-062 T20). */
    public static CompilerException julcMapOfOffChainOnly(SourceLocation location) {
        var info = DiagnosticCodes.JULCMAP_OF_OFF_CHAIN_ONLY;
        return exception(info.code(), info.level(), info.fix(), info.format(), location);
    }

    /**
     * JULC0063: a {@code break} in a loop that updates no variable declared before it. Such a
     * loop compiles its body as plain statements, which cannot stop the loop (ADR-062 T20).
     */
    public static CompilerException breakInLoopWithoutAccumulator(SourceLocation location) {
        var info = DiagnosticCodes.BREAK_IN_LOOP_WITHOUT_ACCUMULATOR;
        return exception(info.code(), info.level(), info.fix(), info.format(), location);
    }

    /**
     * JULC0060: a {@code continue} in a loop the compiler lowers. It is reported at loop lowering,
     * not by subset validation, so code JuLC never compiles is unaffected (ADR-062 T20).
     */
    public static CompilerException continueUnsupported(SourceLocation location) {
        var info = DiagnosticCodes.CONTINUE_UNSUPPORTED;
        return exception(info.code(), info.level(), info.fix(), info.format(), location);
    }

    /**
     * JULC0065: an update of {@code name}, a variable declared before the loop, in a position the
     * loop lowering does not carry; {@code position} says where, for example
     * {@code "inside a switch statement"} (ADR-062 T20).
     */
    public static CompilerException loopUpdatePositionUnsupported(String name, String position, SourceLocation location) {
        var info = DiagnosticCodes.LOOP_UPDATE_POSITION_UNSUPPORTED;
        return exception(info.code(), info.level(), info.fix(), info.format(name, position), location);
    }

    /**
     * JULC0064: a nested loop in the break-aware body of a single-accumulator loop;
     * {@code statement} is {@code "A nested while loop"} or {@code "A nested for-each loop"} (ADR-062 T20).
     */
    public static CompilerException breakLoopStatementUnsupported(String statement, SourceLocation location) {
        var info = DiagnosticCodes.BREAK_LOOP_STATEMENT_UNSUPPORTED;
        return exception(info.code(), info.level(), info.fix(), info.format(statement), location);
    }

    /**
     * JULC0066: a labelled statement, or a {@code break} or {@code continue} that names a label;
     * {@code construct} is the construct as written, for example {@code "break outer"} or
     * {@code "outer:"} (#201).
     */
    public static CompilerException labelUnsupported(String construct, SourceLocation location) {
        var info = DiagnosticCodes.LABEL_UNSUPPORTED;
        return exception(info.code(), info.level(), info.fix(), info.format(construct), location);
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
