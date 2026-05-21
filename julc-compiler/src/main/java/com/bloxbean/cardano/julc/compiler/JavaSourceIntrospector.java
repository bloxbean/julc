package com.bloxbean.cardano.julc.compiler;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;

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
    private static final Set<String> ONCHAIN_LIBRARY_SET = Set.of(ONCHAIN_LIBRARY_ANNOTATION);

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

        public Optional<String> scriptType() {
            return scriptPurpose().map(JavaSourceIntrospector::scriptType);
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

        private SourceParseException(List<String> problems) {
            super("Could not parse Java source: " + problems);
            this.problems = List.copyOf(problems);
        }

        public List<String> problems() {
            return problems;
        }
    }

    public static SourceInfo inspect(String source) {
        var configuration = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        var result = new JavaParser(configuration).parse(source);
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            var problems = result.getProblems().stream()
                    .map(problem -> {
                        String message = problem.getMessage();
                        int stackIdx = message.indexOf("Problem stacktrace");
                        return stackIdx > 0 ? message.substring(0, stackIdx).trim() : message;
                    })
                    .toList();
            throw new SourceParseException(problems.isEmpty() ? List.of("Unknown parse error") : problems);
        }
        return inspectCompilationUnit(result.getResult().get());
    }

    private static SourceInfo inspectCompilationUnit(CompilationUnit cu) {
        String packageName = cu.getPackageDeclaration()
                .map(pd -> pd.getName().asString())
                .orElse("");
        List<TypeDeclaration<?>> topLevelTypes = cu.getTypes();
        Set<TypeDeclaration<?>> topLevelSet = Collections.newSetFromMap(new IdentityHashMap<>());
        topLevelSet.addAll(topLevelTypes);

        var topLevelTypeNames = topLevelTypes.stream()
                .map(type -> type.getNameAsString())
                .toList();
        var validatorType = firstAnnotated(topLevelTypes, packageName, SUPPORTED_VALIDATOR_SET, true);
        var legacyValidatorType = firstAnnotated(topLevelTypes, packageName, LEGACY_VALIDATOR_SET, true);
        var topLevelOnchainLibrary = firstAnnotated(topLevelTypes, packageName, ONCHAIN_LIBRARY_SET, true);

        var nestedOnchainLibraries = new ArrayList<AnnotatedType>();
        for (TypeDeclaration<?> type : cu.findAll(TypeDeclaration.class)) {
            if (!topLevelSet.contains(type) && hasAnnotation(type, ONCHAIN_LIBRARY_ANNOTATION)) {
                nestedOnchainLibraries.add(toAnnotatedType(type, packageName, ONCHAIN_LIBRARY_ANNOTATION, false));
            }
        }
        var roleConflicts = cu.findAll(TypeDeclaration.class).stream()
                .flatMap(type -> roleConflictOn(type).stream())
                .toList();

        return new SourceInfo(packageName, topLevelTypeNames, validatorType, legacyValidatorType,
                topLevelOnchainLibrary, List.copyOf(nestedOnchainLibraries), roleConflicts);
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

    public static boolean isSupportedValidatorAnnotation(String annotationName) {
        return SUPPORTED_VALIDATOR_SET.contains(simpleName(annotationName));
    }

    public static boolean isLegacyValidatorAnnotation(String annotationName) {
        return LEGACY_VALIDATOR_SET.contains(simpleName(annotationName));
    }

    public static boolean hasSupportedValidatorAnnotation(NodeWithAnnotations<?> node) {
        return !supportedValidatorAnnotationsOn(node).isEmpty();
    }

    public static boolean hasLegacyValidatorAnnotation(NodeWithAnnotations<?> node) {
        return !legacyValidatorAnnotationsOn(node).isEmpty();
    }

    public static List<String> supportedValidatorAnnotationsOn(NodeWithAnnotations<?> node) {
        return node.getAnnotations().stream()
                .map(annotation -> simpleName(annotation.getNameAsString()))
                .filter(SUPPORTED_VALIDATOR_SET::contains)
                .toList();
    }

    public static List<String> legacyValidatorAnnotationsOn(NodeWithAnnotations<?> node) {
        return node.getAnnotations().stream()
                .map(annotation -> simpleName(annotation.getNameAsString()))
                .filter(LEGACY_VALIDATOR_SET::contains)
                .toList();
    }

    public static boolean hasOnchainLibraryAnnotation(NodeWithAnnotations<?> node) {
        return hasAnnotation(node, ONCHAIN_LIBRARY_ANNOTATION);
    }

    public static Optional<RoleConflict> roleConflictOn(TypeDeclaration<?> type) {
        if (!hasOnchainLibraryAnnotation(type)) {
            return Optional.empty();
        }

        var conflictingAnnotations = new ArrayList<String>();
        conflictingAnnotations.addAll(supportedValidatorAnnotationsOn(type));
        conflictingAnnotations.addAll(legacyValidatorAnnotationsOn(type));

        if (conflictingAnnotations.isEmpty()) {
            return Optional.empty();
        }

        String simpleName = type.getNameAsString();
        String fqcn = type.getFullyQualifiedName().orElse(simpleName);
        return Optional.of(new RoleConflict(simpleName, fqcn, List.copyOf(conflictingAnnotations)));
    }

    public static boolean hasAnnotation(NodeWithAnnotations<?> node, String annotationName) {
        String expected = simpleName(annotationName);
        return node.getAnnotations().stream()
                .map(annotation -> simpleName(annotation.getNameAsString()))
                .anyMatch(expected::equals);
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

    public static String scriptType(JulcCompiler.ScriptPurpose purpose) {
        return switch (purpose) {
            case MINTING -> "PlutusScriptV3-Minting";
            case WITHDRAW -> "PlutusScriptV3-Withdraw";
            case CERTIFYING -> "PlutusScriptV3-Certifying";
            case VOTING -> "PlutusScriptV3-Voting";
            case PROPOSING -> "PlutusScriptV3-Proposing";
            default -> "PlutusScriptV3";
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

    private static Optional<AnnotatedType> firstAnnotated(List<TypeDeclaration<?>> types,
                                                          String packageName,
                                                          Set<String> annotationNames,
                                                          boolean topLevel) {
        for (TypeDeclaration<?> type : types) {
            for (String annotationName : annotationNames) {
                if (hasAnnotation(type, annotationName)) {
                    return Optional.of(toAnnotatedType(type, packageName, annotationName, topLevel));
                }
            }
        }
        return Optional.empty();
    }

    private static AnnotatedType toAnnotatedType(TypeDeclaration<?> type,
                                                 String packageName,
                                                 String annotationName,
                                                 boolean topLevel) {
        String simpleName = type.getNameAsString();
        String fqcn = type.getFullyQualifiedName()
                .orElse(packageName.isBlank() ? simpleName : packageName + "." + simpleName);
        return new AnnotatedType(simpleName, packageName, fqcn, annotationName,
                annotationNames(type), topLevel);
    }

    private static List<String> annotationNames(NodeWithAnnotations<?> node) {
        return node.getAnnotations().stream()
                .map(AnnotationExpr::getNameAsString)
                .map(JavaSourceIntrospector::simpleName)
                .toList();
    }

    private static boolean mightContainAnnotation(String source, String annotationName) {
        return source.contains("@" + annotationName) || source.contains("." + annotationName);
    }

    private static String simpleName(String name) {
        int idx = name.lastIndexOf('.');
        return idx >= 0 ? name.substring(idx + 1) : name;
    }
}
