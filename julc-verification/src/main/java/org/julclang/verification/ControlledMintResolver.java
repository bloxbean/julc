package org.julclang.verification;

import org.julclang.compiler.frontend.javac.SourceReader;

import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.schema.ContractSchema;
import org.julclang.core.source.SourceLocation;
import org.julclang.verification.SourceAnnotations.Annotation;
import org.julclang.verification.SourceAnnotations.Value;

import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.julclang.verification.dsl.MintingDsl;
import org.julclang.verification.dsl.PropertyIrCodec;
import org.julclang.verification.dsl.type.ContractTypeProjection;

/** Resolves the C.7 controlled-mint annotation against compiler-owned schema metadata. */
public final class ControlledMintResolver {
    private static final String PACKAGE =
            "org.julclang.verification.annotation";

    private ControlledMintResolver() { }

    public static Optional<ControlledMintProperty> resolve(
            String source, String fileName, String validatorTitle, ContractSchema schema) {
        return resolve(source, fileName, validatorTitle, schema, SourceAnnotations.defaultReader());
    }

    /** {@link #resolve(String, String, String, ContractSchema)} with the source read by {@code reader} (ADR-068 M6). */
    static Optional<ControlledMintProperty> resolve(
            String source, String fileName, String validatorTitle, ContractSchema schema,
            SourceReader reader) {
        SourceAnnotations.Unit unit = SourceAnnotations.read(source, reader);
        SourceAnnotations.Type type = exactType(unit, validatorTitle, fileName);
        List<Annotation> matches = annotations(type, unit);
        if (matches.isEmpty()) return Optional.empty();
        Annotation annotation = matches.getFirst();
        if (matches.size() != 1) {
            throw error("Expected exactly one @ControlledMint annotation", annotation, fileName);
        }
        if (schema.purpose() != ContractSchema.Purpose.MINT || schema.datum() != null) {
            throw error("@ControlledMint requires a two-argument minting validator",
                    annotation, fileName);
        }

        String authority = hexMember(annotation, "authority", fileName);
        if (authority.length() != 56) {
            throw error("@ControlledMint authority must encode exactly 28 bytes",
                    annotation, fileName);
        }
        String tokenName = hexMember(annotation, "tokenName", fileName);
        if (tokenName.length() > 64) {
            throw error("@ControlledMint tokenName must encode at most 32 bytes",
                    annotation, fileName);
        }
        long magnitude = longMember(annotation, "quantity", fileName);
        if (magnitude <= 0) {
            throw error("@ControlledMint quantity must be strictly positive",
                    annotation, fileName);
        }
        String action = enumMember(annotation, "action", fileName);
        if (!List.of("MINT", "BURN").contains(action)) {
            throw error("@ControlledMint supports only MintAction.MINT or BURN",
                    annotation, fileName);
        }
        String signedQuantity = "BURN".equals(action)
                ? Long.toString(-magnitude) : Long.toString(magnitude);
        PirType redeemer = dereference(schema.redeemer().type(), schema, annotation, fileName);
        if (!(redeemer instanceof PirType.RecordType record)) {
            throw error("@ControlledMint redeemer must resolve to a named record",
                    annotation, fileName);
        }
        SourceLocation location = location(annotation, fileName);
        String propertyId = validatorTitle + ".controlled-mint-v1";
        String contractSchemaSha256 = ContractTypeProjection.sha256(
                ContractTypeProjection.project(schema));
        String canonicalDsl = PropertyIrCodec.canonicalJson(
                MintingDsl.controlledMintPropertySet(
                        propertyId, authority, tokenName, signedQuantity,
                        contractSchemaSha256));
        return Optional.of(new ControlledMintProperty(
                ControlledMintProperty.SCHEMA_VERSION,
                ControlledMintProperty.TEMPLATE,
                propertyId,
                validatorTitle,
                "minting",
                "authority:" + authority + "|tokenName:" + tokenName
                        + "|quantity:" + signedQuantity,
                authority,
                tokenName,
                signedQuantity,
                action,
                record.name(),
                canonicalDsl,
                new ControlledMintProperty.SourceReference(
                        fileName, location.line(), location.column(), location.fragment()),
                List.of(),
                List.of(
                        "strict redeemer decoding",
                        "fixed authority occurs in the complete signatory list",
                        "exactly one raw entry exists for the current policy",
                        "the current policy contains exactly the configured token and quantity",
                        "mint or burn direction matches the configured action"),
                false));
    }

    private static PirType dereference(
            PirType type, ContractSchema schema, Annotation annotation, String fileName) {
        PirType current = type;
        var seen = new java.util.HashSet<String>();
        while (current instanceof PirType.NamedTypeRef ref) {
            if (!seen.add(ref.stableId())) {
                throw error("@ControlledMint redeemer root is a recursive alias",
                        annotation, fileName);
            }
            current = schema.namedDefinitions().get(ref.stableId());
            if (current == null) {
                throw error("@ControlledMint redeemer has a dangling compiler type",
                        annotation, fileName);
            }
        }
        return current;
    }

    private static String hexMember(
            Annotation annotation, String name, String fileName) {
        Value expression = member(annotation, name, fileName);
        if (expression.string() == null) {
            throw error("@ControlledMint " + name + " must be a hexadecimal string literal",
                    annotation, fileName);
        }
        String value = expression.string();
        if ((value.length() & 1) != 0) {
            throw error("@ControlledMint " + name + " must have even hexadecimal length",
                    annotation, fileName);
        }
        try {
            HexFormat.of().parseHex(value);
        } catch (IllegalArgumentException invalid) {
            throw error("@ControlledMint " + name + " is not valid hexadecimal",
                    annotation, fileName);
        }
        return value.toLowerCase(java.util.Locale.ROOT);
    }

    private static long longMember(
            Annotation annotation, String name, String fileName) {
        Value expression = member(annotation, name, fileName);
        if (expression.integer() != null) return expression.integer();
        throw error("@ControlledMint " + name + " must be a positive long literal",
                annotation, fileName);
    }

    private static String enumMember(
            Annotation annotation, String name, String fileName) {
        String value = member(annotation, name, fileName).text();
        int dot = value.lastIndexOf('.');
        return dot < 0 ? value : value.substring(dot + 1);
    }

    private static Value member(
            Annotation annotation, String name, String fileName) {
        if (annotation.normal()) {
            var matches = annotation.members().stream()
                    .filter(pair -> pair.name().equals(name))
                    .map(SourceAnnotations.Member::value).toList();
            if (matches.size() == 1) return matches.getFirst();
        }
        throw error("@ControlledMint requires member '" + name + "'", annotation, fileName);
    }

    private static List<Annotation> annotations(
            SourceAnnotations.Type type, SourceAnnotations.Unit unit) {
        String qualified = PACKAGE + ".ControlledMint";
        boolean imported = unit.imports().stream()
                .filter(value -> !value.isStatic())
                .anyMatch(value -> value.isAsterisk()
                        ? value.name().equals(PACKAGE)
                        : value.name().equals(qualified));
        boolean samePackage = unit.packageName().equals(PACKAGE);
        return type.annotations().stream().filter(annotation ->
                qualified.equals(annotation.name())
                        || ("ControlledMint".equals(annotation.name())
                        && (imported || samePackage))).toList();
    }

    private static SourceAnnotations.Type exactType(
            SourceAnnotations.Unit unit, String validatorTitle, String fileName) {
        var matches = unit.types().stream()
                .filter(type -> type.name().equals(validatorTitle)).toList();
        if (matches.size() != 1) {
            throw new VerificationPropertyException(
                    "Expected exactly one Java type named '" + validatorTitle + "'",
                    new SourceLocation(fileName, 1, 1, validatorTitle));
        }
        return matches.getFirst();
    }

    private static VerificationPropertyException error(
            String message, Annotation annotation, String fileName) {
        return new VerificationPropertyException(message, location(annotation, fileName));
    }

    private static SourceLocation location(Annotation annotation, String fileName) {
        return new SourceLocation(fileName, annotation.line(), annotation.column(), annotation.text());
    }
}
