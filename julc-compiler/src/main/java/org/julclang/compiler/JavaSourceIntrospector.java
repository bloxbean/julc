package org.julclang.compiler;


import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Parser-backed source inspection for pre-compilation discovery.
 * <p>
 * Raw text checks may be used as negative prefilters before this class, but
 * positive source classification must come from the parsed AST.
 */
public final class JavaSourceIntrospector {

    public static final List<String> SUPPORTED_VALIDATOR_ANNOTATIONS = List.of(
            "SpendingValidator",
            "MintingValidator",
            "WithdrawValidator",
            "CertifyingValidator",
            "VotingValidator",
            "ProposingValidator",
            "MultiValidator"
    );

    public static final List<String> LEGACY_VALIDATOR_ANNOTATIONS = List.of(
            "Validator",
            "MintingPolicy"
    );

    public static final String ONCHAIN_LIBRARY_ANNOTATION = "OnchainLibrary";

    private static final Set<String> SUPPORTED_VALIDATOR_SET = new LinkedHashSet<>(SUPPORTED_VALIDATOR_ANNOTATIONS);
    private static final Set<String> LEGACY_VALIDATOR_SET = new LinkedHashSet<>(LEGACY_VALIDATOR_ANNOTATIONS);

    private JavaSourceIntrospector() {}

    public record SourceInfo(
            String packageName,
            List<String> topLevelTypeNames,
            Optional<AnnotatedType> validatorType,
            Optional<AnnotatedType> legacyValidatorType,
            Optional<AnnotatedType> topLevelOnchainLibrary,
            List<AnnotatedType> nestedOnchainLibraries,
            List<RoleConflict> roleConflicts) {

        public Optional<JulcCompiler.ScriptPurpose> scriptPurpose() {
            return validatorType.map(type -> JavaSourceIntrospector.scriptPurpose(type.annotationName()));
        }

        public boolean hasRoleConflicts() {
            return !roleConflicts.isEmpty();
        }

        public Optional<RoleConflict> firstRoleConflict() {
            return roleConflicts.stream().findFirst();
        }
    }

    public record AnnotatedType(
            String simpleName,
            String packageName,
            String fqcn,
            String annotationName,
            List<String> annotationNames,
            boolean topLevel) {
    }

    public record RoleConflict(
            String simpleName,
            String fqcn,
            List<String> conflictingAnnotations) {

        public String message() {
            String annotations = String.join(", ", conflictingAnnotations.stream()
                    .map(annotation -> "@" + annotation)
                    .toList());
            return "Class " + fqcn
                    + " must not combine @OnchainLibrary with validator annotation(s): "
                    + annotations;
        }
    }

    public static final class SourceParseException extends IllegalArgumentException {
        private final List<String> problems;

        SourceParseException(List<String> problems) {
            super("Could not parse Java source: " + problems);
            this.problems = List.copyOf(problems);
        }

        public List<String> problems() {
            return problems;
        }
    }

    public static SourceInfo inspect(String source) {
        return JavacSourceIntrospection.inspect(source);
    }

    public static boolean mightContainOnchainLibraryAnnotation(String source) {
        return mightContainAnnotation(source, ONCHAIN_LIBRARY_ANNOTATION);
    }

    public static boolean mightContainValidatorAnnotation(String source) {
        for (String annotation : SUPPORTED_VALIDATOR_ANNOTATIONS) {
            if (mightContainAnnotation(source, annotation)) {
                return true;
            }
        }
        for (String annotation : LEGACY_VALIDATOR_ANNOTATIONS) {
            if (mightContainAnnotation(source, annotation)) {
                return true;
            }
        }
        return false;
    }

    public static boolean mightContainTestAnnotation(String source) {
        return mightContainAnnotation(source, "Test");
    }

    /**
     * Whether a pre-filter candidate that the strict reading rejects (ADR-062 T24) plainly has no JuLC role: read
     * leniently, as javac reads it, no type declares a validator or on-chain library annotation. Such a source
     * only mentions an annotation, in a comment, an import or a link, and is skipped as a non-candidate would be.
     * False when that cannot be decided, so that the caller keeps the strict diagnostic and fails closed.
     */
    public static boolean declaresNoJulcRole(String source) {
        return JavacSourceIntrospection.declaresNoJulcRole(source);
    }

    public static boolean isSupportedValidatorAnnotation(String annotationName) {
        return SUPPORTED_VALIDATOR_SET.contains(simpleName(annotationName));
    }

    public static boolean isLegacyValidatorAnnotation(String annotationName) {
        return LEGACY_VALIDATOR_SET.contains(simpleName(annotationName));
    }

    public static JulcCompiler.ScriptPurpose scriptPurpose(String annotationName) {
        return switch (simpleName(annotationName)) {
            case "SpendingValidator" -> JulcCompiler.ScriptPurpose.SPENDING;
            case "MintingValidator" -> JulcCompiler.ScriptPurpose.MINTING;
            case "WithdrawValidator" -> JulcCompiler.ScriptPurpose.WITHDRAW;
            case "CertifyingValidator" -> JulcCompiler.ScriptPurpose.CERTIFYING;
            case "VotingValidator" -> JulcCompiler.ScriptPurpose.VOTING;
            case "ProposingValidator" -> JulcCompiler.ScriptPurpose.PROPOSING;
            case "MultiValidator" -> JulcCompiler.ScriptPurpose.MULTI;
            default -> throw new IllegalArgumentException("Unsupported validator annotation: " + annotationName);
        };
    }

    public static String legacyAnnotationMigrationMessage(AnnotatedType type) {
        return legacyAnnotationMigrationMessage(type.annotationName(), type.simpleName());
    }

    public static String legacyAnnotationMigrationMessage(String annotationName, String typeName) {
        return switch (simpleName(annotationName)) {
            case "Validator" -> "@" + simpleName(annotationName) + " is no longer supported on "
                    + typeName + ". Use @SpendingValidator instead.";
            case "MintingPolicy" -> "@" + simpleName(annotationName) + " is no longer supported on "
                    + typeName + ". Use @MintingValidator instead.";
            default -> "Unsupported legacy validator annotation @" + simpleName(annotationName)
                    + " on " + typeName;
        };
    }

    /**
     * A quick text test before parsing. javac reads a Unicode escape as the character it denotes (ADR-062 T24),
     * so the escapes are also translated, leniently: a source that fails JuLC's stricter reading is not
     * rejected here. Only a source that names the annotation goes on to the strict parser, which then fails
     * closed if it cannot read it.
     */
    private static boolean mightContainAnnotation(String source, String annotationName) {
        if (namesAnnotation(source, annotationName)) return true;
        return source.contains("\\u") && namesAnnotation(UnicodeEscapes.readLeniently(source).text(), annotationName);
    }

    /** {@code @Name}, with whitespace allowed after the {@code @} as Java allows it, or {@code .Name}. */
    private static boolean namesAnnotation(String text, String annotationName) {
        if (text.contains("." + annotationName)) return true;
        for (int at = text.indexOf('@'); at >= 0; at = text.indexOf('@', at + 1)) {
            int k = at + 1;
            while (k < text.length() && Character.isWhitespace(text.charAt(k))) k++;
            if (text.startsWith(annotationName, k)) return true;
        }
        return false;
    }

    private static String simpleName(String name) {
        int idx = name.lastIndexOf('.');
        return idx >= 0 ? name.substring(idx + 1) : name;
    }
}
