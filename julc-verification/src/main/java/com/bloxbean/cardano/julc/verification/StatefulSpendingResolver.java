package com.bloxbean.cardano.julc.verification;

import com.bloxbean.cardano.julc.compiler.pir.PirType;
import com.bloxbean.cardano.julc.compiler.schema.ContractSchema;
import com.bloxbean.cardano.julc.core.source.SourceLocation;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** Resolves the complete C.6 stateful-spending annotation profile. */
public final class StatefulSpendingResolver {
    private static final String PACKAGE =
            "com.bloxbean.cardano.julc.verification.annotation";
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    private StatefulSpendingResolver() { }

    public static Optional<StatefulSpendingProperty> resolve(
            String source, String fileName, String validatorTitle, ContractSchema schema) {
        CompilationUnit unit = StaticJavaParser.parse(source);
        TypeDeclaration<?> type = exactType(unit, validatorTitle, fileName);
        List<AnnotationExpr> monotonic = annotations(type, unit, "Monotonic");
        List<AnnotationExpr> preserves = annotations(type, unit, "PreservesValue");
        if (monotonic.isEmpty() && preserves.isEmpty()) return Optional.empty();

        AnnotationExpr location = monotonic.isEmpty() ? preserves.getFirst() : monotonic.getFirst();
        if (monotonic.size() != 1 || preserves.size() != 1) {
            throw error("Stateful profile requires exactly one @Monotonic and one "
                    + "@PreservesValue", location, fileName);
        }
        RequiresSignerProperty signer = RequiresSignerResolver.resolve(
                        source, fileName, validatorTitle, schema)
                .orElseThrow(() -> error("Stateful profile also requires exactly one "
                        + "@RequiresSigner", location, fileName));
        if (schema.purpose() != ContractSchema.Purpose.SPEND || schema.datum() == null) {
            throw error("Stateful profile requires a three-argument spending validator",
                    location, fileName);
        }

        AnnotationExpr monotonicAnnotation = monotonic.getFirst();
        String current = stringMember(monotonicAnnotation, "current", fileName);
        String next = stringMember(monotonicAnnotation, "next", fileName);
        String relation = enumMember(monotonicAnnotation, "relation", fileName);
        if (!"GREATER_THAN".equals(relation)) {
            throw error("C.6 supports only Relation.GREATER_THAN",
                    monotonicAnnotation, fileName);
        }
        String output = enumMember(preserves.getFirst(), "output", fileName);
        if (!"SINGLE_CONTINUING_OUTPUT".equals(output)) {
            throw error("C.6 supports only OutputSelection.SINGLE_CONTINUING_OUTPUT",
                    preserves.getFirst(), fileName);
        }

        var currentSelection = resolveSelection(current, "datum", PirType.IntegerType.class,
                schema.datum(), schema, monotonicAnnotation, fileName);
        var nextSelection = resolveSelection(next, "redeemer", PirType.IntegerType.class,
                schema.redeemer(), schema, monotonicAnnotation, fileName);
        var authority = new StatefulSpendingProperty.Selection(
                "datum", signer.path().getLast().name(), "bytes");

        PirType.RecordType datum = recordRoot(
                schema.datum(), schema, location, fileName, "datum");
        PirType.RecordType redeemer = recordRoot(
                schema.redeemer(), schema, location, fileName, "redeemer");
        String sourcePath = String.join("|", signer.sourcePath(), current, next);
        return Optional.of(new StatefulSpendingProperty(
                StatefulSpendingProperty.SCHEMA_VERSION,
                StatefulSpendingProperty.TEMPLATE,
                validatorTitle + ".stateful-spending-v1",
                validatorTitle,
                "spending",
                sourcePath,
                authority,
                currentSelection,
                nextSelection,
                datum.name(),
                redeemer.name(),
                relation,
                output,
                List.of(
                        new StatefulSpendingProperty.SourceReference(
                                "RequiresSigner", signer.source().file(),
                                signer.source().line(), signer.source().column(),
                                signer.source().fragment()),
                        sourceReference("Monotonic", monotonicAnnotation, fileName),
                        sourceReference("PreservesValue", preserves.getFirst(), fileName)),
                List.of(),
                List.of(
                        "strict current datum, redeemer, and successor datum decoding",
                        "current datum authority occurs in the complete signatory list",
                        "exactly one full-address continuing output",
                        "continuing output structurally preserves the own-input value",
                        "continuing datum preserves authority",
                        "continuing state equals redeemer next state",
                        "current state is strictly less than next state"),
                false));
    }

    private static StatefulSpendingProperty.Selection resolveSelection(
            String path,
            String expectedRoot,
            Class<? extends PirType> expectedType,
            ContractSchema.Argument argument,
            ContractSchema schema,
            AnnotationExpr annotation,
            String fileName) {
        String fieldName = directField(path, expectedRoot, annotation, fileName);
        PirType.RecordType root = recordRoot(
                argument, schema, annotation, fileName, expectedRoot);
        List<PirType.Field> fields = root.fields().stream()
                .filter(field -> field.name().equals(fieldName)).toList();
        if (fields.size() != 1) {
            throw error("Stateful path '" + path + "' was "
                    + (fields.isEmpty() ? "not found" : "ambiguous")
                    + " in " + root.name(), annotation, fileName);
        }
        PirType resolved = dereference(fields.getFirst().type(), schema, annotation, fileName);
        if (!expectedType.isInstance(resolved)) {
            throw error("Stateful path '" + path + "' must resolve to integer; found "
                    + resolved.getClass().getSimpleName(), annotation, fileName);
        }
        return new StatefulSpendingProperty.Selection(expectedRoot, fieldName, "integer");
    }

    private static PirType.RecordType recordRoot(
            ContractSchema.Argument argument,
            ContractSchema schema,
            AnnotationExpr annotation,
            String fileName,
            String rootName) {
        if (argument == null) {
            throw error("Stateful profile has no " + rootName + " root", annotation, fileName);
        }
        PirType resolved = dereference(argument.type(), schema, annotation, fileName);
        if (!(resolved instanceof PirType.RecordType record)) {
            throw error("Stateful " + rootName + " root must resolve to a record",
                    annotation, fileName);
        }
        return record;
    }

    private static PirType dereference(
            PirType type,
            ContractSchema schema,
            AnnotationExpr annotation,
            String fileName) {
        var seen = new ArrayList<String>();
        PirType current = type;
        while (current instanceof PirType.NamedTypeRef ref) {
            if (!seen.add(ref.stableId())) {
                throw error("Stateful path crosses recursive alias " + ref.name(),
                        annotation, fileName);
            }
            current = schema.namedDefinitions().get(ref.stableId());
            if (current == null) {
                throw error("Stateful path has dangling compiler type " + ref.stableId(),
                        annotation, fileName);
            }
        }
        return current;
    }

    private static String directField(
            String path, String root, AnnotationExpr annotation, String fileName) {
        String[] segments = path.split("\\.", -1);
        if (segments.length != 2 || !root.equals(segments[0])
                || !IDENTIFIER.matcher(segments[1]).matches()) {
            throw error("Stateful path must be exactly " + root + ".<field>; found '"
                    + path + "'", annotation, fileName);
        }
        return segments[1];
    }

    private static String stringMember(
            AnnotationExpr annotation, String member, String fileName) {
        Expression value = member(annotation, member, fileName);
        if (value instanceof StringLiteralExpr literal) return literal.asString();
        throw error("@Monotonic " + member + " must be a string literal",
                annotation, fileName);
    }

    private static String enumMember(
            AnnotationExpr annotation, String member, String fileName) {
        String value = member(annotation, member, fileName).toString();
        int separator = value.lastIndexOf('.');
        return separator < 0 ? value : value.substring(separator + 1);
    }

    private static Expression member(
            AnnotationExpr annotation, String name, String fileName) {
        if (annotation instanceof NormalAnnotationExpr normal) {
            var values = normal.getPairs().stream()
                    .filter(pair -> pair.getNameAsString().equals(name))
                    .map(pair -> pair.getValue()).toList();
            if (values.size() == 1) return values.getFirst();
        }
        throw error("@" + annotation.getName().getIdentifier()
                + " requires member '" + name + "'", annotation, fileName);
    }

    private static TypeDeclaration<?> exactType(
            CompilationUnit unit, String validatorTitle, String fileName) {
        var matches = unit.getTypes().stream()
                .filter(type -> type.getNameAsString().equals(validatorTitle)).toList();
        if (matches.size() != 1) {
            throw new VerificationPropertyException(
                    "Expected exactly one Java type named '" + validatorTitle + "'",
                    new SourceLocation(fileName, 1, 1, validatorTitle));
        }
        return matches.getFirst();
    }

    private static List<AnnotationExpr> annotations(
            TypeDeclaration<?> type, CompilationUnit unit, String simpleName) {
        String qualifiedName = PACKAGE + "." + simpleName;
        boolean imported = unit.getImports().stream()
                .filter(importDeclaration -> !importDeclaration.isStatic())
                .anyMatch(importDeclaration -> importDeclaration.isAsterisk()
                        ? importDeclaration.getNameAsString().equals(PACKAGE)
                        : importDeclaration.getNameAsString().equals(qualifiedName));
        boolean samePackage = unit.getPackageDeclaration()
                .map(declaration -> declaration.getNameAsString().equals(PACKAGE))
                .orElse(false);
        return type.getAnnotations().stream().filter(annotation -> {
            String name = annotation.getNameAsString();
            return qualifiedName.equals(name)
                    || (simpleName.equals(name) && (imported || samePackage));
        }).toList();
    }

    private static StatefulSpendingProperty.SourceReference sourceReference(
            String name, AnnotationExpr annotation, String fileName) {
        SourceLocation location = location(annotation, fileName);
        return new StatefulSpendingProperty.SourceReference(
                name, fileName, location.line(), location.column(), location.fragment());
    }

    private static VerificationPropertyException error(
            String message, AnnotationExpr annotation, String fileName) {
        return new VerificationPropertyException(message, location(annotation, fileName));
    }

    private static SourceLocation location(AnnotationExpr annotation, String fileName) {
        var begin = annotation.getBegin().orElse(null);
        return new SourceLocation(fileName, begin == null ? 1 : begin.line,
                begin == null ? 1 : begin.column, annotation.toString());
    }
}
