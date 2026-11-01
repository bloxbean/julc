package org.julclang.compiler.frontend.javac.subset;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.PackageTree;
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
 *   <li>A plain class or interface is not registered, so its simple name is read as a record, a sealed interface or a
 *       library of that name anywhere in a compilation, whichever scope that is in ({@link Reading#registered}). A type
 *       parameter of such a name is read the same way.</li>
 *   <li>That frontend's resolver has no inherited member and no static import, so a type of a program reached by one of
 *       those is written with its qualifier.</li>
 *   <li>A sealed interface's variant is read by its simple name, so two variants of one interface do not share it.</li>
 *   <li>A record does not take a type key of {@code TypeMethodRegistry}, which reads a record's name ahead of its members.
 *       A record {@code Value} stays open: ADR-062 T33 lists it.</li>
 *   <li>The datum of a three-parameter entry point is read as an {@code Optional} when its type's text starts with that word,
 *       so the text and the type agree.</li>
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
    /** The type key a record of that name keeps, by ADR-062 T33. */
    private static final String OPEN_TYPE_KEY = "Value";

    /**
     * What the frontend's implicit imports and its registries read in one environment.
     *
     * @param libraries the stdlib's classes, by simple name
     * @param ledger    the ledger's types at any depth, by simple name
     * @param registered the simple names of the records, sealed interfaces and libraries offered to a compilation, read or not
     */
    public record Reading(Map<String, String> libraries, Map<String, Set<String>> ledger, Set<String> registered) {

        /** {@code identities} is every API class's qualified name. */
        public static Reading of(Collection<String> identities, Set<String> registered) {
            var libraries = new HashMap<String, String>();
            var ledger = new HashMap<String, Set<String>>();
            for (String identity : identities) {
                int dot = identity.lastIndexOf('.');
                String name = identity.substring(dot + 1);
                String owner = identity.substring(0, dot);
                if (owner.equals(STDLIB) || owner.equals(STDLIB_LIBRARIES)) libraries.put(name, identity);
                if (identity.startsWith(LEDGER)) ledger.computeIfAbsent(name, k -> new TreeSet<>()).add(identity);
            }
            return new Reading(Map.copyOf(libraries), Map.copyOf(ledger), Set.copyOf(registered));
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

    public static void check(JavacFacts facts, CompilationUnitTree unit, Reading reading) {
        new Scan(facts, unit, reading).scan(unit, null);
    }

    private static final class Scan extends TreeScanner<Void, Void> {

        private final JavacFacts facts;
        private final CompilationUnitTree unit;
        private final Reading reading;

        Scan(JavacFacts facts, CompilationUnitTree unit, Reading reading) {
            this.facts = facts;
            this.unit = unit;
            this.reading = reading;
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
        public Void visitPackage(PackageTree tree, Void unused) {
            return null;
        }

        @Override
        public Void visitClass(ClassTree tree, Void unused) {
            String name = tree.getSimpleName().toString();
            String read = oneType(name);
            if (read != null && !read.equals(facts.identity((TypeElement) facts.element(tree)))) {
                throw reject(name, read, describe(facts.element(tree)), tree);
            }
            if (tree.getKind() == Tree.Kind.RECORD && !name.equals(OPEN_TYPE_KEY) && TYPE_KEYS.contains(name)) {
                throw reject(name, "a type key of its lowering", describe(facts.element(tree)), tree);
            }
            if (facts.element(tree) instanceof TypeElement type && isSealedInterface(type)) requireVariantNames(type, tree);
            return super.visitClass(tree, unused);
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
            var ledger = simple ? reading.ledger().get(name) : null;
            boolean registered = reading.registered().contains(name);
            if (read == null && ledger == null && !registered) return;
            Element element = facts.elementOrNull(tree);
            // A method has a namespace of its own, and a package is no binding of a name in a type's place.
            if (element == null || element instanceof ExecutableElement || element instanceof PackageElement) return;
            if (read != null) {
                if (!(element instanceof TypeElement type) || !read.equals(facts.identity(type))) {
                    throw reject(name, read, describe(element), tree);
                }
                return;
            }
            String expected = ledger != null ? String.join(" or ", ledger) : "the type a compilation registers under that name";
            if (element instanceof TypeElement type) {
                if (ledger != null && ledger.contains(facts.identity(type))) return;
                if (isPlain(type) || simple && registered && isOutsideTheResolver(type, tree)) {
                    throw reject(name, expected, describe(element), tree);
                }
            } else if (ledger != null || registered && element instanceof TypeParameterElement parameter && !isSpecialized(parameter)) {
                throw reject(name, expected, describe(element), tree);
            }
        }

        /** A library's generic method is specialized (ADR-059), which binds its type parameter ahead of any type of that name. */
        private boolean isSpecialized(TypeParameterElement parameter) {
            for (Element e = parameter.getGenericElement(); e != null; e = e.getEnclosingElement()) {
                if (e instanceof TypeElement && facts.hasAnnotation(e, JavacNames.ONCHAIN_LIBRARY)) return true;
            }
            return false;
        }

        /**
         * Whether the frontend has no registration of {@code type}, so that it reads the name as something else: a class or
         * an interface that is not sealed, of a source or of the platform. A library of another unit is registered; the
         * frontend's list of libraries does not hold the units it compiles as roots.
         */
        private boolean isPlain(TypeElement type) {
            boolean classOrInterface = type.getKind() == ElementKind.CLASS || type.getKind() == ElementKind.INTERFACE;
            if (!classOrInterface || isSealedInterface(type)) return false;
            if (!facts.isDeclaredInSource(type)) return facts.identity(type).startsWith("java.");
            return !facts.hasAnnotation(type, JavacNames.ONCHAIN_LIBRARY) || facts.unitOf(type) == unit;
        }

        private static boolean isSealedInterface(TypeElement type) {
            return type.getKind() == ElementKind.INTERFACE && type.getModifiers().contains(Modifier.SEALED);
        }

        /** Whether a member type came in by inheritance or a static import: no enclosing class holds it, and no plain import names it. */
        private boolean isOutsideTheResolver(TypeElement type, Tree use) {
            if (!(type.getEnclosingElement() instanceof TypeElement owner)) return false;
            for (Tree scope = enclosingType(use); scope != null; scope = enclosingType(scope)) {
                if (owner.equals(facts.element(scope))) return false;
            }
            String identity = facts.identity(type);
            String onDemand = facts.identity(owner) + ".*";
            return unit.getImports().stream().noneMatch(declared -> !declared.isStatic()
                    && (declared.getQualifiedIdentifier().toString().equals(identity)
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
