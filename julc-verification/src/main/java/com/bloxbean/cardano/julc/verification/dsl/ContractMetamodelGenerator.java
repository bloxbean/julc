package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.compiler.pir.PirType;
import com.bloxbean.cardano.julc.compiler.schema.ContractSchema;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslPropertySet;
import com.bloxbean.cardano.julc.verification.dsl.type.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Generates deterministic Java accessors from compiler-owned contract types. */
public final class ContractMetamodelGenerator {
    private ContractMetamodelGenerator() { }

    public static String generate(
            ContractSchema schema, String packageName, String className) {
        if (!packageName.matches("[a-z][A-Za-z0-9_.]*")
                || !className.matches("[A-Z][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid generated metamodel name");
        }
        if (schema.purpose() == ContractSchema.Purpose.MINT) {
            if (schema.datum() != null) {
                throw new IllegalArgumentException("Minting DSL interface must not have a datum");
            }
            return mintingModel(packageName, className);
        }
        if (schema.purpose() == ContractSchema.Purpose.WITHDRAW) {
            if (schema.datum() != null) {
                throw new IllegalArgumentException(
                        "Rewarding DSL interface must not have a datum");
            }
            return rewardingModel(packageName, className);
        }
        if (schema.purpose() == ContractSchema.Purpose.CERTIFY) {
            if (schema.datum() != null) {
                throw new IllegalArgumentException(
                        "Certifying DSL interface must not have a datum");
            }
            return certifyingModel(packageName, className);
        }
        if (schema.purpose() != ContractSchema.Purpose.SPEND || schema.datum() == null) {
            throw new IllegalArgumentException(
                    "DSL metamodel requires spending, minting, rewarding, or certifying");
        }
        PirType type = resolve(schema.datum().type(), schema);
        if (!(type instanceof PirType.RecordType record)) {
            throw new IllegalArgumentException("DSL metamodel v1 requires a record datum");
        }
        var accessors = new StringBuilder();
        for (PirType.Field field : record.fields()) {
            if (!field.name().matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
                throw new IllegalArgumentException("Unsupported Java field name " + field.name());
            }
            PirType fieldType = resolve(field.type(), schema);
            String wrapper;
            String selector;
            if (fieldType instanceof PirType.ByteStringType) {
                wrapper = "ByteStringExpr";
                selector = "bytesField";
            } else if (fieldType instanceof PirType.IntegerType) {
                wrapper = "IntegerExpr";
                selector = "integerField";
            } else {
                throw new IllegalArgumentException("DSL metamodel v1 does not support field "
                        + field.name() + " of type " + fieldType);
            }
            accessors.append("        public ").append(wrapper).append(" ")
                    .append(field.name()).append("() { return value.")
                    .append(selector).append("(\"").append(field.name()).append("\"); }\n");
        }
        return """
                package %s;

                import com.bloxbean.cardano.julc.verification.dsl.*;

                /** Generated from compiler-owned ContractSchema; do not edit. */
                public final class %s {
                    private final SpendingContractModel value = new SpendingContractModel();
                    private final Datum datum = new Datum(value.datum());

                    public Datum datum() { return datum; }
                    public ContextExpr context() { return value.context(); }
                    public BoolExpr exactUplcSucceeds() { return value.exactUplcSucceeds(); }
                    public BoolExpr validSpendingContext() { return value.validSpendingContext(); }

                    public static final class Datum {
                        private final DatumExpr value;
                        private Datum(DatumExpr value) { this.value = value; }
                %s    }
                }
                """.formatted(packageName, className, accessors);
    }

    /** Generate the opt-in schema-4 model from the retained compiler type graph. */
    public static String generateTypedV4(
            ContractSchema schema, String packageName, String className) {
        if (!packageName.matches("[a-z][A-Za-z0-9_.]*")
                || !className.matches("[A-Z][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid generated metamodel name");
        }
        ProjectedContractTypes projection = ContractTypeProjection.project(schema);
        return new TypedModelGenerator(
                projection, packageName, className,
                ContractTypeProjection.sha256(projection),
                DslPropertySet.TYPED_SCHEMA_VERSION).generate();
    }

    /** Generate the opt-in schema-5 model with pinned ledger-context vocabulary. */
    public static String generateTypedV5(
            ContractSchema schema, String packageName, String className) {
        if (!packageName.matches("[a-z][A-Za-z0-9_.]*")
                || !className.matches("[A-Z][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid generated metamodel name");
        }
        ProjectedContractTypes projection = ContractTypeProjection.project(schema);
        return new TypedModelGenerator(
                projection, packageName, className,
                ContractTypeProjection.sha256(projection),
                DslPropertySet.LEDGER_SCHEMA_VERSION).generate();
    }

    /** Generate the opt-in schema-6 model with compositional authorization. */
    public static String generateTypedV6(
            ContractSchema schema, String packageName, String className) {
        if (!packageName.matches("[a-z][A-Za-z0-9_.]*")
                || !className.matches("[A-Z][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid generated metamodel name");
        }
        ProjectedContractTypes projection = ContractTypeProjection.project(schema);
        return new TypedModelGenerator(
                projection, packageName, className,
                ContractTypeProjection.sha256(projection),
                DslPropertySet.AUTHORIZATION_SCHEMA_VERSION).generate();
    }

    /** Generate the opt-in schema-7 model with guarded certificate payloads. */
    public static String generateTypedV7(
            ContractSchema schema, String packageName, String className) {
        if (!packageName.matches("[a-z][A-Za-z0-9_.]*")
                || !className.matches("[A-Z][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid generated metamodel name");
        }
        ProjectedContractTypes projection = ContractTypeProjection.project(schema);
        return new TypedModelGenerator(
                projection, packageName, className,
                ContractTypeProjection.sha256(projection),
                DslPropertySet.CERTIFICATE_PAYLOAD_SCHEMA_VERSION).generate();
    }

    /** Generate the opt-in schema-8 model with explicit multi-asset value semantics. */
    public static String generateTypedV8(
            ContractSchema schema, String packageName, String className) {
        if (!packageName.matches("[a-z][A-Za-z0-9_.]*")
                || !className.matches("[A-Z][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid generated metamodel name");
        }
        ProjectedContractTypes projection = ContractTypeProjection.project(schema);
        return new TypedModelGenerator(
                projection, packageName, className,
                ContractTypeProjection.sha256(projection),
                DslPropertySet.VALUE_ALGEBRA_SCHEMA_VERSION).generate();
    }

    private static String mintingModel(String packageName, String className) {
        return """
                package %s;

                import com.bloxbean.cardano.julc.verification.dsl.*;

                /** Generated from compiler-owned minting ContractSchema; do not edit. */
                public final class %s {
                    private final MintingContractModel value = new MintingContractModel();

                    public ContextExpr context() { return value.context(); }
                    public PolicyIdExpr ownPolicy() { return value.ownPolicy(); }
                    public BoolExpr redeemerStrictlyDecodes() {
                        return value.redeemerStrictlyDecodes();
                    }
                    public BoolExpr exactUplcSucceeds() { return value.exactUplcSucceeds(); }
                    public BoolExpr validMintingContext() { return value.validMintingContext(); }
                }
                """.formatted(packageName, className);
    }

    private static String rewardingModel(String packageName, String className) {
        return """
                package %s;

                import com.bloxbean.cardano.julc.verification.dsl.*;

                /** Generated from compiler-owned rewarding ContractSchema; do not edit. */
                public final class %s {
                    private final RewardingContractModel value = new RewardingContractModel();

                    public ContextExpr context() { return value.context(); }
                    public CredentialExpr rewardingCredential() {
                        return value.rewardingCredential();
                    }
                    public BoolExpr redeemerStrictlyDecodes() {
                        return value.redeemerStrictlyDecodes();
                    }
                }
                """.formatted(packageName, className);
    }

    private static String certifyingModel(String packageName, String className) {
        return """
                package %s;

                import com.bloxbean.cardano.julc.verification.dsl.*;

                /** Generated from compiler-owned certifying ContractSchema; do not edit. */
                public final class %s {
                    private final CertifyingContractModel value =
                            new CertifyingContractModel();

                    public ContextExpr context() { return value.context(); }
                    public TxCertExpr certificate() { return value.certificate(); }
                    public IntegerExpr certificateIndex() {
                        return value.certificateIndex();
                    }
                    public BoolExpr redeemerStrictlyDecodes() {
                        return value.redeemerStrictlyDecodes();
                    }
                }
                """.formatted(packageName, className);
    }

    private static PirType resolve(PirType type, ContractSchema schema) {
        if (type instanceof PirType.NamedTypeRef ref) {
            PirType result = schema.namedDefinitions().get(ref.stableId());
            if (result == null) result = schema.namedDefinitions().get(ref.name());
            if (result == null) {
                throw new IllegalArgumentException("Unknown named type " + ref.name());
            }
            return result;
        }
        return type;
    }

    private static final class TypedModelGenerator {
        private final ProjectedContractTypes projection;
        private final String packageName;
        private final String className;
        private final String schemaHash;
        private final int dslSchemaVersion;
        private final Map<String, String> names = new LinkedHashMap<>();

        private TypedModelGenerator(
                ProjectedContractTypes projection,
                String packageName,
                String className,
                String schemaHash,
                int dslSchemaVersion) {
            this.projection = projection;
            this.packageName = packageName;
            this.className = className;
            this.schemaHash = schemaHash;
            this.dslSchemaVersion = dslSchemaVersion;
            validateGeneratedNames(projection);
            for (var definition : projection.definitions()) {
                names.put(definition.stableId(), "Type_" + javaName(definition.sourceName())
                        + "_" + shortHash(definition.stableId()));
            }
        }

        private String generate() {
            var body = new StringBuilder();
            body.append("package ").append(packageName).append(";\n\n")
                    .append("import com.bloxbean.cardano.julc.verification.dsl.*;\n")
                    .append("import com.bloxbean.cardano.julc.verification.dsl.ir.*;\n")
                    .append("import com.bloxbean.cardano.julc.verification.dsl.type.*;\n")
                    .append("import java.util.function.Function;\n\n")
                    .append("/** Generated from compiler-owned ContractSchema; do not edit. */\n")
                    .append("public final class ").append(className).append(" {\n")
                    .append("    public static final String CONTRACT_SCHEMA_SHA256 = \"")
                    .append(schemaHash).append("\";\n")
                    .append("    private final ").append(baseModel()).append(" base = new ")
                    .append(baseModel()).append("();\n");
            appendRoots(body);
            body.append("\n    public DslPropertySet properties(DslProperty... properties) {\n")
                    .append("        return DslPropertySet.")
                    .append(switch (dslSchemaVersion) {
                        case DslPropertySet.VALUE_ALGEBRA_SCHEMA_VERSION -> "typedV8";
                        case DslPropertySet.CERTIFICATE_PAYLOAD_SCHEMA_VERSION -> "typedV7";
                        case DslPropertySet.AUTHORIZATION_SCHEMA_VERSION -> "typedV6";
                        case DslPropertySet.LEDGER_SCHEMA_VERSION -> "typedV5";
                        default -> "typedV4";
                    })
                    .append("(DslPurpose.")
                    .append(dslPurpose()).append(", CONTRACT_SCHEMA_SHA256, properties);\n")
                    .append("    }\n")
                    .append(dslSchemaVersion >= DslPropertySet.LEDGER_SCHEMA_VERSION
                            ? "    public LedgerContextExpr context() { return LedgerExpressions.context(); }\n"
                            : "    public ContextExpr context() { return base.context(); }\n");
            if (dslSchemaVersion >= DslPropertySet.AUTHORIZATION_SCHEMA_VERSION) {
                body.append("    private final AuthorizationDsl authorization = new AuthorizationDsl();\n")
                        .append("    public AuthorizationDsl authorization() { return authorization; }\n");
            }
            if (dslSchemaVersion >= DslPropertySet.LEDGER_SCHEMA_VERSION
                    && projection.purpose() == com.bloxbean.cardano.julc.compiler.schema
                            .ContractSchema.Purpose.SPEND) {
                body.append("    public LedgerTxOutRefExpr currentOutputRef() {\n")
                        .append("        return new LedgerTxOutRefExpr(new LedgerHelperNode(\n")
                        .append("            LedgerHelperNode.LedgerHelperKind.CURRENT_OUTPUT_REF,\n")
                        .append("            java.util.List.of(context().node()),\n")
                        .append("            new LedgerTypeRef(LedgerTypeRef.LedgerKind.TX_OUT_REF)));\n")
                        .append("    }\n")
                        .append("    public LedgerTxInInfoOptionExpr ownInput() {\n")
                        .append("        return new LedgerTxInInfoOptionExpr(new LedgerHelperNode(\n")
                        .append("            LedgerHelperNode.LedgerHelperKind.FIND_OWN_INPUT,\n")
                        .append("            java.util.List.of(context().node()),\n")
                        .append("            new OptionalTypeRef(new LedgerTypeRef(\n")
                        .append("                LedgerTypeRef.LedgerKind.TX_IN_INFO))));\n")
                        .append("    }\n")
                        .append("    public LedgerTxOutListExpr continuingOutputs() {\n")
                        .append("        return new LedgerTxOutListExpr(new LedgerHelperNode(\n")
                        .append("            LedgerHelperNode.LedgerHelperKind.CONTINUING_OUTPUTS,\n")
                        .append("            java.util.List.of(context().node()),\n")
                        .append("            new ListTypeRef(new LedgerTypeRef(\n")
                        .append("                LedgerTypeRef.LedgerKind.TX_OUT))));\n")
                        .append("    }\n");
            }
            appendPurposeRoots(body);
            for (var definition : projection.definitions()) appendDefinition(body, definition);
            appendOptionalWrappers(body);
            appendCollectionWrappers(body);
            return body.append("}\n").toString();
        }

        private void appendRoots(StringBuilder body) {
            if (projection.datumType() != null) {
                body.append("    private final Optional_")
                        .append(typeSuffix(projection.datumType())).append(" datum = new Optional_")
                        .append(typeSuffix(projection.datumType()))
                        .append("(TypedExpressions.optionalRoot(\"typedDatum\", ")
                        .append(typeExpression(projection.datumType())).append("));\n")
                        .append("    public Optional_").append(typeSuffix(projection.datumType()))
                        .append(" datum() { return datum; }\n");
            }
            body.append("    private final Optional_")
                    .append(typeSuffix(projection.redeemerType())).append(" redeemer = new Optional_")
                    .append(typeSuffix(projection.redeemerType()))
                    .append("(TypedExpressions.optionalRoot(\"typedRedeemer\", ")
                    .append(typeExpression(projection.redeemerType())).append("));\n")
                    .append("    public Optional_").append(typeSuffix(projection.redeemerType()))
                    .append(" redeemer() { return redeemer; }\n");
        }

        private void appendPurposeRoots(StringBuilder body) {
            switch (projection.purpose()) {
                case SPEND -> { }
                case MINT -> body.append(
                        "    public PolicyIdExpr ownPolicy() { return base.ownPolicy(); }\n");
                case WITHDRAW -> body.append(
                        "    public CredentialExpr rewardingCredential() { return base.rewardingCredential(); }\n");
                case CERTIFY -> body.append(dslSchemaVersion
                        >= DslPropertySet.CERTIFICATE_PAYLOAD_SCHEMA_VERSION
                        ? "    public TxCertExpr certificate() { return LedgerExpressions.currentCertificate(); }\n"
                                + "    public IntegerExpr certificateIndex() { return LedgerExpressions.currentCertificateIndex(); }\n"
                        : "    public TxCertExpr certificate() { return base.certificate(); }\n"
                                + "    public IntegerExpr certificateIndex() { return base.certificateIndex(); }\n");
                default -> throw new IllegalArgumentException(
                        "Structural DSL does not support " + projection.purpose());
            }
        }

        private void appendDefinition(
                StringBuilder body, ProjectedContractTypes.NominalDefinition definition) {
            String name = names.get(definition.stableId());
            var reference = new NominalTypeRef(definition.stableId(), definition.nominalKind());
            body.append("\n    public static final class ").append(name).append(" {\n")
                    .append("        private final TypedValueExpr value;\n")
                    .append("        private ").append(name)
                    .append("(TypedValueExpr value) { this.value = value; }\n");
            body.append("        public BoolExpr structurallyEquals(").append(name)
                    .append(" other) { return value.eq(other.value); }\n")
                    .append("        public BoolExpr structurallyNotEquals(").append(name)
                    .append(" other) { return value.ne(other.value); }\n");
            if (definition.nominalKind() == NominalTypeRef.NominalKind.RECORD) {
                for (var field : definition.fields()) {
                    appendRecordAccessor(body, reference, field);
                }
            } else {
                for (var constructor : definition.constructors()) {
                    String method = javaName(constructor.name());
                    String payload = name + "_" + method;
                    body.append("        public BoolExpr is").append(method).append("() {\n")
                            .append("            return TypedExpressions.isConstructor(value, ")
                            .append(typeExpression(reference)).append(", \"")
                            .append(javaString(constructor.name())).append("\");\n")
                            .append("        }\n")
                            .append("        public BoolExpr when").append(method)
                            .append("(Function<").append(payload).append(", BoolExpr> predicate) {\n")
                            .append("            return TypedExpressions.whenConstructor(value, ")
                            .append(typeExpression(reference)).append(", \"")
                            .append(javaString(constructor.name()))
                            .append("\", raw -> predicate.apply(new ").append(payload)
                            .append("(raw)));\n")
                            .append("        }\n");
                }
            }
            body.append("    }\n");
            if (definition.nominalKind() == NominalTypeRef.NominalKind.SUM) {
                for (var constructor : definition.constructors()) {
                    appendVariantPayload(body, reference, name, constructor);
                }
            }
        }

        private void appendRecordAccessor(
                StringBuilder body,
                NominalTypeRef owner,
                ProjectedContractTypes.Field field) {
            body.append("        public ").append(wrapperType(field.type())).append(' ')
                    .append(javaMember(field.name())).append("() {\n")
                    .append("            var raw = TypedExpressions.field(value, ")
                    .append(typeExpression(owner)).append(", \"")
                    .append(javaString(field.name())).append("\", ")
                    .append(typeExpression(field.type())).append(");\n")
                    .append("            return ").append(wrap(field.type(), "raw"))
                    .append(";\n        }\n");
        }

        private void appendVariantPayload(
                StringBuilder body,
                NominalTypeRef sum,
                String sumName,
                ProjectedContractTypes.Constructor constructor) {
            String payload = sumName + "_" + javaName(constructor.name());
            body.append("\n    public static final class ").append(payload).append(" {\n")
                    .append("        private final TypedValueExpr value;\n")
                    .append("        private ").append(payload)
                    .append("(TypedValueExpr value) { this.value = value; }\n");
            for (var field : constructor.fields()) {
                body.append("        public ").append(wrapperType(field.type())).append(' ')
                        .append(javaMember(field.name())).append("() {\n")
                        .append("            var raw = TypedExpressions.variantField(value, ")
                        .append(typeExpression(sum)).append(", \"")
                        .append(javaString(constructor.name())).append("\", \"")
                        .append(javaString(field.name())).append("\", ")
                        .append(typeExpression(field.type())).append(");\n")
                        .append("            return ").append(wrap(field.type(), "raw"))
                        .append(";\n        }\n");
            }
            body.append("    }\n");
        }

        private void appendOptionalWrappers(StringBuilder body) {
            var optionals = new LinkedHashMap<String, VerificationTypeRef>();
            if (projection.datumType() != null) {
                optionals.put(typeSuffix(projection.datumType()), projection.datumType());
            }
            optionals.put(typeSuffix(projection.redeemerType()), projection.redeemerType());
            for (var definition : projection.definitions()) {
                definition.fields().forEach(field -> collectOptionals(field.type(), optionals));
                definition.constructors().forEach(constructor -> constructor.fields()
                        .forEach(field -> collectOptionals(field.type(), optionals)));
            }
            for (var entry : optionals.entrySet()) {
                VerificationTypeRef element = entry.getValue();
                body.append("\n    public static final class Optional_")
                        .append(entry.getKey()).append(" {\n")
                        .append("        private final TypedOptionExpr value;\n")
                        .append("        private Optional_").append(entry.getKey())
                        .append("(TypedOptionExpr value) { this.value = value; }\n")
                        .append("        public BoolExpr isPresent() { return value.isPresent(); }\n")
                        .append("        public BoolExpr isEmpty() { return value.isEmpty(); }\n")
                        .append("        public BoolExpr exists(Function<")
                        .append(wrapperType(element)).append(", BoolExpr> predicate) {\n")
                        .append("            return value.exists(raw -> predicate.apply(")
                        .append(wrap(element, "raw")).append("));\n")
                        .append("        }\n")
                        .append("    }\n");
            }
        }

        private void appendCollectionWrappers(StringBuilder body) {
            var lists = new LinkedHashMap<String, VerificationTypeRef>();
            var maps = new LinkedHashMap<String, AssocMapTypeRef>();
            if (projection.datumType() != null) {
                collectCollections(projection.datumType(), lists, maps);
            }
            collectCollections(projection.redeemerType(), lists, maps);
            for (var definition : projection.definitions()) {
                definition.fields().forEach(field ->
                        collectCollections(field.type(), lists, maps));
                definition.constructors().forEach(constructor -> constructor.fields()
                        .forEach(field -> collectCollections(field.type(), lists, maps)));
            }
            for (var entry : lists.entrySet()) appendListWrapper(
                    body, entry.getKey(), entry.getValue());
            for (var entry : maps.entrySet()) appendMapWrapper(
                    body, entry.getKey(), entry.getValue());
        }

        private void collectCollections(
                VerificationTypeRef type,
                Map<String, VerificationTypeRef> lists,
                Map<String, AssocMapTypeRef> maps) {
            switch (type) {
                case OptionalTypeRef optional ->
                        collectCollections(optional.elementType(), lists, maps);
                case ListTypeRef list -> {
                    lists.put(typeSuffix(list.elementType()), list.elementType());
                    collectCollections(list.elementType(), lists, maps);
                }
                case AssocMapTypeRef map -> {
                    maps.put(typeSuffix(map), map);
                    // lookupAll returns a structural list even when no List<V>
                    // appeared in the source schema.
                    lists.put(typeSuffix(map.valueType()), map.valueType());
                    collectCollections(map.keyType(), lists, maps);
                    collectCollections(map.valueType(), lists, maps);
                }
                default -> { }
            }
        }

        private void appendListWrapper(
                StringBuilder body, String suffix, VerificationTypeRef element) {
            String elementWrapper = wrapperType(element);
            body.append("\n    public static final class List_").append(suffix).append(" {\n")
                    .append("        private final TypedListExpr value;\n")
                    .append("        private List_").append(suffix)
                    .append("(TypedListExpr value) { this.value = value; }\n")
                    .append("        public BoolExpr isEmpty() { return value.isEmpty(); }\n")
                    .append("        public BoolExpr isNotEmpty() { return value.isNotEmpty(); }\n")
                    .append("        public BoolExpr exists(Function<").append(elementWrapper)
                    .append(", BoolExpr> predicate) { return value.exists(raw -> predicate.apply(")
                    .append(wrap(element, "raw")).append(")); }\n")
                    .append("        public BoolExpr all(Function<").append(elementWrapper)
                    .append(", BoolExpr> predicate) { return value.all(raw -> predicate.apply(")
                    .append(wrap(element, "raw")).append(")); }\n")
                    .append("        public BoolExpr none(Function<").append(elementWrapper)
                    .append(", BoolExpr> predicate) { return value.none(raw -> predicate.apply(")
                    .append(wrap(element, "raw")).append(")); }\n")
                    .append("        public IntegerExpr count(Function<").append(elementWrapper)
                    .append(", BoolExpr> predicate) { return value.count(raw -> predicate.apply(")
                    .append(wrap(element, "raw")).append(")); }\n")
                    .append("        public BoolExpr exactlyOne(Function<")
                    .append(elementWrapper).append(", BoolExpr> predicate) { return value.exactlyOne(raw -> predicate.apply(")
                    .append(wrap(element, "raw")).append(")); }\n")
                    .append("        public BoolExpr contains(").append(elementWrapper)
                    .append(" item) { return value.contains(")
                    .append(rawValue(element, "item")).append("); }\n")
                    .append("        public Optional_").append(typeSuffix(element))
                    .append(" at(IntegerExpr index) { return new Optional_")
                    .append(typeSuffix(element)).append("(value.at(index)); }\n")
                    .append("        public BoolExpr structurallyEquals(List_").append(suffix)
                    .append(" other) { return value.structurallyEquals(other.value); }\n")
                    .append(dslSchemaVersion >= DslPropertySet.AUTHORIZATION_SCHEMA_VERSION
                            && element.equals(new BuiltinTypeRef(
                                    BuiltinTypeRef.BuiltinKind.BYTE_STRING))
                            ? "        public AuthoritySetExpr asAuthorities() { return new AuthorizationDsl().fromContractBytes(value); }\n"
                            : "")
                    .append("    }\n");
        }

        private void appendMapWrapper(
                StringBuilder body, String suffix, AssocMapTypeRef map) {
            String key = wrapperType(map.keyType());
            String value = wrapperType(map.valueType());
            body.append("\n    public static final class Map_").append(suffix).append(" {\n")
                    .append("        private final TypedAssocMapExpr value;\n")
                    .append("        private Map_").append(suffix)
                    .append("(TypedAssocMapExpr value) { this.value = value; }\n")
                    .append("        public Map_").append(suffix)
                    .append(" entries() { return this; }\n")
                    .append("        public BoolExpr existsEntry(java.util.function.BiFunction<")
                    .append(key).append(", ").append(value)
                    .append(", BoolExpr> predicate) { return value.existsEntry((rawKey, rawValue) -> predicate.apply(")
                    .append(wrap(map.keyType(), "rawKey")).append(", ")
                    .append(wrap(map.valueType(), "rawValue")).append(")); }\n")
                    .append("        public BoolExpr allEntries(java.util.function.BiFunction<")
                    .append(key).append(", ").append(value)
                    .append(", BoolExpr> predicate) { return value.allEntries((rawKey, rawValue) -> predicate.apply(")
                    .append(wrap(map.keyType(), "rawKey")).append(", ")
                    .append(wrap(map.valueType(), "rawValue")).append(")); }\n")
                    .append("        public IntegerExpr countEntry(java.util.function.BiFunction<")
                    .append(key).append(", ").append(value)
                    .append(", BoolExpr> predicate) { return value.countEntry((rawKey, rawValue) -> predicate.apply(")
                    .append(wrap(map.keyType(), "rawKey")).append(", ")
                    .append(wrap(map.valueType(), "rawValue")).append(")); }\n")
                    .append("        public BoolExpr containsKey(").append(key)
                    .append(" key) { return value.containsKey(")
                    .append(rawValue(map.keyType(), "key")).append("); }\n")
                    .append("        public IntegerExpr countKey(").append(key)
                    .append(" key) { return value.countKey(")
                    .append(rawValue(map.keyType(), "key")).append("); }\n")
                    .append("        public Optional_").append(typeSuffix(map.valueType()))
                    .append(" lookupFirst(").append(key).append(" key) { return new Optional_")
                    .append(typeSuffix(map.valueType())).append("(value.lookupFirst(")
                    .append(rawValue(map.keyType(), "key")).append(")); }\n")
                    .append("        public List_").append(typeSuffix(map.valueType()))
                    .append(" lookupAll(").append(key).append(" key) { return new List_")
                    .append(typeSuffix(map.valueType())).append("(value.lookupAll(")
                    .append(rawValue(map.keyType(), "key")).append(")); }\n")
                    .append("        public BoolExpr structurallyEquals(Map_").append(suffix)
                    .append(" other) { return value.structurallyEquals(other.value); }\n")
                    .append("    }\n");
        }

        private String rawValue(VerificationTypeRef type, String expression) {
            return switch (type) {
                case BuiltinTypeRef ignored -> "new TypedValueExpr(" + expression
                        + ".node(), " + typeExpression(type) + ")";
                case LedgerTypeRef ignored -> throw new IllegalArgumentException(
                        "Contract model cannot wrap a ledger type");
                case NominalTypeRef ignored -> expression + ".value";
                case OptionalTypeRef ignored -> "new TypedValueExpr(" + expression
                        + ".value.node(), " + typeExpression(type) + ")";
                case ListTypeRef ignored -> "new TypedValueExpr(" + expression
                        + ".value.node(), " + typeExpression(type) + ")";
                case AssocMapTypeRef ignored -> "new TypedValueExpr(" + expression
                        + ".value.node(), " + typeExpression(type) + ")";
            };
        }

        private void collectOptionals(
                VerificationTypeRef type, Map<String, VerificationTypeRef> result) {
            switch (type) {
                case OptionalTypeRef optional -> {
                    result.put(typeSuffix(optional.elementType()), optional.elementType());
                    collectOptionals(optional.elementType(), result);
                }
                case ListTypeRef list -> {
                    // Safe indexing returns Option<T>.
                    result.put(typeSuffix(list.elementType()), list.elementType());
                    collectOptionals(list.elementType(), result);
                }
                case AssocMapTypeRef map -> {
                    // First-match lookup returns Option<V>.
                    result.put(typeSuffix(map.valueType()), map.valueType());
                    collectOptionals(map.keyType(), result);
                    collectOptionals(map.valueType(), result);
                }
                default -> { }
            }
        }

        private String wrapperType(VerificationTypeRef type) {
            return switch (type) {
                case BuiltinTypeRef builtin -> switch (builtin.builtin()) {
                    case BOOLEAN -> "BoolExpr";
                    case INTEGER -> "IntegerExpr";
                    case BYTE_STRING -> "ByteStringExpr";
                    case STRING -> "StringExpr";
                    case UNIT, DATA -> "TypedValueExpr";
                };
                case NominalTypeRef nominal -> names.get(nominal.stableId());
                case LedgerTypeRef ignored -> throw new IllegalArgumentException(
                        "Contract model cannot expose a ledger type");
                case OptionalTypeRef optional -> "Optional_" + typeSuffix(optional.elementType());
                case ListTypeRef list -> "List_" + typeSuffix(list.elementType());
                case AssocMapTypeRef map -> "Map_" + typeSuffix(map);
            };
        }

        private String wrap(VerificationTypeRef type, String raw) {
            return switch (type) {
                case BuiltinTypeRef builtin -> switch (builtin.builtin()) {
                    case BOOLEAN -> "new BoolExpr(" + raw + ".node())";
                    case INTEGER -> "new IntegerExpr(" + raw + ".node())";
                    case BYTE_STRING -> "new ByteStringExpr(" + raw + ".node())";
                    case STRING -> "new StringExpr(" + raw + ".node())";
                    case UNIT, DATA -> raw;
                };
                case NominalTypeRef nominal -> "new " + names.get(nominal.stableId())
                        + "(" + raw + ")";
                case LedgerTypeRef ignored -> throw new IllegalArgumentException(
                        "Contract model cannot wrap a ledger type");
                case OptionalTypeRef optional -> "new Optional_"
                        + typeSuffix(optional.elementType()) + "(new TypedOptionExpr("
                        + raw + ".node(), " + typeExpression(optional.elementType()) + "))";
                case ListTypeRef list -> "new List_" + typeSuffix(list.elementType())
                        + "(new TypedListExpr(" + raw + ".node(), "
                        + typeExpression(list.elementType()) + "))";
                case AssocMapTypeRef map -> "new Map_" + typeSuffix(map)
                        + "(new TypedAssocMapExpr(" + raw + ".node(), "
                        + typeExpression(map.keyType()) + ", "
                        + typeExpression(map.valueType()) + "))";
            };
        }

        private String typeExpression(VerificationTypeRef type) {
            return switch (type) {
                case BuiltinTypeRef builtin -> "new BuiltinTypeRef(BuiltinTypeRef.BuiltinKind."
                        + builtin.builtin() + ")";
                case NominalTypeRef nominal -> "new NominalTypeRef(\""
                        + javaString(nominal.stableId()) + "\", NominalTypeRef.NominalKind."
                        + nominal.nominalKind() + ")";
                case LedgerTypeRef ledger -> "new LedgerTypeRef(LedgerTypeRef.LedgerKind."
                        + ledger.ledgerType() + ")";
                case OptionalTypeRef optional -> "new OptionalTypeRef("
                        + typeExpression(optional.elementType()) + ")";
                case ListTypeRef list -> "new ListTypeRef("
                        + typeExpression(list.elementType()) + ")";
                case AssocMapTypeRef map -> "new AssocMapTypeRef("
                        + typeExpression(map.keyType()) + ", "
                        + typeExpression(map.valueType()) + ")";
            };
        }

        private String baseModel() {
            return switch (projection.purpose()) {
                case SPEND -> "SpendingContractModel";
                case MINT -> "MintingContractModel";
                case WITHDRAW -> "RewardingContractModel";
                case CERTIFY -> "CertifyingContractModel";
                default -> throw new IllegalArgumentException(
                        "Unsupported structural DSL purpose " + projection.purpose());
            };
        }

        private String dslPurpose() {
            return switch (projection.purpose()) {
                case SPEND -> "SPENDING";
                case MINT -> "MINTING";
                case WITHDRAW -> "REWARDING";
                case CERTIFY -> "CERTIFYING";
                default -> throw new IllegalArgumentException(
                        "Unsupported structural DSL purpose " + projection.purpose());
            };
        }

        private static String typeSuffix(VerificationTypeRef type) {
            return shortHash(ContractTypeProjection.canonicalTypeJson(type));
        }

        private static void validateGeneratedNames(ProjectedContractTypes projection) {
            for (var definition : projection.definitions()) {
                if (definition.nominalKind() == NominalTypeRef.NominalKind.RECORD) {
                    requireDistinctGeneratedNames(definition.stableId(),
                            definition.fields().stream().map(
                                    ProjectedContractTypes.Field::name).toList(), true);
                } else {
                    requireDistinctGeneratedNames(definition.stableId(),
                            definition.constructors().stream().map(
                                    ProjectedContractTypes.Constructor::name).toList(), false);
                    for (var constructor : definition.constructors()) {
                        requireDistinctGeneratedNames(
                                definition.stableId() + "." + constructor.name(),
                                constructor.fields().stream().map(
                                        ProjectedContractTypes.Field::name).toList(), true);
                    }
                }
            }
        }

        private static void requireDistinctGeneratedNames(
                String owner, java.util.List<String> sourceNames, boolean members) {
            var generated = new java.util.HashSet<String>();
            for (String sourceName : sourceNames) {
                String name = members ? javaMember(sourceName) : javaName(sourceName);
                if (!generated.add(name)) {
                    throw new IllegalArgumentException(
                            "Compiler-owned names collide in generated structural DSL model at "
                                    + owner + ": " + sourceNames);
                }
            }
        }

        private static String javaName(String raw) {
            var result = new StringBuilder();
            boolean capitalize = true;
            for (int index = 0; index < raw.length(); index++) {
                char ch = raw.charAt(index);
                if (!Character.isJavaIdentifierPart(ch)) {
                    capitalize = true;
                    continue;
                }
                result.append(capitalize ? Character.toUpperCase(ch) : ch);
                capitalize = false;
            }
            if (result.isEmpty()) result.append("Generated");
            if (!Character.isJavaIdentifierStart(result.charAt(0))) result.insert(0, 'T');
            return result.toString();
        }

        private static String javaMember(String raw) {
            String name = javaName(raw);
            return Character.toLowerCase(name.charAt(0)) + name.substring(1);
        }

        private static String javaString(String raw) {
            return raw.replace("\\", "\\\\").replace("\"", "\\\"");
        }

        private static String shortHash(String value) {
            try {
                return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 10);
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 unavailable", impossible);
            }
        }
    }
}
