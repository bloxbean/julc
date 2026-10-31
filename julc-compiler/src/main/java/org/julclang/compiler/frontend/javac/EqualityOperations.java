package org.julclang.compiler.frontend.javac;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * The operations whose JVM implementation compares elements or keys with {@code equals} (ADR-068 D6), matched by
 * their exact signatures, and the static types each one compares, after substitution: the receiver of
 * {@code .equals}, and the element, key or value types of a collection operation. {@link ArrayIdentityEquality}
 * decides each type.
 * <p>
 * The table, derived from the implementations (parameter types are erasures):
 * <ul>
 *   <li>any instance {@code equals(Object)}, and {@code java.util.Objects.equals(Object, Object)} (which calls
 *       {@code a.equals(b)});</li>
 *   <li>{@code JulcList.contains(T)} ({@code JulcArrayList} delegates to {@code List.contains});</li>
 *   <li>{@code JulcMap.get(K)}, {@code lookup(K)}, {@code containsKey(K)}, {@code delete(K)}, and
 *       {@code getOrDefault(K, V)}, a default method that calls {@code get} ({@code JulcAssocMap} compares keys with
 *       {@code Objects.equals}). Plain {@code JulcMap.insert} only prepends, so it is not in the table;</li>
 *   <li>the stdlib wrappers Java code calls: {@code MapLib.lookup}, {@code member} and {@code delete};
 *       {@code ListsLib.contains}. {@code TypedMapLib}'s methods compare keys too, but they are generic templates,
 *       which Java code cannot call (ADR-068 D6, ADR-059), so they are not in the table;</li>
 *   <li>{@code java.util.Collection}'s {@code contains(Object)}, {@code remove(Object)}, {@code containsAll},
 *       {@code removeAll} and {@code retainAll(Collection)}, and {@code List.indexOf(Object)} and
 *       {@code lastIndexOf(Object)}; {@code java.util.Map}'s {@code get(Object)}, {@code getOrDefault(Object,
 *       Object)}, {@code containsKey(Object)} and {@code remove(Object)} (keys), {@code containsValue(Object)}
 *       (values), and {@code remove(Object, Object)} (the key and the value).</li>
 * </ul>
 */
public final class EqualityOperations {

    /** Where a comparison's type comes from: the receiver's or the first argument's view as a collection. */
    private enum From { RECEIVER, FIRST_ARGUMENT }

    /** One compared type: the view's type argument at {@code index}. */
    private record Comparison(From from, String view, int index) {
    }

    private record Rule(String owner, String method, List<String> parameters, List<Comparison> comparisons) {
    }

    private static final String JULC_LIST = "org.julclang.core.types.JulcList";
    private static final String JULC_MAP = "org.julclang.core.types.JulcMap";
    private static final String COLLECTION = "java.util.Collection";
    private static final String LIST = "java.util.List";
    private static final String MAP = "java.util.Map";
    private static final String OBJECT = "java.lang.Object";
    private static final String DATA = "org.julclang.core.PlutusData";

    private static final Comparison JULC_ELEMENT = new Comparison(From.RECEIVER, JULC_LIST, 0);
    private static final Comparison JULC_KEY = new Comparison(From.RECEIVER, JULC_MAP, 0);
    private static final Comparison ELEMENT = new Comparison(From.RECEIVER, COLLECTION, 0);
    private static final Comparison KEY = new Comparison(From.RECEIVER, MAP, 0);
    private static final Comparison VALUE = new Comparison(From.RECEIVER, MAP, 1);

    private static final List<Rule> RULES = List.of(
            new Rule(JULC_LIST, "contains", List.of(OBJECT), List.of(JULC_ELEMENT)),
            new Rule(JULC_MAP, "get", List.of(OBJECT), List.of(JULC_KEY)),
            new Rule(JULC_MAP, "lookup", List.of(OBJECT), List.of(JULC_KEY)),
            new Rule(JULC_MAP, "containsKey", List.of(OBJECT), List.of(JULC_KEY)),
            new Rule(JULC_MAP, "delete", List.of(OBJECT), List.of(JULC_KEY)),
            new Rule(JULC_MAP, "getOrDefault", List.of(OBJECT, OBJECT), List.of(JULC_KEY)),
            new Rule("org.julclang.stdlib.lib.MapLib", "lookup", List.of(JULC_MAP, DATA),
                    List.of(new Comparison(From.FIRST_ARGUMENT, JULC_MAP, 0))),
            new Rule("org.julclang.stdlib.lib.MapLib", "member", List.of(JULC_MAP, DATA),
                    List.of(new Comparison(From.FIRST_ARGUMENT, JULC_MAP, 0))),
            new Rule("org.julclang.stdlib.lib.MapLib", "delete", List.of(JULC_MAP, DATA),
                    List.of(new Comparison(From.FIRST_ARGUMENT, JULC_MAP, 0))),
            new Rule("org.julclang.stdlib.lib.ListsLib", "contains", List.of(JULC_LIST, DATA),
                    List.of(new Comparison(From.FIRST_ARGUMENT, JULC_LIST, 0))),
            new Rule(COLLECTION, "contains", List.of(OBJECT), List.of(ELEMENT)),
            new Rule(COLLECTION, "remove", List.of(OBJECT), List.of(ELEMENT)),
            new Rule(COLLECTION, "containsAll", List.of(COLLECTION), List.of(ELEMENT)),
            new Rule(COLLECTION, "removeAll", List.of(COLLECTION), List.of(ELEMENT)),
            new Rule(COLLECTION, "retainAll", List.of(COLLECTION), List.of(ELEMENT)),
            new Rule(LIST, "indexOf", List.of(OBJECT), List.of(ELEMENT)),
            new Rule(LIST, "lastIndexOf", List.of(OBJECT), List.of(ELEMENT)),
            new Rule(MAP, "get", List.of(OBJECT), List.of(KEY)),
            new Rule(MAP, "getOrDefault", List.of(OBJECT, OBJECT), List.of(KEY)),
            new Rule(MAP, "containsKey", List.of(OBJECT), List.of(KEY)),
            new Rule(MAP, "containsValue", List.of(OBJECT), List.of(VALUE)),
            new Rule(MAP, "remove", List.of(OBJECT), List.of(KEY)),
            new Rule(MAP, "remove", List.of(OBJECT, OBJECT), List.of(KEY, VALUE)));

    /** The operations in the table, as {@code owner.method(parameters) [compared]}, for reports. */
    public static final List<String> TABLE = tableText();

    /**
     * One compared type of an equality-using call: the operation ({@code Owner.method}), the static type, whether it
     * is a collection operation (membership or lookup, not {@code equals}), whether the compared values are elements,
     * keys or values of a JuLC collection (so an array of bytes among them is compared deeply after ADR-068 M1), and
     * whether the collection is raw, when the compared type is the argument's static type.
     */
    public record Site(String operation, TypeMirror compared, boolean collectionOperation, boolean julcCollection,
                       boolean raw) {
    }

    private final Types types;
    private final Elements elements;
    private final ArrayIdentityEquality views;

    public EqualityOperations(Types types, Elements elements) {
        this.types = types;
        this.elements = elements;
        this.views = new ArrayIdentityEquality(types, elements);
    }

    /**
     * The compared types of a call to {@code method}: none when the method compares no elements or keys, two for
     * {@code Map.remove(key, value)}.
     *
     * @param receiver  the static type of the receiver of an instance call (the enclosing class for an implicit
     *                  receiver), or null for a static call
     * @param arguments the static types of the arguments
     */
    public List<Site> sites(ExecutableElement method, TypeMirror receiver, List<? extends TypeMirror> arguments) {
        String name = method.getSimpleName().toString();
        var owner = (TypeElement) method.getEnclosingElement();
        boolean isStatic = method.getModifiers().contains(Modifier.STATIC);
        var parameters = method.getParameters().stream().map(p -> types.erasure(p.asType()).toString()).toList();
        if (!isStatic && name.equals("equals") && parameters.equals(List.of(OBJECT)) && receiver != null) {
            return List.of(new Site(owner.getSimpleName() + ".equals", receiver, false, false, false));
        }
        if (isStatic && name.equals("equals") && owner.getQualifiedName().contentEquals("java.util.Objects")
                && parameters.equals(List.of(OBJECT, OBJECT)) && arguments.size() == 2) {
            return List.of(new Site("Objects.equals", arguments.getFirst(), false, false, false));
        }
        for (Rule rule : RULES) {
            if (!rule.method().equals(name) || !rule.parameters().equals(parameters)) continue;
            var ruleOwner = elements.getTypeElement(rule.owner());
            if (ruleOwner == null || !isSubtype(owner, ruleOwner)) continue;
            String operation = owner.getSimpleName() + "." + name;
            var sites = new ArrayList<Site>();
            for (Comparison comparison : rule.comparisons()) {
                TypeMirror base = comparison.from() == From.RECEIVER ? (isStatic ? null : receiver)
                        : arguments.isEmpty() ? null : arguments.getFirst();
                if (base == null) continue;
                var view = views.asSuper(base, elements.getTypeElement(comparison.view()));
                boolean julc = !comparison.view().startsWith("java.util.");
                if (view == null) {
                    // Not resolvable as the collection: the base itself is compared, which the check reports.
                    sites.add(new Site(operation, base, true, julc, false));
                } else if (view.getTypeArguments().size() > comparison.index()) {
                    sites.add(new Site(operation, view.getTypeArguments().get(comparison.index()), true, julc, false));
                } else {
                    // A raw collection has no element or key type: the compared value's static type is all there is.
                    int argument = (comparison.from() == From.FIRST_ARGUMENT ? 1 : 0) + comparison.index();
                    if (arguments.size() > argument) sites.add(new Site(operation, arguments.get(argument), true, julc, true));
                }
            }
            return sites;
        }
        return List.of();
    }

    private boolean isSubtype(TypeElement type, TypeElement target) {
        return types.isSubtype(types.erasure(type.asType()), types.erasure(target.asType()));
    }

    private static List<String> tableText() {
        var text = new ArrayList<String>();
        text.add("*.equals(java.lang.Object) [receiver]");
        text.add("java.util.Objects.equals(java.lang.Object,java.lang.Object) [first argument]");
        for (Rule rule : RULES) {
            var compared = rule.comparisons().stream().map(c -> (c.from() == From.RECEIVER ? "receiver" : "first argument")
                    + " as " + c.view().substring(c.view().lastIndexOf('.') + 1) + "<" + c.index() + ">").toList();
            text.add(rule.owner() + "." + rule.method() + "(" + String.join(",", rule.parameters()) + ") ["
                    + String.join(", ", compared) + "]");
        }
        return List.copyOf(text);
    }
}
