package org.julclang.compiler.uplc;

import org.julclang.core.BuiltinSemantics;
import org.julclang.core.DefaultFun;
import org.julclang.core.NamedDeBruijn;
import org.julclang.core.Term;
import org.julclang.core.recursion.Trampoline;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Binds a closed value that a script repeats once, at its root, and reads it through a variable (issue #325, ADR-076
 * D2). Two kinds of value are shared, the two that issue #151 measured:
 * <ul>
 *   <li>a <b>forced builtin</b>, {@code force (builtin b)} or {@code force (force (builtin b))}, which every use of a
 *       polymorphic builtin is: {@code headList}, {@code tailList}, {@code sndPair} and their kin; and</li>
 *   <li>the <b>Z combinator</b> {@code \f. (\x. f (\v. x x v)) (\x. f (\v. x x v))} that {@link UplcGenerator} writes for
 *       every recursive binding.</li>
 * </ul>
 * <b>Why the program means the same.</b> Both are closed values: forcing a builtin only instantiates it, and the Z
 * combinator is a lambda. So {@code [(\s. T[s/v]) v]} is a beta-expansion of {@code T}: evaluating it gives the same
 * results, failures and traces. It evaluates {@code v} once, at the start, where it cannot fail.
 * <p>
 * <b>Cost.</b> A variable is one machine step. A forced builtin was two, a force and a builtin, so each use that runs
 * saves one step. The Z combinator was one step, a lambda, so it costs the same. Each shared value adds the steps of
 * its binding once: an application, a lambda and the value.
 * <p>
 * <b>Size.</b> A value is shared only where the bits it saves exceed what its binding costs. Each use saves the value's
 * bits less those of a variable, whose index grows with the number of lambdas around the use.
 * <p>
 * The binders are ordered by kind and builtin, so the result depends only on the term.
 */
final class ClosedValueSharing {

    /** The Z combinator exactly as {@link UplcGenerator} writes it. */
    static final Term Z = zCombinator();

    private static final int TERM_TAG_BITS = 4;
    private static final int BUILTIN_TAG_BITS = 7;

    private ClosedValueSharing() {
    }

    private static Term zCombinator() {
        var innerBody = Term.lam("v", Term.apply(Term.apply(Term.var(2), Term.var(2)), Term.var(1)));
        var branch = Term.lam("x", Term.apply(Term.var(2), innerBody));
        return Term.lam("f", Term.apply(branch, branch));
    }

    /** A shareable value: what it is, what it costs in bits, and a name for its binder. */
    private record Candidate(Term value, int bits, String name, int order) {}

    /** {@code term} with each repeated shareable value bound once around it; {@code term} itself if none pays. */
    static Term share(Term term) {
        var uses = new LinkedHashMap<Term, List<Integer>>();
        census(term, uses);
        var candidates = new ArrayList<Candidate>();
        for (var entry : uses.entrySet()) {
            var candidate = candidate(entry.getKey());
            if (candidate != null) candidates.add(candidate);
        }
        candidates.sort(Comparator.comparingInt(Candidate::order));
        // Choose, assuming every chosen binder sits between a use and its own binder: an upper bound on the index.
        var chosen = new ArrayList<Candidate>();
        int slots = candidates.size();
        for (var candidate : candidates) {
            long saved = 0;
            for (int depth : uses.get(candidate.value())) saved += candidate.bits() - variableBits(depth + slots);
            long binding = 2L * TERM_TAG_BITS + candidate.bits();
            if (uses.get(candidate.value()).size() >= 2 && saved > binding) chosen.add(candidate);
        }
        if (chosen.isEmpty()) return term;
        var slotOf = new LinkedHashMap<Term, Integer>();
        var names = new ArrayList<String>();
        for (int i = 0; i < chosen.size(); i++) {
            slotOf.put(chosen.get(i).value(), i);
            names.add(chosen.get(i).name());
        }
        // The innermost binder is the last chosen; a use at lambda depth d reads slot i as index d + (m - i).
        Term body = replace(term, 0, slotOf, names).run();
        for (int i = chosen.size() - 1; i >= 0; i--) {
            body = Term.apply(Term.lam(chosen.get(i).name(), body), chosen.get(i).value());
        }
        return body;
    }

    /** Every shareable value in {@code term}, with the lambda depth of each use, in pre-order. */
    private static void census(Term root, Map<Term, List<Integer>> uses) {
        record Visit(Term term, int depth) {}
        var pending = new ArrayDeque<Visit>();
        pending.push(new Visit(root, 0));
        while (!pending.isEmpty()) {
            var visit = pending.pop();
            var term = visit.term();
            if (isForcedBuiltin(term) || term.equals(Z)) {
                uses.computeIfAbsent(term, t -> new ArrayList<>()).add(visit.depth());
                continue;
            }
            switch (term) {
                case Term.Lam(var name, var body) -> pending.push(new Visit(body, visit.depth() + 1));
                case Term.Apply(var function, var argument) -> {
                    pending.push(new Visit(argument, visit.depth()));
                    pending.push(new Visit(function, visit.depth()));
                }
                case Term.Force(var inner) -> pending.push(new Visit(inner, visit.depth()));
                case Term.Delay(var inner) -> pending.push(new Visit(inner, visit.depth()));
                case Term.Constr constr -> {
                    for (int i = constr.fields().size() - 1; i >= 0; i--) pending.push(new Visit(constr.fields().get(i), visit.depth()));
                }
                case Term.Case match -> {
                    for (int i = match.branches().size() - 1; i >= 0; i--) pending.push(new Visit(match.branches().get(i), visit.depth()));
                    pending.push(new Visit(match.scrutinee(), visit.depth()));
                }
                case Term.Var _, Term.Const _, Term.Builtin _, Term.Error _ -> { }
            }
        }
    }

    /** {@code force (builtin b)} or {@code force (force (builtin b))}: a builtin forced at least once. */
    private static boolean isForcedBuiltin(Term term) {
        int forces = 0;
        while (term instanceof Term.Force(var inner)) {
            forces++;
            term = inner;
        }
        return forces > 0 && term instanceof Term.Builtin;
    }

    private static Candidate candidate(Term value) {
        if (value.equals(Z)) return new Candidate(value, bits(value), "#z", 0);
        int forces = 0;
        Term inner = value;
        while (inner instanceof Term.Force(var next)) {
            forces++;
            inner = next;
        }
        if (!(inner instanceof Term.Builtin(DefaultFun fun))) return null;
        // Only a builtin forced exactly as often as its type arity is a value: one more force fails when it runs, and
        // binding that at the root would make it fail where it might not have run.
        var signature = BuiltinSemantics.find(fun);
        if (signature == null || signature.typeArity() != forces) return null;
        int bits = forces * TERM_TAG_BITS + TERM_TAG_BITS + BUILTIN_TAG_BITS;
        return new Candidate(value, bits, "#" + fun.name().substring(0, 1).toLowerCase() + fun.name().substring(1),
                1 + fun.ordinal() * 4 + forces);
    }

    /** The FLAT bits of a closed term built of lambdas, applications and variables with small indices. */
    private static int bits(Term term) {
        int bits = 0;
        var pending = new ArrayDeque<Term>();
        pending.push(term);
        while (!pending.isEmpty()) {
            switch (pending.pop()) {
                case Term.Lam(var name, var body) -> {
                    bits += TERM_TAG_BITS;
                    pending.push(body);
                }
                case Term.Apply(var function, var argument) -> {
                    bits += TERM_TAG_BITS;
                    pending.push(function);
                    pending.push(argument);
                }
                case Term.Var(var name) -> bits += variableBits(name.index());
                default -> throw new IllegalArgumentException("not a combinator");
            }
        }
        return bits;
    }

    /** The FLAT bits of a variable of de Bruijn index {@code index}: the tag, then the index in 7-bit groups. */
    private static int variableBits(int index) {
        int groups = 1;
        for (long rest = index >>> 7; rest > 0; rest >>>= 7) groups++;
        return TERM_TAG_BITS + 8 * groups;
    }

    /** {@code term} with each chosen value read from its binder, stack-safe (ADR-074). */
    private static Trampoline<Term> replace(Term term, int depth, Map<Term, Integer> slotOf, List<String> names) {
        return Trampoline.defer(() -> {
            if (isForcedBuiltin(term) || term.equals(Z)) {
                Integer slot = slotOf.get(term);
                if (slot != null) {
                    return Trampoline.done(new Term.Var(new NamedDeBruijn(names.get(slot), depth + names.size() - slot)));
                }
                return Trampoline.done(term);
            }
            return switch (term) {
                case Term.Lam(var name, var body) -> replace(body, depth + 1, slotOf, names).map(b -> (Term) Term.lam(name, b));
                case Term.Apply(var function, var argument) -> replace(function, depth, slotOf, names)
                        .then(f -> replace(argument, depth, slotOf, names).map(a -> (Term) Term.apply(f, a)));
                case Term.Force(var inner) -> replace(inner, depth, slotOf, names).map(i -> (Term) Term.force(i));
                case Term.Delay(var inner) -> replace(inner, depth, slotOf, names).map(i -> (Term) Term.delay(i));
                case Term.Constr constr -> Trampoline.each(constr.fields(), f -> replace(f, depth, slotOf, names))
                        .map(fields -> (Term) new Term.Constr(constr.tag(), fields));
                case Term.Case match -> replace(match.scrutinee(), depth, slotOf, names)
                        .then(s -> Trampoline.each(match.branches(), b -> replace(b, depth, slotOf, names))
                                .map(branches -> (Term) new Term.Case(s, branches)));
                case Term.Var _, Term.Const _, Term.Builtin _, Term.Error _ -> Trampoline.done(term);
            };
        });
    }
}
