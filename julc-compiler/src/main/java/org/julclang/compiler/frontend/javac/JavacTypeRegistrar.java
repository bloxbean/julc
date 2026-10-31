package org.julclang.compiler.frontend.javac;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerTypeDiagnostics;
import org.julclang.compiler.error.CompilerDiagnostic;
import org.julclang.compiler.error.DiagnosticCodes;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.resolve.TypeRegistrar;
import org.julclang.compiler.resolve.TypeResolver;
import org.julclang.core.source.SourceLocation;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
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

/**
 * Registers records, sealed interfaces, {@code @NewType} and ledger types from javac's model (ADR-068 D7, I11). The
 * ledger comes from the signature stubs, so no ledger source is parsed; everything else from the units javac
 * attributed. Types are registered by identity into the same {@link TypeResolver} registries the JavaParser frontend
 * fills, and in the same order (dependencies first, by {@link TypeRegistrar#dependencyGroups}), so a recursive group's
 * references come out alike.
 * <p>
 * Tags (I11): a source interface's follow its {@code permits} clause; without one, the records nested in it in
 * {@code ClassTree.getMembers()} order, and javac's permitted subclasses must be exactly those. A class-file
 * interface's follow its {@code PermittedSubclasses} attribute.
 */
final class JavacTypeRegistrar {

    private static final String NEW_TYPE = "org.julclang.stdlib.annotation.NewType";

    /** A record or sealed interface, with its tree when it comes from source. */
    record Declared(TypeElement element, ClassTree tree, CompilationUnitTree unit) {
    }

    private final JavacFacts facts;
    private final TypeResolver registry;
    private final List<Declared> records = new ArrayList<>();
    private final List<Declared> interfaces = new ArrayList<>();

    JavacTypeRegistrar(JavacFacts facts) {
        this.facts = facts;
        this.registry = facts.registry();
    }

    /** Registers every ledger type of the stubs, in the order of the ledger's sources. */
    void registerLedger(JavacPlatform platform) {
        var records = new LinkedHashMap<String, Declared>();
        var sealed = new LinkedHashMap<String, Declared>();
        for (String name : platform.stubTopLevelNames()) {
            if (!name.startsWith(LedgerLayouts.PACKAGE + ".") || TypeResolver.ledgerHashFqcns().contains(name)) continue;
            collect(facts.elements().getTypeElement(name), records, sealed);
        }
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
        // getRecordComponents() does not complete a class reached through another; getTypeElement does.
        TypeElement complete = facts.elements().getTypeElement(type.getQualifiedName());
        if (complete.getKind() == ElementKind.RECORD) records.put(JavacFacts.identity(complete), new Declared(complete, null, null));
        if (isSealedInterface(complete)) sealed.put(JavacFacts.identity(complete), new Declared(complete, null, null));
        for (TypeElement nested : ElementFilter.typesIn(complete.getEnclosedElements())) collect(nested, records, sealed);
    }

    /** Registers the records and sealed interfaces of {@code units}, at any depth, in source order. */
    void registerSources(List<CompilationUnitTree> units) {
        var records = new LinkedHashMap<String, Declared>();
        var sealed = new LinkedHashMap<String, Declared>();
        for (CompilationUnitTree unit : units) {
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitClass(ClassTree tree, Void unused) {
                    var element = (TypeElement) facts.element(new TreePath(getCurrentPath(), tree));
                    var declared = new Declared(element, tree, unit);
                    // Only local types can share an identity: javac rejects every other duplicate.
                    if (element.getKind() == ElementKind.RECORD && records.putIfAbsent(JavacFacts.identity(element), declared) != null) {
                        throw errorAt(declared, "Duplicate record type '" + JavacFacts.identity(element)
                                + "' found across compilation units");
                    }
                    if (isSealedInterface(element)) sealed.put(JavacFacts.identity(element), declared);
                    return super.visitClass(tree, null);
                }
            }.scan(unit, null);
        }
        register(records, sealed);
    }

    private static boolean isSealedInterface(TypeElement type) {
        return type.getKind() == ElementKind.INTERFACE && type.getModifiers().contains(Modifier.SEALED);
    }

    private void register(Map<String, Declared> records, Map<String, Declared> sealed) {
        this.records.addAll(records.values());
        this.interfaces.addAll(sealed.values());
        var variants = new LinkedHashMap<String, List<TypeElement>>();
        sealed.forEach((identity, declared) -> variants.put(identity, variants(declared)));

        var names = new LinkedHashSet<>(records.keySet());
        names.addAll(sealed.keySet());
        var deps = new LinkedHashMap<String, Set<String>>();
        records.forEach((identity, declared) -> {
            var found = new LinkedHashSet<String>();
            for (RecordComponentElement component : declared.element().getRecordComponents()) {
                dependencies(component.asType(), names, found);
            }
            deps.put(identity, found);
        });
        sealed.forEach((identity, declared) -> {
            var found = new LinkedHashSet<String>();
            for (TypeElement variant : variants.get(identity)) {
                String name = JavacFacts.identity(variant);
                if (names.contains(name)) found.add(name);
            }
            deps.put(identity, found);
        });

        for (var group : TypeRegistrar.dependencyGroups(deps)) {
            String first = group.getFirst();
            if (group.size() == 1 && !deps.getOrDefault(first, Set.of()).contains(first)) {
                register(first, records, sealed, variants);
                continue;
            }
            registerRecursive(group, records, sealed, variants);
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
            var unproductive = TypeRegistrar.unproductive(group, registry);
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
        var ordered = variants.get(identity).stream().map(v -> new TypeResolver.SealedVariant(
                v.getSimpleName().toString(), JavacFacts.identity(v))).toList();
        registry.registerSealed(element.getSimpleName().toString(), identity, ordered,
                !element.getTypeParameters().isEmpty());
    }

    private void registerRecord(String identity, Declared declared) {
        var element = declared.element();
        String name = element.getSimpleName().toString();
        var components = element.getRecordComponents();
        if (isNewType(element)) {
            if (components.size() != 1) {
                throw errorAt(declared, "@NewType record '" + name + "' must have exactly 1 field, got " + components.size());
            }
            PirType underlying = underlying(components.getFirst().asType());
            if (underlying == null) {
                throw errorAt(declared, "@NewType record '" + name + "' field type '" + written(declared, 0) + "' is not"
                        + " supported. Supported types: byte[], BigInteger, String, boolean");
            }
            registry.registerNewType(name, identity, underlying);
            return;
        }
        requireNoByteVarargs(element, components);
        var fields = new ArrayList<PirType.Field>();
        for (int i = 0; i < components.size(); i++) {
            String component = components.get(i).getSimpleName().toString();
            SourceLocation where = declared.tree() == null ? null : facts.location(declared.unit(), componentType(declared, i));
            PirType type = facts.pirType(components.get(i).asType(), where);
            var array = PirType.nativeArrayIn(type);
            if (array != null) {
                throw CompilerTypeDiagnostics.nativeArrayNotDataEncodable(
                        "Component '" + component + "' of record " + name, array, where);
            }
            fields.add(new PirType.Field(component, type));
        }
        registry.registerRecord(name, identity, fields);
    }

    /**
     * {@code byte...}: javac's type is {@code byte[]}, but a varargs constructor is not a {@code byte[]} component, and
     * the source path rejects it as a {@code byte}. The subset does not widen (I2). Any other varargs component is an
     * array, which is rejected as one.
     */
    private static void requireNoByteVarargs(TypeElement record, List<? extends RecordComponentElement> components) {
        boolean varargs = ElementFilter.constructorsIn(record.getEnclosedElements()).stream()
                .anyMatch(constructor -> constructor.isVarArgs() && constructor.getParameters().size() == components.size());
        if (varargs && components.getLast().asType() instanceof ArrayType array
                && array.getComponentType().getKind() == TypeKind.BYTE) {
            throw new CompilerException("Unsupported primitive type: byte. Supported types: boolean, int, long");
        }
    }

    /** {@code byte[]}, {@code BigInteger}, {@code String} or {@code boolean}, by identity; otherwise null. */
    private static PirType underlying(TypeMirror type) {
        if (type.getKind() == TypeKind.BOOLEAN) return new PirType.BoolType();
        if (type instanceof ArrayType array) {
            return array.getComponentType().getKind() == TypeKind.BYTE ? new PirType.ByteStringType() : null;
        }
        if (type instanceof DeclaredType declared) {
            return switch (JavacFacts.identity((TypeElement) declared.asElement())) {
                case "java.math.BigInteger" -> new PirType.IntegerType();
                case "java.lang.String" -> new PirType.StringType();
                default -> null;
            };
        }
        return null;
    }

    private static boolean isNewType(TypeElement record) {
        return hasAnnotation(record, NEW_TYPE);
    }

    static boolean hasAnnotation(TypeElement type, String annotation) {
        for (AnnotationMirror mirror : type.getAnnotationMirrors()) {
            if (JavacFacts.identity((TypeElement) mirror.getAnnotationType().asElement()).equals(annotation)) return true;
        }
        return false;
    }

    /** The types a record component names: the base type and its type arguments, at any depth. */
    private void dependencies(TypeMirror type, Set<String> names, Set<String> found) {
        if (!(type instanceof DeclaredType declared)) return;
        String name = JavacFacts.identity((TypeElement) declared.asElement());
        if (names.contains(name)) found.add(name);
        for (TypeMirror argument : declared.getTypeArguments()) dependencies(argument, names, found);
    }

    /** {@code declared}'s variants, in tag order (I11). */
    private List<TypeElement> variants(Declared declared) {
        var element = declared.element();
        var permitted = element.getPermittedSubclasses().stream().map(facts::typeElement).map(this::complete).toList();
        if (declared.tree() == null) {
            requireFixedTags(element, permitted);
            return permitted;
        }
        if (!declared.tree().getPermitsClause().isEmpty()) return permitted;
        var nested = new ArrayList<TypeElement>();
        for (Tree member : declared.tree().getMembers()) {
            if (member instanceof ClassTree tree && tree.getKind() == Tree.Kind.RECORD) {
                var record = (TypeElement) facts.element(TreePath.getPath(declared.unit(), tree));
                if (directlyImplements(record, element)) nested.add(record);
            }
        }
        if (!nested.equals(permitted)) {
            throw permitsRequired(declared, permitted);
        }
        return nested;
    }

    private TypeElement complete(TypeElement type) {
        return type.getQualifiedName().isEmpty() ? type : facts.elements().getTypeElement(type.getQualifiedName());
    }

    private boolean directlyImplements(TypeElement record, TypeElement parent) {
        return record.getInterfaces().stream().anyMatch(i -> facts.typeElement(i).equals(parent));
    }

    /** A ledger tag the ledger fixes is JuLC's (I11): a stub that disagrees is a broken build, not a new encoding. */
    private void requireFixedTags(TypeElement element, List<TypeElement> permitted) {
        var fixed = LedgerLayouts.FIXED_TAGS.get(JavacFacts.identity(element));
        if (fixed == null) return;
        var names = permitted.stream().map(v -> v.getSimpleName().toString()).toList();
        if (!fixed.equals(names)) {
            throw new BrokenJulcBuildException("the javac stub of " + element + " permits " + names + ", not " + fixed);
        }
    }

    private CompilerException permitsRequired(Declared declared, List<TypeElement> permitted) {
        var info = DiagnosticCodes.SEALED_PERMITS_REQUIRED;
        var location = facts.location(declared.unit(), declared.tree());
        var names = permitted.stream().map(v -> v.getSimpleName().toString()).toList();
        return new CompilerException(List.of(new CompilerDiagnostic(CompilerDiagnostic.Level.ERROR,
                info.format(declared.element().getSimpleName(), String.join(", ", names)), location.fileName(),
                location.line(), location.column(), info.fix(), info.code())));
    }

    /** The {@code index}-th component of a source record, as javac's tree holds it. */
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
        return declared.tree() == null ? declared.element().getRecordComponents().get(component).asType().toString()
                : componentType(declared, component).toString();
    }

    /** A rejection at {@code declared}, as the source path anchors one: its declaration's start. */
    private CompilerException errorAt(Declared declared, String message) {
        if (declared.tree() == null) throw new BrokenJulcBuildException("the ledger stubs break a type rule: " + message);
        var location = facts.location(declared.unit(), declared.tree());
        return new CompilerException(List.of(new CompilerDiagnostic(CompilerDiagnostic.Level.ERROR, message,
                location.fileName(), location.line(), location.column())));
    }
}
