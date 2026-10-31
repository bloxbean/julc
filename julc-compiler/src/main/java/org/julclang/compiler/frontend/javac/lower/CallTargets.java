package org.julclang.compiler.frontend.javac.lower;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.frontend.javac.JavacNames;
import org.julclang.compiler.frontend.javac.LedgerLayouts;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.pir.TypeMethodRegistry;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import java.util.Map;
import java.util.Set;

/**
 * Resolves a method invocation to its {@link CallTarget}, as a function of the tree: it builds no term and changes nothing.
 * The method is the one javac bound (I12), and classification reads the receiver's static type too, because an inherited
 * method does not name it: {@code equals} on a {@code byte[]}, a sealed interface or {@code PlutusData} is
 * {@code Object.equals}, an element of none of them.
 * <p>
 * The shape of a receiver or qualifier is part of what the reference accepts, so it is read here, and parentheses are not
 * looked through. A static call needs a simple class name. A record is read through a variable, or a call whose type the
 * reference can see ({@link #typedCall}). Anything wider would widen the subset (I2).
 */
final class CallTargets {

    /** The list methods that take a function. */
    private static final Set<String> HOF_METHODS = Set.of("map", "filter", "any", "all", "find");

    /** The stdlib lookup's keys that are simple names: the rest are the owner's qualified name. */
    private static final Map<String, String> SIMPLE_STDLIB_KEYS = Map.of(
            JavacNames.MATH, "Math",
            JavacNames.JULC_LIST, "JulcList",
            JavacNames.JULC_MAP, "JulcMap",
            JavacNames.JULC_ARRAY, "JulcArray",
            JavacNames.OPTIONAL, "Optional");

    /**
     * A method has one lambda per declared parameter, and a call of a varargs method has any number of arguments, so
     * neither a helper nor a library method lowers. The stdlib's variadic methods build their own terms.
     */
    private static final CallTarget VARARGS = new CallTarget.NotLowered("a call of a varargs method");

    private final JavacFacts facts;
    private final Typing typing;
    private final TypeMethodRegistry typeMethods;

    CallTargets(JavacFacts facts, Typing typing, TypeMethodRegistry typeMethods) {
        this.facts = facts;
        this.typing = typing;
        this.typeMethods = typeMethods;
    }

    /** The method {@code tree} invokes, as javac bound it. */
    private ExecutableElement method(MethodInvocationTree tree) {
        return (ExecutableElement) facts.element(tree.getMethodSelect());
    }

    CallTarget classify(MethodInvocationTree tree) {
        var method = method(tree);
        var owner = (TypeElement) method.getEnclosingElement();
        if (!(tree.getMethodSelect() instanceof MemberSelectTree select)) return unqualified(tree, method, owner);
        return method.getModifiers().contains(Modifier.STATIC)
                ? qualifiedStatic(tree, select, method, owner)
                : instance(tree, select, method, owner);
    }

    private static String name(ExecutableElement method) {
        return method.getSimpleName().toString();
    }

    /** The reference binds a simple-name call to a static method of the class being lowered, and nothing else. */
    private CallTarget unqualified(MethodInvocationTree tree, ExecutableElement method, TypeElement owner) {
        var enclosing = facts.enclosing(tree, Tree.Kind.CLASS, Tree.Kind.RECORD, Tree.Kind.INTERFACE);
        if (method.getModifiers().contains(Modifier.STATIC) && owner.equals(facts.element(enclosing))) {
            return method.isVarArgs() ? VARARGS : new CallTarget.Local(facts.identity(owner) + "." + name(method), method);
        }
        return new CallTarget.NotLowered("a call of " + name(method) + " that is not a static method of this class");
    }

    private CallTarget qualifiedStatic(MethodInvocationTree tree, MemberSelectTree select, ExecutableElement method,
                                       TypeElement owner) {
        if (!(select.getExpression() instanceof IdentifierTree qualifier && facts.element(qualifier) instanceof TypeElement)) {
            return new CallTarget.NotLowered("a static call not qualified by a simple class name");
        }
        String identity = facts.identity(owner);
        String name = name(method);
        int arguments = tree.getArguments().size();
        if (identity.equals(JavacNames.BIG_INTEGER) && name.equals("valueOf") && arguments == 1) {
            return CallTarget.Intrinsic.IDENTITY;
        }
        if (name.equals("fromPlutusData") && arguments == 1 && inLedger(identity)
                && facts.registry().lookupNominal(facts.identity(owner)).isPresent()) {
            return CallTarget.Intrinsic.IDENTITY;
        }
        if (identity.equals(JavacNames.PLUTUS_DATA) && name.equals("cast") && arguments == 2) {
            return CallTarget.Intrinsic.CAST;
        }
        if (facts.inLibraryUnit(owner)) return library(identity, name, method);
        if (owner.equals(facts.element(facts.enclosing(tree, Tree.Kind.CLASS, Tree.Kind.RECORD, Tree.Kind.INTERFACE)))
                || facts.declaredInSource(method)) {
            return new CallTarget.Rejected("Undefined variable: " + qualifier.getName());
        }
        return new CallTarget.Stdlib(SIMPLE_STDLIB_KEYS.getOrDefault(identity, identity), name);
    }

    private static boolean inLedger(String identity) {
        return identity.startsWith(LedgerLayouts.PACKAGE + ".");
    }

    /** ADR-059: a generic method is a template, which only a language-neutral producer can specialize. */
    private CallTarget library(String identity, String name, ExecutableElement method) {
        if (method.isVarArgs()) return VARARGS;
        if (!method.getTypeParameters().isEmpty()) {
            return new CallTarget.Rejected("Generic library method " + identity + "." + name + " cannot be called from Java yet: "
                    + "generic @OnchainLibrary methods are specialized only by language-neutral library "
                    + "providers (ADR-059). Provide a concrete overload-free method for Java callers.");
        }
        return new CallTarget.Library(identity, name, method);
    }

    private CallTarget instance(MethodInvocationTree tree, MemberSelectTree select, ExecutableElement method,
                                TypeElement owner) {
        String name = name(method);
        var receiver = select.getExpression();
        if (isObjectEquals(method)) return equalsTarget(tree, receiver);
        String identity = facts.identity(owner);
        if (name.equals("toPlutusData") && tree.getArguments().isEmpty()) {
            // The reference encodes the value and ignores what a record declares: T31 again.
            return isApi(identity) ? CallTarget.Intrinsic.TO_DATA : new CallTarget.NotLowered("a declared toPlutusData");
        }
        if (isAccessor(method, owner)) return accessor(method, name, receiver);
        String key = TypeKeys.of(identity);
        if (key == null) return notInRegistry(tree, name, receiver);
        if (key.equals("ListType") && HOF_METHODS.contains(name)) return new CallTarget.Hof(name);
        return typeMethods.contains(key, name) ? new CallTarget.TypeMethod(key, name) : notInRegistry(tree, name, receiver);
    }

    private static boolean isApi(String identity) {
        return identity.startsWith("org.julclang.core.") || inLedger(identity);
    }

    private boolean isObjectEquals(ExecutableElement method) {
        return name(method).equals("equals") && method.getParameters().size() == 1
                && isObject(method.getParameters().getFirst().asType());
    }

    private boolean isObject(TypeMirror type) {
        return type.getKind() == TypeKind.DECLARED && facts.identity(facts.typeElement(type)).equals(JavacNames.OBJECT);
    }

    /**
     * Java's {@code a.equals(b)} between unrelated classes is false, while on-chain it compares representations: ADR-067's
     * cross-type {@code equals} (T29). {@code JULC0078} comes with the subset port; until then it does not lower. On an
     * array {@code equals} is identity, which the subset rejects ({@code JULC0087}), so this never reaches a term either.
     */
    private CallTarget equalsTarget(MethodInvocationTree tree, ExpressionTree receiver) {
        var types = facts.types();
        var left = types.erasure(facts.type(receiver));
        var right = types.erasure(facts.type(tree.getArguments().getFirst()));
        if (left.getKind() == TypeKind.ARRAY) return new CallTarget.NotLowered("an equals on an array");
        var boxed = right instanceof PrimitiveType primitive ? types.boxedClass(primitive).asType() : right;
        boolean related = types.isSubtype(left, boxed) || types.isSubtype(boxed, left);
        return related ? new CallTarget.Equals() : new CallTarget.NotLowered("an equals between unrelated types");
    }

    /** A record's accessor has the name of a component and no parameter, so nothing else can be one. */
    private boolean isAccessor(ExecutableElement method, TypeElement owner) {
        if (owner.getKind() != ElementKind.RECORD || !method.getParameters().isEmpty()) return false;
        return facts.components(owner).stream().anyMatch(component -> component.getSimpleName().equals(method.getSimpleName()));
    }

    private CallTarget accessor(ExecutableElement method, String name, ExpressionTree receiver) {
        // JuLC compiles a record as its components, so the reference ignores a declared accessor: T31, JULC0080 to come.
        if (facts.declaredInSource(method)) return new CallTarget.NotLowered("a declared accessor");
        var type = typing.of(receiver);
        if (!(type instanceof PirType.RecordType)) return wrapper(name, type);
        boolean visible = receiver instanceof IdentifierTree || receiver instanceof MethodInvocationTree call && typedCall(call);
        return visible ? new CallTarget.Accessor(name) : new CallTarget.Unresolved(name);
    }

    /**
     * The reference looks a wrapper's component up among the methods of its value, so a {@code @NewType} component named
     * {@code abs} lowers to an absolute value where Java reads the component. {@code value} and {@code hash} are the
     * names that mean the component.
     */
    private CallTarget wrapper(String name, PirType type) {
        boolean component = name.equals("value") || name.equals("hash");
        return typeMethods.contains(TypeKeys.of(type), name) && !component
                ? new CallTarget.NotLowered("a wrapper's component named like a method of its value")
                : new CallTarget.Wrapper(name);
    }

    /**
     * Whether the reference knows the type of {@code call} when it reads a member of the result. It knows a library
     * method's, a registry method's and a record component's, not a helper's or a stdlib intrinsic's: chaining on those is
     * {@code JULC0055}, which ADR-068 D4 keeps ({@code box("L", 2).amt().compareTo(...)}).
     */
    boolean typedCall(MethodInvocationTree call) {
        return switch (classify(call)) {
            case CallTarget.Accessor ignored -> true;
            case CallTarget.Wrapper ignored -> true;
            case CallTarget.Library ignored -> true;
            case CallTarget.TypeMethod ignored -> true;
            case CallTarget.Equals ignored -> true;
            case CallTarget.Intrinsic intrinsic -> intrinsic != CallTarget.Intrinsic.TO_DATA;
            default -> false;
        };
    }

    /**
     * No lowering on this receiver. The reference reads an unregistered no-argument method on bytes as an accessor of a
     * Data-wrapped hash, whatever the method: on the JVM {@code hash.toString()} is a string, so it does not lower here.
     */
    private CallTarget notInRegistry(MethodInvocationTree tree, String name, ExpressionTree receiver) {
        boolean bytes = tree.getArguments().isEmpty() && typing.of(receiver) instanceof PirType.ByteStringType;
        return bytes ? new CallTarget.NotLowered("the method " + name + " of a byte string") : new CallTarget.Unresolved(name);
    }
}
