package org.julclang.compiler.frontend.javac;

import com.sun.source.util.Trees;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The {@code JULC0087} predicate of ADR-068 D6: whether the JVM's equality for some value of a static type
 * {@code T} reaches an array compared by identity. It is decided on javac's types ({@code javax.lang.model}), after
 * type substitution, so that a call site is checked at the type javac bound (I12). M0 uses it to scan the corpus;
 * M3's subset rule reuses it.
 * <p>
 * The rule, case by case:
 * <ul>
 *   <li>a primitive type: no;</li>
 *   <li>an array type: yes, except {@code byte[]} as an element, key or value type of a JuLC collection
 *       ({@code JulcList}, {@code JulcMap}, {@code JulcArray}), whose JVM equality ADR-068 M1 makes deep;</li>
 *   <li>a type on the {@linkplain #VALUE_EQUALITY_ALLOW_LIST value-equality allow-list}: no;</li>
 *   <li>{@code Optional}, {@code Tuple2}, {@code Tuple3}, the JuLC collections, and {@code java.util} collections and
 *       maps: if any type argument can;</li>
 *   <li>a record: if it declares {@code equals} itself, yes (declaring {@code equals} is not enough: only the
 *       allow-list says an {@code equals} compares by value); otherwise, if any component's type can, after
 *       substitution;</li>
 *   <li>a sealed interface or class: if any permitted variant can;</li>
 *   <li>an enum: no ({@code Enum.equals} is the identity of a constant, never of an array);</li>
 *   <li>a type variable: {@link Kind#TYPE_VARIABLE}, rejected unless the caller checks it where it is bound;</li>
 *   <li>anything else, including a wildcard without an upper bound and an erroneous type: {@link Kind#UNRESOLVED},
 *       rejected conservatively.</li>
 * </ul>
 * Recursion follows finite paths only: a type already on the current path contributes nothing new, so recursive
 * types such as {@code PlutusData.ListData} or a user's recursive sealed type terminate. A path deeper than
 * {@link #MAX_DEPTH} (a type whose arguments grow along the path) is {@link Kind#UNRESOLVED}.
 * <p>
 * A record declares {@code equals} when its member {@code equals(java.lang.Object)} (that exact signature: an
 * {@code equals(String)} overload is not an override) is explicit by provenance, not by modifiers (javac marks the
 * implicit one {@code public final}, and an explicit one may be written {@code final} too):
 * <ul>
 *   <li>a source record: the method has a tree ({@code Trees.getTree}); javac's implicit member has none;</li>
 *   <li>a class-file record: the method's code does not invoke the {@code java.lang.runtime.ObjectMethods}
 *       bootstrap, through which javac implements every implicit record {@code equals} (read with
 *       {@code java.lang.classfile} from {@code Elements.getFileObjectOf});</li>
 *   <li>anything else (a source record without {@code Trees}, an unreadable class file): explicit, conservatively.</li>
 * </ul>
 * A collection is checked through its instantiated collection supertype ({@code Hidden<T> extends
 * ArrayList<byte[]>} is a collection of {@code byte[]}, whatever {@code T} is), and a collection class outside
 * {@code java.util} and the JuLC collections that declares its own {@code equals} is unresolved.
 */
public final class ArrayIdentityEquality {

    /** The leaf text of a path that reached a type javac could not attribute. */
    public static final String ERROR_TYPE = "<error> ";

    /** The deepest component path the check follows. */
    public static final int MAX_DEPTH = 32;

    /**
     * Types whose {@code equals} compares every component by value (ADR-068 D6). Each entry has a test of that claim.
     * {@code PlutusData}'s variants: {@code BytesData} uses {@code Arrays.equals}, {@code IntData} its
     * {@code BigInteger}, and {@code ConstrData}, {@code ListData} and {@code MapData} compare their tag and fields,
     * which are themselves {@code PlutusData}. The ledger hash types override {@code equals} with
     * {@code Arrays.equals}.
     */
    public static final Set<String> VALUE_EQUALITY_ALLOW_LIST = Set.of(
            "java.math.BigInteger",
            "java.lang.String",
            "java.lang.Boolean",
            "java.lang.Integer",
            "java.lang.Long",
            "org.julclang.core.PlutusData.ConstrData",
            "org.julclang.core.PlutusData.MapData",
            "org.julclang.core.PlutusData.ListData",
            "org.julclang.core.PlutusData.IntData",
            "org.julclang.core.PlutusData.BytesData",
            "org.julclang.ledger.PubKeyHash",
            "org.julclang.ledger.PolicyId",
            "org.julclang.ledger.TxId",
            "org.julclang.ledger.TokenName",
            "org.julclang.ledger.ScriptHash",
            "org.julclang.ledger.ValidatorHash",
            "org.julclang.ledger.DatumHash");

    /** The JuLC collections: an array of bytes as their element, key or value is compared deeply (after M1). */
    public static final Set<String> JULC_COLLECTIONS = Set.of(
            "org.julclang.core.types.JulcList",
            "org.julclang.core.types.JulcMap",
            "org.julclang.core.types.JulcArray");

    /** Containers whose equality is their type arguments' equality. */
    public static final Set<String> CONTAINERS = Set.of(
            "java.util.Optional",
            "org.julclang.core.types.Tuple2",
            "org.julclang.core.types.Tuple3");

    /** What the check found. */
    public enum Kind {
        /** No finite path reaches an array compared by identity. */
        SAFE,
        /** A path reaches an array compared by identity. */
        ARRAY_IDENTITY,
        /** A path reaches a type the check cannot decide, which is rejected conservatively. */
        UNRESOLVED,
        /** A path reaches a type variable; it is decided where the variable is bound. */
        TYPE_VARIABLE
    }

    /**
     * The verdict, and the component path that decided it (empty when safe), such as
     * {@code Datum.owner: byte[]}.
     */
    public record Result(Kind kind, String path) {
        /** Whether {@code JULC0087} rejects the comparison at this type. */
        public boolean rejected() {
            return kind != Kind.SAFE;
        }
    }

    private static final Result SAFE = new Result(Kind.SAFE, "");

    private final Types types;
    private final Elements elements;
    private final Trees trees;

    /** A check without source trees: a source record's {@code equals} is then taken as explicit, conservatively. */
    public ArrayIdentityEquality(Types types, Elements elements) {
        this(types, elements, null);
    }

    /** A check over javac's types, elements and, for source records, trees. */
    public ArrayIdentityEquality(Types types, Elements elements, Trees trees) {
        this.types = types;
        this.elements = elements;
        this.trees = trees;
    }

    /** Whether the JVM's equality for some value of {@code type} reaches an array compared by identity. */
    public Result check(TypeMirror type) {
        return check(type, false);
    }

    /**
     * As {@link #check(TypeMirror)}, for values that are elements, keys or values of a JuLC collection when
     * {@code julcCollectionElement} is true (the operand of {@code JulcList.contains} or {@code JulcMap.get}, say).
     */
    public Result check(TypeMirror type, boolean julcCollectionElement) {
        return check(type, julcCollectionElement, new HashSet<>(), new ArrayList<>());
    }

    private Result check(TypeMirror type, boolean inJulcCollection, Set<String> onPath, List<String> path) {
        if (path.size() > MAX_DEPTH) return result(Kind.UNRESOLVED, path, "deeper than " + MAX_DEPTH);
        TypeKind kind = type.getKind();
        if (kind.isPrimitive()) return SAFE;
        switch (kind) {
            case ARRAY -> {
                var component = ((ArrayType) type).getComponentType();
                if (inJulcCollection && component.getKind() == TypeKind.BYTE) return SAFE;
                return result(Kind.ARRAY_IDENTITY, path, type.toString());
            }
            case TYPEVAR -> {
                return result(Kind.TYPE_VARIABLE, path, type.toString());
            }
            case WILDCARD -> {
                var bound = ((WildcardType) type).getExtendsBound();
                if (bound == null) return result(Kind.UNRESOLVED, path, type.toString());
                return check(bound, inJulcCollection, onPath, path);
            }
            case INTERSECTION -> {
                for (var bound : ((IntersectionType) type).getBounds()) {
                    var r = check(bound, inJulcCollection, onPath, path);
                    if (r.rejected()) return r;
                }
                return SAFE;
            }
            case DECLARED -> {
                return declared((DeclaredType) type, onPath, path);
            }
            case ERROR -> {
                return result(Kind.UNRESOLVED, path, ERROR_TYPE + type);
            }
            default -> {
                return result(Kind.UNRESOLVED, path, type.toString());
            }
        }
    }

    private Result declared(DeclaredType type, Set<String> onPath, List<String> path) {
        var element = (TypeElement) type.asElement();
        String name = element.getQualifiedName().toString();
        if (VALUE_EQUALITY_ALLOW_LIST.contains(name)) return SAFE;
        String key = type.toString();
        if (!onPath.add(key)) return SAFE;
        try {
            for (String collection : JULC_COLLECTIONS) {
                var view = asSuper(type, elements.getTypeElement(collection));
                if (view != null) return collection(type, element, view, true, onPath, path);
            }
            if (CONTAINERS.contains(name)) return typeArguments(type, false, onPath, path);
            for (String collection : List.of("java.util.Map", "java.util.Collection")) {
                var view = asSuper(type, elements.getTypeElement(collection));
                if (view != null) return collection(type, element, view, false, onPath, path);
            }
            if (element.getKind() == ElementKind.ENUM) return SAFE;
            if (element.getKind() == ElementKind.RECORD) return record(type, element, onPath, path);
            if (element.getModifiers().contains(Modifier.SEALED)) return sealed(type, element, onPath, path);
            return result(Kind.UNRESOLVED, path, name + " (not a record, sealed type, container or allow-listed type)");
        } finally {
            onPath.remove(key);
        }
    }

    /**
     * A collection, through its instantiated collection supertype {@code view}; a collection class of another
     * package that declares its own {@code equals} cannot be decided.
     */
    private Result collection(DeclaredType type, TypeElement element, DeclaredType view, boolean julcCollection,
                              Set<String> onPath, List<String> path) {
        String name = element.getQualifiedName().toString();
        if (!name.startsWith("java.util.") && !name.startsWith("org.julclang.core.types.") && declaresEqualsMethod(element)) {
            return result(Kind.UNRESOLVED, path, name + " declares equals and is not on the value-equality allow-list");
        }
        if (view != type) path.add(simpleName(type) + " as " + simpleName(view));
        try {
            return typeArguments(view, julcCollection, onPath, path);
        } finally {
            if (view != type) path.removeLast();
        }
    }

    private Result typeArguments(DeclaredType type, boolean julcCollection, Set<String> onPath, List<String> path) {
        var arguments = type.getTypeArguments();
        if (arguments.isEmpty()) return result(Kind.UNRESOLVED, path, type + " (raw)");
        for (int i = 0; i < arguments.size(); i++) {
            path.add(simpleName(type) + "<" + i + ">");
            var r = check(arguments.get(i), julcCollection, onPath, path);
            path.removeLast();
            if (r.rejected()) return r;
        }
        return SAFE;
    }

    private Result record(DeclaredType type, TypeElement element, Set<String> onPath, List<String> path) {
        if (declaresEquals(element)) {
            return result(Kind.UNRESOLVED, path, element.getQualifiedName()
                    + " declares equals and is not on the value-equality allow-list");
        }
        for (RecordComponentElement component : element.getRecordComponents()) {
            TypeMirror componentType;
            var accessor = component.getAccessor();
            if (accessor != null) {
                componentType = ((ExecutableType) types.asMemberOf(type, accessor)).getReturnType();
            } else {
                componentType = types.asMemberOf(type, component);
            }
            path.add(simpleName(type) + "." + component.getSimpleName());
            var r = check(componentType, false, onPath, path);
            path.removeLast();
            if (r.rejected()) return r;
        }
        return SAFE;
    }

    private Result sealed(DeclaredType type, TypeElement element, Set<String> onPath, List<String> path) {
        var permitted = element.getPermittedSubclasses();
        if (permitted.isEmpty()) return result(Kind.UNRESOLVED, path, element.getQualifiedName() + " (no permitted subclasses)");
        for (TypeMirror variant : permitted) {
            var variantElement = (TypeElement) types.asElement(variant);
            var variantType = substitute(type, element, variantElement);
            path.add(simpleName(type) + "|" + variantElement.getSimpleName());
            var r = check(variantType, false, onPath, path);
            path.removeLast();
            if (r.rejected()) return r;
        }
        return SAFE;
    }

    /**
     * The variant's type with the sealed parent's type arguments substituted, where the variant passes one of its
     * own type variables straight to the parent; a variable it does not pass stays a type variable, which the check
     * then reports as {@link Kind#TYPE_VARIABLE}.
     */
    private TypeMirror substitute(DeclaredType parent, TypeElement parentElement, TypeElement variant) {
        var parameters = variant.getTypeParameters();
        if (parameters.isEmpty()) return variant.asType();
        var supertype = findSupertype(variant.asType(), parentElement, new HashSet<>());
        var arguments = new TypeMirror[parameters.size()];
        for (int i = 0; i < parameters.size(); i++) arguments[i] = parameters.get(i).asType();
        if (supertype != null && parent.getTypeArguments().size() == supertype.getTypeArguments().size()) {
            for (int j = 0; j < supertype.getTypeArguments().size(); j++) {
                var passed = supertype.getTypeArguments().get(j);
                if (passed.getKind() != TypeKind.TYPEVAR) continue;
                Element passedElement = ((TypeVariable) passed).asElement();
                for (int i = 0; i < parameters.size(); i++) {
                    TypeParameterElement parameter = parameters.get(i);
                    if (parameter.equals(passedElement)) arguments[i] = parent.getTypeArguments().get(j);
                }
            }
        }
        return types.getDeclaredType(variant, arguments);
    }

    private DeclaredType findSupertype(TypeMirror type, TypeElement target, Set<String> seen) {
        for (TypeMirror supertype : types.directSupertypes(type)) {
            if (supertype.getKind() != TypeKind.DECLARED || !seen.add(supertype.toString())) continue;
            var declared = (DeclaredType) supertype;
            if (declared.asElement().equals(target)) return declared;
            var found = findSupertype(supertype, target, seen);
            if (found != null) return found;
        }
        return null;
    }

    /** Whether {@code record} declares {@code equals(Object)} itself, by provenance (see the class comment). */
    boolean declaresEquals(TypeElement record) {
        var method = equalsMethod(record);
        if (method == null) return false;
        var file = elements.getFileObjectOf(record);
        if (file == null) return true;
        if (file.getKind() == JavaFileObject.Kind.SOURCE) return trees == null || trees.getTree(method) != null;
        if (file.getKind() == JavaFileObject.Kind.CLASS) {
            try (var in = file.openInputStream()) {
                return !implicitRecordEquals(in.readAllBytes());
            } catch (IOException | IllegalArgumentException e) {
                return true;
            }
        }
        return true;
    }

    /** Whether a class (not only a record) declares {@code equals(Object)} among its own members. */
    private boolean declaresEqualsMethod(TypeElement type) {
        return equalsMethod(type) != null;
    }

    /** The type's own {@code equals(java.lang.Object)}, or null. */
    private ExecutableElement equalsMethod(TypeElement type) {
        for (Element member : type.getEnclosedElements()) {
            if (member.getKind() != ElementKind.METHOD || !member.getSimpleName().contentEquals("equals")) continue;
            var method = (ExecutableElement) member;
            if (method.getParameters().size() == 1 && types.isSameType(method.getParameters().getFirst().asType(),
                    elements.getTypeElement("java.lang.Object").asType())) {
                return method;
            }
        }
        return null;
    }

    /**
     * Whether the class file's {@code equals(Ljava/lang/Object;)Z} is javac's implicit record method: its code
     * invokes the {@code java.lang.runtime.ObjectMethods} bootstrap. Public so that a build check can apply the same
     * test to the API's class files.
     */
    public static boolean implicitRecordEquals(byte[] classFile) {
        ClassModel model = ClassFile.of().parse(classFile);
        for (MethodModel method : model.methods()) {
            if (!method.methodName().equalsString("equals")
                    || !method.methodType().equalsString("(Ljava/lang/Object;)Z")) continue;
            var code = method.code();
            if (code.isEmpty()) return false;
            for (CodeElement element : code.get()) {
                if (element instanceof InvokeDynamicInstruction indy
                        && indy.bootstrapMethod().owner().descriptorString().equals("Ljava/lang/runtime/ObjectMethods;")) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    /**
     * {@code type} viewed as {@code target}, with its type arguments substituted, or null when it is not one. A type
     * variable or wildcard is viewed through its upper bound, an intersection through its bounds.
     */
    public DeclaredType asSuper(TypeMirror type, TypeElement target) {
        if (target == null) return null;
        return switch (type.getKind()) {
            case DECLARED -> findView((DeclaredType) type, target, new HashSet<>());
            case TYPEVAR -> asSuper(((TypeVariable) type).getUpperBound(), target);
            case WILDCARD -> {
                var bound = ((WildcardType) type).getExtendsBound();
                yield bound == null ? null : asSuper(bound, target);
            }
            case INTERSECTION -> {
                for (var bound : ((IntersectionType) type).getBounds()) {
                    var view = asSuper(bound, target);
                    if (view != null) yield view;
                }
                yield null;
            }
            default -> null;
        };
    }

    private DeclaredType findView(DeclaredType type, TypeElement target, Set<String> seen) {
        if (type.asElement().equals(target)) return type;
        if (!seen.add(type.toString())) return null;
        for (TypeMirror supertype : types.directSupertypes(type)) {
            if (supertype.getKind() != TypeKind.DECLARED) continue;
            var found = findView((DeclaredType) supertype, target, seen);
            if (found != null) return found;
        }
        return null;
    }

    private static String simpleName(DeclaredType type) {
        return type.asElement().getSimpleName().toString();
    }

    private static Result result(Kind kind, List<String> path, String leaf) {
        var text = new StringBuilder();
        for (String step : path) text.append(step).append(" > ");
        return new Result(kind, text.append(leaf).toString());
    }
}
