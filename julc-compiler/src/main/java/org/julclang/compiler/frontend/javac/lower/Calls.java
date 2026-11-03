package org.julclang.compiler.frontend.javac.lower;

import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.SwitchExpressionTree;
import org.julclang.compiler.CompilationContext;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerTargetDiagnostics;
import org.julclang.compiler.CompilerTypeDiagnostics;
import org.julclang.compiler.LoweringRequirements;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.frontend.javac.JavacNames;
import org.julclang.compiler.frontend.javac.NotPorted;
import org.julclang.compiler.frontend.javac.SwitchResults;
import org.julclang.compiler.pir.PirHelpers;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.pir.StdlibLookup;
import org.julclang.compiler.pir.TypeMethodRegistry;
import org.julclang.compiler.resolve.LibraryMethodRegistry;
import org.julclang.core.DefaultFun;

import javax.lang.model.element.Element;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Lowers a {@link CallTarget}. The reference's shapes are kept, including those that evaluate an operand twice or not in
 * Java's order, because what evaluates when is the handler's.
 * <p>
 * <b>Evaluation order.</b> Terms are built receiver first, then arguments from left to right, which is the order of
 * diagnostics. UPLC evaluates a builtin's arguments left to right, and {@link TypeMethodRegistry}'s and the stdlib's
 * handlers choose that order: most apply the receiver and then the arguments, as Java does. Not all: {@code prepend},
 * {@code insert} and {@code contains} evaluate their argument first, and {@code abs}, {@code min}, {@code max},
 * {@code signum} and {@code compareTo} evaluate an operand more than once (#146), unless it is a read of a method
 * ({@code PirHelpers.readOnce}). A helper's arguments are applied left to
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
    private final Lambdas lambdas;
    private final Lowering lowering;
    private final CompilationContext context;
    private final StdlibLookup stdlib;
    private final TypeMethodRegistry typeMethods;
    /** The library methods called so far, as signatures: the same coercions as a compiled method's, no bodies. */
    private final LibraryMethodRegistry librarySites;

    Calls(JavacFacts facts, Terms terms, Typing typing, CallTargets targets, Lambdas lambdas, Lowering lowering,
          CompilationContext context, StdlibLookup stdlib, TypeMethodRegistry typeMethods) {
        this.facts = facts;
        this.terms = terms;
        this.typing = typing;
        this.targets = targets;
        this.lambdas = lambdas;
        this.lowering = lowering;
        this.context = context;
        this.stdlib = stdlib;
        this.typeMethods = typeMethods;
        this.librarySites = LibraryMethodRegistry.forCompilation(context);
    }

    PirTerm call(MethodInvocationTree tree, Frame frame) {
        return switch (targets.classify(tree)) {
            case CallTarget.Intrinsic intrinsic -> intrinsic(intrinsic, tree, frame);
            case CallTarget.FromData target -> fromData(target, tree, frame);
            case CallTarget.Stdlib target -> stdlib(target, tree, frame);
            case CallTarget.Library target -> library(target, tree, frame);
            case CallTarget.Local target -> local(target, tree, frame);
            case CallTarget.Accessor target -> accessor(target, tree, frame);
            case CallTarget.Wrapper target -> wrapper(target, tree, frame);
            case CallTarget.TypeMethod target -> typeMethod(target, tree, frame);
            case CallTarget.Equals ignored -> equality(tree, frame);
            case CallTarget.Hof target -> hof(target, tree, frame);
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

    /**
     * {@code T.fromPlutusData(d)} for a ledger type {@code T} whose shape holds no map: {@code d} is evaluated once, then
     * checked whole for exactly {@code T}'s shape, the strict boundary's check, before anything reads it. The JVM's decoder
     * reads the same Data, and ignores what that shape has not; the script refuses it, so a value it accepts is the JVM's.
     * A hash or an integer is its decode, which is its check. The value is {@code d} as {@code T} holds it.
     */
    private PirTerm fromData(CallTarget.FromData target, MethodInvocationTree tree, Frame frame) {
        var argument = tree.getArguments().getFirst();
        var term = lowering.expr(argument, frame);
        var data = PirHelpers.wrapEncode(term, typing.of(argument, term));
        if (target.type() instanceof PirType.ByteStringType || target.type() instanceof PirType.IntegerType) {
            return terms.decode(data, target.type());
        }
        return terms.checkedDecode(data, target.type(), read -> facts.exactShapeCheck(read, target.type()));
    }

    /** {@code PlutusData.cast(d, T.class)} views {@code d} as {@code T}; a map variable holds a pair list, so it decodes. */
    private PirTerm cast(MethodInvocationTree tree, Frame frame) {
        if (!(tree.getArguments().get(1) instanceof MemberSelectTree literal && literal.getIdentifier().contentEquals("class"))) {
            throw facts.rejection("PlutusData.cast() second argument must be a literal ClassName.class expression", tree);
        }
        var argument = tree.getArguments().getFirst();
        var inner = lowering.expr(argument, frame);
        return toMap(tree) && !isMap(argument, inner) ? new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnMapData), inner) : inner;
    }

    private boolean toMap(MethodInvocationTree cast) {
        try {
            return typing.of(cast) instanceof PirType.MapType;
        } catch (NotPorted e) {
            return false;
        }
    }

    /**
     * Whether {@code inner} holds a decoded map, so a cast to a map decodes a value once, or not at all. javac's type goes
     * first, since a binder can say Data of a decoded value ({@link Lambdas}, a {@code var}), and it is the type of what
     * {@link #valuesOf} reaches: a cast that does not decode says nothing of the value. One term cannot decode one value it
     * may hold and leave another, so a cast of both stops.
     */
    private boolean isMap(ExpressionTree argument, PirTerm inner) {
        var values = new ArrayList<ExpressionTree>();
        valuesOf(argument, new HashSet<>(), values);
        if (values.size() == 1) {
            try {
                return typing.of(values.getFirst(), inner) instanceof PirType.MapType;
            } catch (NotPorted e) {
                return typing.ofTerm(inner) instanceof PirType.MapType;
            }
        }
        boolean map = typing.of(values.getFirst()) instanceof PirType.MapType;
        for (var value : values) {
            if (typing.of(value) instanceof PirType.MapType != map) {
                throw NotPorted.at(facts, argument, "a cast to a map of a value that holds another type on one path");
            }
        }
        return map;
    }

    /**
     * What {@code tree} may evaluate to, through parentheses, a choice of terms, a cast that does not decode and a variable,
     * each variable once. A {@code @Param}'s value is what is applied, not its initializer, so the read ends at it.
     */
    private void valuesOf(ExpressionTree tree, Set<Element> read, List<ExpressionTree> values) {
        switch (tree) {
            case ParenthesizedTree parenthesized -> valuesOf(parenthesized.getExpression(), read, values);
            case ConditionalExpressionTree conditional -> {
                valuesOf(conditional.getTrueExpression(), read, values);
                valuesOf(conditional.getFalseExpression(), read, values);
            }
            case SwitchExpressionTree selected -> SwitchResults.of(selected).forEach(result -> valuesOf(result, read, values));
            case MethodInvocationTree call when targets.classify(call) == CallTarget.Intrinsic.CAST && !toMap(call) ->
                    valuesOf(call.getArguments().getFirst(), read, values);
            case IdentifierTree name when facts.elementOrNull(name) instanceof VariableElement variable
                    && facts.initializer(variable) != null && !facts.hasAnnotation(variable, JavacNames.PARAM) -> {
                if (read.add(variable)) valuesOf(facts.initializer(variable), read, values);
            }
            default -> values.add(tree);
        }
    }

    // --- Static methods ---

    private PirTerm stdlib(CallTarget.Stdlib target, MethodInvocationTree tree, Frame frame) {
        var arguments = arguments(tree, frame);
        requireRepresentation(target, tree, arguments);
        var result = lookup(target, arguments, tree);
        if (result != null) {
            // A var of a literal is typed by its elements, so keep them with it, as the reference does (ADR-046).
            if (target.className().equals("JulcArray") && target.method().equals("of")) typing.recordArrayLiteral(result, arguments.types());
            return result;
        }
        if (target.className().equals("JulcMap") && target.method().equals("of")) {
            throw CompilerTypeDiagnostics.julcMapOfOffChainOnly(facts.location(tree));
        }
        throw NotPorted.at(facts, tree, "a method with no stdlib lowering");
    }

    /**
     * The lookup is keyed by name, and a name's handler applies its builtin to the term. So the overload that takes
     * {@code PlutusData.BytesData} where the builtin takes bytes needs a term that holds bytes: {@code Builtins.bData(a)} is
     * Data, and the builtin fails on it where Java goes on. {@code PlutusData} has no such check, since a term of that type
     * can hold a list or a text, and the stdlib's own bodies pass it so.
     */
    private void requireRepresentation(CallTarget.Stdlib target, MethodInvocationTree tree, Arguments arguments) {
        var parameters = target.element().getParameters();
        for (int i = 0; i < arguments.terms().size() && i < parameters.size(); i++) {
            var parameter = parameters.get(i).asType();
            boolean bytesData = parameter.getKind() == TypeKind.DECLARED
                    && facts.identity(facts.typeElement(parameter)).equals(JavacNames.BYTES_DATA);
            if (bytesData && !(arguments.types().get(i) instanceof PirType.ByteStringType)) {
                throw NotPorted.at(facts, tree.getArguments().get(i), "an argument of " + target.method() + " that is Data");
            }
        }
    }

    private PirTerm lookup(CallTarget.Keyed target, Arguments arguments, MethodInvocationTree tree) {
        if (stdlib == null) return null;
        requireAvailable(stdlib.requirements(target.className(), target.method()), tree);
        return stdlib.lookup(context, target.className(), target.method(), arguments.terms(), arguments.types()).orElse(null);
    }

    /** The stdlib lookup comes first, as the reference composes them; a library method is then a call of its binder. */
    private PirTerm library(CallTarget.Library target, MethodInvocationTree tree, Frame frame) {
        var arguments = arguments(tree, frame);
        var result = lookup(target, arguments, tree);
        if (result != null) return result;
        if (librarySites.lookupMethod(target.className() + "." + target.method()).isEmpty()) {
            librarySites.register(target.className(), target.method(), facts.methodType(target.element()), null);
        }
        return librarySites.lookup(target.className(), target.method(), arguments.terms(), arguments.types()).orElseThrow();
    }

    /** A static method of this class: its binder, applied to each argument from left to right. */
    private PirTerm local(CallTarget.Local target, MethodInvocationTree tree, Frame frame) {
        var type = facts.methodType(target.element());
        PirTerm function = terms.method(target.element(), target.binder(), type);
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
        if (!(targets.seen(receiver, scope) instanceof PirType.RecordType record)) {
            throw CompilerTypeDiagnostics.unresolvedMember(target.component(), facts.location(tree));
        }
        var fields = record.fields();
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).name().equals(target.component())) {
                var decoded = receiver instanceof IdentifierTree name
                        ? frame.patterns().binding(facts.element(name), target.component(), i) : null;
                return decoded != null ? terms.generated(decoded, fields.get(i).type())
                        : PirHelpers.fieldExtraction(scope, i, fields.get(i).type());
            }
        }
        throw NotPorted.at(facts, tree, "a component the type does not register");
    }

    /**
     * The value of a ledger hash or {@code @NewType} is its component. A byte string here was read from a call, and so is
     * decoded ({@link CallTargets} stops the rest). Of the {@code @NewType}s, the registry has only what the underlying
     * type does, which is {@code value}; the rest is the reference's {@code JULC0055}.
     */
    private PirTerm wrapper(CallTarget.Wrapper target, MethodInvocationTree tree, Frame frame) {
        var receiver = receiver(tree);
        var scope = lowering.expr(receiver, frame);
        var type = typing.of(receiver, scope);
        String member = target.component();
        // What find leaves is an Optional to the reference, which has no member of an element.
        var seen = targets.seen(receiver, scope);
        if (seen instanceof PirType.DataType || seen instanceof PirType.OptionalType) {
            throw CompilerTypeDiagnostics.unresolvedMember(member, facts.location(tree));
        }
        if (member.equals("value") && typeMethods.contains(TypeKeys.of(type), member)) {
            return dispatch(TypeKeys.of(type), member, scope, type, new Arguments(List.of(), List.of()), tree);
        }
        if (type instanceof PirType.ByteStringType) return scope;
        throw CompilerTypeDiagnostics.unresolvedMember(member, facts.location(tree));
    }

    private PirTerm typeMethod(CallTarget.TypeMethod target, MethodInvocationTree tree, Frame frame) {
        var receiver = receiver(tree);
        var scope = lowering.expr(receiver, frame);
        var type = typing.of(receiver, scope);
        var arguments = arguments(tree, frame);
        if (!TypeKeys.accepts(target.key(), type)) throw NotPorted.at(facts, tree, "a method of a subtype of an API type");
        var seen = targets.requireSeen(target.key(), target.method(), receiver, scope, tree);
        if (type instanceof PirType.ListType list && target.method().equals("contains")) {
            arguments = encodedTarget(list, arguments);
        }
        var result = dispatch(target.key(), target.method(), scope, type, arguments, tree);
        typeMethods.resolveReturnType(seen, target.method()).ifPresent(declared -> typing.recordRead(result, declared));
        return result;
    }

    /**
     * A list method that takes a function: the receiver, then the lambda, which the handler applies to each element.
     * {@link Lambdas} states the order. The reference reads the receiver as it reads a registry method's.
     */
    private PirTerm hof(CallTarget.Hof target, MethodInvocationTree tree, Frame frame) {
        var receiver = receiver(tree);
        var scope = lowering.expr(receiver, frame);
        var type = typing.of(receiver, scope);
        if (!(targets.seen(receiver, scope) instanceof PirType.ListType seen)) {
            throw CompilerTypeDiagnostics.unresolvedMember(target.method(), facts.location(tree));
        }
        var function = lambdas.function(tree, seen, target.method().equals("map"), frame);
        var arguments = new Arguments(List.of(function), List.of(typing.ofTerm(function)));
        var dispatched = dispatch(CallTarget.Hof.KEY, target.method(), scope, type, arguments, tree);
        var result = target.method().equals("find") && lambdas.readsElement(tree)
                ? PirHelpers.fieldExtraction(dispatched, 0, typing.of(tree)) : dispatched;
        typeMethods.resolveReturnType(seen, target.method()).ifPresent(declared -> typing.recordRead(result, declared));
        return result;
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
     * {@code equals} is the receiver's: the key is what the receiver is, not the class that declares the method. A read
     * through what the reference does not type is seen as Data, and holds what javac's element type decoded it to. So
     * {@code equals} takes the row of what it holds, and with no such row a value that is not Data has no {@code equals}.
     */
    private PirTerm equality(MethodInvocationTree tree, Frame frame) {
        var receiver = receiver(tree);
        var scope = lowering.expr(receiver, frame);
        var seen = targets.seen(receiver, scope);
        var held = typing.of(receiver, scope);
        boolean decoded = seen instanceof PirType.DataType && !targets.isStdlibCall(receiver)
                && typeMethods.contains(TypeKeys.of(held), "equals");
        var type = decoded ? held : seen;
        var arguments = arguments(tree, frame);
        String key = TypeKeys.of(type);
        boolean notData = seen instanceof PirType.DataType && !decoded && PirHelpers.wrapEncode(scope, held) != scope;
        if (notData || !typeMethods.contains(key, "equals")) {
            throw CompilerTypeDiagnostics.unresolvedMember("equals", facts.location(tree));
        }
        return dispatch(key, "equals", scope, type, arguments, tree);
    }

    private PirTerm dispatch(String key, String method, PirTerm scope, PirType type, Arguments arguments,
                             MethodInvocationTree tree) {
        requireAvailable(typeMethods.requirementsExact(context, key, method), tree);
        return typeMethods.dispatchExact(context, key, method, scope, arguments.terms(), type, arguments.types())
                .orElseThrow(() -> new CompilerException("No lowering for " + key + "." + method));
    }
}
