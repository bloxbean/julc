package org.julclang.verification;

import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.schema.ContractSchema;
import org.julclang.verification.dsl.PropertyIrCodec;
import org.julclang.verification.dsl.RequiresSignerDslLowering;
import org.julclang.verification.dsl.type.ContractTypeProjection;
import org.julclang.verification.dsl.DslPropertyValidator;
import org.julclang.core.source.SourceLocation;
import org.julclang.verification.SourceAnnotations.Annotation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** Resolves {@code @RequiresSigner} through compiler-owned {@link ContractSchema}. */
public final class RequiresSignerResolver {
    private static final String ANNOTATION_PACKAGE =
            "org.julclang.verification.annotation";
    private static final String ANNOTATION_NAME = ANNOTATION_PACKAGE + ".RequiresSigner";
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    private RequiresSignerResolver() { }

    public static Optional<RequiresSignerProperty> resolve(
            String source, String fileName, String validatorTitle, ContractSchema schema) {
        return resolve(source, fileName, validatorTitle, schema, SourceAnnotations.DEFAULT_READER);
    }

    /** {@link #resolve(String, String, String, ContractSchema)} with the source read by {@code reader} (ADR-068 M6). */
    static Optional<RequiresSignerProperty> resolve(
            String source, String fileName, String validatorTitle, ContractSchema schema,
            SourceAnnotations.Reader reader) {
        var unit = SourceAnnotations.read(source, reader);
        List<SourceAnnotations.Type> matches = new ArrayList<>();
        for (var type : unit.types()) {
            if (type.name().equals(validatorTitle)) matches.add(type);
        }
        if (matches.size() != 1) {
            throw new VerificationPropertyException(
                    "Expected exactly one Java type named '" + validatorTitle + "', found "
                            + matches.size(), new SourceLocation(fileName, 1, 1, validatorTitle));
        }
        var type = matches.getFirst();
        List<Annotation> annotations = type.annotations().stream()
                .filter(annotation -> isRequiresSigner(annotation, unit))
                .toList();
        if (annotations.isEmpty()) return Optional.empty();
        if (annotations.size() != 1) {
            throw error("@RequiresSigner may appear only once", annotations.getFirst(), fileName);
        }
        Annotation annotation = annotations.getFirst();
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
        var projection = ContractTypeProjection.project(schema);
        var canonicalDsl = DslPropertyValidator.validateAndNormalize(
                RequiresSignerDslLowering.lower(
                        propertyId, segments[1], projection),
                schema, DslPropertyValidator.MAX_AST_NODES);
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
                PropertyIrCodec.canonicalJson(canonicalDsl),
                ContractTypeProjection.canonicalJson(projection),
                ContractTypeProjection.sha256(projection),
                new RequiresSignerProperty.SourceReference(
                        fileName, location.line(), location.column(), location.fragment()),
                List.of(),
                List.of(
                        "scriptInfo is SpendingScript with an attached datum",
                        "attached datum strictly decodes as " + record.name(),
                        "decoded " + path + " occurs anywhere in txInfo.signatories"),
                false));
    }

    private static String annotationValue(Annotation annotation, String fileName) {
        if (annotation.single() != null && annotation.single().string() != null) {
            return annotation.single().string();
        }
        if (annotation.normal()) {
            var values = annotation.members().stream()
                    .filter(member -> member.name().equals("value"))
                    .map(SourceAnnotations.Member::value)
                    .toList();
            if (values.size() == 1 && values.getFirst().string() != null) {
                return values.getFirst().string();
            }
        }
        throw error("@RequiresSigner requires one string-literal value", annotation, fileName);
    }

    private static PirType dereference(
            PirType type, ContractSchema schema, Annotation annotation, String fileName) {
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

    private static boolean isRequiresSigner(Annotation annotation, SourceAnnotations.Unit unit) {
        String name = annotation.name();
        if (ANNOTATION_NAME.equals(name)) return true;
        if (!"RequiresSigner".equals(name)) return false;
        boolean samePackage = unit.packageName().equals(ANNOTATION_PACKAGE);
        if (samePackage) return true;
        return unit.imports().stream()
                .filter(importDeclaration -> !importDeclaration.isStatic())
                .anyMatch(importDeclaration -> importDeclaration.isAsterisk()
                        ? importDeclaration.name().equals(ANNOTATION_PACKAGE)
                        : importDeclaration.name().equals(ANNOTATION_NAME));
    }

    private static VerificationPropertyException error(
            String message, Annotation annotation, String fileName) {
        return new VerificationPropertyException(message, location(annotation, fileName));
    }

    private static SourceLocation location(Annotation annotation, String fileName) {
        return new SourceLocation(fileName, annotation.line(), annotation.column(), annotation.text());
    }
}
