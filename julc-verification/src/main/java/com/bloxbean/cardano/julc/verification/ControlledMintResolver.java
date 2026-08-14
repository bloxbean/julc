package com.bloxbean.cardano.julc.verification;

import com.bloxbean.cardano.julc.compiler.pir.PirType;
import com.bloxbean.cardano.julc.compiler.schema.ContractSchema;
import com.bloxbean.cardano.julc.core.source.SourceLocation;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;

import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/** Resolves the C.7 controlled-mint annotation against compiler-owned schema metadata. */
public final class ControlledMintResolver {
    private static final String PACKAGE =
            "com.bloxbean.cardano.julc.verification.annotation";

    private ControlledMintResolver() { }

    public static Optional<ControlledMintProperty> resolve(
            String source, String fileName, String validatorTitle, ContractSchema schema) {
        CompilationUnit unit = StaticJavaParser.parse(source);
        TypeDeclaration<?> type = exactType(unit, validatorTitle, fileName);
        List<AnnotationExpr> matches = annotations(type, unit);
        if (matches.isEmpty()) return Optional.empty();
        AnnotationExpr annotation = matches.getFirst();
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
        return Optional.of(new ControlledMintProperty(
                ControlledMintProperty.SCHEMA_VERSION,
                ControlledMintProperty.TEMPLATE,
                validatorTitle + ".controlled-mint-v1",
                validatorTitle,
                "minting",
                "authority:" + authority + "|tokenName:" + tokenName
                        + "|quantity:" + signedQuantity,
                authority,
                tokenName,
                signedQuantity,
                action,
                record.name(),
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
            PirType type, ContractSchema schema, AnnotationExpr annotation, String fileName) {
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
            AnnotationExpr annotation, String name, String fileName) {
        Expression expression = member(annotation, name, fileName);
        if (!(expression instanceof StringLiteralExpr literal)) {
            throw error("@ControlledMint " + name + " must be a hexadecimal string literal",
                    annotation, fileName);
        }
        String value = literal.asString();
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
            AnnotationExpr annotation, String name, String fileName) {
        Expression expression = member(annotation, name, fileName);
        if (expression instanceof LongLiteralExpr literal) {
            try {
                return literal.asNumber().longValue();
            } catch (RuntimeException ignored) { }
        }
        if (expression.isIntegerLiteralExpr()) {
            try {
                return expression.asIntegerLiteralExpr().asNumber().longValue();
            } catch (RuntimeException ignored) { }
        }
        throw error("@ControlledMint " + name + " must be a positive long literal",
                annotation, fileName);
    }

    private static String enumMember(
            AnnotationExpr annotation, String name, String fileName) {
        String value = member(annotation, name, fileName).toString();
        int dot = value.lastIndexOf('.');
        return dot < 0 ? value : value.substring(dot + 1);
    }

    private static Expression member(
            AnnotationExpr annotation, String name, String fileName) {
        if (annotation instanceof NormalAnnotationExpr normal) {
            var matches = normal.getPairs().stream()
                    .filter(pair -> pair.getNameAsString().equals(name))
                    .map(pair -> pair.getValue()).toList();
            if (matches.size() == 1) return matches.getFirst();
        }
        throw error("@ControlledMint requires member '" + name + "'", annotation, fileName);
    }

    private static List<AnnotationExpr> annotations(
            TypeDeclaration<?> type, CompilationUnit unit) {
        String qualified = PACKAGE + ".ControlledMint";
        boolean imported = unit.getImports().stream()
                .filter(value -> !value.isStatic())
                .anyMatch(value -> value.isAsterisk()
                        ? value.getNameAsString().equals(PACKAGE)
                        : value.getNameAsString().equals(qualified));
        boolean samePackage = unit.getPackageDeclaration()
                .map(value -> value.getNameAsString().equals(PACKAGE)).orElse(false);
        return type.getAnnotations().stream().filter(annotation ->
                qualified.equals(annotation.getNameAsString())
                        || ("ControlledMint".equals(annotation.getNameAsString())
                        && (imported || samePackage))).toList();
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
