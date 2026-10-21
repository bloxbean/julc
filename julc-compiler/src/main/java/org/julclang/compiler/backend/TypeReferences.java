package org.julclang.compiler.backend;

import org.julclang.compiler.pir.PirType;

import java.util.Map;

/**
 * The single mapping from source {@link LibraryType.Reference}s to PIR representations
 * (ADR-059), shared by every provider. Built-in names are {@code Int}, {@code Bool},
 * {@code String}, {@code Bytes}, {@code Unit}, {@code Data}, {@code List[a]},
 * {@code Map[k,v]}, {@code JulcOptional[a]} and {@code Function[a,b]}; any other name must be a
 * described nominal type. Representations are never inferred from erased shapes.
 */
public final class TypeReferences {
    private TypeReferences() {}

    /**
     * @param reference a concrete reference (no type variables)
     * @param types     the nominal type descriptions visible to the caller
     * @throws IllegalArgumentException for variables, unknown names or wrong type-argument counts
     */
    public static PirType representation(LibraryType.Reference reference, Map<String, LibraryType> types) {
        if (reference.variable())
            throw new IllegalArgumentException("Unbound type variable " + reference.name());
        var arguments = reference.arguments();
        if (types.containsKey(reference.name())) {
            if (!arguments.isEmpty())
                throw new IllegalArgumentException("Nominal type " + reference.name() + " takes no type arguments");
            return types.get(reference.name()).representation();
        }
        int arity = switch (reference.name()) {
            case "List", "JulcOptional", "Array" -> 1;
            case "Map", "Function" -> 2;
            default -> 0;
        };
        if (arguments.size() != arity)
            throw new IllegalArgumentException("Type " + reference.name() + " takes " + arity
                    + " type arguments, not " + arguments.size());
        return switch (reference.name()) {
            case "Int" -> new PirType.IntegerType();
            case "Bool" -> new PirType.BoolType();
            case "String" -> new PirType.StringType();
            case "Bytes" -> new PirType.ByteStringType();
            case "Unit" -> new PirType.UnitType();
            case "Data" -> new PirType.DataType();
            case "List" -> new PirType.ListType(representation(arguments.getFirst(), types));
            case "Map" -> new PirType.MapType(representation(arguments.get(0), types),
                    representation(arguments.get(1), types));
            case "JulcOptional" -> new PirType.OptionalType(representation(arguments.getFirst(), types));
            // A PV11 array (CIP-138) of Data-encoded elements; it has no Data encoding itself.
            case "Array" -> new PirType.ArrayType(representation(arguments.getFirst(), types));
            case "Function" -> new PirType.FunType(representation(arguments.get(0), types),
                    representation(arguments.get(1), types));
            default -> throw new IllegalArgumentException("No source type description for " + reference.name());
        };
    }

    /**
     * The nominal types visible to an instantiation: the provider's descriptions plus the
     * producer-owned types the request carries (ADR-059). A producer type must be a
     * constructor-encoded record or sum and must not redefine a provider-described name.
     *
     * @throws IllegalArgumentException for a colliding name or an unsupported producer type
     */
    public static Map<String, LibraryType> withProducerTypes(Map<String, LibraryType> providerTypes,
                                                             LibraryRequest.Instantiate request) {
        if (request.producerTypes().isEmpty()) return providerTypes;
        var merged = new java.util.LinkedHashMap<>(providerTypes);
        request.producerTypes().forEach((name, representation) -> {
            if (providerTypes.containsKey(name))
                throw new IllegalArgumentException("producer type " + name
                        + " collides with a type described by the provider");
            if (!(representation instanceof PirType.RecordType || representation instanceof PirType.SumType))
                throw new IllegalArgumentException("producer type " + name
                        + " must be a constructor-encoded record or sum, not " + PirVerifier.show(representation));
            merged.put(name, new LibraryType(name, representation, false, java.util.List.of()));
        });
        return merged;
    }

    /**
     * A stable fingerprint of a request's producer types, for specialization keys: two
     * producer types with the same name but different representations never share an
     * implementation. Empty when the request carries none.
     */
    public static java.util.List<String> producerFingerprints(LibraryRequest.Instantiate request) {
        return request.producerTypes().entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .toList();
    }

    /**
     * Whether a representation satisfies {@link LibraryScheme.Constraint#DATA_ENCODABLE}: it has
     * a Plutus Data encoding that generic code can store as a list element.
     */
    public static boolean dataEncodable(PirType type) {
        return switch (type) {
            case PirType.IntegerType _, PirType.ByteStringType _, PirType.StringType _, PirType.BoolType _,
                 PirType.DataType _, PirType.RecordType _, PirType.SumType _, PirType.NamedTypeRef _ -> true;
            case PirType.ListType list -> !(list.elemType() instanceof PirType.PairType) && dataEncodable(list.elemType());
            case PirType.MapType map -> dataEncodable(map.keyType()) && dataEncodable(map.valueType());
            case PirType.OptionalType optional -> dataEncodable(optional.elemType());
            default -> false;
        };
    }
}
