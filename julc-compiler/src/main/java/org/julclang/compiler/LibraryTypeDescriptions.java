package org.julclang.compiler;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.type.Type;
import org.julclang.compiler.backend.LibraryType;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.resolve.ImportResolver;
import org.julclang.compiler.resolve.TypeResolver;

import java.util.*;

/** Source identity and capabilities derived alongside the existing encoding planner. */
final class LibraryTypeDescriptions {
    private final TypeResolver resolver;
    private final Map<String, RecordDeclaration> records = new LinkedHashMap<>();
    private final Map<String, LibraryType> types = new LinkedHashMap<>();

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
    }

    Map<String, LibraryType> types() { return Collections.unmodifiableMap(types); }

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
            default -> throw unsupported(type);
        };
        int arity = switch(mapped) { case "List", "JulcOptional" -> 1; case "Map" -> 2; default -> 0; };
        if (args.size() != arity) throw new IllegalArgumentException("Raw or invalid container signature: " + type);
        return ref(mapped, args);
    }

    PirType representation(LibraryType.Reference ref) {
        if (types.containsKey(ref.name())) return types.get(ref.name()).representation();
        var args = ref.arguments();
        return switch (ref.name()) {
            case "Int" -> new PirType.IntegerType();
            case "Bool" -> new PirType.BoolType();
            case "String" -> new PirType.StringType();
            case "Bytes" -> new PirType.ByteStringType();
            case "Unit" -> new PirType.UnitType();
            case "Data" -> new PirType.DataType();
            case "List" -> new PirType.ListType(representation(args.getFirst()));
            case "Map" -> new PirType.MapType(representation(args.get(0)), representation(args.get(1)));
            case "JulcOptional" -> new PirType.OptionalType(representation(args.getFirst()));
            default -> throw new IllegalArgumentException("No source type description for " + ref.name());
        };
    }

    private static LibraryType.Reference ref(String name, List<LibraryType.Reference> args) {
        return new LibraryType.Reference(name, args);
    }
    private static IllegalArgumentException unsupported(Type type) {
        return new IllegalArgumentException("Unsupported on-chain source type: " + type);
    }
}
