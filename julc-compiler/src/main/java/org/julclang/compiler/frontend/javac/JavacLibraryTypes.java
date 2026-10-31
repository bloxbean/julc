package org.julclang.compiler.frontend.javac;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.LibraryTypeDescriptions;
import org.julclang.compiler.backend.LibraryType;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.resolve.TypeResolver;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * The Java library types a producer such as Elci sees ({@code LibraryType}, ADR-059), described from javac's model
 * (ADR-068 D7, D8, I5): the ledger's from the stubs through the metadata-only task when no source is given, and the
 * supplied units', as roots, otherwise. Identity is nominal; a ledger record's special layout comes from
 * {@link LedgerLayouts}, by FQCN, a source record's from its declared {@code toPlutusData} (I11).
 */
public final class JavacLibraryTypes {

    /** The described types and the named definitions behind them. */
    public record Described(Map<String, LibraryType> types, Map<String, PirType> definitions) {
    }

    private static final String ONCHAIN_LIBRARY = "org.julclang.stdlib.annotation.OnchainLibrary";
    private static final String CONSTR_DATA = "org.julclang.core.PlutusData.ConstrData";

    private final JavacFacts facts;

    private JavacLibraryTypes(JavacFacts facts) {
        this.facts = facts;
    }

    /** The types of {@code sources}, and the ledger's. */
    public static Described describe(List<String> sources) {
        if (sources.isEmpty()) {
            var metadata = JavacGate.metadata(new CompilerOptions().setJavacGate(JavacGateMode.ENFORCE));
            return metadata.read((elements, types) -> {
                var facts = JavacFacts.of(elements, types, new TypeResolver());
                var registrar = new JavacTypeRegistrar(facts);
                registrar.registerLedger(JavacFrontend.shared().platform());
                return new JavacLibraryTypes(facts).describe(registrar);
            });
        }
        var program = JavacProgram.analyze(sources.stream().map(JavacFrontend.Unit::of).toList(), List.of());
        program.roots().forEach(unit -> requireLibraries(program.facts(), unit));
        return new JavacLibraryTypes(program.facts()).describe(program.declarations());
    }

    /** Only {@code @OnchainLibrary} classes can be exported, as in the source path. */
    private static void requireLibraries(JavacFacts facts, CompilationUnitTree unit) {
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitClass(ClassTree tree, Void unused) {
                var element = (TypeElement) facts.element(new TreePath(getCurrentPath(), tree));
                if (tree.getKind() == Tree.Kind.CLASS && !JavacTypeRegistrar.hasAnnotation(element, ONCHAIN_LIBRARY)) {
                    throw new IllegalArgumentException("Only @OnchainLibrary sources can be exported: "
                            + JavacFacts.identity(element));
                }
                return super.visitClass(tree, null);
            }
        }.scan(unit, null);
    }

    private Described describe(JavacTypeRegistrar declared) {
        var registry = facts.registry();
        var registered = registry.registry();
        var records = new LinkedHashMap<String, JavacTypeRegistrar.Declared>();
        for (var record : declared.records()) records.put(JavacFacts.identity(record.element()), record);
        var types = new LinkedHashMap<String, LibraryType>();
        var special = new LinkedHashSet<String>();
        records.forEach((identity, record) -> {
            var element = record.element();
            if (!element.getTypeParameters().isEmpty()) {
                throw new IllegalArgumentException("Generic record type is not yet supported by library descriptions: "
                        + identity);
            }
            var representation = registry.lookupNominal(identity).orElseThrow();
            var fields = fields(element);
            boolean newType = registered.newTypes().containsKey(identity);
            boolean regular = representation instanceof PirType.RecordType r
                    && r.fields().size() == fields.size()
                    && IntStream.range(0, fields.size()).allMatch(i -> r.fields().get(i).name().equals(fields.get(i).name()));
            if (!constructorData(record, identity)) regular = false;
            boolean variant = element.getEnclosingElement().getKind() == ElementKind.INTERFACE;
            if (!regular && !newType) special.add(identity);
            types.put(identity, new LibraryType(identity, representation, newType,
                    !variant && (regular || newType) ? fields : List.of()));
        });
        for (var sum : declared.sealedInterfaces()) {
            String identity = JavacFacts.identity(sum.element());
            if (!(registry.lookupNominal(identity).orElse(null) instanceof PirType.SumType representation)) continue;
            var constructors = new ArrayList<LibraryType.Constructor>();
            for (var constructor : representation.constructors()) {
                var candidates = records.entrySet().stream().filter(e ->
                        e.getValue().element().getSimpleName().contentEquals(constructor.name())
                                && e.getValue().element().getInterfaces().stream()
                                        .anyMatch(parent -> reference(parent).name().equals(identity))).toList();
                if (candidates.size() != 1) {
                    throw new IllegalArgumentException("Cannot identify source constructor " + identity + "."
                            + constructor.name());
                }
                var record = candidates.getFirst();
                constructors.add(new LibraryType.Constructor(constructor.name(), constructor.tag(),
                        fields(record.getValue().element())));
                // A sum variant must not advertise independent tag-zero record operations.
                types.remove(record.getKey());
            }
            types.put(identity, new LibraryType(identity, representation, false, List.of(), constructors));
        }
        for (String identity : TypeResolver.ledgerHashFqcns()) {
            types.put(identity, new LibraryType(identity, new PirType.ByteStringType(), true,
                    List.of(new LibraryType.Field("hash", ref("Bytes")))));
        }
        LibraryTypeDescriptions.deriveOperations(types, special, registry.namedDefinitions());
        return new Described(java.util.Collections.unmodifiableMap(types), Map.copyOf(registry.namedDefinitions()));
    }

    /**
     * Whether a record's Data is a constructor. A ledger record's layout is keyed by FQCN, never inferred from stub
     * metadata (I11); a source record says so by the {@code toPlutusData} it declares.
     */
    private static boolean constructorData(JavacTypeRegistrar.Declared record, String identity) {
        if (record.tree() == null) return !LedgerLayouts.NOT_CONSTRUCTOR_DATA.contains(identity);
        return ElementFilter.methodsIn(record.element().getEnclosedElements()).stream()
                .filter(method -> method.getSimpleName().contentEquals("toPlutusData"))
                .allMatch(method -> method.getReturnType() instanceof DeclaredType declared
                        && JavacFacts.identity((TypeElement) declared.asElement()).equals(CONSTR_DATA));
    }

    private List<LibraryType.Field> fields(TypeElement record) {
        return record.getRecordComponents().stream().map(component ->
                new LibraryType.Field(component.getSimpleName().toString(), reference(component.asType()))).toList();
    }

    private LibraryType.Reference reference(TypeMirror type) {
        return switch (type.getKind()) {
            case VOID -> ref("Unit");
            case BOOLEAN -> ref("Bool");
            case INT, LONG, SHORT, BYTE, CHAR -> ref("Int");
            case ARRAY -> {
                if (((ArrayType) type).getComponentType().getKind() != TypeKind.BYTE) throw unsupported(type);
                yield ref("Bytes");
            }
            case DECLARED -> declared((DeclaredType) type);
            default -> throw unsupported(type);
        };
    }

    private LibraryType.Reference declared(DeclaredType type) {
        var arguments = type.getTypeArguments().stream().map(this::reference).toList();
        String identity = JavacFacts.identity((TypeElement) type.asElement());
        // Nominal identities before primitive aliases; identity is never inferred from erased PIR.
        if (facts.registry().lookupNominal(identity).isPresent()) {
            if (!arguments.isEmpty()) throw unsupported(type);
            return new LibraryType.Reference(identity, arguments);
        }
        String mapped = switch (identity) {
            case "java.math.BigInteger", "java.lang.Integer", "java.lang.Long" -> "Int";
            case "java.lang.Boolean" -> "Bool";
            case "java.lang.String" -> "String";
            case "org.julclang.core.PlutusData" -> "Data";
            case "java.util.List", "org.julclang.core.types.JulcList" -> "List";
            case "java.util.Map", "org.julclang.core.types.JulcMap" -> "Map";
            case "java.util.Optional" -> "JulcOptional";
            case "org.julclang.core.types.JulcArray" -> "Array";
            default -> throw unsupported(type);
        };
        int arity = switch (mapped) {
            case "List", "JulcOptional", "Array" -> 1;
            case "Map" -> 2;
            default -> 0;
        };
        if (arguments.size() != arity) {
            throw new IllegalArgumentException("Raw or invalid container signature: " + type);
        }
        return new LibraryType.Reference(mapped, arguments);
    }

    private static LibraryType.Reference ref(String name) {
        return new LibraryType.Reference(name, List.of());
    }

    private static IllegalArgumentException unsupported(TypeMirror type) {
        return new IllegalArgumentException("Unsupported on-chain source type: " + type);
    }
}
