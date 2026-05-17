// GENERATED FROM julc-compiler/src/main/resources/diagnostics.json
// DO NOT EDIT - run `./gradlew :julc-compiler:generateDiagnosticCodes` and commit.
package com.bloxbean.cardano.julc.compiler.error;

public final class DiagnosticCodes {
    private DiagnosticCodes() {}

    public static final DiagnosticInfo ARRAY_UNSUPPORTED = new DiagnosticInfo(
            "JULC0014",
            "ARRAY_UNSUPPORTED",
            CompilerDiagnostic.Level.ERROR,
            "SYNTAX",
            "arrays are not supported on-chain",
            "Use `JulcList<T>` or `List<T>` instead of `T[]`.");

    public static final DiagnosticInfo BREAK_OUTSIDE_LOOP = new DiagnosticInfo(
            "JULC0004",
            "BREAK_OUTSIDE_LOOP",
            CompilerDiagnostic.Level.ERROR,
            "CONTROL_FLOW",
            "break statement outside of a loop",
            "Place the `break` inside a loop, or refactor to use early `return` from a helper method.");

    public static final DiagnosticInfo CIRCULAR_TYPE_DEPENDENCY = new DiagnosticInfo(
            "JULC0030",
            "CIRCULAR_TYPE_DEPENDENCY",
            CompilerDiagnostic.Level.ERROR,
            "TYPE",
            "Circular type dependency detected among: {0}",
            "Break the cycle by introducing a parameterized record or refactoring the involved types.");

    public static final DiagnosticInfo C_STYLE_FOR_UNSUPPORTED = new DiagnosticInfo(
            "JULC0018",
            "C_STYLE_FOR_UNSUPPORTED",
            CompilerDiagnostic.Level.ERROR,
            "SYNTAX",
            "C-style for loops are not supported on-chain",
            "Use for-each over a list or while loops instead");

    public static final DiagnosticInfo DO_WHILE_UNSUPPORTED = new DiagnosticInfo(
            "JULC0019",
            "DO_WHILE_UNSUPPORTED",
            CompilerDiagnostic.Level.ERROR,
            "SYNTAX",
            "do-while loops are not supported on-chain",
            "Use while loops or for-each instead");

    public static final DiagnosticInfo DUPLICATE_TYPE_DECLARATION = new DiagnosticInfo(
            "JULC0029",
            "DUPLICATE_TYPE_DECLARATION",
            CompilerDiagnostic.Level.ERROR,
            "TYPE",
            "Duplicate type declaration: {0}",
            "Rename one of the types, or ensure they are not both on the compilation classpath.");

    public static final DiagnosticInfo ENTRYPOINT_MISSING = new DiagnosticInfo(
            "JULC0009",
            "ENTRYPOINT_MISSING",
            CompilerDiagnostic.Level.ERROR,
            "VALIDATOR",
            "No @Entrypoint method found in {0}",
            "Annotate exactly one `public static` method with `@Entrypoint`. The method must return `boolean` (or `void` for newer signature shapes).");

    public static final DiagnosticInfo ENTRYPOINT_WRONG_PARAMETER_COUNT = new DiagnosticInfo(
            "JULC0008",
            "ENTRYPOINT_WRONG_PARAMETER_COUNT",
            CompilerDiagnostic.Level.ERROR,
            "VALIDATOR",
            "{0} entrypoint must have {1} parameters{2}, found {3} in {4}.{5}(){6}",
            "Adjust the entrypoint signature to match the validator annotation. Spending: `(Datum, Redeemer, ScriptContext)`. Others: `(Redeemer, ScriptContext)`.");

    public static final DiagnosticInfo FLOATING_POINT_UNSUPPORTED = new DiagnosticInfo(
            "JULC0020",
            "FLOATING_POINT_UNSUPPORTED",
            CompilerDiagnostic.Level.ERROR,
            "TYPE",
            "floating point types (float/double) are not supported on-chain",
            "Use `BigInteger` for integer math. For fractional values, scale up (e.g. parts-per-million) and do integer math, or use `Rational` from the ledger types when modelling protocol params.");

    public static final DiagnosticInfo LAMBDA_STORED_IN_VARIABLE_UNSUPPORTED = new DiagnosticInfo(
            "JULC0022",
            "LAMBDA_STORED_IN_VARIABLE_UNSUPPORTED",
            CompilerDiagnostic.Level.ERROR,
            "SYNTAX",
            "Lambda cannot be stored in a variable",
            "Pass the lambda directly: `list.map(x -> ...)`, `list.filter(p -> ...)`. If you need to share lambda logic, extract it as a regular static method (not as a lambda).");

    public static final DiagnosticInfo METHOD_BODY_MISSING = new DiagnosticInfo(
            "JULC0001",
            "METHOD_BODY_MISSING",
            CompilerDiagnostic.Level.ERROR,
            "VALIDATOR",
            "Method must have a body: {0}",
            "Provide a body for every method in your validator class.");

    public static final DiagnosticInfo METHOD_MISSING_RETURN = new DiagnosticInfo(
            "JULC0006",
            "METHOD_MISSING_RETURN",
            CompilerDiagnostic.Level.ERROR,
            "CONTROL_FLOW",
            "Method {0} may not return a value on all execution paths",
            "Ensure every if/else branch returns, or add a fallthrough return at the end of the method.");

    public static final DiagnosticInfo MUTUAL_RECURSION_TOO_LARGE = new DiagnosticInfo(
            "JULC0023",
            "MUTUAL_RECURSION_TOO_LARGE",
            CompilerDiagnostic.Level.ERROR,
            "PIR",
            "Mutually recursive bindings with more than 2 participants not yet supported: {0}",
            "Refactor: combine the helpers into a single function with an accumulator, or break the recursion via an explicit dispatch on a sealed interface.");

    public static final DiagnosticInfo NEWTYPE_UNSUPPORTED_FIELD_TYPE = new DiagnosticInfo(
            "JULC0027",
            "NEWTYPE_UNSUPPORTED_FIELD_TYPE",
            CompilerDiagnostic.Level.ERROR,
            "TYPE",
            "Unsupported @NewType field type: {0}",
            "Change the underlying field to a supported primitive, or remove @NewType.");

    public static final DiagnosticInfo NEWTYPE_WRONG_FIELD_COUNT = new DiagnosticInfo(
            "JULC0026",
            "NEWTYPE_WRONG_FIELD_COUNT",
            CompilerDiagnostic.Level.ERROR,
            "TYPE",
            "@NewType record {0} must have exactly one field",
            "Reduce to one field, or remove @NewType and use a plain record (which compiles to ConstrData).");

    public static final DiagnosticInfo NULL_UNSUPPORTED = new DiagnosticInfo(
            "JULC0017",
            "NULL_UNSUPPORTED",
            CompilerDiagnostic.Level.ERROR,
            "SYNTAX",
            "null is not supported on-chain",
            "Use Optional<T> (Optional.of(x) / Optional.empty()) to represent absence of a value");

    public static final DiagnosticInfo PARAM_RAW_PLUTUS_DATA = new DiagnosticInfo(
            "JULC0013",
            "PARAM_RAW_PLUTUS_DATA",
            CompilerDiagnostic.Level.ERROR,
            "LIBRARY",
            "@Param type ''{0}'' is not allowed. @Param values are always raw Data at runtime; using a typed Data subtype causes the compiler to misinterpret the runtime representation.",
            "Use byte[], BigInteger, typed records, redeemers, or @Param PlutusData only for opaque data.");

    public static final DiagnosticInfo RETURN_INSIDE_WHILE = new DiagnosticInfo(
            "JULC0003",
            "RETURN_INSIDE_WHILE",
            CompilerDiagnostic.Level.ERROR,
            "CONTROL_FLOW",
            "Cannot return inside while loop",
            "Use a boolean accumulator and return after the loop. `break` works inside `for-each` if you only need early termination.");

    public static final DiagnosticInfo SOURCE_PARSE_FAILED = new DiagnosticInfo(
            "JULC0028",
            "SOURCE_PARSE_FAILED",
            CompilerDiagnostic.Level.ERROR,
            "PIR",
            "Failed to parse {0} source: {1}",
            "Check the file with a Java IDE or `javac` \u2014 the JuLC compiler relies on JavaParser to produce a clean AST.");

    public static final DiagnosticInfo STDLIB_METHOD_WRONG_ARITY = new DiagnosticInfo(
            "JULC0025",
            "STDLIB_METHOD_WRONG_ARITY",
            CompilerDiagnostic.Level.ERROR,
            "STDLIB",
            "Stdlib method called with wrong number of arguments: {0}",
            "See the message itself \u2014 it includes a `Usage:` hint with the correct signature.");

    public static final DiagnosticInfo SWITCH_FIELD_SHADOWS_PARAMETER = new DiagnosticInfo(
            "JULC0021",
            "SWITCH_FIELD_SHADOWS_PARAMETER",
            CompilerDiagnostic.Level.WARNING,
            "CONTROL_FLOW",
            "Switch case binding name shadows a method parameter",
            "Always use distinct names. Example: rename `time` parameter to `point` when switching on `IntervalBoundType.Finite(time)`.");

    public static final DiagnosticInfo SWITCH_NOT_EXHAUSTIVE = new DiagnosticInfo(
            "JULC0005",
            "SWITCH_NOT_EXHAUSTIVE",
            CompilerDiagnostic.Level.ERROR,
            "CONTROL_FLOW",
            "Switch on sealed interface {0} is not exhaustive. Missing cases: {1}",
            "Add the missing case branches or include a `default ->` branch to handle all remaining variants.");

    public static final DiagnosticInfo SWITCH_REQUIRES_SEALED_INTERFACE = new DiagnosticInfo(
            "JULC0007",
            "SWITCH_REQUIRES_SEALED_INTERFACE",
            CompilerDiagnostic.Level.ERROR,
            "TYPE",
            "switch expression requires a sealed interface type, got: {0}",
            "Use field access (`pair.first()`, `pair.second()`) for records. Reserve `switch` for sealed interfaces with permitted record variants.");

    public static final DiagnosticInfo THROW_UNSUPPORTED = new DiagnosticInfo(
            "JULC0016",
            "THROW_UNSUPPORTED",
            CompilerDiagnostic.Level.ERROR,
            "SYNTAX",
            "throw is not supported on-chain",
            "Return false from the validator to reject a transaction");

    public static final DiagnosticInfo TRY_CATCH_UNSUPPORTED = new DiagnosticInfo(
            "JULC0015",
            "TRY_CATCH_UNSUPPORTED",
            CompilerDiagnostic.Level.ERROR,
            "SYNTAX",
            "try/catch is not supported on-chain",
            "Use if/else checks instead of exception handling");

    public static final DiagnosticInfo TYPE_RESOLUTION_FAILED = new DiagnosticInfo(
            "JULC0012",
            "TYPE_RESOLUTION_FAILED",
            CompilerDiagnostic.Level.ERROR,
            "TYPE",
            "Cannot resolve type: {0}",
            "Ensure the type is imported and is one of: a record, sealed interface, ledger type, JulcList/JulcMap/Optional/Tuple, or primitive (BigInteger, byte[], boolean, String).");

    public static final DiagnosticInfo UNDEFINED_VARIABLE = new DiagnosticInfo(
            "JULC0011",
            "UNDEFINED_VARIABLE",
            CompilerDiagnostic.Level.ERROR,
            "TYPE",
            "Undefined variable: {0}",
            "Check spelling and declaration order. If a switch case binds a field with the same name as your method parameter, rename one of them \u2014 the field shadows the parameter inside the case body (see JULC0021).");

    public static final DiagnosticInfo UNKNOWN_METHOD_ON_TYPE = new DiagnosticInfo(
            "JULC0024",
            "UNKNOWN_METHOD_ON_TYPE",
            CompilerDiagnostic.Level.ERROR,
            "STDLIB",
            "Unknown method: {0}",
            "Check the stdlib catalog at https://julc.dev/ai/catalog.json or the stdlib reference at https://julc.dev/stdlib/stdlib-guide/. Common: `JulcList<T>` has `head/tail/get/size/isEmpty/contains/prepend/reverse/concat/take/drop/map/filter/any/all/find`.");

    public static final DiagnosticInfo VALIDATOR_ANNOTATION_MISSING = new DiagnosticInfo(
            "JULC0010",
            "VALIDATOR_ANNOTATION_MISSING",
            CompilerDiagnostic.Level.ERROR,
            "VALIDATOR",
            "No validator annotation found{0}",
            "Add the appropriate validator annotation at the class level.");

    public static final DiagnosticInfo VARIABLE_UNINITIALIZED = new DiagnosticInfo(
            "JULC0002",
            "VARIABLE_UNINITIALIZED",
            CompilerDiagnostic.Level.ERROR,
            "SYNTAX",
            "Variable must be initialized: {0}",
            "Initialize at declaration, e.g. `var x = BigInteger.ZERO;`. Re-bind via a new `var` if you need a different value later (UPLC is single-assignment).");


    private static final java.util.List<DiagnosticInfo> ALL = java.util.List.of(
            ARRAY_UNSUPPORTED,
            BREAK_OUTSIDE_LOOP,
            CIRCULAR_TYPE_DEPENDENCY,
            C_STYLE_FOR_UNSUPPORTED,
            DO_WHILE_UNSUPPORTED,
            DUPLICATE_TYPE_DECLARATION,
            ENTRYPOINT_MISSING,
            ENTRYPOINT_WRONG_PARAMETER_COUNT,
            FLOATING_POINT_UNSUPPORTED,
            LAMBDA_STORED_IN_VARIABLE_UNSUPPORTED,
            METHOD_BODY_MISSING,
            METHOD_MISSING_RETURN,
            MUTUAL_RECURSION_TOO_LARGE,
            NEWTYPE_UNSUPPORTED_FIELD_TYPE,
            NEWTYPE_WRONG_FIELD_COUNT,
            NULL_UNSUPPORTED,
            PARAM_RAW_PLUTUS_DATA,
            RETURN_INSIDE_WHILE,
            SOURCE_PARSE_FAILED,
            STDLIB_METHOD_WRONG_ARITY,
            SWITCH_FIELD_SHADOWS_PARAMETER,
            SWITCH_NOT_EXHAUSTIVE,
            SWITCH_REQUIRES_SEALED_INTERFACE,
            THROW_UNSUPPORTED,
            TRY_CATCH_UNSUPPORTED,
            TYPE_RESOLUTION_FAILED,
            UNDEFINED_VARIABLE,
            UNKNOWN_METHOD_ON_TYPE,
            VALIDATOR_ANNOTATION_MISSING,
            VARIABLE_UNINITIALIZED
    );

    private static final java.util.Map<String, DiagnosticInfo> BY_CODE = ALL.stream()
            .collect(java.util.stream.Collectors.toUnmodifiableMap(
                    DiagnosticInfo::code,
                    info -> info));

    public static java.util.List<DiagnosticInfo> all() {
        return ALL;
    }

    public static java.util.Optional<DiagnosticInfo> find(String code) {
        return java.util.Optional.ofNullable(BY_CODE.get(code));
    }
}
