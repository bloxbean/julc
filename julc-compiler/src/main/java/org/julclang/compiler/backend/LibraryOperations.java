package org.julclang.compiler.backend;

import org.julclang.compiler.codegen.StrictBoundaryGenerator;
import org.julclang.compiler.pir.PirType;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The operations a described library type supports (ADR-059), whichever frontend described it. */
public final class LibraryOperations {

    private LibraryOperations() {
    }

    /**
     * {@code types}, in their order, each with its {@link #operations}.
     *
     * @param special          the identities whose runtime encoding is not constructor data
     * @param namedDefinitions the named types {@code types} refer to
     */
    public static Map<String, LibraryType> derive(Map<String, LibraryType> types, Set<String> special,
                                                  Map<String, PirType> namedDefinitions) {
        var specialRepresentations = special.stream().map(id -> types.get(id).representation()).toList();
        var derived = new LinkedHashMap<String, LibraryType>();
        types.forEach((identity, type) -> derived.put(identity, new LibraryType(type.identity(), type.representation(),
                type.newType(), type.fields(), type.constructors(),
                operations(type, special, specialRepresentations, namedDefinitions))));
        return derived;
    }

    /**
     * Operations derived only from verified facts (ADR-059): special layouts get none, and a
     * type containing one has no equality or strict codec, because the strict boundary checker
     * and {@code EqualsData} would treat its map encoding as constructor data.
     */
    private static Set<LibraryType.Operation> operations(
            LibraryType type, Set<String> special, List<PirType> specialRepresentations,
            Map<String, PirType> namedDefinitions) {
        var representation = type.representation();
        if (special.contains(type.identity()) || PirType.containsNativeOpaque(representation))
            return Set.of();
        var operations = EnumSet.noneOf(LibraryType.Operation.class);
        if (type.newType()) {
            if (!(representation instanceof PirType.IntegerType || representation instanceof PirType.ByteStringType
                    || representation instanceof PirType.StringType || representation instanceof PirType.BoolType))
                return Set.of();
            operations.addAll(EnumSet.of(LibraryType.Operation.CONSTRUCT, LibraryType.Operation.PROJECT,
                    LibraryType.Operation.ENCODE));
        } else if (representation instanceof PirType.RecordType && !type.fields().isEmpty()
                || representation instanceof PirType.RecordType record && record.fields().isEmpty()) {
            operations.addAll(EnumSet.of(LibraryType.Operation.CONSTRUCT, LibraryType.Operation.PROJECT,
                    LibraryType.Operation.MATCH, LibraryType.Operation.ENCODE));
        } else if (representation instanceof PirType.SumType) {
            operations.addAll(EnumSet.of(LibraryType.Operation.CONSTRUCT, LibraryType.Operation.MATCH,
                    LibraryType.Operation.ENCODE));
        } else {
            return Set.of();
        }
        if (!containsSpecial(representation, specialRepresentations, namedDefinitions, new HashSet<>())
                && strictlyCheckable(representation, namedDefinitions))
            operations.addAll(EnumSet.of(LibraryType.Operation.EQUALS, LibraryType.Operation.DECODE_STRICT,
                    LibraryType.Operation.BOUNDARY));
        return operations;
    }

    private static boolean containsSpecial(PirType type, List<PirType> specialRepresentations,
                                           Map<String, PirType> namedDefinitions, Set<String> visiting) {
        if (specialRepresentations.contains(type)) return true;
        return switch (type) {
            case PirType.NamedTypeRef ref -> visiting.add(ref.stableId())
                    && namedDefinitions.containsKey(ref.stableId())
                    && containsSpecial(namedDefinitions.get(ref.stableId()), specialRepresentations,
                            namedDefinitions, visiting);
            case PirType.RecordType record -> record.fields().stream().anyMatch(field ->
                    containsSpecial(field.type(), specialRepresentations, namedDefinitions, visiting));
            case PirType.SumType sum -> sum.constructors().stream().flatMap(c -> c.fields().stream())
                    .anyMatch(field -> containsSpecial(field.type(), specialRepresentations, namedDefinitions, visiting));
            case PirType.ListType list -> containsSpecial(list.elemType(), specialRepresentations, namedDefinitions, visiting);
            case PirType.MapType map -> containsSpecial(map.keyType(), specialRepresentations, namedDefinitions, visiting)
                    || containsSpecial(map.valueType(), specialRepresentations, namedDefinitions, visiting);
            case PirType.OptionalType optional ->
                    containsSpecial(optional.elemType(), specialRepresentations, namedDefinitions, visiting);
            case PirType.ArrayType array -> containsSpecial(array.elemType(), specialRepresentations, namedDefinitions, visiting);
            default -> false;
        };
    }

    private static boolean strictlyCheckable(PirType type, Map<String, PirType> namedDefinitions) {
        try {
            new StrictBoundaryGenerator(namedDefinitions).ensureSupported(type);
            return true;
        } catch (IllegalArgumentException | IllegalStateException e) {
            return false;
        }
    }
}
