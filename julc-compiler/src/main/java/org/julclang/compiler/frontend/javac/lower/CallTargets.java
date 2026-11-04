package org.julclang.compiler.frontend.javac.lower;

import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.error.DiagnosticCodes;
import org.julclang.compiler.CompilerTypeDiagnostics;
import org.julclang.compiler.frontend.javac.ArrayIdentityEquality;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.frontend.javac.JavacNames;
import org.julclang.compiler.frontend.javac.LedgerLayouts;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.pir.TypeMethodRegistry;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.util.ElementFilter;
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
    private static final CallTarget VARARGS = new CallTarget.Rejected(DiagnosticCodes.EXPRESSION_UNSUPPORTED, "a call of a varargs method");

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
            // A library class declares only its concrete methods to itself: a template has no binder there (ADR-059).
            if (!method.getTypeParameters().isEmpty() && facts.inOfferedLibrary(owner) && !facts.specializes(method)) {
                return new CallTarget.Rejected(DiagnosticCodes.UNKNOWN_METHOD_ON_TYPE, name(method));
            }
            return method.isVarArgs() ? VARARGS : new CallTarget.Local(facts.identity(owner) + "." + name(method), method);
        }
        // The reference binds a simple name to a static method of the class it lowers, and names anything else unknown (I2a).
        return new CallTarget.Rejected(DiagnosticCodes.UNKNOWN_METHOD_ON_TYPE, name(method));
    }

    /**
     * A static call through a qualified class name, {@code java.math.BigInteger.valueOf(1)}: the reference resolves a static
     * call through its class's simple name and rejects this, uncoded, but for {@code JulcMap.of}, which it recognises by
     * either name ({@code JULC0062}). So here it is {@code JULC0123}, and {@code JULC0062} for {@code JulcMap.of}. A static
     * method reached through an expression that is no class name is not ported.
     */
    private CallTarget qualifiedByName(MemberSelectTree select, ExecutableElement method, TypeElement owner) {
        if (!(facts.element(select.getExpression()) instanceof TypeElement)) {
            // A static method through an expression: the reference reads a member of that value, which has none (JULC0055).
            return new CallTarget.Unresolved(name(method));
        }
        // Through a nested class's qualified name, Outer.Inner.f(a): the reference reads the outer name as a variable.
        var outermost = select.getExpression();
        while (outermost instanceof MemberSelectTree qualified) outermost = qualified.getExpression();
        if (outermost instanceof IdentifierTree outer && facts.element(outer) instanceof TypeElement) {
            return new CallTarget.Rejected(DiagnosticCodes.UNDEFINED_VARIABLE, outer.getName());
        }
        if (facts.identity(owner).equals(JavacNames.JULC_MAP) && name(method).equals("of")) {
            return new CallTarget.Rejected(DiagnosticCodes.JULCMAP_OF_OFF_CHAIN_ONLY);
        }
        return new CallTarget.Rejected(DiagnosticCodes.STATIC_CALL_QUALIFIED_CLASS_NAME, select.toString());
    }

    private CallTarget qualifiedStatic(MethodInvocationTree tree, MemberSelectTree select, ExecutableElement method,
                                       TypeElement owner) {
        if (!(select.getExpression() instanceof IdentifierTree qualifier && facts.element(qualifier) instanceof TypeElement)) {
            return qualifiedByName(select, method, owner);
        }
        String identity = facts.identity(owner);
        String name = name(method);
        int arguments = tree.getArguments().size();
        if (identity.equals(JavacNames.BIG_INTEGER) && name.equals("valueOf") && arguments == 1) {
            return CallTarget.Intrinsic.IDENTITY;
        }
        if (name.equals("fromPlutusData") && arguments == 1 && inLedger(identity)) {
            // The reference's identity checks nothing where the JVM decodes or throws, and leaves a hash undecoded: this
            // checks what the JVM's decoder reads, and decodes what the type holds (the entry points' slice).
            if (facts.registry().lookupNominal(facts.identity(owner)).isPresent()) {
                var type = facts.pirType(method.getReturnType(), tree);
                // A map may repeat a key, and the JVM's value holds each key once, so its Data is not that value; a Value
                // is a map, and its type here a record of it.
                return facts.holdsMap(type) ? new CallTarget.Rejected(DiagnosticCodes.DATA_VIEW_UNSUPPORTED, identity + ".fromPlutusData",
                        "a ledger type that holds a map, whose Data can repeat a key the JVM's map holds once")
                        : new CallTarget.FromData(type);
            }
        }
        if (identity.equals(JavacNames.PLUTUS_DATA) && name.equals("cast") && arguments == 2) {
            return CallTarget.Intrinsic.CAST;
        }
        // The reference's library registry holds the classes of every unit it compiles as a library, a root it was given in
        // the pool as well included: a bundled source the host did not give has only what the stdlib lookup lowers (D8).
        // Only a class's methods are lowered: an interface's, a record's or an enum's static method has no binder.
        if (facts.inOfferedLibrary(owner) && owner.getKind() == ElementKind.CLASS) return library(identity, name, method);
        if (facts.inLibraryUnit(owner) || facts.hasAnnotation(owner, JavacNames.ONCHAIN_LIBRARY)) {
            return new CallTarget.Library(identity, name, method, false);
        }
        if (owner.equals(facts.element(facts.enclosing(tree, Tree.Kind.CLASS, Tree.Kind.RECORD, Tree.Kind.INTERFACE)))
                || facts.declaredInSource(method)) {
            return new CallTarget.Rejected(DiagnosticCodes.UNDEFINED_VARIABLE, qualifier.getName());
        }
        return new CallTarget.Stdlib(SIMPLE_STDLIB_KEYS.getOrDefault(identity, identity), name, method);
    }

    private static boolean inLedger(String identity) {
        return identity.startsWith(LedgerLayouts.PACKAGE + ".");
    }

    /** ADR-059: a generic method is a template, which only a language-neutral producer can specialize. */
    private CallTarget library(String identity, String name, ExecutableElement method) {
        if (method.isVarArgs()) return VARARGS;
        // The reference's registry knows a name: any generic overload of it makes the name a template's.
        boolean template = ElementFilter.methodsIn(method.getEnclosingElement().getEnclosedElements()).stream()
                .anyMatch(other -> other.getSimpleName().equals(method.getSimpleName())
                        && other.getModifiers().contains(Modifier.STATIC) && !other.getTypeParameters().isEmpty());
        if (template) {
            return new CallTarget.Rejected(DiagnosticCodes.GENERIC_LIBRARY_CALL_UNSUPPORTED, identity + "." + name);
        }
        return new CallTarget.Library(identity, name, method, true);
    }

    private CallTarget instance(MethodInvocationTree tree, MemberSelectTree select, ExecutableElement method,
                                TypeElement owner) {
        String name = name(method);
        var receiver = select.getExpression();
        if (isObjectEquals(method)) return equalsTarget(receiver);
        String identity = facts.identity(owner);
        if (name.equals("toPlutusData") && tree.getArguments().isEmpty()) {
            // The reference encodes the value and ignores what a record declares: T31 again.
            return isApi(identity) ? CallTarget.Intrinsic.TO_DATA
                    : new CallTarget.Rejected(DiagnosticCodes.METHOD_NAME_READ_BY_SPELLING, facts.identity(owner) + ".toPlutusData");
        }
        if (isAccessor(method, owner)) {
            // A record reads a component from a constructor, and this Data has none.
            if (LedgerLayouts.NOT_CONSTRUCTOR_DATA.contains(identity)) {
                return new CallTarget.Rejected(DiagnosticCodes.DATA_VIEW_UNSUPPORTED, identity + "." + name,
                        "a component of a ledger type whose Data is a map, not a constructor");
            }
            return accessor(name, receiver);
        }
        String key = TypeKeys.of(identity);
        if (key == null) return notInRegistry(name);
        if (key.equals(CallTarget.Hof.KEY) && HOF_METHODS.contains(name)) return new CallTarget.Hof(name);
        return typeMethods.contains(key, name) ? new CallTarget.TypeMethod(key, name) : notInRegistry(name);
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
     * On an array {@code equals} is identity, which the subset rejects ({@code JULC0087}) before lowering; a method lowered on
     * its own meets the same rule here, in the scan's words, and reaches no term either.
     */
    private CallTarget equalsTarget(ExpressionTree receiver) {
        var type = facts.type(receiver);
        if (facts.types().erasure(type).getKind() != TypeKind.ARRAY) return new CallTarget.Equals();
        return new CallTarget.Rejected(DiagnosticCodes.ARRAY_EQUALS_UNSUPPORTED, "Object.equals", ArrayIdentityEquality.text(type),
                "compares an array by identity");
    }

    /** A record's accessor has the name of a component and no parameter, so nothing else can be one. */
    private boolean isAccessor(ExecutableElement method, TypeElement owner) {
        if (owner.getKind() != ElementKind.RECORD || !method.getParameters().isEmpty()) return false;
        return facts.components(owner).stream().anyMatch(component -> component.getSimpleName().equals(method.getSimpleName()));
    }

    private CallTarget accessor(String name, ExpressionTree receiver) {
        var type = typing.of(receiver);
        if (!(type instanceof PirType.RecordType)) return wrapper(name, type, receiver);
        boolean visible = receiver instanceof IdentifierTree || receiver instanceof MethodInvocationTree call && typedCall(call);
        return visible ? new CallTarget.Accessor(name) : new CallTarget.Unresolved(name);
    }

    /**
     * The reference looks a wrapper's component up among the methods of its value, so a {@code @NewType} component named
     * {@code abs} lowers to an absolute value where Java reads the component. {@code value} and {@code hash} are the
     * names that mean the component.
     */
    private CallTarget wrapper(String name, PirType type, ExpressionTree receiver) {
        return new CallTarget.Wrapper(name);
    }

    /** Whether {@code tree} is a call of an API static method, whose result's type the reference does not know. */
    boolean isStdlibCall(ExpressionTree tree) {
        return tree instanceof MethodInvocationTree call && classify(call) instanceof CallTarget.Stdlib;
    }

    /**
     * The type the reference gives {@code tree}, whose lowered term is {@code term}: what a member is looked up on and what a
     * {@code var} binds. It knows a {@code new} and the result of a helper, a library method or an accessor; of anything
     * else it reads the term, which says what a variable was bound with, and what a registry method declares on the
     * receiver the reference saw. So {@code Optional.of(a).isPresent()} and {@code JulcList.of(x).head().add(y)} are
     * {@code JULC0055}, and so are they through a {@code var}, parentheses or a conditional: javac knowing the type does not
     * widen the subset (I2). What a read decodes to is still javac's, which is {@link Typing#of}'s.
     */
    PirType seen(ExpressionTree tree, PirTerm term) {
        // A cast is seen as its type, as the reference reads it, unless that is Data: then as what it casts.
        if (tree instanceof TypeCastTree cast) {
            PirType written;
            try {
                written = typing.of(cast);
            } catch (CompilerException e) {
                written = new PirType.DataType();
            }
            return written instanceof PirType.DataType ? seen(cast.getExpression(), term) : written;
        }
        boolean known = tree instanceof NewClassTree || tree instanceof MethodInvocationTree call
                && !(classify(call) instanceof CallTarget.Stdlib || classify(call) instanceof CallTarget.TypeMethod
                || classify(call) instanceof CallTarget.Hof);
        return known ? typing.of(tree, term) : typing.ofVariable(term);
    }

    /**
     * {@link #seen}, for a read of {@code member} on a type of the registry's {@code key}. Every member a receiver's type
     * decides is read through this: {@code JULC0055} where the reference does not see the type, whatever javac knows.
     */
    PirType requireSeen(String key, String member, ExpressionTree receiver, PirTerm term, Tree at) {
        var seen = seen(receiver, term);
        if (!TypeKeys.accepts(key, seen)) throw CompilerTypeDiagnostics.unresolvedMember(member, facts.location(at));
        return seen;
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
            case CallTarget.Hof ignored -> true;
            case CallTarget.Equals ignored -> true;
            case CallTarget.FromData ignored -> true;
            case CallTarget.Intrinsic intrinsic -> intrinsic != CallTarget.Intrinsic.TO_DATA;
            default -> false;
        };
    }

    /**
     * No lowering on this receiver: {@code JULC0055}. The reference reads an unregistered no-argument method on bytes as an
     * accessor of a Data-wrapped hash, whatever the method, where on the JVM {@code hash.toString()} is a string; the gate
     * rejects {@code toString} and {@code hashCode} there ({@code JULC0101}), and no such method has an on-chain value.
     */
    private CallTarget notInRegistry(String name) {
        return new CallTarget.Unresolved(name);
    }
}
