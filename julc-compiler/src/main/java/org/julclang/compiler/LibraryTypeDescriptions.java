package org.julclang.compiler;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.type.Type;
import org.julclang.compiler.backend.LibraryType;
import org.julclang.compiler.backend.TypeReferences;
import org.julclang.compiler.codegen.StrictBoundaryGenerator;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.resolve.ImportResolver;
import org.julclang.compiler.resolve.TypeResolver;

import java.util.*;

/** Source identity and capabilities derived alongside the existing encoding planner. */
final class LibraryTypeDescriptions {
    private final TypeResolver resolver;
    private final Map<String, RecordDeclaration> records = new LinkedHashMap<>();
    private final Map<String, LibraryType> types = new LinkedHashMap<>();
    /** Declared records whose runtime encoding is not constructor data, e.g. the map-backed Value. */
    private final Set<String> special = new LinkedHashSet<>();
    /** Type variables of the generic method being described (ADR-059, #181). */
    private Set<String> variables = Set.of();

    LibraryTypeDescriptions(List<CompilationUnit> units, TypeResolver resolver) {
        this.resolver = resolver;
        for (var unit : units) for (var record : unit.findAll(RecordDeclaration.class))
            records.put(record.getFullyQualifiedName().orElseThrow(), record);
        for (var entry : records.entrySet()) {
            var identity = entry.getKey();
            var record = entry.getValue();
            if (!record.getTypeParameters().isEmpty())
                throw new IllegalArgumentException("Generic record type is not yet supported by library descriptions: " + identity);
            scope(record.findCompilationUnit().orElseThrow());
            var representation = resolver.resolveNameToType(identity).orElseThrow();
            var fields = fields(record);
            boolean newType = resolver.isNewType(identity);
            // A declaration does not prove the runtime is constructor data (e.g. ledger Value).
            boolean regular = representation instanceof PirType.RecordType r
                    && r.fields().size() == fields.size()
                    && java.util.stream.IntStream.range(0, fields.size()).allMatch(i ->
                        r.fields().get(i).name().equals(fields.get(i).name()));
            // A non-constructor codec (or unspecified Data result) cannot support record projection.
            if (record.getMethodsByName("toPlutusData").stream().anyMatch(m ->
                    !m.getType().asString().equals("PlutusData.ConstrData")
                    && !m.getType().asString().equals("ConstrData"))) regular = false;
            boolean variant = record.getParentNode().orElse(null) instanceof ClassOrInterfaceDeclaration owner
                    && owner.isInterface();
            if (!regular && !newType) special.add(identity);
            types.put(identity, new LibraryType(identity, representation, newType,
                    !variant && (regular || newType) ? fields : List.of()));
        }
        for (var unit : units) for (var sum : unit.findAll(ClassOrInterfaceDeclaration.class)) {
            if (!sum.isInterface()) continue;
            String identity = sum.getFullyQualifiedName().orElseThrow();
            scope(unit);
            var representation = resolver.resolveNameToType(identity).orElse(null);
            if (!(representation instanceof PirType.SumType s)) continue;
            var constructors = new ArrayList<LibraryType.Constructor>();
            for (var ctor : s.constructors()) {
                var candidates = records.entrySet().stream().filter(e ->
                        e.getValue().getNameAsString().equals(ctor.name()) &&
                        e.getValue().getImplementedTypes().stream().anyMatch(t -> {
                            scope(e.getValue().findCompilationUnit().orElseThrow());
                            return reference(t).name().equals(identity);
                        })).toList();
                if (candidates.size() != 1)
                    throw new IllegalArgumentException("Cannot identify source constructor " + identity + "." + ctor.name());
                var record = candidates.getFirst().getValue();
                scope(record.findCompilationUnit().orElseThrow());
                constructors.add(new LibraryType.Constructor(ctor.name(), ctor.tag(), fields(record)));
                // A sum variant must not advertise independent tag-zero record operations.
                types.remove(candidates.getFirst().getKey());
            }
            types.put(identity, new LibraryType(identity, representation, false, List.of(), constructors));
        }
        for (String identity : TypeResolver.ledgerHashFqcns())
            types.put(identity, new LibraryType(identity, new PirType.ByteStringType(), true,
                    List.of(new LibraryType.Field("hash", ref("Bytes", List.of())))));
        var specialRepresentations = special.stream().map(id -> types.get(id).representation()).toList();
        var namedDefinitions = resolver.namedDefinitions();
        types.replaceAll((identity, type) -> new LibraryType(type.identity(), type.representation(),
                type.newType(), type.fields(), type.constructors(),
                operations(type, specialRepresentations, namedDefinitions)));
    }

    /**
     * Operations derived only from verified facts (ADR-059): special layouts get none, and a
     * type containing one has no equality or strict codec, because the strict boundary checker
     * and {@code EqualsData} would treat its map encoding as constructor data.
     */
    private Set<LibraryType.Operation> operations(
            LibraryType type, List<PirType> specialRepresentations, Map<String, PirType> namedDefinitions) {
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

    Map<String, LibraryType> types() { return Collections.unmodifiableMap(types); }

    /** Describe references to these names as scheme type variables until reset. */
    void variables(Set<String> names) {
        variables = Set.copyOf(names);
    }

    void scope(CompilationUnit unit) {
        var known = new LinkedHashSet<>(resolver.allRegisteredFqcns());
        known.addAll(TypeResolver.ledgerHashFqcns());
        resolver.setCurrentImportResolver(new ImportResolver(unit, known));
    }

    private List<LibraryType.Field> fields(RecordDeclaration record) {
        return record.getParameters().stream().map(p ->
                new LibraryType.Field(p.getNameAsString(), reference(p.getType()))).toList();
    }

    LibraryType.Reference reference(Type type) {
        if (type.isVoidType()) return ref("Unit", List.of());
        if (type.isArrayType() && type.asArrayType().getComponentType().asString().equals("byte"))
            return ref("Bytes", List.of());
        if (type.isPrimitiveType()) return switch(type.asString()) {
            case "boolean" -> ref("Bool", List.of());
            case "int", "long", "short", "byte", "char" -> ref("Int", List.of());
            default -> throw unsupported(type);
        };
        if (!type.isClassOrInterfaceType()) throw unsupported(type);
        var ct = type.asClassOrInterfaceType();
        if (ct.getScope().isEmpty() && variables.contains(ct.getNameAsString())) {
            if (ct.getTypeArguments().isPresent()) throw unsupported(type);
            return LibraryType.Reference.variable(ct.getNameAsString());
        }
        String name = ct.getNameWithScope();
        var args = ct.getTypeArguments().map(a -> a.stream().map(this::reference).toList()).orElse(List.of());
        String resolved = resolver.resolveClassName(name);
        // Resolve nominal identities before primitive aliases; never infer identity from erased PIR.
        if (resolver.resolveNameToType(name).isPresent()) {
            if (!args.isEmpty()) throw unsupported(type);
            if (!resolver.allRegisteredFqcns().contains(resolved) && !TypeResolver.ledgerHashFqcns().contains(resolved)) {
                var matches = resolver.allRegisteredFqcns().stream().filter(n -> n.equals(name) || n.endsWith("." + name)).toList();
                if (matches.size() != 1) throw unsupported(type);
                resolved = matches.getFirst();
            }
            return ref(resolved, args);
        }
        String mapped = switch(resolved) {
            case "BigInteger", "java.math.BigInteger", "Integer", "java.lang.Integer", "Long", "java.lang.Long" -> "Int";
            case "Boolean", "java.lang.Boolean" -> "Bool";
            case "String", "java.lang.String" -> "String";
            case "PlutusData", "org.julclang.core.PlutusData" -> "Data";
            case "List", "java.util.List", "JulcList", "org.julclang.core.types.JulcList" -> "List";
            case "Map", "java.util.Map", "JulcMap", "org.julclang.core.types.JulcMap" -> "Map";
            case "Optional", "java.util.Optional" -> "JulcOptional";
            case "JulcArray", "org.julclang.core.types.JulcArray" -> "Array";
            default -> throw unsupported(type);
        };
        int arity = switch(mapped) { case "List", "JulcOptional", "Array" -> 1; case "Map" -> 2; default -> 0; };
        if (args.size() != arity) throw new IllegalArgumentException("Raw or invalid container signature: " + type);
        return ref(mapped, args);
    }

    PirType representation(LibraryType.Reference ref) {
        return TypeReferences.representation(ref, types);
    }

    private static LibraryType.Reference ref(String name, List<LibraryType.Reference> args) {
        return new LibraryType.Reference(name, args);
    }
    private static IllegalArgumentException unsupported(Type type) {
        return new IllegalArgumentException("Unsupported on-chain source type: " + type);
    }
}
