package org.julclang.compiler.pir;

import org.julclang.compiler.debug.PirDebugProvenance;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.julclang.core.source.SourceLocation;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Java's {@code (int)} of a {@code long} keeps the low 32 bits, so the javac frontend lowers it to a wrap,
 * {@code ((x + 2^31) mod 2^32) - 2^31} ({@link #narrowToInt}). For an {@code x} that is already an {@code int}'s value the
 * wrap is the identity, and a script pays for it at run time. This pass removes the wrap exactly where {@link #rangeOf} shows
 * that {@code x}'s value is inside {@code int}'s range (ADR-068 r18). Any other wrap stays.
 * <p>
 * <b>Why this runs on the assembled term.</b> A cast's operand is often a call, and a call site can come before the
 * called method's body is lowered, as for {@link DelayedMethodPass}. By assembly every body exists. It runs at every level,
 * as that pass does: it finishes how a cast lowers. The match is on the term's shape, so a wrap the program wrote out by hand, on
 * either frontend, is removed under the same proof, and the removal is as correct there.
 * <p>
 * <b>Why removing a wrap keeps the program's meaning.</b>
 * <ul>
 *   <li>The value: for {@code -2^31 <= x < 2^31}, {@code x + 2^31} is in {@code [0, 2^32)}, so {@code mod} leaves it, and the
 *       subtraction gives {@code x}. This is an identity on integers, and holds wherever the term came from.</li>
 *   <li>Failure and traces: {@code x} is evaluated once and first in both forms. The three builtins cannot fail on an integer
 *       (the modulus is a non-zero constant) and do not trace, and {@link #rangeOf} knows a range only of a term that leaves an
 *       integer, so nothing else that could fail or trace is removed.</li>
 *   <li>Cost: a removed wrap runs three builtins fewer and holds three constants fewer where it runs, and the script is smaller. On a
 *       path that evaluates a method but does not call it, {@code \i. f(B, i)} can reduce to a partial application that costs a few
 *       machine steps more (32,000 CPU and 200 memory in the review's probe). The I1 gate holds the corpus's rows: each one this moves shrinks.</li>
 * </ul>
 * <b>What is known ({@link #rangeOf}), and it is conservative:</b> an integer constant; {@code indexByteString} is
 * {@code 0..255}; {@code modInteger} by a known positive {@code k} is {@code 0..k-1}, and {@code remainderInteger} by one is
 * {@code -(k-1)..k-1}, what Java's {@code %} lowers to; a wrap is {@code int}'s range; a {@code let}'s body; a variable bound by a
 * {@code let}, as its value; and a saturated call of a function a {@code let} binds, as its body, where nothing is known of a
 * parameter. Everything else is unknown, and the wrap stays. {@code lengthOfByteString} and a list's length have no upper
 * bound, so they are not enough. {@code &} is not lowered at all (the javac frontend rejects it), so it has no rule.
 * <p>
 * <b>Scoping.</b> PIR is scoped by name, and two binders can share one (a root's helper and its library copy), so a name is read
 * only through {@link Scope}, which every binder form extends: a {@code let}'s value is read in the scope of the {@code let}, and
 * every other binder (a lambda's parameter, a recursive group, a match's fields) hides what it names. A
 * recursive group's functions are unknown, which also keeps the reading finite: a value only reads binders outside itself.
 */
public final class NarrowingElisionPass {

    public record Result(PirTerm term, Map<PirTerm, SourceLocation> positions) {}

    /** The inclusive bounds of an integer term's value. */
    private record Range(BigInteger low, BigInteger high) {
        boolean fitsInt() {
            return low.compareTo(INT.low) >= 0 && high.compareTo(INT.high) <= 0;
        }

        boolean positive() {
            return low.signum() > 0;
        }
    }

    /** One binder in scope: its value and the scope of that value, or, with no value, a name nothing is known of. */
    private record Scope(String name, PirTerm value, Scope valueScope, Scope outer) {}

    private static final BigInteger INT_RANGE = BigInteger.ONE.shiftLeft(32);
    private static final BigInteger INT_OFFSET = BigInteger.ONE.shiftLeft(31);
    private static final Range INT = new Range(INT_OFFSET.negate(), INT_OFFSET.subtract(BigInteger.ONE));
    private static final Range BYTE = new Range(BigInteger.ZERO, BigInteger.valueOf(255));
    private static final String PASS = "adr068.narrowing-elision";

    private final IdentityHashMap<PirTerm, SourceLocation> positions = new IdentityHashMap<>();
    private final PirDebugProvenance provenance;

    public NarrowingElisionPass(Map<PirTerm, SourceLocation> positions, PirDebugProvenance provenance) {
        this.provenance = provenance;
        if (positions != null) this.positions.putAll(positions);
    }

    /** {@code (int)} of a {@code long}: {@code ((x + 2^31) mod 2^32) - 2^31}, the form this pass reads. */
    public static PirTerm narrowToInt(PirTerm value) {
        var shifted = PirHelpers.builtinApp2(DefaultFun.AddInteger, value, integer(INT_OFFSET));
        var wrapped = PirHelpers.builtinApp2(DefaultFun.ModInteger, shifted, integer(INT_RANGE));
        return PirHelpers.builtinApp2(DefaultFun.SubtractInteger, wrapped, integer(INT_OFFSET));
    }

    private static PirTerm integer(BigInteger value) {
        return new PirTerm.Const(Constant.integer(value));
    }

    /** A term with no wrap comes back as the same object, so a program with no {@code (int)} of a {@code long} is untouched. */
    public Result lower(PirTerm term) {
        return new Result(holdsNarrowing(term) ? walk(term, null) : term, positions);
    }

    private static boolean holdsNarrowing(PirTerm term) {
        if (narrowedOperand(term) != null) return true;
        boolean[] holds = {false};
        PirHelpers.mapChildren(term, child -> {
            holds[0] |= holdsNarrowing(child);
            return child;
        });
        return holds[0];
    }

    /** The {@code x} of {@code ((x + 2^31) mod 2^32) - 2^31}, exactly as {@link #narrowToInt} builds it, or null. */
    private static PirTerm narrowedOperand(PirTerm term) {
        var wrapped = constantRight(term, DefaultFun.SubtractInteger, INT_OFFSET);
        var shifted = wrapped == null ? null : constantRight(wrapped, DefaultFun.ModInteger, INT_RANGE);
        return shifted == null ? null : constantRight(shifted, DefaultFun.AddInteger, INT_OFFSET);
    }

    /** The left operand of {@code fun} applied to it and the integer {@code right}, or null. */
    private static PirTerm constantRight(PirTerm term, DefaultFun fun, BigInteger right) {
        return term instanceof PirTerm.App(PirTerm.App(PirTerm.Builtin(var builtin), var left), PirTerm.Const(Constant.IntegerConst(var value)))
                && builtin == fun && value.equals(right) ? left : null;
    }

    private PirTerm walk(PirTerm term, Scope scope) {
        var operand = narrowedOperand(term);
        if (operand != null) {
            var range = rangeOf(operand, scope);
            if (range != null && range.fitsInt()) return elided(term, walk(operand, scope));
        }
        return remember(term, switch (term) {
            case PirTerm.Let let -> new PirTerm.Let(let.name(), walk(let.value(), scope),
                    walk(let.body(), new Scope(let.name(), let.value(), scope, scope)));
            case PirTerm.LetRec rec -> {
                var inner = hide(scope, rec.bindings().stream().map(PirTerm.Binding::name).toList());
                yield new PirTerm.LetRec(rec.bindings().stream()
                        .map(binding -> new PirTerm.Binding(binding.name(), walk(binding.value(), inner))).toList(),
                        walk(rec.body(), inner));
            }
            case PirTerm.Lam lam -> new PirTerm.Lam(lam.param(), lam.paramType(), walk(lam.body(), hide(scope, List.of(lam.param()))));
            case PirTerm.PairMatch match -> new PirTerm.PairMatch(walk(match.scrutinee(), scope), match.pairType(),
                    match.firstName(), match.secondName(), walk(match.body(), hide(scope, List.of(match.firstName(), match.secondName()))));
            case PirTerm.ListMatch match -> new PirTerm.ListMatch(walk(match.scrutinee(), scope), match.headName(), match.tailName(),
                    walk(match.nilBranch(), scope), walk(match.consBranch(), hide(scope, List.of(match.headName(), match.tailName()))));
            case PirTerm.DataMatch match -> new PirTerm.DataMatch(walk(match.scrutinee(), scope), match.branches().stream()
                    .map(branch -> new PirTerm.MatchBranch(branch.constructorName(), branch.bindings(), branch.bindingTypes(),
                            walk(branch.body(), hide(scope, bound(branch))), branch.patternVar())).toList());
            // The remaining forms bind nothing.
            case PirTerm.Var _, PirTerm.Const _, PirTerm.Builtin _, PirTerm.Error _, PirTerm.App _, PirTerm.IfThenElse _,
                 PirTerm.Trace _, PirTerm.DataConstr _, PirTerm.IntegerCase _ -> PirHelpers.mapChildren(term, child -> walk(child, scope));
        });
    }

    private static List<String> bound(PirTerm.MatchBranch branch) {
        var names = new ArrayList<>(branch.bindings());
        if (branch.patternVar() != null) names.add(branch.patternVar());
        return names;
    }

    private static Scope hide(Scope scope, List<String> names) {
        for (var name : names) scope = new Scope(name, null, null, scope);
        return scope;
    }

    /**
     * What is known of the value {@code term} leaves in {@code scope}, or null if nothing is. Each form reads one term further
     * but for a divisor, which is read once, and a value only reads binders outside itself, so this ends.
     */
    private static Range rangeOf(PirTerm term, Scope scope) {
        if (narrowedOperand(term) != null) return INT;
        return switch (term) {
            case PirTerm.Const(Constant.IntegerConst(var value)) -> new Range(value, value);
            case PirTerm.Var read -> {
                var binder = binderOf(read.name(), scope);
                yield binder == null || binder.value() == null ? null : rangeOf(binder.value(), binder.valueScope());
            }
            case PirTerm.Let let -> rangeOf(let.body(), new Scope(let.name(), let.value(), scope, scope));
            case PirTerm.App app -> rangeOfCall(app, scope);
            default -> null;
        };
    }

    private static Scope binderOf(String name, Scope scope) {
        while (scope != null && !scope.name().equals(name)) scope = scope.outer();
        return scope;
    }

    private static Range rangeOfCall(PirTerm.App call, Scope scope) {
        var arguments = new ArrayList<PirTerm>();
        PirTerm head = call;
        while (head instanceof PirTerm.App app) {
            arguments.addFirst(app.argument());
            head = app.function();
        }
        return switch (head) {
            case PirTerm.Builtin builtin when arguments.size() == 2 -> switch (builtin.fun()) {
                case IndexByteString -> BYTE;
                case ModInteger -> {
                    var divisor = rangeOf(arguments.get(1), scope);
                    yield divisor == null || !divisor.positive() ? null
                            : new Range(BigInteger.ZERO, divisor.high().subtract(BigInteger.ONE));
                }
                case RemainderInteger -> {
                    var divisor = rangeOf(arguments.get(1), scope);
                    yield divisor == null || !divisor.positive() ? null
                            : new Range(divisor.high().subtract(BigInteger.ONE).negate(), divisor.high().subtract(BigInteger.ONE));
                }
                default -> null;
            };
            case PirTerm.Var function -> {
                var binder = binderOf(function.name(), scope);
                yield binder == null || binder.value() == null ? null : rangeOfBody(binder.value(), arguments.size(), binder.valueScope());
            }
            default -> null;
        };
    }

    /** The result of a function of exactly {@code count} parameters, applied to as many arguments; nothing is known of them. */
    private static Range rangeOfBody(PirTerm function, int count, Scope scope) {
        var body = function;
        for (int i = 0; i < count; i++) {
            if (!(body instanceof PirTerm.Lam lam)) return null;
            scope = new Scope(lam.param(), null, null, scope);
            body = lam.body();
        }
        return rangeOf(body, scope);
    }

    /** The operand keeps its own position: a failure in it is still reported where it is. */
    private PirTerm elided(PirTerm wrap, PirTerm operand) {
        var location = positions.get(wrap);
        if (location != null) positions.putIfAbsent(operand, location);
        return operand;
    }

    private PirTerm remember(PirTerm original, PirTerm replacement) {
        if (original.equals(replacement)) return original;
        var location = positions.get(original);
        if (location != null) positions.put(replacement, location);
        if (provenance != null) provenance.transfer(original, replacement, PASS);
        return replacement;
    }
}
