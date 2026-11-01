package org.julclang.compiler.frontend.javac.subset;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.InstanceOfTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.util.TreeScanner;
import org.julclang.compiler.CompilerTypeDiagnostics;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.frontend.javac.JavacNames;
import org.julclang.compiler.resolve.TypeRegistry;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * A subset rule ({@code JULC0100}): a name the JavaParser frontend reads by its spelling binds, at every use, to what that
 * frontend reads. javac's element decides, so the rule does not depend on how a name came to mean something else: a nested
 * or imported type, a variable, a parameter, a pattern, an inherited member or a type parameter.
 * <ul>
 *   <li>A name of {@link #NAMES}, a class of the implicit stdlib imports ({@link Reading#libraries}) and a name of
 *       {@link #ANNOTATIONS} in an annotation are read as one type, ahead of what a program declares. Such a name, qualified
 *       or not, binds to that type. {@code resolveClassType} reads a type's last name, so {@code Outer.BigInteger} is
 *       read as well.</li>
 *   <li>A name of the ledger, at any depth, is reached by an implicit import unless a record or a sealed interface of a
 *       program takes it, which that frontend registers ({@link Reading#ledger}). So a simple name binds to the ledger's type,
 *       or to a record, a sealed interface or a library of another unit. A qualified name goes through the qualifier.</li>
 *   <li>A top-level type of the unnamed package has its bare name as its key, so the implicit import wins, another type of
 *       that name takes it, and a package's name joins it. It takes no name of the ledger, of another type of the compilation,
 *       or of a package of the compilation.</li>
 *   <li>A variable does not take the name of a type offered to a compilation ({@link Reading#offered}): that frontend reads
 *       {@code name.method()} as a static call first, on every class of a library unit, whatever the program bound.</li>
 *   <li>A plain class or interface is not registered, so its simple name is read as a record, a sealed interface or a
 *       class of a library unit of that name anywhere in a compilation, whichever scope that is in
 *       ({@link Reading#registered}). A type parameter of such a name is read the same way.</li>
 *   <li>That frontend's resolver has no inherited member and no static import, so a type of a program reached by one of
 *       those, or through a qualifier that is not its owner, is written with its owner. A guard's constants are read the same
 *       way, so a name in a guard does not bind to such a member.</li>
 *   <li>A sealed interface's variant is read by its simple name, so two variants of one interface do not share it. With no
 *       permits clause its variants are the records nested in it, and a different list of javac's is {@code JULC0092}.</li>
 *   <li>A class or a record does not take a type key of {@code TypeMethodRegistry}, which reads the name of a record, or of a
 *       variant that is a class, ahead of its members. A sealed interface is no such reading: its rows are {@code SumType}'s.</li>
 *   <li>The datum of a three-parameter entry point is read as an {@code Optional} when its type's text starts with that word,
 *       so the text and the type agree.</li>
 *   <li>{@code new} is read by the type's last name, and never its body, so it has no class body. {@code instanceof} on a
 *       variant compares a constructor tag, so its operand's static type is that variant or its sealed interface.</li>
 * </ul>
 * A declaration of a name of the first group is rejected where it stands, used or not, which also says where to rename. The
 * lists are held to the code: {@code SpelledNameLintTest} fails on a name the frontend compares that is on no list, and
 * {@link Reading} comes from the API this environment has. An import is not read: what it brings in is read where it is used.
 */
public final class SpelledTypes {

    /** Each name lowering reads as one API type, ahead of what a program declares, with that type. */
    public static final Map<String, String> NAMES = names();

    /** Each annotation the frontend reads by its last name, with the annotation that is. */
    public static final Map<String, String> ANNOTATIONS = annotations();

    private static final String LEDGER = "org.julclang.ledger.";
    private static final String STDLIB = "org.julclang.stdlib";
    private static final String STDLIB_LIBRARIES = STDLIB + ".lib";
    private static final String OPTIONAL = "java.util.Optional";
    private static final Set<String> TYPE_KEYS = SpelledMethods.REGISTRY.typeKeys();

    /**
     * What the frontend's implicit imports and its registries read in one environment.
     *
     * @param libraries the stdlib's classes, by simple name
     * @param ledger    the ledger's types at any depth, by simple name
     * @param registered the simple names of the records and sealed interfaces offered to a compilation, read or not, and of
     *                   every class of a unit it compiles as a library
     * @param offered    those, and the simple names of the classes of the units it compiles as roots
     * @param repeated   the names of {@code offered} that more than one declaration takes
     * @param packages   the first name of every package offered
     * @param unnamed    the names of the top-level types of the unnamed package offered
     */
    public record Reading(Map<String, String> libraries, Map<String, Set<String>> ledger, Set<String> registered,
                          Set<String> offered, Set<String> repeated, Set<String> packages, Set<String> unnamed) {

        /** {@code identities} is every API class's qualified name. */
        public static Reading of(Collection<String> identities, Set<String> registered, Set<String> offered,
                                 Set<String> repeated, Set<String> packages, Set<String> unnamed) {
            var libraries = new HashMap<String, String>();
            var ledger = new HashMap<String, Set<String>>();
            for (String identity : identities) {
                int dot = identity.lastIndexOf('.');
                String name = identity.substring(dot + 1);
                String owner = identity.substring(0, dot);
                if (owner.equals(STDLIB) || owner.equals(STDLIB_LIBRARIES)) libraries.put(name, identity);
                if (identity.startsWith(LEDGER)) ledger.computeIfAbsent(name, k -> new TreeSet<>()).add(identity);
            }
            return new Reading(Map.copyOf(libraries), Map.copyOf(ledger), Set.copyOf(registered), Set.copyOf(offered),
                    Set.copyOf(repeated), Set.copyOf(packages), Set.copyOf(unnamed));
        }

        /** What the frontend reads a top-level type of the unnamed package of this name as, ahead of it, or null. */
        String readAhead(String name) {
            if (ledger.containsKey(name)) return String.join(" or ", ledger.get(name));
            if (repeated.contains(name)) return "the other type of that name in this compilation";
            return packages.contains(name) ? "the package of that name in this compilation" : null;
        }
    }

    private SpelledTypes() {
    }

    private static Map<String, String> names() {
        var names = new TreeMap<String, String>();
        add(names, "java.lang.", "String", "Boolean", "Integer", "Long", "Short", "Byte", "Character", "Object", "Math");
        add(names, "java.math.", "BigInteger");
        add(names, "java.util.", "List", "Map", "Optional");
        add(names, "org.julclang.core.", "PlutusData");
        add(names, JavacNames.PLUTUS_DATA + ".", "ConstrData", "MapData", "ListData", "IntData", "BytesData");
        add(names, JavacNames.TYPES, "JulcValue", "JulcG1", "JulcG2", "JulcMlResult", "JulcScalars", "JulcG1Points",
                "JulcG2Points", "JulcList", "JulcArray", "JulcMap", "Tuple2", "Tuple3", "AssetEntry");
        add(names, LEDGER, TypeRegistry.ledgerHashNames().toArray(String[]::new));
        return Map.copyOf(names);
    }

    private static void add(Map<String, String> names, String prefix, String... simple) {
        for (String name : simple) names.put(name, prefix + name);
    }

    private static Map<String, String> annotations() {
        var annotations = new HashMap<String, String>();
        Stream.concat(Stream.of(JavacNames.ENTRYPOINT, JavacNames.PARAM, JavacNames.ONCHAIN_LIBRARY, JavacNames.NEW_TYPE),
                        JavacNames.VALIDATORS.stream())
                .forEach(identity -> annotations.put(identity.substring(identity.lastIndexOf('.') + 1), identity));
        return Map.copyOf(annotations);
    }

    /** The top-level types of the unnamed package in the units javac did not read, which {@link #check} cannot place. */
    public static void checkUnreadUnits(Reading reading) {
        for (String name : reading.unnamed()) {
            String read = reading.readAhead(name);
            if (read != null) throw CompilerTypeDiagnostics.typeNameReadBySpelling(name, read, "a top-level type of the unnamed package", null);
        }
    }

    /** @param compiledAsLibraries the units read by javac that the frontend compiles as libraries */
    public static void check(JavacFacts facts, CompilationUnitTree unit, Reading reading, Set<CompilationUnitTree> compiledAsLibraries) {
        new Scan(facts, unit, reading, compiledAsLibraries).scan(unit, null);
    }

    private static final class Scan extends TreeScanner<Void, Void> {

        private final JavacFacts facts;
        private final CompilationUnitTree unit;
        private final Reading reading;
        private final Set<CompilationUnitTree> compiledAsLibraries;
        /** The name of the annotation being read, which is the one place an annotation type's name is not a type's. */
        private Tree annotationName;

        Scan(JavacFacts facts, CompilationUnitTree unit, Reading reading, Set<CompilationUnitTree> compiledAsLibraries) {
            this.facts = facts;
            this.unit = unit;
            this.reading = reading;
            this.compiledAsLibraries = compiledAsLibraries;
        }

        /** The one type {@code name} is read as, or null. */
        private String oneType(String name) {
            String type = NAMES.get(name);
            return type != null ? type : reading.libraries().get(name);
        }

        @Override
        public Void visitImport(ImportTree tree, Void unused) {
            return null;
        }

        @Override
        public Void visitClass(ClassTree tree, Void unused) {
            String name = tree.getSimpleName().toString();
            String read = oneType(name);
            if (read != null && !read.equals(facts.identity((TypeElement) facts.element(tree)))) {
                throw reject(name, read, describe(facts.element(tree)), tree);
            }
            boolean classOrRecord = tree.getKind() == Tree.Kind.RECORD || tree.getKind() == Tree.Kind.CLASS;
            if (classOrRecord && TYPE_KEYS.contains(name)) {
                throw reject(name, "a type key of its lowering", describe(facts.element(tree)), tree);
            }
            if (facts.element(tree).getEnclosingElement() instanceof PackageElement owner && owner.isUnnamed()) {
                String ahead = reading.readAhead(name);
                if (ahead != null) throw reject(name, ahead, describe(facts.element(tree)), tree);
            }
            if (facts.element(tree) instanceof TypeElement type && isSealedInterface(type)) {
                requireVariantNames(type, tree);
                requirePermits(type, tree);
            }
            return super.visitClass(tree, unused);
        }

        /** With no permits clause the frontend reads the records nested in the interface, whatever javac permits. */
        private void requirePermits(TypeElement sealed, ClassTree tree) {
            if (!tree.getPermitsClause().isEmpty()) return;
            var permitted = facts.permitted(sealed);
            if (!facts.nestedVariants(tree, sealed).equals(permitted)) {
                throw CompilerTypeDiagnostics.sealedPermitsRequired(sealed.getSimpleName().toString(),
                        permitted.stream().map(variant -> variant.getSimpleName().toString()).toList(), facts.location(tree));
            }
        }

        private void requireVariantNames(TypeElement sealed, ClassTree tree) {
            var names = new HashSet<String>();
            for (TypeElement variant : facts.permitted(sealed)) {
                if (!names.add(variant.getSimpleName().toString())) {
                    throw reject(variant.getSimpleName().toString(), "the first variant of that name of " + sealed.getSimpleName(),
                            describe(variant), tree);
                }
            }
        }

        @Override
        public Void visitTypeParameter(TypeParameterTree tree, Void unused) {
            String read = oneType(tree.getName().toString());
            if (read != null) throw reject(tree.getName().toString(), read, "a type parameter", tree);
            return super.visitTypeParameter(tree, unused);
        }

        @Override
        public Void visitAnnotation(AnnotationTree tree, Void unused) {
            var type = tree.getAnnotationType();
            annotationName = type;
            String name = type instanceof MemberSelectTree select ? select.getIdentifier().toString() : type.toString();
            String read = ANNOTATIONS.get(name);
            if (read != null && !read.equals(facts.identity((TypeElement) facts.element(type)))) {
                throw reject(name, read, describe(facts.element(type)), tree);
            }
            return super.visitAnnotation(tree, unused);
        }

        @Override
        public Void visitMethod(MethodTree tree, Void unused) {
            var parameters = tree.getParameters();
            if (parameters.size() == 3 && !facts.isGenerated(tree) && facts.hasAnnotation(facts.element(tree), JavacNames.ENTRYPOINT)) {
                requireDatumReading(parameters.getFirst().getType());
            }
            return super.visitMethod(tree, unused);
        }

        private void requireDatumReading(Tree type) {
            String text = type.toString();
            boolean optional = facts.type(type) instanceof DeclaredType declared && facts.identity(declared).equals(OPTIONAL);
            if (text.startsWith("Optional") != optional) {
                throw reject(text, optional ? "a datum that is always present" : "an optional datum", "the type " + facts.type(type), type);
            }
        }

        /** The frontend reads the type's last name and never the body. */
        @Override
        public Void visitNewClass(NewClassTree tree, Void unused) {
            if (tree.getClassBody() != null) {
                throw reject("new " + tree.getIdentifier(), "a new " + tree.getIdentifier() + " with no body", "a class body", tree);
            }
            return super.visitNewClass(tree, unused);
        }

        /** Constructor tags mean something only within one sealed interface, and a lowered cast leaves nothing else to read. */
        @Override
        public Void visitInstanceOf(InstanceOfTree tree, Void unused) {
            if (tree.getType() != null && facts.types().asElement(facts.type(tree.getType())) instanceof TypeElement tested) {
                var sealedParents = tested.getInterfaces().stream().map(facts::typeElement).filter(Scan::isSealedInterface).toList();
                var operand = facts.types().asElement(facts.type(tree.getExpression()));
                if (!sealedParents.isEmpty() && !tested.equals(operand) && !sealedParents.contains(operand)) {
                    throw reject(tree.toString(), "a comparison of constructor tags of " + tested.getSimpleName() + "'s sealed interface",
                            "the type " + facts.type(tree.getExpression()), tree);
                }
            }
            return super.visitInstanceOf(tree, unused);
        }

        /** A guard's constants are read through enclosing declarations, past what a class inherits. */
        @Override
        public Void visitCase(CaseTree tree, Void unused) {
            if (tree.getGuard() != null) {
                new TreeScanner<Void, Void>() {
                    @Override
                    public Void visitIdentifier(IdentifierTree name, Void ignored) {
                        var element = facts.elementOrNull(name);
                        boolean member = element instanceof TypeElement || element != null && element.getKind() == ElementKind.FIELD;
                        if (member && isOutsideTheResolver(element, name)) {
                            throw reject(name.getName().toString(), "a member of an enclosing class", describe(element), name);
                        }
                        return null;
                    }
                }.scan(tree.getGuard(), null);
            }
            return super.visitCase(tree, unused);
        }

        @Override
        public Void visitIdentifier(IdentifierTree tree, Void unused) {
            use(tree, tree.getName().toString(), true);
            return null;
        }

        @Override
        public Void visitMemberSelect(MemberSelectTree tree, Void unused) {
            use(tree, tree.getIdentifier().toString(), false);
            return super.visitMemberSelect(tree, unused);
        }

        private void use(Tree tree, String name, boolean simple) {
            String read = oneType(name);
            var ledger = reading.ledger().get(name);
            boolean readInTheLedger = simple && ledger != null;
            boolean registered = reading.registered().contains(name);
            boolean offered = simple && reading.offered().contains(name);
            if (read == null && !readInTheLedger && !registered && !offered) return;
            Element element = facts.elementOrNull(tree);
            // A method has a namespace of its own, and a package is no binding of a name in a type's place.
            if (element == null || element instanceof ExecutableElement || element instanceof PackageElement) return;
            if (read != null) {
                if (!(element instanceof TypeElement type) || !read.equals(facts.identity(type))) {
                    throw reject(name, read, describe(element), tree);
                }
                return;
            }
            String expected = readInTheLedger ? String.join(" or ", ledger) : "the type of that name this compilation is offered";
            if (element instanceof TypeElement type) {
                if (ledger != null && ledger.contains(facts.identity(type))) return;
                boolean readAsAnother = isPlain(type, tree) && (readInTheLedger || registered && !compiledAsLibraries.contains(facts.unitOf(type)));
                if (readAsAnother || registered && isOutsideTheResolver(type, tree)) {
                    throw reject(name, expected, describe(element), tree);
                }
            } else if (readInTheLedger || offered && element instanceof VariableElement
                    || registered && element instanceof TypeParameterElement parameter && !isSpecialized(parameter)) {
                throw reject(name, expected, describe(element), tree);
            }
        }

        /** Specialization binds a generic method of a library ahead of any type of that name (ADR-059), and nothing else. */
        private boolean isSpecialized(TypeParameterElement parameter) {
            return parameter.getGenericElement() instanceof ExecutableElement method && compiledAsLibraries.contains(facts.unitOf(method));
        }

        /**
         * Whether the frontend has no registration of {@code type}, so that it reads the name as something else: a class or an
         * interface of a source that is not sealed, or any type of the API or the platform, whose registered types were read
         * before this. A library is registered in a unit the frontend compiles as one, and an annotation's name is a type's
         * everywhere but where it stands as the annotation.
         */
        private boolean isPlain(TypeElement type, Tree use) {
            if (type.getKind() == ElementKind.ANNOTATION_TYPE) return use != annotationName;
            if (!facts.isDeclaredInSource(type)) return true;
            boolean classOrInterface = type.getKind() == ElementKind.CLASS || type.getKind() == ElementKind.INTERFACE;
            if (!classOrInterface || isSealedInterface(type)) return false;
            return !facts.hasAnnotation(type, JavacNames.ONCHAIN_LIBRARY) || !compiledAsLibraries.contains(facts.unitOf(type));
        }

        private static boolean isSealedInterface(TypeElement type) {
            return type.getKind() == ElementKind.INTERFACE && type.getModifiers().contains(Modifier.SEALED);
        }

        /**
         * Whether a member came in by inheritance or a static import: no enclosing class holds it and no plain import names it,
         * or a qualifier that is not its owner reaches it.
         */
        private boolean isOutsideTheResolver(Element member, Tree use) {
            if (!(member.getEnclosingElement() instanceof TypeElement owner)) return false;
            if (use instanceof MemberSelectTree select) return !owner.equals(facts.elementOrNull(select.getExpression()));
            for (Tree scope = enclosingType(use); scope != null; scope = enclosingType(scope)) {
                if (owner.equals(facts.element(scope))) return false;
            }
            String imported = facts.identity(owner) + "." + member.getSimpleName();
            String onDemand = facts.identity(owner) + ".*";
            return unit.getImports().stream().noneMatch(declared -> !declared.isStatic()
                    && (declared.getQualifiedIdentifier().toString().equals(imported)
                    || declared.getQualifiedIdentifier().toString().equals(onDemand)));
        }

        private Tree enclosingType(Tree tree) {
            return facts.enclosing(tree, Tree.Kind.CLASS, Tree.Kind.INTERFACE, Tree.Kind.RECORD);
        }

        private static String describe(Element element) {
            return switch (element) {
                case TypeElement type -> "the type " + type.getQualifiedName();
                case VariableElement variable -> "the " + kind(variable) + " " + variable.getSimpleName();
                default -> "a type parameter";
            };
        }

        private static String kind(VariableElement variable) {
            return switch (variable.getKind()) {
                case FIELD -> "field";
                case PARAMETER -> "parameter";
                case BINDING_VARIABLE -> "pattern variable";
                default -> "variable";
            };
        }

        private RuntimeException reject(String name, String read, String here, Tree where) {
            return CompilerTypeDiagnostics.typeNameReadBySpelling(name, read, here, facts.location(where));
        }
    }
}
