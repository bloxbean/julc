package org.julclang.compiler.frontend.javac;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The operations whose JVM implementation compares elements or keys with {@code equals} (ADR-068 D6), and the static
 * type each one compares, after substitution: the receiver of {@code .equals}, and the element or key type of a
 * collection operation. {@link ArrayIdentityEquality} decides that type.
 * <p>
 * The table, derived from the implementations:
 * <ul>
 *   <li>any {@code equals(Object)} instance method, and {@code java.util.Objects.equals(a, b)} (which calls
 *       {@code a.equals(b)});</li>
 *   <li>{@code JulcList.contains} ({@code JulcArrayList} delegates to {@code List.contains});</li>
 *   <li>{@code JulcMap.get}, {@code lookup}, {@code containsKey}, {@code delete}, and {@code getOrDefault}, a default
 *       method that calls {@code get} ({@code JulcAssocMap} compares keys with {@code Objects.equals}). Plain
 *       {@code JulcMap.insert} only prepends, so it is not in the table;</li>
 *   <li>the stdlib wrappers that call them: {@code TypedMapLib.lookup}, {@code member}, {@code insert} (which
 *       deletes first) and {@code remove}; {@code MapLib.lookup}, {@code member} and {@code delete};
 *       {@code ListsLib.contains};</li>
 *   <li>{@code java.util.Collection}'s {@code contains}, {@code remove(Object)}, {@code containsAll},
 *       {@code removeAll} and {@code retainAll}, and {@code List.indexOf} and {@code lastIndexOf};
 *       {@code java.util.Map}'s {@code get}, {@code getOrDefault}, {@code containsKey} and {@code remove} (keys) and
 *       {@code containsValue} (values).</li>
 * </ul>
 */
public final class EqualityOperations {

    /** How an operation finds the compared type. */
    private enum From { RECEIVER_ELEMENT, RECEIVER_KEY, RECEIVER_VALUE, FIRST_ARGUMENT_ELEMENT, FIRST_ARGUMENT_KEY }

    private record Rule(String owner, Set<String> methods, From from, String view) {
    }

    private static final String JULC_LIST = "org.julclang.core.types.JulcList";
    private static final String JULC_MAP = "org.julclang.core.types.JulcMap";

    private static final List<Rule> RULES = List.of(
            new Rule(JULC_LIST, Set.of("contains"), From.RECEIVER_ELEMENT, JULC_LIST),
            new Rule(JULC_MAP, Set.of("get", "lookup", "containsKey", "delete", "getOrDefault"), From.RECEIVER_KEY, JULC_MAP),
            new Rule("org.julclang.stdlib.lib.TypedMapLib", Set.of("lookup", "member", "insert", "remove"),
                    From.FIRST_ARGUMENT_KEY, JULC_MAP),
            new Rule("org.julclang.stdlib.lib.MapLib", Set.of("lookup", "member", "delete"), From.FIRST_ARGUMENT_KEY, JULC_MAP),
            new Rule("org.julclang.stdlib.lib.ListsLib", Set.of("contains"), From.FIRST_ARGUMENT_ELEMENT, JULC_LIST),
            new Rule("java.util.Collection", Set.of("contains", "remove", "containsAll", "removeAll", "retainAll",
                    "indexOf", "lastIndexOf"), From.RECEIVER_ELEMENT, "java.util.Collection"),
            new Rule("java.util.Map", Set.of("get", "getOrDefault", "containsKey", "remove"), From.RECEIVER_KEY, "java.util.Map"),
            new Rule("java.util.Map", Set.of("containsValue"), From.RECEIVER_VALUE, "java.util.Map"));

    /** The operations in the table, as {@code owner.method}, for reports. */
    public static final List<String> TABLE = tableText();

    /**
     * One equality-using call: the operation ({@code Owner.method}), the static type it compares, whether it is a
     * collection operation (membership or lookup, not {@code equals}), and whether the compared values are elements,
     * keys or values of a JuLC collection (so an array of bytes among them is compared deeply after ADR-068 M1).
     */
    public record Site(String operation, TypeMirror compared, boolean collectionOperation, boolean julcCollection) {
    }

    private final Types types;
    private final Elements elements;

    public EqualityOperations(Types types, Elements elements) {
        this.types = types;
        this.elements = elements;
    }

    /**
     * The equality site of a call to {@code method}, or empty when the method compares no elements or keys.
     *
     * @param receiver  the static type of the receiver of an instance call (the enclosing class for an implicit
     *                  receiver), or null for a static call
     * @param arguments the static types of the arguments
     */
    public Optional<Site> site(ExecutableElement method, TypeMirror receiver, List<? extends TypeMirror> arguments) {
        String name = method.getSimpleName().toString();
        var owner = (TypeElement) method.getEnclosingElement();
        boolean isStatic = method.getModifiers().contains(Modifier.STATIC);
        if (!isStatic && name.equals("equals") && method.getParameters().size() == 1
                && isObject(method.getParameters().getFirst().asType()) && receiver != null) {
            return Optional.of(new Site(owner.getSimpleName() + ".equals", receiver, false, false));
        }
        if (isStatic && name.equals("equals") && owner.getQualifiedName().contentEquals("java.util.Objects")
                && arguments.size() == 2) {
            return Optional.of(new Site("Objects.equals", arguments.getFirst(), false, false));
        }
        for (Rule rule : RULES) {
            if (!rule.methods().contains(name)) continue;
            var ruleOwner = elements.getTypeElement(rule.owner());
            if (ruleOwner == null || !isSubtype(owner, ruleOwner)) continue;
            if (rule.owner().startsWith("java.util.") && name.equals("remove")
                    && (method.getParameters().isEmpty() || method.getParameters().getFirst().asType().getKind().isPrimitive())) {
                continue;
            }
            TypeMirror base = switch (rule.from()) {
                case RECEIVER_ELEMENT, RECEIVER_KEY, RECEIVER_VALUE -> isStatic ? null : receiver;
                case FIRST_ARGUMENT_ELEMENT, FIRST_ARGUMENT_KEY -> arguments.isEmpty() ? null : arguments.getFirst();
            };
            if (base == null) continue;
            int index = rule.from() == From.RECEIVER_VALUE ? 1 : 0;
            var view = asSuper(base, elements.getTypeElement(rule.view()));
            TypeMirror compared;
            if (view != null && view.getTypeArguments().size() > index) {
                compared = view.getTypeArguments().get(index);
            } else {
                // A raw collection has no element or key type: the compared value's own static type is all there is.
                int argument = rule.from() == From.FIRST_ARGUMENT_ELEMENT || rule.from() == From.FIRST_ARGUMENT_KEY ? 1 : 0;
                if (arguments.size() <= argument) continue;
                compared = arguments.get(argument);
            }
            return Optional.of(new Site(owner.getSimpleName() + "." + name, compared, true,
                    !rule.view().startsWith("java.util.")));
        }
        return Optional.empty();
    }

    private boolean isObject(TypeMirror type) {
        return types.isSameType(type, elements.getTypeElement("java.lang.Object").asType());
    }

    private boolean isSubtype(TypeElement type, TypeElement target) {
        return types.isSubtype(types.erasure(type.asType()), types.erasure(target.asType()));
    }

    /** {@code type} viewed as {@code target}, with its type arguments, or null when it is not one. */
    public DeclaredType asSuper(TypeMirror type, TypeElement target) {
        if (target == null || type.getKind() != TypeKind.DECLARED) return null;
        return find((DeclaredType) type, target, new HashSet<>());
    }

    private DeclaredType find(DeclaredType type, TypeElement target, Set<String> seen) {
        if (type.asElement().equals(target)) return type;
        if (!seen.add(type.toString())) return null;
        for (TypeMirror supertype : types.directSupertypes(type)) {
            if (supertype.getKind() != TypeKind.DECLARED) continue;
            var found = find((DeclaredType) supertype, target, seen);
            if (found != null) return found;
        }
        return null;
    }

    private static List<String> tableText() {
        var text = new ArrayList<String>();
        text.add("*.equals(Object) [receiver]");
        text.add("java.util.Objects.equals [first argument]");
        Map<From, String> what = Map.of(From.RECEIVER_ELEMENT, "receiver element", From.RECEIVER_KEY, "receiver key",
                From.RECEIVER_VALUE, "receiver value", From.FIRST_ARGUMENT_ELEMENT, "first argument's element",
                From.FIRST_ARGUMENT_KEY, "first argument's key");
        for (Rule rule : RULES) {
            for (String method : rule.methods().stream().sorted().toList()) {
                text.add(rule.owner() + "." + method + " [" + what.get(rule.from()) + "]");
            }
        }
        return List.copyOf(text);
    }
}
