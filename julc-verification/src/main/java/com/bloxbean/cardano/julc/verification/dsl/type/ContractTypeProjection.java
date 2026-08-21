package com.bloxbean.cardano.julc.verification.dsl.type;

import com.bloxbean.cardano.julc.compiler.pir.PirType;
import com.bloxbean.cardano.julc.compiler.schema.ContractSchema;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Projects and authenticates the existing compiler type graph without resolving source again. */
public final class ContractTypeProjection {
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private ContractTypeProjection() { }

    public static ProjectedContractTypes project(ContractSchema schema) {
        var projector = new Projector(schema.namedDefinitions());
        var selected = schema.singleInterface();
        VerificationTypeRef datum = selected.datum() == null
                ? null : projector.type(selected.datum().type());
        VerificationTypeRef redeemer = projector.type(selected.redeemer().type());
        List<ProjectedContractTypes.Parameter> parameters = schema.parameters().stream()
                .map(parameter -> new ProjectedContractTypes.Parameter(
                        parameter.name(), projector.type(parameter.type())))
                .toList();
        List<ProjectedContractTypes.NominalDefinition> definitions =
                projector.reachableDefinitions();
        var projected = new ProjectedContractTypes(
                ProjectedContractTypes.SCHEMA_VERSION,
                selected.purpose(), datum, redeemer, parameters, definitions);
        validate(projected);
        return projected;
    }

    public static String canonicalJson(ProjectedContractTypes projection) {
        try {
            return JSON.writeValueAsString(projection);
        } catch (Exception impossible) {
            throw new IllegalStateException("Cannot serialize projected contract types", impossible);
        }
    }

    /** Stable serialized identity for generated names; never depend on record toString(). */
    public static String canonicalTypeJson(VerificationTypeRef type) {
        try {
            return JSON.writeValueAsString(type);
        } catch (Exception impossible) {
            throw new IllegalStateException("Cannot serialize projected contract type", impossible);
        }
    }

    public static String sha256(ProjectedContractTypes projection) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonicalJson(projection).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public static ProjectedContractTypes readCanonical(String canonicalJson, long maxBytes)
            throws IOException {
        byte[] bytes = canonicalJson.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > maxBytes) {
            throw new IOException("Projected contract type graph size " + bytes.length
                    + " is outside 1.." + maxBytes + " bytes");
        }
        ProjectedContractTypes result = JSON.readValue(bytes, ProjectedContractTypes.class);
        validate(result);
        if (!java.util.Arrays.equals(bytes,
                canonicalJson(result).getBytes(StandardCharsets.UTF_8))) {
            throw new IOException("Projected contract type graph is not in canonical form");
        }
        return result;
    }

    /** Rechecks references after strict JSON decoding or before manifest publication. */
    public static void validate(ProjectedContractTypes projection) {
        var definitions = new LinkedHashMap<String, ProjectedContractTypes.NominalDefinition>();
        for (var definition : projection.definitions()) {
            if (definitions.putIfAbsent(definition.stableId(), definition) != null) {
                throw new IllegalArgumentException(
                        "Duplicate projected nominal ID " + definition.stableId());
            }
        }
        check(projection.datumType(), definitions, 0);
        check(projection.redeemerType(), definitions, 0);
        projection.parameters().forEach(parameter -> check(parameter.type(), definitions, 0));
        projection.definitions().forEach(definition -> {
            definition.fields().forEach(field -> check(field.type(), definitions, 0));
            definition.constructors().forEach(constructor -> constructor.fields()
                    .forEach(field -> check(field.type(), definitions, 0)));
        });
    }

    private static void check(
            VerificationTypeRef type,
            Map<String, ProjectedContractTypes.NominalDefinition> definitions,
            int depth) {
        if (type == null) return;
        if (depth > 256) throw new IllegalArgumentException("Projected type nesting exceeds 256");
        switch (type) {
            case BuiltinTypeRef ignored -> { }
            case LedgerTypeRef ignored -> throw new IllegalArgumentException(
                    "Compiler contract projection cannot contain ledger types");
            case NominalTypeRef nominal -> {
                if (nominal.nominalKind() == NominalTypeRef.NominalKind.NEWTYPE) {
                    throw new IllegalArgumentException(
                            "Compiler-owned newtype identity is not available yet");
                }
                var definition = definitions.get(nominal.stableId());
                if (definition == null || definition.nominalKind() != nominal.nominalKind()) {
                    throw new IllegalArgumentException(
                            "Unknown or mismatched nominal reference " + nominal.stableId());
                }
            }
            case OptionalTypeRef optional -> check(optional.elementType(), definitions, depth + 1);
            case ListTypeRef list -> check(list.elementType(), definitions, depth + 1);
            case AssocMapTypeRef map -> {
                check(map.keyType(), definitions, depth + 1);
                check(map.valueType(), definitions, depth + 1);
            }
        }
    }

    private static final class Projector {
        private final Map<String, PirType> definitions;
        private final IdentityHashMap<PirType, String> identities = new IdentityHashMap<>();
        private final java.util.Set<String> needed = new java.util.LinkedHashSet<>();

        private Projector(Map<String, PirType> definitions) {
            this.definitions = definitions;
            definitions.forEach((stableId, type) -> identities.put(type, stableId));
        }

        private ProjectedContractTypes.NominalDefinition definition(
                Map.Entry<String, PirType> entry) {
            String stableId = entry.getKey();
            return switch (entry.getValue()) {
                case PirType.RecordType record -> new ProjectedContractTypes.NominalDefinition(
                        stableId, record.name(), NominalTypeRef.NominalKind.RECORD,
                        fields(record.fields()), List.of());
                case PirType.SumType sum -> new ProjectedContractTypes.NominalDefinition(
                        stableId, sum.name(), NominalTypeRef.NominalKind.SUM, List.of(),
                        sum.constructors().stream()
                                .sorted(Comparator.comparingInt(PirType.Constructor::tag))
                                .map(constructor -> new ProjectedContractTypes.Constructor(
                                        constructor.name(), constructor.tag(),
                                        fields(constructor.fields())))
                                .toList());
                default -> throw new IllegalArgumentException(
                        "Named compiler definition is not record or sum: " + stableId);
            };
        }

        private List<ProjectedContractTypes.NominalDefinition> reachableDefinitions() {
            var projected = new LinkedHashMap<String,
                    ProjectedContractTypes.NominalDefinition>();
            while (projected.size() < needed.size()) {
                List<String> pending = needed.stream()
                        .filter(stableId -> !projected.containsKey(stableId))
                        .sorted().toList();
                if (pending.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Cannot close projected nominal dependency graph");
                }
                for (String stableId : pending) {
                    PirType definition = definitions.get(stableId);
                    if (definition == null) {
                        throw new IllegalArgumentException(
                                "Dangling compiler nominal reference " + stableId);
                    }
                    projected.put(stableId, definition(
                            Map.entry(stableId, definition)));
                }
            }
            return projected.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(Map.Entry::getValue).toList();
        }

        private List<ProjectedContractTypes.Field> fields(List<PirType.Field> fields) {
            var result = new ArrayList<ProjectedContractTypes.Field>(fields.size());
            for (var field : fields) {
                result.add(new ProjectedContractTypes.Field(field.name(), type(field.type())));
            }
            return result;
        }

        private VerificationTypeRef type(PirType type) {
            return switch (type) {
                case PirType.IntegerType ignored -> builtin(BuiltinTypeRef.BuiltinKind.INTEGER);
                case PirType.ByteStringType ignored -> builtin(
                        BuiltinTypeRef.BuiltinKind.BYTE_STRING);
                case PirType.StringType ignored -> builtin(BuiltinTypeRef.BuiltinKind.STRING);
                case PirType.BoolType ignored -> builtin(BuiltinTypeRef.BuiltinKind.BOOLEAN);
                case PirType.UnitType ignored -> builtin(BuiltinTypeRef.BuiltinKind.UNIT);
                case PirType.DataType ignored -> builtin(BuiltinTypeRef.BuiltinKind.DATA);
                case PirType.OptionalType optional -> new OptionalTypeRef(type(optional.elemType()));
                case PirType.ListType list -> new ListTypeRef(type(list.elemType()));
                case PirType.MapType map -> new AssocMapTypeRef(
                        type(map.keyType()), type(map.valueType()));
                case PirType.NamedTypeRef named -> named(named.stableId(), named.kind());
                case PirType.RecordType record -> named(identity(record), PirType.NamedKind.RECORD);
                case PirType.SumType sum -> named(identity(sum), PirType.NamedKind.SUM);
                case PirType.PairType ignored -> unsupported("pair");
                case PirType.ArrayType ignored -> unsupported("array");
                case PirType.FunType ignored -> unsupported("function");
            };
        }

        private VerificationTypeRef named(String stableId, PirType.NamedKind kind) {
            PirType definition = definitions.get(stableId);
            if (definition == null) {
                throw new IllegalArgumentException("Dangling compiler nominal reference " + stableId);
            }
            boolean correct = kind == PirType.NamedKind.RECORD
                    ? definition instanceof PirType.RecordType
                    : definition instanceof PirType.SumType;
            if (!correct) {
                throw new IllegalArgumentException("Compiler nominal kind mismatch for " + stableId);
            }
            needed.add(stableId);
            return new NominalTypeRef(stableId, kind == PirType.NamedKind.RECORD
                    ? NominalTypeRef.NominalKind.RECORD : NominalTypeRef.NominalKind.SUM);
        }

        private String identity(PirType type) {
            String identity = identities.get(type);
            if (identity != null) return identity;
            List<String> matches = definitions.entrySet().stream()
                    .filter(entry -> entry.getValue().equals(type))
                    .map(Map.Entry::getKey).toList();
            if (matches.size() == 1) return matches.getFirst();
            throw new IllegalArgumentException(matches.isEmpty()
                    ? "Unnamed nominal compiler type " + type
                    : "Ambiguous structural nominal compiler type " + matches);
        }

        private static BuiltinTypeRef builtin(BuiltinTypeRef.BuiltinKind kind) {
            return new BuiltinTypeRef(kind);
        }

        private static VerificationTypeRef unsupported(String kind) {
            throw new IllegalArgumentException(
                    "Verification type projection does not support " + kind + " types");
        }
    }
}
