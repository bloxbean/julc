package com.bloxbean.cardano.julc.verification;

import com.bloxbean.cardano.julc.compiler.pir.PirType;
import com.bloxbean.cardano.julc.compiler.schema.ContractSchema;
import com.bloxbean.cardano.julc.core.source.SourceLocation;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** Resolves {@code @RequiresSigner} through compiler-owned {@link ContractSchema}. */
public final class RequiresSignerResolver {
    private static final String ANNOTATION_PACKAGE =
            "com.bloxbean.cardano.julc.verification.annotation";
    private static final String ANNOTATION_NAME = ANNOTATION_PACKAGE + ".RequiresSigner";
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    private RequiresSignerResolver() { }

    public static Optional<RequiresSignerProperty> resolve(
            String source, String fileName, String validatorTitle, ContractSchema schema) {
        var unit = StaticJavaParser.parse(source);
        List<TypeDeclaration<?>> matches = new ArrayList<>();
        for (TypeDeclaration<?> type : unit.getTypes()) {
            if (type.getNameAsString().equals(validatorTitle)) matches.add(type);
        }
        if (matches.size() != 1) {
            throw new VerificationPropertyException(
                    "Expected exactly one Java type named '" + validatorTitle + "', found "
                            + matches.size(), new SourceLocation(fileName, 1, 1, validatorTitle));
        }
        var type = matches.getFirst();
        List<AnnotationExpr> annotations = type.getAnnotations().stream()
                .filter(annotation -> isRequiresSigner(annotation, unit))
                .toList();
        if (annotations.isEmpty()) return Optional.empty();
        if (annotations.size() != 1) {
            throw error("@RequiresSigner may appear only once", annotations.getFirst(), fileName);
        }
        AnnotationExpr annotation = annotations.getFirst();
        String path = annotationValue(annotation, fileName);
        String[] segments = path.split("\\.", -1);
        if (segments.length != 2 || !segments[0].equals("datum")
                || !IDENTIFIER.matcher(segments[1]).matches()) {
            throw error("@RequiresSigner path must be exactly datum.<field>; found '"
                    + path + "'", annotation, fileName);
        }
        if (schema.purpose() != ContractSchema.Purpose.SPEND) {
            throw error("@RequiresSigner is supported only for spending validators", annotation,
                    fileName);
        }
        if (schema.datum() == null) {
            throw error("@RequiresSigner requires a three-argument spending validator with a datum",
                    annotation, fileName);
        }

        PirType datum = dereference(schema.datum().type(), schema, annotation, fileName);
        if (!(datum instanceof PirType.RecordType record)) {
            throw error("@RequiresSigner datum root must resolve to a record, found "
                    + describe(datum), annotation, fileName);
        }
        List<PirType.Field> fields = record.fields().stream()
                .filter(field -> field.name().equals(segments[1]))
                .toList();
        if (fields.size() != 1) {
            throw error("@RequiresSigner field '" + segments[1] + "' was "
                    + (fields.isEmpty() ? "not found" : "ambiguous")
                    + " in datum type " + record.name(), annotation, fileName);
        }
        PirType owner = dereference(fields.getFirst().type(), schema, annotation, fileName);
        if (!(owner instanceof PirType.ByteStringType)) {
            throw error("@RequiresSigner field '" + path
                    + "' must resolve to byte[] or a key-hash type; found " + describe(owner),
                    annotation, fileName);
        }

        SourceLocation location = location(annotation, fileName);
        String propertyId = validatorTitle + ".requires-signer." + segments[1];
        return Optional.of(new RequiresSignerProperty(
                RequiresSignerProperty.SCHEMA_VERSION,
                RequiresSignerProperty.TEMPLATE,
                propertyId,
                validatorTitle,
                "spending",
                path,
                List.of(
                        new RequiresSignerProperty.PathSegment(
                                "root", "datum", "record:" + record.name()),
                        new RequiresSignerProperty.PathSegment(
                                "field", segments[1], "bytes")),
                record.name(),
                "bytes",
                new RequiresSignerProperty.SourceReference(
                        fileName, location.line(), location.column(), location.fragment()),
                List.of(),
                List.of(
                        "scriptInfo is SpendingScript with an attached datum",
                        "attached datum strictly decodes as " + record.name(),
                        "decoded " + path + " occurs anywhere in txInfo.signatories"),
                false));
    }

    private static String annotationValue(AnnotationExpr annotation, String fileName) {
        if (annotation instanceof SingleMemberAnnotationExpr single
                && single.getMemberValue() instanceof StringLiteralExpr literal) {
            return literal.asString();
        }
        if (annotation instanceof NormalAnnotationExpr normal) {
            var values = normal.getPairs().stream()
                    .filter(pair -> pair.getNameAsString().equals("value"))
                    .map(pair -> pair.getValue())
                    .toList();
            if (values.size() == 1 && values.getFirst() instanceof StringLiteralExpr literal) {
                return literal.asString();
            }
        }
        throw error("@RequiresSigner requires one string-literal value", annotation, fileName);
    }

    private static PirType dereference(
            PirType type, ContractSchema schema, AnnotationExpr annotation, String fileName) {
        var seen = new ArrayList<String>();
        PirType current = type;
        while (current instanceof PirType.NamedTypeRef ref) {
            if (!seen.add(ref.stableId())) {
                throw error("@RequiresSigner path crosses a recursive alias at " + ref.name(),
                        annotation, fileName);
            }
            current = schema.namedDefinitions().get(ref.stableId());
            if (current == null) {
                throw error("@RequiresSigner path has dangling compiler type " + ref.stableId(),
                        annotation, fileName);
            }
        }
        return current;
    }

    private static String describe(PirType type) {
        return type == null ? "missing type" : type.getClass().getSimpleName();
    }

    private static boolean isRequiresSigner(
            AnnotationExpr annotation,
            com.github.javaparser.ast.CompilationUnit unit) {
        String name = annotation.getNameAsString();
        if (ANNOTATION_NAME.equals(name)) return true;
        if (!"RequiresSigner".equals(name)) return false;
        boolean samePackage = unit.getPackageDeclaration()
                .map(declaration -> declaration.getNameAsString().equals(ANNOTATION_PACKAGE))
                .orElse(false);
        if (samePackage) return true;
        return unit.getImports().stream()
                .filter(importDeclaration -> !importDeclaration.isStatic())
                .anyMatch(importDeclaration -> importDeclaration.isAsterisk()
                        ? importDeclaration.getNameAsString().equals(ANNOTATION_PACKAGE)
                        : importDeclaration.getNameAsString().equals(ANNOTATION_NAME));
    }

    private static VerificationPropertyException error(
            String message, AnnotationExpr annotation, String fileName) {
        return new VerificationPropertyException(message, location(annotation, fileName));
    }

    private static SourceLocation location(AnnotationExpr annotation, String fileName) {
        var begin = annotation.getBegin().orElse(null);
        return new SourceLocation(fileName,
                begin == null ? 1 : begin.line,
                begin == null ? 1 : begin.column,
                annotation.toString());
    }
}
