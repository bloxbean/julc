package org.julclang.compiler.frontend.javac;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreeScanner;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerTypeDiagnostics;
import org.julclang.compiler.error.CompilerDiagnostic;
import org.julclang.compiler.error.DiagnosticCodes;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.resolve.TypeGroups;
import org.julclang.compiler.resolve.TypeRegistry;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.RecordComponentElement;
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
import java.util.Set;
import java.util.TreeMap;

/**
 * Registers records, sealed interfaces, {@code @NewType} and ledger types from javac's model (ADR-068 D7, I11). The
 * ledger comes from the signature stubs, so no ledger source is parsed; everything else from the units javac
 * attributed. Types are registered by identity into a {@link TypeRegistry}, in the JavaParser frontend's order
 * (dependencies first, by {@link TypeGroups#dependencyGroups}), so a recursive group's references come out alike.
 * <p>
 * Tags (I11): a source interface's follow its {@code permits} clause; without one, the records nested in it in
 * {@code ClassTree.getMembers()} order, counting every nested record whose {@code implements} clause names a type of
 * the interface's simple name, as the JavaParser frontend counts them; and javac's permitted subclasses must be exactly
 * that list, or {@code JULC0092}. A class-file interface's follow its {@code PermittedSubclasses} attribute.
 */
final class JavacTypeRegistrar {

    private static final String NEW_TYPE = "org.julclang.stdlib.annotation.NewType";

    /** A record or sealed interface, with its tree when it comes from source. */
    record Declared(TypeElement element, ClassTree tree) {
    }

    private final JavacFacts facts;
    private final JavacPlatform platform;
    private final TypeRegistry registry;
    private final List<Declared> records = new ArrayList<>();
    private final List<Declared> interfaces = new ArrayList<>();

    JavacTypeRegistrar(JavacFacts facts, JavacPlatform platform) {
        this.facts = facts;
        this.platform = platform;
        this.registry = facts.registry();
    }

    /**
     * Registers every ledger type of the stubs, in the order of the ledger's sources. The package's members are listed,
     * not looked up by name: a name goes through {@code Elements.getTypeElement}, where a user's {@code class org}
     * obscures the package.
     */
    void registerLedger() {
        var ledger = facts.elements().getPackageElement(LedgerLayouts.PACKAGE);
        if (ledger == null) throw new BrokenJulcBuildException("the javac stubs do not define " + LedgerLayouts.PACKAGE);
        var topLevel = new TreeMap<String, TypeElement>();
        for (TypeElement type : ElementFilter.typesIn(ledger.getEnclosedElements())) {
            String identity = facts.identity(type);
            if (!TypeRegistry.ledgerHashFqcns().contains(identity)) topLevel.put(identity, type);
        }
        var records = new LinkedHashMap<String, Declared>();
        var sealed = new LinkedHashMap<String, Declared>();
        topLevel.values().forEach(type -> collect(type, records, sealed));
        register(records, sealed);
    }

    /** The records registered so far, in declaration order: the ledger's, then the units'. */
    List<Declared> records() {
        return records;
    }

    /** The sealed interfaces registered so far, in declaration order. */
    List<Declared> sealedInterfaces() {
        return interfaces;
    }

    private void collect(TypeElement type, Map<String, Declared> records, Map<String, Declared> sealed) {
        if (type.getKind() == ElementKind.RECORD) records.put(facts.identity(type), new Declared(type, null));
        if (isSealedInterface(type)) sealed.put(facts.identity(type), new Declared(type, null));
        for (TypeElement nested : ElementFilter.typesIn(type.getEnclosedElements())) collect(nested, records, sealed);
    }

    /** Registers the records and sealed interfaces of {@code units}, at any depth, in source order. */
    void registerSources(List<CompilationUnitTree> units) {
        var records = new LinkedHashMap<String, Declared>();
        var sealed = new LinkedHashMap<String, Declared>();
        for (CompilationUnitTree unit : units) {
            new TreeScanner<Void, Void>() {
                @Override
                public Void visitClass(ClassTree tree, Void unused) {
                    var element = (TypeElement) facts.element(tree);
                    requireNoApiIdentity(element, tree);
                    var declared = new Declared(element, tree);
                    if (element.getKind() == ElementKind.RECORD) records.put(facts.identity(element), declared);
                    if (isSealedInterface(element)) sealed.put(facts.identity(element), declared);
                    return super.visitClass(tree, null);
                }
            }.scan(unit, null);
        }
        register(records, sealed);
    }

    /**
     * {@code JULC0081} at any depth: {@code Ownership} checks top-level names before javac runs, but a nested class of
     * a user's {@code class java} can take the qualified name of an API type too. No source replaces one (D3).
     */
    private void requireNoApiIdentity(TypeElement type, ClassTree tree) {
        if (type.getNestingKind() == NestingKind.TOP_LEVEL || !platform.declares(facts.identity(type))) return;
        var info = DiagnosticCodes.LEDGER_TYPE_REDECLARED;
        var at = facts.location(tree);
        throw new CompilerException(List.of(new CompilerDiagnostic(CompilerDiagnostic.Level.ERROR,
                info.format(at.fileName(), facts.identity(type), "API"), at.fileName(), at.line(), at.column(),
                "Rename the type or the class around it: a name of JuLC's API or of the platform means that type (ADR-068 D3)",
                info.code())));
    }

    private static boolean isSealedInterface(TypeElement type) {
        return type.getKind() == ElementKind.INTERFACE && type.getModifiers().contains(Modifier.SEALED);
    }

    private void register(Map<String, Declared> records, Map<String, Declared> sealed) {
        this.records.addAll(records.values());
        this.interfaces.addAll(sealed.values());
        var variants = new LinkedHashMap<String, List<TypeElement>>();
        sealed.forEach((identity, declared) -> variants.put(identity, variants(declared)));
        var nonRecordVariants = new LinkedHashSet<String>();
        variants.values().forEach(list -> list.forEach(variant -> {
            if (variant.getKind() != ElementKind.RECORD) nonRecordVariants.add(facts.identity(variant));
        }));
        requireDistinctIdentities(records.keySet(), sealed.keySet(), nonRecordVariants);

        var names = new LinkedHashSet<>(records.keySet());
        names.addAll(sealed.keySet());
        var deps = new LinkedHashMap<String, Set<String>>();
        records.forEach((identity, declared) -> {
            var found = new LinkedHashSet<String>();
            for (RecordComponentElement component : facts.components(declared.element())) {
                dependencies(component.asType(), names, found);
            }
            deps.put(identity, found);
        });
        sealed.forEach((identity, declared) -> {
            var found = new LinkedHashSet<String>();
            for (TypeElement variant : variants.get(identity)) {
                String name = facts.identity(variant);
                if (names.contains(name)) found.add(name);
            }
            deps.put(identity, found);
        });

        for (var group : TypeGroups.dependencyGroups(deps)) {
            String first = group.getFirst();
            if (group.size() == 1 && !deps.getOrDefault(first, Set.of()).contains(first)) {
                register(first, records, sealed, variants);
                continue;
            }
            registerRecursive(group, records, sealed, variants);
        }
    }

    /**
     * The registries hold one definition per identity, and a record found first would silently take the place of what
     * else has its identity (F801). {@link JavacFacts#identity} keeps identities apart, so this is the invariant's
     * check: the source must not decide whether it holds.
     */
    static void requireDistinctIdentities(Set<String> records, Set<String> sealed, Set<String> nonRecordVariants) {
        for (String identity : records) {
            if (sealed.contains(identity) || nonRecordVariants.contains(identity)) {
                throw new CompilerException("Type identity '" + identity + "' names a record and another declaration");
            }
        }
    }

    /** A recursive group: its identities are published first, and the variant records complete before their sums. */
    private void registerRecursive(List<String> group, Map<String, Declared> records, Map<String, Declared> sealed,
                                   Map<String, List<TypeElement>> variants) {
        try {
            for (String identity : group) {
                Declared declared = records.containsKey(identity) ? records.get(identity) : sealed.get(identity);
                if (records.containsKey(identity) && isNewType(declared.element())) {
                    throw errorAt(declared, "@NewType cannot be recursive: '" + identity + "'");
                }
                registry.predeclareNamedType(identity, declared.element().getSimpleName().toString(),
                        records.containsKey(identity) ? PirType.NamedKind.RECORD : PirType.NamedKind.SUM);
            }
            for (String identity : group) if (records.containsKey(identity)) register(identity, records, sealed, variants);
            for (String identity : group) if (sealed.containsKey(identity)) register(identity, records, sealed, variants);
            var unproductive = TypeGroups.unproductive(group, registry);
            if (!unproductive.isEmpty()) {
                String first = unproductive.getFirst();
                throw errorAt(records.containsKey(first) ? records.get(first) : sealed.get(first),
                        "Recursive type dependency has no finite base constructor: " + unproductive);
            }
        } catch (RuntimeException e) {
            registry.discardForwardTypes(group);
            throw e;
        }
    }

    private void register(String identity, Map<String, Declared> records, Map<String, Declared> sealed,
                          Map<String, List<TypeElement>> variants) {
        if (records.containsKey(identity)) {
            registerRecord(identity, records.get(identity));
            return;
        }
        var declared = sealed.get(identity);
        var element = declared.element();
        var ordered = variants.get(identity).stream().map(v -> new TypeRegistry.SealedVariant(
                v.getSimpleName().toString(), facts.identity(v))).toList();
        registry.registerSealed(element.getSimpleName().toString(), identity, ordered,
                !element.getTypeParameters().isEmpty());
    }

    private void registerRecord(String identity, Declared declared) {
        var element = declared.element();
        String name = element.getSimpleName().toString();
        var components = facts.components(element);
        boolean byteVarargs = hasByteVarargs(element, components);
        if (isNewType(element)) {
            if (components.size() != 1) {
                throw errorAt(declared, "@NewType record '" + name + "' must have exactly 1 field, got " + components.size());
            }
            PirType underlying = byteVarargs ? null : underlying(components.getFirst().asType());
            if (underlying == null) {
                throw errorAt(declared, "@NewType record '" + name + "' field type '"
                        + (byteVarargs ? "byte" : written(declared, 0)) + "' is not supported."
                        + " Supported types: byte[], BigInteger, String, boolean");
            }
            registry.registerNewType(name, identity, underlying);
            return;
        }
        if (byteVarargs) {
            throw facts.rejection("Unsupported primitive type: byte. Supported types: boolean, int, long",
                    componentType(declared, components.size() - 1));
        }
        var fields = new ArrayList<PirType.Field>();
        for (int i = 0; i < components.size(); i++) {
            String component = components.get(i).getSimpleName().toString();
            Tree where = declared.tree() == null ? null : componentType(declared, i);
            PirType type = facts.pirType(components.get(i).asType(), where);
            var array = PirType.nativeArrayIn(type);
            if (array != null) {
                throw CompilerTypeDiagnostics.nativeArrayNotDataEncodable("Component '" + component + "' of record " + name,
                        array, where == null ? null : facts.location(where));
            }
            fields.add(new PirType.Field(component, type));
        }
        registry.registerRecord(name, identity, fields);
    }

    /**
     * {@code byte...} as the canonical constructor's last parameter. javac's type is {@code byte[]}, but a call passes
     * its arguments one by one, and the JavaParser frontend rejects it as a {@code byte}, so the subset does not widen
     * (I2). Any other varargs component is an array, and rejected as one. A varargs constructor that is not the
     * canonical one says nothing about the components.
     */
    private boolean hasByteVarargs(TypeElement record, List<? extends RecordComponentElement> components) {
        return !components.isEmpty() && ElementFilter.constructorsIn(record.getEnclosedElements()).stream()
                .anyMatch(constructor -> facts.elements().isCanonicalConstructor(constructor) && constructor.isVarArgs())
                && components.getLast().asType() instanceof ArrayType array
                && array.getComponentType().getKind() == TypeKind.BYTE;
    }

    /** {@code byte[]}, {@code BigInteger}, {@code String} or {@code boolean}, by identity; otherwise null. */
    private PirType underlying(TypeMirror type) {
        if (type.getKind() == TypeKind.BOOLEAN) return new PirType.BoolType();
        if (type instanceof ArrayType array) {
            return array.getComponentType().getKind() == TypeKind.BYTE ? new PirType.ByteStringType() : null;
        }
        if (type instanceof DeclaredType declared) {
            return switch (facts.identity((TypeElement) declared.asElement())) {
                case "java.math.BigInteger" -> new PirType.IntegerType();
                case "java.lang.String" -> new PirType.StringType();
                default -> null;
            };
        }
        return null;
    }

    private boolean isNewType(TypeElement record) {
        return facts.hasAnnotation(record, NEW_TYPE);
    }

    /** The types a record component names: the base type and its type arguments, at any depth. */
    private void dependencies(TypeMirror type, Set<String> names, Set<String> found) {
        if (!(type instanceof DeclaredType declared)) return;
        String name = facts.identity((TypeElement) declared.asElement());
        if (names.contains(name)) found.add(name);
        for (TypeMirror argument : declared.getTypeArguments()) dependencies(argument, names, found);
    }

    /** {@code declared}'s variants, in tag order (I11). */
    private List<TypeElement> variants(Declared declared) {
        var element = declared.element();
        var permitted = facts.permitted(element);
        if (declared.tree() == null) {
            requireFixedTags(facts.identity(element), permitted.stream().map(v -> v.getSimpleName().toString()).toList());
            return permitted;
        }
        if (!declared.tree().getPermitsClause().isEmpty()) return permitted;
        var nested = new ArrayList<TypeElement>();
        for (Tree member : declared.tree().getMembers()) {
            if (member instanceof ClassTree tree && tree.getKind() == Tree.Kind.RECORD) {
                var record = (TypeElement) facts.element(tree);
                if (namesParent(record, element)) nested.add(record);
            }
        }
        if (!nested.equals(permitted)) throw permitsRequired(declared, permitted);
        return nested;
    }

    /**
     * Whether {@code record} implements a type of {@code parent}'s simple name. Not {@code parent} itself: a nested
     * record implementing another {@code S} has a tag on the JavaParser frontend, so it must not drop out here and shift
     * the others silently. It makes the list differ from javac's, which is {@code JULC0092} (F800).
     */
    private boolean namesParent(TypeElement record, TypeElement parent) {
        return record.getInterfaces().stream()
                .anyMatch(i -> facts.typeElement(i).getSimpleName().contentEquals(parent.getSimpleName()));
    }

    /** A tag the ledger fixes is JuLC's (I11): a stub that disagrees is a broken build, not a new encoding. */
    static void requireFixedTags(String identity, List<String> permitted) {
        var fixed = LedgerLayouts.FIXED_TAGS.get(identity);
        if (fixed != null && !fixed.equals(permitted)) {
            throw new BrokenJulcBuildException("the javac stub of " + identity + " permits " + permitted + ", not " + fixed);
        }
    }

    private CompilerException permitsRequired(Declared declared, List<TypeElement> permitted) {
        var info = DiagnosticCodes.SEALED_PERMITS_REQUIRED;
        var location = facts.location(declared.tree());
        var names = permitted.stream().map(v -> v.getSimpleName().toString()).toList();
        return new CompilerException(List.of(new CompilerDiagnostic(CompilerDiagnostic.Level.ERROR,
                info.format(declared.element().getSimpleName(), String.join(", ", names)), location.fileName(),
                location.line(), location.column(), info.fix(), info.code())));
    }

    /** The {@code index}-th component's type tree in a source record, or the record if javac's tree has none. */
    private Tree componentType(Declared declared, int index) {
        int seen = 0;
        for (Tree member : declared.tree().getMembers()) {
            if (member instanceof VariableTree variable && !variable.getModifiers().getFlags().contains(Modifier.STATIC)) {
                if (seen++ == index) return variable.getType();
            }
        }
        return declared.tree();
    }

    private String written(Declared declared, int component) {
        if (declared.tree() == null) return facts.components(declared.element()).get(component).asType().toString();
        return facts.location(componentType(declared, component)).fragment();
    }

    /** A rejection at {@code declared}, as the JavaParser frontend anchors one: its declaration's start. */
    private CompilerException errorAt(Declared declared, String message) {
        if (declared.tree() == null) throw new BrokenJulcBuildException("the ledger stubs break a type rule: " + message);
        return facts.rejection(message, declared.tree());
    }
}
