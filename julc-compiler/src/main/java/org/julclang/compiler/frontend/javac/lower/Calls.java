package org.julclang.compiler.frontend.javac.lower;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import org.julclang.compiler.CompilationContext;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerTargetDiagnostics;
import org.julclang.compiler.CompilerTypeDiagnostics;
import org.julclang.compiler.LoweringRequirements;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.frontend.javac.NotPorted;
import org.julclang.compiler.pir.PirHelpers;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.pir.StdlibLookup;
import org.julclang.compiler.pir.TypeMethodRegistry;
import org.julclang.compiler.resolve.LibraryMethodRegistry;
import org.julclang.core.DefaultFun;

import java.util.ArrayList;
import java.util.List;

/**
 * Lowers a {@link CallTarget}. The reference's shapes are kept, including those that evaluate an operand twice or not in
 * Java's order, because what evaluates when is the handler's.
 * <p>
 * <b>Evaluation order.</b> Terms are built receiver first, then arguments from left to right, which is the order of
 * diagnostics. UPLC evaluates a builtin's arguments left to right, and {@link TypeMethodRegistry}'s and the stdlib's
 * handlers choose that order: most apply the receiver and then the arguments, as Java does. Not all: {@code prepend},
 * {@code insert} and {@code contains} evaluate their argument first, and {@code abs}, {@code min}, {@code max},
 * {@code signum} and {@code compareTo} evaluate an operand more than once (#146). A helper's arguments are applied left to
 * right. The order D6 fixes is {@link Operators}'.
 */
final class Calls {

    /** The lowered arguments of a call, and the type of each. */
    private record Arguments(List<PirTerm> terms, List<PirType> types) {
    }

    private final JavacFacts facts;
    private final Terms terms;
    private final Typing typing;
    private final CallTargets targets;
    private final Lowering lowering;
    private final CompilationContext context;
    private final StdlibLookup stdlib;
    private final TypeMethodRegistry typeMethods;
    /** The library methods called so far, as signatures: the same coercions as a compiled method's, no bodies. */
    private final LibraryMethodRegistry librarySites;

    Calls(JavacFacts facts, Terms terms, Typing typing, CallTargets targets, Lowering lowering, CompilationContext context,
          StdlibLookup stdlib, TypeMethodRegistry typeMethods) {
        this.facts = facts;
        this.terms = terms;
        this.typing = typing;
        this.targets = targets;
        this.lowering = lowering;
        this.context = context;
        this.stdlib = stdlib;
        this.typeMethods = typeMethods;
        this.librarySites = LibraryMethodRegistry.forCompilation(context);
    }

    PirTerm call(MethodInvocationTree tree, Frame frame) {
        return switch (targets.classify(tree)) {
            case CallTarget.Intrinsic intrinsic -> intrinsic(intrinsic, tree, frame);
            case CallTarget.Stdlib target -> stdlib(target, tree, frame);
            case CallTarget.Library target -> library(target, tree, frame);
            case CallTarget.Local target -> local(target, tree, frame);
            case CallTarget.Accessor target -> accessor(target, tree, frame);
            case CallTarget.Wrapper target -> wrapper(target, tree, frame);
            case CallTarget.TypeMethod target -> typeMethod(target, tree, frame);
            case CallTarget.Equals ignored -> equality(tree, frame);
            case CallTarget.Hof ignored -> throw NotPorted.at(facts, tree.getArguments().getFirst());
            case CallTarget.Unresolved target ->
                    throw CompilerTypeDiagnostics.unresolvedMember(target.member(), facts.location(tree));
            case CallTarget.Rejected target -> throw facts.rejection(target.message(), tree);
            case CallTarget.NotLowered target -> throw NotPorted.at(facts, tree, target.construct());
        };
    }

    private static ExpressionTree receiver(MethodInvocationTree tree) {
        return ((MemberSelectTree) tree.getMethodSelect()).getExpression();
    }

    private Arguments arguments(MethodInvocationTree tree, Frame frame) {
        var lowered = new ArrayList<PirTerm>();
        var types = new ArrayList<PirType>();
        for (var argument : tree.getArguments()) {
            var term = lowering.expr(argument, frame);
            lowered.add(term);
            types.add(typing.of(argument, term));
        }
        return new Arguments(lowered, types);
    }

    private void requireAvailable(LoweringRequirements requirements, MethodInvocationTree tree) {
        CompilerTargetDiagnostics.requireAvailable(context, requirements, facts.location(tree));
    }

    // --- Intrinsics ---

    private PirTerm intrinsic(CallTarget.Intrinsic intrinsic, MethodInvocationTree tree, Frame frame) {
        return switch (intrinsic) {
            case IDENTITY -> lowering.expr(tree.getArguments().getFirst(), frame);
            case CAST -> cast(tree, frame);
            case TO_DATA -> {
                var receiver = receiver(tree);
                var scope = lowering.expr(receiver, frame);
                yield PirHelpers.wrapEncode(scope, typing.of(receiver, scope));
            }
        };
    }

    /** {@code PlutusData.cast(d, T.class)} views {@code d} as {@code T}; a map variable holds a pair list, so it decodes. */
    private PirTerm cast(MethodInvocationTree tree, Frame frame) {
        if (!(tree.getArguments().get(1) instanceof MemberSelectTree literal && literal.getIdentifier().contentEquals("class"))) {
            throw facts.rejection("PlutusData.cast() second argument must be a literal ClassName.class expression", tree);
        }
        var inner = lowering.expr(tree.getArguments().getFirst(), frame);
        boolean toMap;
        try {
            toMap = typing.of(tree) instanceof PirType.MapType;
        } catch (NotPorted e) {
            toMap = false;
        }
        if (toMap && !(typing.ofTerm(inner) instanceof PirType.MapType)) {
            return new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnMapData), inner);
        }
        return inner;
    }

    // --- Static methods ---

    private PirTerm stdlib(CallTarget.Stdlib target, MethodInvocationTree tree, Frame frame) {
        var arguments = arguments(tree, frame);
        var result = lookup(target.className(), target.method(), arguments, tree);
        if (result != null) return result;
        if (target.className().equals("JulcMap") && target.method().equals("of")) {
            throw CompilerTypeDiagnostics.julcMapOfOffChainOnly(facts.location(tree));
        }
        throw NotPorted.at(facts, tree, "a method with no stdlib lowering");
    }

    private PirTerm lookup(String className, String method, Arguments arguments, MethodInvocationTree tree) {
        if (stdlib == null) return null;
        requireAvailable(stdlib.requirements(className, method), tree);
        return stdlib.lookup(context, className, method, arguments.terms(), arguments.types()).orElse(null);
    }

    /** The stdlib lookup comes first, as the reference composes them; a library method is then a call of its binder. */
    private PirTerm library(CallTarget.Library target, MethodInvocationTree tree, Frame frame) {
        var arguments = arguments(tree, frame);
        var result = lookup(target.className(), target.method(), arguments, tree);
        if (result != null) return result;
        if (librarySites.lookupMethod(target.className() + "." + target.method()).isEmpty()) {
            librarySites.register(target.className(), target.method(), facts.methodType(target.element()), null);
        }
        return librarySites.lookup(target.className(), target.method(), arguments.terms(), arguments.types()).orElseThrow();
    }

    /** A static method of this class: its binder, applied to each argument from left to right. */
    private PirTerm local(CallTarget.Local target, MethodInvocationTree tree, Frame frame) {
        var type = facts.methodType(target.element());
        PirTerm function = terms.method(target.binder(), type);
        var parameterTypes = LibraryMethodRegistry.getParamTypes(type);
        var arguments = tree.getArguments();
        for (int i = 0; i < arguments.size(); i++) {
            var argument = arguments.get(i);
            var term = lowering.expr(argument, frame);
            typing.requireNativeBoundary(target.element().getSimpleName() + " argument " + (i + 1),
                    typing.of(argument, term), parameterTypes.get(i), argument);
            function = new PirTerm.App(function, term);
        }
        return function;
    }

    // --- Instance methods ---

    private PirTerm accessor(CallTarget.Accessor target, MethodInvocationTree tree, Frame frame) {
        var receiver = receiver(tree);
        var scope = lowering.expr(receiver, frame);
        var record = (PirType.RecordType) typing.of(receiver);
        var fields = record.fields();
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).name().equals(target.component())) {
                return PirHelpers.fieldExtraction(scope, i, fields.get(i).type());
            }
        }
        throw NotPorted.at(facts, tree, "a component the type does not register");
    }

    /**
     * The value of a ledger hash or {@code @NewType} is its component, with the reference's lowering, which depends on where
     * the value came from: a hash read from a call has been decoded, and a variable may hold it as Data ({@code hash}'s
     * handler). Of the {@code @NewType}s, the registry has only what the underlying type does; the rest is the reference's
     * {@code JULC0055}, or for bytes its read of an accessor.
     */
    private PirTerm wrapper(CallTarget.Wrapper target, MethodInvocationTree tree, Frame frame) {
        var receiver = receiver(tree);
        var scope = lowering.expr(receiver, frame);
        var type = typing.of(receiver, scope);
        String member = target.component();
        boolean fromCall = receiver instanceof MethodInvocationTree;
        if (type instanceof PirType.ByteStringType && member.equals("hash") && fromCall
                && targets.typedCall((MethodInvocationTree) receiver)) {
            return scope;
        }
        String key = TypeKeys.of(type);
        if (typeMethods.contains(key, member)) {
            return dispatch(key, member, scope, type, new Arguments(List.of(), List.of()), tree);
        }
        if (type instanceof PirType.ByteStringType) {
            return fromCall ? scope : new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnBData), scope);
        }
        throw CompilerTypeDiagnostics.unresolvedMember(member, facts.location(tree));
    }

    private PirTerm typeMethod(CallTarget.TypeMethod target, MethodInvocationTree tree, Frame frame) {
        var receiver = receiver(tree);
        var scope = lowering.expr(receiver, frame);
        var type = typing.of(receiver, scope);
        var arguments = arguments(tree, frame);
        if (!TypeKeys.accepts(target.key(), type)) throw NotPorted.at(facts, tree, "a method of a subtype of an API type");
        if (!TypeKeys.accepts(target.key(), dispatchType(receiver, scope))) {
            throw CompilerTypeDiagnostics.unresolvedMember(target.method(), facts.location(tree));
        }
        if (type instanceof PirType.ListType list && target.method().equals("contains")) {
            arguments = encodedTarget(list, arguments);
        }
        return dispatch(target.key(), target.method(), scope, type, arguments, tree);
    }

    /**
     * D6, #297: {@code contains} decodes integers and bytes and compares anything else with {@code EqualsData}, so a target
     * that is not Data yet (a nested list or map, a string, a boolean) is encoded by its static type. The reference passes
     * it as it is, which fails on-chain. A D13 semantic fix; a target that was Data is the same term.
     */
    private static Arguments encodedTarget(PirType.ListType list, Arguments arguments) {
        var element = list.elemType();
        if (element instanceof PirType.IntegerType || element instanceof PirType.ByteStringType) return arguments;
        var target = arguments.terms().getFirst();
        var encoded = PirHelpers.wrapEncode(target, arguments.types().getFirst());
        return encoded == target ? arguments : new Arguments(List.of(encoded), List.of(new PirType.DataType()));
    }

    /**
     * The type the reference dispatches a member on. It knows no stdlib method's return type, so a member of one's result
     * is looked up on what the term says: {@code Optional.of(a).isPresent()} is {@code JULC0055}, and javac's type does not
     * widen the subset (I2). A list literal's term says it is a list, so its reads go on, typed by javac.
     */
    private PirType dispatchType(ExpressionTree receiver, PirTerm scope) {
        boolean stdlibCall = receiver instanceof MethodInvocationTree call && targets.classify(call) instanceof CallTarget.Stdlib;
        return stdlibCall ? typing.ofTerm(scope) : typing.of(receiver, scope);
    }

    /** {@code equals} is the receiver's: the key is what the receiver is, not the class that declares the method. */
    private PirTerm equality(MethodInvocationTree tree, Frame frame) {
        var receiver = receiver(tree);
        var scope = lowering.expr(receiver, frame);
        var type = dispatchType(receiver, scope);
        var arguments = arguments(tree, frame);
        String key = TypeKeys.of(type);
        if (!typeMethods.contains(key, "equals")) throw CompilerTypeDiagnostics.unresolvedMember("equals", facts.location(tree));
        return dispatch(key, "equals", scope, type, arguments, tree);
    }

    private PirTerm dispatch(String key, String method, PirTerm scope, PirType type, Arguments arguments,
                             MethodInvocationTree tree) {
        requireAvailable(typeMethods.requirementsExact(context, key, method), tree);
        var result = typeMethods.dispatchExact(context, key, method, scope, arguments.terms(), type, arguments.types())
                .orElseThrow(() -> new CompilerException("No lowering for " + key + "." + method));
        typeMethods.returnTypeExact(key, method, type).ifPresent(declared -> typing.record(result, declared));
        return result;
    }
}
