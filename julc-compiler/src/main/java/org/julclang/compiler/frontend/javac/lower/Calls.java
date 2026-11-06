package org.julclang.compiler.frontend.javac.lower;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import org.julclang.compiler.CompilationContext;
import org.julclang.compiler.error.DiagnosticCodes;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerTargetDiagnostics;
import org.julclang.compiler.CompilerTypeDiagnostics;
import org.julclang.compiler.LoweringRequirements;
import org.julclang.compiler.error.CompilerDiagnostic;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.frontend.javac.JavacNames;
import org.julclang.compiler.pir.PirHelpers;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.pir.StdlibLookup;
import org.julclang.compiler.pir.TypeMethodRegistry;
import org.julclang.compiler.resolve.LibraryMethodRegistry;

import javax.lang.model.type.TypeKind;
import java.util.ArrayList;
import java.util.List;

/**
 * Lowers a {@link CallTarget}. The reference's shapes are kept, including those that evaluate an operand twice or not in
 * Java's order, because what evaluates when is the handler's.
 * <p>
 * <b>Evaluation order.</b> Terms are built receiver first, then arguments from left to right, which is the order of
 * diagnostics. UPLC evaluates a builtin's arguments left to right, and {@link TypeMethodRegistry}'s and the stdlib's
 * handlers choose that order: most apply the receiver and then the arguments, as Java does. Not all: {@code prepend},
 * {@code insert} and {@code contains} evaluate their argument first. {@code abs}, {@code min}, {@code max},
 * {@code signum} and {@code compareTo} read an operand more than once, so an operand that is not a variable or a
 * literal is bound first and evaluated once, as in Java ({@code PirHelpers.readOnce}, #146). A helper's arguments are
 * applied left to right. The order D6 fixes is {@link Operators}'.
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
    private final Casts casts;
    private final Lowering lowering;
    private final CompilationContext context;
    private final StdlibLookup stdlib;
    private final TypeMethodRegistry typeMethods;
    /** The library methods called so far, as signatures: the same coercions as a compiled method's, no bodies. */
    private final LibraryMethodRegistry librarySites;
    private final List<Lowering.LibraryCall> libraryCalls = new ArrayList<>();
    private final List<CompilerDiagnostic> warnings = new ArrayList<>();

    Calls(JavacFacts facts, Terms terms, Typing typing, CallTargets targets, Lambdas lambdas, Casts casts, Lowering lowering,
          CompilationContext context, StdlibLookup stdlib, TypeMethodRegistry typeMethods) {
        this.facts = facts;
        this.terms = terms;
        this.typing = typing;
        this.targets = targets;
        this.lambdas = lambdas;
        this.casts = casts;
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
            case CallTarget.Rejected target -> throw facts.rejection(target.info(), tree, target.arguments());
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
            case CAST -> casts.plutusDataCast(tree, frame);
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
        return terms.checkedDecode(data, target.type());
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
        // The reference resolves no such method and names it uncoded (I2a).
        throw facts.rejection(DiagnosticCodes.UNKNOWN_METHOD_ON_TYPE, tree, target.className() + "." + target.method());
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
                throw facts.rejection(DiagnosticCodes.DATA_VIEW_UNSUPPORTED, tree.getArguments().get(i), target.className() + "." + target.method(),
                        "the argument is Data where the builtin takes bytes; unBData converts it");
            }
        }
    }

    private PirTerm lookup(CallTarget.Keyed target, Arguments arguments, MethodInvocationTree tree) {
        if (stdlib == null) return null;
        requireAvailable(stdlib.requirements(target.className(), target.method()), tree);
        return stdlib.lookup(context, target.className(), target.method(), arguments.terms(), arguments.types()).orElse(null);
    }

    /**
     * The stdlib lookup comes first, as the reference composes them; a library method is then a call of its binder, which
     * {@link #libraryCalls} records. A unit the reference does not compile as a library has no binder, so the reference's
     * {@code Undefined variable} follows the lookup.
     */
    private PirTerm library(CallTarget.Library target, MethodInvocationTree tree, Frame frame) {
        var arguments = arguments(tree, frame);
        var qualifier = ((IdentifierTree) ((MemberSelectTree) tree.getMethodSelect()).getExpression()).getName().toString();
        var result = lookup(target, arguments, tree);
        if (result == null) {
            // The reference throws its message alone here, with no position; this has its code and the qualifier's.
            if (!target.bound()) {
                throw facts.rejection(DiagnosticCodes.UNDEFINED_VARIABLE, ((MemberSelectTree) tree.getMethodSelect()).getExpression(), qualifier);
            }
            String binder = target.className() + "." + target.method();
            libraryCalls.add(new Lowering.LibraryCall(binder, qualifier));
            if (librarySites.lookupMethod(binder).isEmpty()) {
                librarySites.register(target.className(), target.method(), facts.methodType(target.element()), null);
            }
            result = librarySites.lookup(target.className(), target.method(), arguments.terms(), arguments.types()).orElseThrow();
        }
        if (target.bound()) narrowerArguments(target, qualifier, tree, arguments);
        return result;
    }

    /**
     * The library calls lowered since the last drain, in the order they lowered: what a library method needs registered
     * before the reference can lower it.
     */
    List<Lowering.LibraryCall> drainLibraryCalls() {
        var drained = List.copyOf(libraryCalls);
        libraryCalls.clear();
        return drained;
    }

    /** What {@link #narrowerArguments} found since the last drain. */
    List<CompilerDiagnostic> drainWarnings() {
        var drained = List.copyOf(warnings);
        warnings.clear();
        return drained;
    }

    /**
     * The reference's warning for an argument of a primitive type passed where a library method takes Data. It looks the
     * method up by the class name as written, and its registry holds qualified names, so only a library of the unnamed
     * package is ever found.
     */
    private void narrowerArguments(CallTarget.Library target, String qualifier, MethodInvocationTree tree, Arguments arguments) {
        if (!target.className().equals(qualifier)) return;
        var expected = LibraryMethodRegistry.getParamTypes(facts.methodType(target.element()));
        for (int i = 0; i < arguments.types().size() && i < expected.size(); i++) {
            var callerType = arguments.types().get(i);
            if (!isSpecificPrimitive(callerType) || !(expected.get(i) instanceof PirType.DataType)) continue;
            String key = qualifier + "." + target.method();
            String name = LibraryMethodRegistry.pirTypeName(callerType);
            var at = facts.location(tree.getArguments().get(i));
            warnings.add(new CompilerDiagnostic(CompilerDiagnostic.Level.WARNING, "Argument " + (i + 1) + " to " + key
                    + "() has type " + name + " but the library method expects Data. The library will receive a decoded "
                    + "primitive instead of raw Data, which may cause runtime errors.", at.fileName(), at.line(), at.column(),
                    "Use PlutusData instead of " + name + " for this argument, or change the library method parameter type."));
        }
    }

    private static boolean isSpecificPrimitive(PirType type) {
        return type instanceof PirType.ByteStringType || type instanceof PirType.IntegerType || type instanceof PirType.BoolType
                || type instanceof PirType.StringType || type instanceof PirType.ListType || type instanceof PirType.MapType;
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
        throw CompilerTypeDiagnostics.unresolvedMember(target.component(), facts.location(tree));
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
        // A byte string's component is its bytes. Of another value, the reference looks the name up among the value's methods and
        // rejects it there (JULC0055), or reads a method: this keeps its rejection, so as not to accept what it refuses.
        if (type instanceof PirType.ByteStringType) return scope;
        throw CompilerTypeDiagnostics.unresolvedMember(member, facts.location(tree));
    }

    private PirTerm typeMethod(CallTarget.TypeMethod target, MethodInvocationTree tree, Frame frame) {
        var receiver = receiver(tree);
        var scope = lowering.expr(receiver, frame);
        var type = typing.of(receiver, scope);
        var arguments = arguments(tree, frame);
        if (!TypeKeys.accepts(target.key(), type)) throw CompilerTypeDiagnostics.unresolvedMember(target.method(), facts.location(tree));
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
