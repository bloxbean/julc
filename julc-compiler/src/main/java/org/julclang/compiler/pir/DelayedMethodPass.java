package org.julclang.compiler.pir;

import org.julclang.compiler.debug.PirDebugProvenance;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.julclang.core.DefaultUni;
import org.julclang.core.cbor.PlutusDataCborEncoder;
import org.julclang.core.source.SourceLocation;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/**
 * A method with no parameters is a value, so a {@code let} would run its body once at script start, whether or not it is
 * called. Java runs it at each call. A body that cannot fail and does little work may stay a shared {@code let}: its value
 * and its failure are Java's, and what it costs at script start is small ({@link #WORK_LIMIT}). Any other body becomes a
 * function of a unit, and each read of its binder a call (ADR-068, open question 5).
 * <p>
 * <b>Why this runs on the assembled term.</b> Both frontends lower a call of such a method to a read of its binder, and
 * a call site can come before the method's body is lowered, so no call site knows yet whether the body may fail. By
 * assembly every body exists. A read stays a {@code Var} until here, so a handler that takes one must not treat it as
 * having no effect: {@link PirHelpers#isMethodRead}. {@code PirBackend} runs this after the literal folds, so a body they
 * reduce to a constant stays shared, and before the passes that move a conversion, which must see a delayed read as a call.
 * <p>
 * <b>{@link #mayFail} decides, and it is conservative:</b> only what it can show is total is total. A call of a bound,
 * non-recursive function is read through its body, with what is known of each argument in scope, so
 * {@code ByteStringLib.zeros(32)} is total and {@code zeros(n)} is not. Bindings are walked from the outside in, and a
 * method's dependencies are bound outside it, so a method that reads a delayed one is a call and is delayed too.
 * <p>
 * <b>What stays strict.</b> This pass sees the methods of a validator or a {@code compileMethod} program. A library
 * reached through a provider import (ADR-059) is linked by the backend as a definition, so a method with no parameters
 * there still runs once when the program starts, whether or not it is called, and fails the program if it fails.
 */
public final class DelayedMethodPass {

    /** The kind of value a total term leaves. A term that may fail has none. */
    private enum Kind { INTEGER, BYTES, STRING, BOOL, UNIT, DATA, DATA_LIST, PAIR_LIST, OTHER }

    /**
     * What is known of a total term's value: its kind, an upper bound on its size in bytes, and its constant, or its
     * function and that function's scope. A function that reaches a parameter is a value of {@link Kind#OTHER}: what a
     * call of a parameter runs is not known.
     *
     * @param work a count of what evaluating the term runs, in {@link #WORK_LIMIT}'s units. A value held by a variable has
     *             been evaluated, so a read counts as a read, whatever the value took
     */
    private record Known(Kind kind, long size, Constant constant, PirTerm.Lam function, Map<String, Known> scope, long work) {
        static Known of(Kind kind, long size) {
            return new Known(kind, size, null, null, null, 0);
        }

        static Known of(Constant constant) {
            return new Known(kindOf(constant), sizeOf(constant), constant, null, null, 0);
        }

        static Known of(PirTerm.Lam function, Map<String, Known> scope) {
            return new Known(Kind.OTHER, 0, null, function, scope, 0);
        }

        /** This value, with {@code more} run to reach it. */
        Known after(long more) {
            return new Known(kind, size, constant, function, scope, work + more);
        }

        /** This value as a variable holds it. */
        Known held() {
            return after(-work);
        }

        Known asArgument() {
            return function == null ? held() : of(Kind.OTHER, 0);
        }
    }

    /**
     * A builtin's kinds, what its arguments must show when those kinds are not enough, and an upper bound on the size of its
     * result: by default one more than its arguments', which a builtin here exceeds only if it is {@link Signature#sized}.
     */
    private record Signature(List<Kind> parameters, Kind result, Predicate<List<Known>> holds,
                             ToLongFunction<List<Known>> size) {
        static Signature of(Kind result, Kind... parameters) {
            return new Signature(List.of(parameters), result, arguments -> true,
                    arguments -> 1 + arguments.stream().mapToLong(Known::size).sum());
        }

        Signature when(Predicate<List<Known>> holds) {
            return new Signature(parameters, result, holds, size);
        }

        Signature sized(ToLongFunction<List<Known>> size) {
            return new Signature(parameters, result, holds, size);
        }
    }

    /**
     * The most work a body may do and stay a strict {@code let}. Work counts each constant, read, function, {@code let},
     * branch, application and constructor it evaluates as 1, and each byte a builtin's arguments or a string field hold as
     * 1, a value built by a constructor holding its fields' bytes. It is a count of what a body runs, not of what each part
     * costs: a hash of no bytes costs more than a read. A body past it is read as one that may fail.
     * {@code MerklePatriciaForestry.nullHash2}, the largest of the shared bodies a program uses, does 141. No argument
     * within it reaches what a builtin here refuses: Plutus 1.63.0.0's semantics variants D and E bound integers to 2^262143
     * and byte strings to 65,536 bytes ({@code Universe/Cardano.hs}).
     */
    private static final long WORK_LIMIT = 1024;

    /** {@code maximumOutputLength} in Plutus 1.63.0.0's {@code PlutusCore/Bitwise.hs}. */
    private static final long REPLICATE_LIMIT = 8192;

    /**
     * The builtins that return, on arguments of these kinds that show what {@link Signature#holds} asks. Nothing else is
     * listed, so nothing else is total.
     */
    private static final Map<DefaultFun, Signature> TOTAL_BUILTINS = totalBuiltins();

    private static Map<DefaultFun, Signature> totalBuiltins() {
        var total = new HashMap<DefaultFun, Signature>();
        for (var fun : List.of(DefaultFun.AddInteger, DefaultFun.SubtractInteger, DefaultFun.MultiplyInteger))
            total.put(fun, Signature.of(Kind.INTEGER, Kind.INTEGER, Kind.INTEGER));
        for (var fun : List.of(DefaultFun.EqualsInteger, DefaultFun.LessThanInteger, DefaultFun.LessThanEqualsInteger))
            total.put(fun, Signature.of(Kind.BOOL, Kind.INTEGER, Kind.INTEGER));
        total.put(DefaultFun.AppendByteString, Signature.of(Kind.BYTES, Kind.BYTES, Kind.BYTES));
        for (var fun : List.of(DefaultFun.EqualsByteString, DefaultFun.LessThanByteString, DefaultFun.LessThanEqualsByteString))
            total.put(fun, Signature.of(Kind.BOOL, Kind.BYTES, Kind.BYTES));
        total.put(DefaultFun.LengthOfByteString, Signature.of(Kind.INTEGER, Kind.BYTES));
        // A digest's size is its own, and an empty input gives the same 32 bytes as a long one.
        for (var fun : List.of(DefaultFun.Sha2_256, DefaultFun.Sha3_256, DefaultFun.Blake2b_256, DefaultFun.Keccak_256))
            total.put(fun, Signature.of(Kind.BYTES, Kind.BYTES).sized(arguments -> 32));
        total.put(DefaultFun.Blake2b_224, Signature.of(Kind.BYTES, Kind.BYTES).sized(arguments -> 28));
        total.put(DefaultFun.Ripemd_160, Signature.of(Kind.BYTES, Kind.BYTES).sized(arguments -> 20));
        total.put(DefaultFun.ReplicateByte, Signature.of(Kind.BYTES, Kind.INTEGER, Kind.INTEGER)
                .when(arguments -> constantIn(arguments.get(0), REPLICATE_LIMIT) && constantIn(arguments.get(1), 255))
                .sized(arguments -> ((Constant.IntegerConst) arguments.get(0).constant()).value().longValueExact()));
        total.put(DefaultFun.AppendString, Signature.of(Kind.STRING, Kind.STRING, Kind.STRING));
        total.put(DefaultFun.EqualsString, Signature.of(Kind.BOOL, Kind.STRING, Kind.STRING));
        total.put(DefaultFun.EncodeUtf8, Signature.of(Kind.BYTES, Kind.STRING));
        total.put(DefaultFun.IData, Signature.of(Kind.DATA, Kind.INTEGER));
        total.put(DefaultFun.BData, Signature.of(Kind.DATA, Kind.BYTES));
        total.put(DefaultFun.ConstrData, Signature.of(Kind.DATA, Kind.INTEGER, Kind.DATA_LIST)
                .when(arguments -> constantIn(arguments.get(0), Long.MAX_VALUE)));
        total.put(DefaultFun.ListData, Signature.of(Kind.DATA, Kind.DATA_LIST));
        total.put(DefaultFun.MapData, Signature.of(Kind.DATA, Kind.PAIR_LIST));
        total.put(DefaultFun.EqualsData, Signature.of(Kind.BOOL, Kind.DATA, Kind.DATA));
        total.put(DefaultFun.MkNilData, Signature.of(Kind.DATA_LIST, Kind.UNIT));
        total.put(DefaultFun.MkNilPairData, Signature.of(Kind.PAIR_LIST, Kind.UNIT));
        total.put(DefaultFun.MkCons, Signature.of(Kind.DATA_LIST, Kind.DATA, Kind.DATA_LIST));
        return Map.copyOf(total);
    }

    private static boolean constantIn(Known argument, long most) {
        return argument.constant() instanceof Constant.IntegerConst integer && integer.value().signum() >= 0
                && integer.value().compareTo(BigInteger.valueOf(most)) <= 0;
    }

    /**
     * Whether evaluating {@code term} may do anything but return a value: fail, or trace, which a {@code let} would do at
     * another time than Java does, or run past {@link #WORK_LIMIT}, which a {@code let} would charge a script that does not
     * call it. A variable the term does not bind, and {@code env} does not hold, may fail.
     *
     * @param env the constant argument of each parameter in scope
     */
    public static boolean mayFail(PirTerm term, Map<String, Constant> env) {
        var bound = new HashMap<String, Known>();
        env.forEach((name, constant) -> bound.put(name, Known.of(constant)));
        return known(term, bound, new Reading()) == null;
    }

    /** {@link #mayFail(PirTerm, Map)} of a term that takes no argument. */
    public static boolean mayFail(PirTerm term) {
        return mayFail(term, Map.of());
    }

    /**
     * One reading of a term.
     *
     * @param calling the functions being read through, which a cycle would meet again
     * @param results what each function gave on each argument, by the {@link Known} that holds it, so a function read many
     *                times is walked once. A null ends the reading, whatever {@code calling} held when it came.
     */
    private record Reading(Set<String> calling, Map<Known, Map<List<Known>, Optional<Known>>> results) {
        Reading() {
            this(new HashSet<>(), new IdentityHashMap<>());
        }
    }

    /**
     * What is known of the value of {@code term}, or null if it may fail. The work of a term includes its parts', so a term
     * past {@link #WORK_LIMIT} ends the reading, and no count grows past it.
     *
     * @param bound the variables bound to a total term in scope, and what is known of each
     */
    private static Known known(PirTerm term, Map<String, Known> bound, Reading reading) {
        var value = evaluated(term, bound, reading);
        return value == null || value.work() > WORK_LIMIT ? null : value;
    }

    private static Known evaluated(PirTerm term, Map<String, Known> bound, Reading reading) {
        return switch (term) {
            case PirTerm.Const constant -> Known.of(constant.value()).after(1);
            case PirTerm.Var variable -> bound.containsKey(variable.name()) ? bound.get(variable.name()).after(1) : null;
            case PirTerm.Lam lam -> Known.of(lam, bound).after(1);
            case PirTerm.Builtin _ -> Known.of(Kind.OTHER, 0);
            case PirTerm.Let let -> {
                var value = known(let.value(), bound, reading);
                var body = value == null ? null : known(let.body(), with(bound, let.name(), value), reading);
                yield body == null ? null : body.after(value.work() + 1);
            }
            case PirTerm.IfThenElse branch -> {
                var condition = known(branch.cond(), bound, reading);
                if (condition == null || condition.kind() != Kind.BOOL) yield null;
                var then = known(branch.thenBranch(), bound, reading);
                if (then == null) yield null;
                var otherwise = known(branch.elseBranch(), bound, reading);
                if (otherwise == null) yield null;
                yield Known.of(then.kind() == otherwise.kind() ? then.kind() : Kind.OTHER, Math.max(then.size(), otherwise.size()))
                        .after(condition.work() + Math.max(then.work(), otherwise.work()) + 1);
            }
            case PirTerm.App app -> called(app, bound, reading);
            case PirTerm.DataConstr constr -> constructed(constr, bound, reading);
            default -> null;
        };
    }

    private static Map<String, Known> with(Map<String, Known> bound, String name, Known value) {
        var inner = new HashMap<>(bound);
        inner.put(name, value.held());
        return inner;
    }

    private static Kind kindOf(Constant constant) {
        return switch (constant) {
            case Constant.IntegerConst _ -> Kind.INTEGER;
            case Constant.ByteStringConst _ -> Kind.BYTES;
            case Constant.StringConst _ -> Kind.STRING;
            case Constant.BoolConst _ -> Kind.BOOL;
            case Constant.UnitConst _ -> Kind.UNIT;
            case Constant.DataConst _ -> Kind.DATA;
            case Constant.ListConst list when list.elemType() instanceof DefaultUni.Data -> Kind.DATA_LIST;
            default -> Kind.OTHER;
        };
    }

    private static long sizeOf(Constant constant) {
        return switch (constant) {
            case Constant.IntegerConst integer -> integer.value().bitLength() / 8 + 1;
            case Constant.ByteStringConst bytes -> bytes.value().length;
            case Constant.StringConst string -> string.value().getBytes(StandardCharsets.UTF_8).length;
            case Constant.DataConst data -> PlutusDataCborEncoder.encode(data.value()).length;
            case Constant.ListConst list -> list.values().stream().mapToLong(DelayedMethodPass::sizeOf).sum();
            default -> 0;
        };
    }

    /** A builtin in the table, or a bound function, applied to all its arguments, each total. */
    private static Known called(PirTerm.App app, Map<String, Known> bound, Reading reading) {
        var terms = new ArrayDeque<PirTerm>();
        PirTerm head = app;
        while (head instanceof PirTerm.App next) {
            terms.addFirst(next.argument());
            head = next.function();
        }
        var arguments = new ArrayList<Known>();
        long work = 1;
        for (var term : terms) {
            var argument = known(term, bound, reading);
            if (argument == null) return null;
            work += argument.work();
            arguments.add(argument.asArgument());
        }
        Known result = null;
        if (head instanceof PirTerm.Builtin builtin) {
            result = builtinResult(builtin.fun(), arguments);
        } else if (head instanceof PirTerm.Var function && bound.get(function.name()) instanceof Known callee && callee.function() != null) {
            result = functionResult(function.name(), callee, arguments, reading);
        }
        return result == null ? null : result.after(work);
    }

    private static Known builtinResult(DefaultFun fun, List<Known> arguments) {
        var signature = TOTAL_BUILTINS.get(fun);
        if (signature == null || !signature.parameters().equals(arguments.stream().map(Known::kind).toList())) return null;
        if (!signature.holds().test(arguments)) return null;
        return Known.of(signature.result(), signature.size().applyAsLong(arguments)).after(arguments.stream().mapToLong(Known::size).sum());
    }

    /** The result of a function's body, with each parameter bound to its argument: all of them, or the call may fail. */
    private static Known functionResult(String name, Known callee, List<Known> arguments, Reading reading) {
        if (!reading.calling().add(name)) return null;
        var results = reading.results().computeIfAbsent(callee, ignored -> new HashMap<>());
        var result = results.get(arguments);
        if (result == null) {
            result = Optional.ofNullable(bodyResult(callee, arguments, reading));
            results.put(arguments, result);
        }
        reading.calling().remove(name);
        return result.orElse(null);
    }

    private static Known bodyResult(Known callee, List<Known> arguments, Reading reading) {
        var scope = callee.scope();
        PirTerm body = callee.function();
        for (var argument : arguments) {
            if (!(body instanceof PirTerm.Lam lam)) return null;
            scope = with(scope, lam.param(), argument);
            body = lam.body();
        }
        return body instanceof PirTerm.Lam ? null : known(body, scope, reading);
    }

    /** A constructor is Data when each field is total and of the kind its declared type encodes. A string field encodes each byte it holds. */
    private static Known constructed(PirTerm.DataConstr constr, Map<String, Known> bound, Reading reading) {
        var fields = fieldTypes(constr);
        if (fields == null || fields.size() != constr.fields().size() || constr.tag() < 0) return null;
        long size = 1;   // Held at the limit: past it any builtin that reads the value ends the reading, and sharing cannot wrap it round.
        long work = 1;
        for (int i = 0; i < fields.size(); i++) {
            var field = known(constr.fields().get(i), bound, reading);
            if (field == null || field.kind() != encoded(fields.get(i))) return null;
            size = Math.min(size + field.size(), WORK_LIMIT + 1);
            work += field.work() + (field.kind() == Kind.STRING ? field.size() : 0);
        }
        return Known.of(Kind.DATA, size).after(work);
    }

    private static List<PirType> fieldTypes(PirTerm.DataConstr constr) {
        if (constr.dataType() instanceof PirType.RecordType record) {
            return record.fields().stream().map(PirType.Field::type).toList();
        }
        if (constr.dataType() instanceof PirType.SumType sum) {
            for (var constructor : sum.constructors()) {
                if (constructor.tag() == constr.tag()) {
                    return constructor.fields().stream().map(PirType.Field::type).toList();
                }
            }
        }
        return null;
    }

    private static Kind encoded(PirType field) {
        return switch (field) {
            case PirType.IntegerType _ -> Kind.INTEGER;
            case PirType.ByteStringType _ -> Kind.BYTES;
            case PirType.StringType _ -> Kind.STRING;
            case PirType.BoolType _ -> Kind.BOOL;
            case PirType.ListType _ -> Kind.DATA_LIST;
            case PirType.MapType _ -> Kind.PAIR_LIST;
            case PirType.DataType _, PirType.RecordType _, PirType.SumType _, PirType.NamedTypeRef _ -> Kind.DATA;
            default -> null;
        };
    }

    public record Result(PirTerm term, Map<PirTerm, SourceLocation> positions) {}

    private static final String RULE = "adr068.delayed-method";

    private final Set<String> methods;
    private final Set<String> fields;
    private final IdentityHashMap<PirTerm, SourceLocation> positions = new IdentityHashMap<>();
    private final PirDebugProvenance provenance;

    /**
     * @param methods the binders of the program's methods; a source name cannot shadow one
     * @param fields  the binders of its static fields, which a method's body may read
     */
    public DelayedMethodPass(Set<String> methods, Set<String> fields, Map<PirTerm, SourceLocation> positions,
                             PirDebugProvenance provenance) {
        this.methods = methods;
        this.fields = fields;
        this.provenance = provenance;
        if (positions != null) this.positions.putAll(positions);
    }

    public Result lower(PirTerm term) {
        if (methods.isEmpty()) return new Result(term, positions);
        return new Result(walk(term, Map.of(), Set.of()), positions);
    }

    /**
     * @param total   the fields and methods in scope that are bound to a total term, as {@link #known} reads them
     * @param delayed the delayed binders in scope
     */
    private PirTerm walk(PirTerm term, Map<String, Known> total, Set<String> delayed) {
        return remember(term, switch (term) {
            case PirTerm.Var read when delayed.contains(read.name()) -> call(read);
            case PirTerm.Let let -> walkLet(let, total, delayed);
            case PirTerm.LetRec rec -> walkLetRec(rec, total, delayed);
            default -> PirHelpers.mapChildren(term, child -> walk(child, total, delayed));
        });
    }

    private static PirTerm call(PirTerm.Var read) {
        var function = new PirTerm.Var(read.name(), new PirType.FunType(new PirType.UnitType(), read.type()));
        return new PirTerm.App(function, new PirTerm.Const(Constant.unit()));
    }

    private PirTerm walkLet(PirTerm.Let let, Map<String, Known> total, Set<String> delayed) {
        var value = walk(let.value(), total, delayed);
        if (!methods.contains(let.name()) && !fields.contains(let.name())) {
            return new PirTerm.Let(let.name(), value, walk(let.body(), total, delayed));
        }
        var known = known(value, total, new Reading());
        if (known != null) return new PirTerm.Let(let.name(), value, walk(let.body(), with(total, let.name(), known), delayed));
        var inner = new HashMap<>(total);
        inner.remove(let.name());
        if (!methods.contains(let.name())) return new PirTerm.Let(let.name(), value, walk(let.body(), inner, delayed));
        return new PirTerm.Let(let.name(), delayedBody(value), walk(let.body(), inner, plus(delayed, let.name())));
    }

    /** A binding of a recursive group that is not a function can only be a method that reads itself, which is not total. */
    private PirTerm walkLetRec(PirTerm.LetRec rec, Map<String, Known> total, Set<String> delayed) {
        var inner = delayed;
        for (var binding : rec.bindings()) {
            if (methods.contains(binding.name()) && !(binding.value() instanceof PirTerm.Lam)) inner = plus(inner, binding.name());
        }
        var scope = inner;
        var bindings = rec.bindings().stream().map(binding -> {
            var value = walk(binding.value(), total, scope);
            return new PirTerm.Binding(binding.name(), scope.contains(binding.name()) ? delayedBody(value) : value);
        }).toList();
        return new PirTerm.LetRec(bindings, walk(rec.body(), total, scope));
    }

    private static PirTerm delayedBody(PirTerm body) {
        return new PirTerm.Lam("#_", new PirType.UnitType(), body);
    }

    private static Set<String> plus(Set<String> names, String name) {
        var result = new HashSet<>(names);
        result.add(name);
        return result;
    }

    private PirTerm remember(PirTerm original, PirTerm replacement) {
        if (original.equals(replacement)) return original;
        var location = positions.get(original);
        if (location != null) positions.put(replacement, location);
        if (provenance != null) provenance.transfer(original, replacement, RULE);
        return replacement;
    }
}
