package org.julclang.compiler.uplc;

import org.julclang.compiler.CompilerTestVm;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.julclang.core.NamedDeBruijn;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.core.Term;
import org.julclang.vm.EvalResult;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * The guards of ADR-076 D1 that {@link HandlerBindingSharingFuzzTest}'s programs do not reach: a value that is not
 * total, the fixpoint closed-value sharing leaves behind (and a binder that only looks like it), a binding inside
 * another's value or under a lambda, an open term, and a long chain. The decision walks the program a bounded number of
 * times per round of exclusions; finding the branch point and rebuilding each shared value's scope are linear in the
 * program's depth for each method, so a chain of n methods costs O(n * depth) at worst: 4,000 take well under a second.
 */
class HandlerBindingSharingTest {

    /**
     * {@code \d. let x = unIData d in if x == 0 then H0 else if x == 1 then H1 else error}, each handler
     * {@code handler(x)} built in a scope where {@code x} is variable 1, under {@code outer} binders.
     */
    private static Term twoHandlers(Function<Integer, Term> handler) {
        Term dispatch = Term.error();
        for (int h = 1; h >= 0; h--) {
            Term test = Term.apply(Term.apply(Term.builtin(DefaultFun.EqualsInteger), Term.var(1)), integer(h));
            dispatch = Term.force(Term.apply(Term.apply(Term.apply(Term.force(Term.builtin(DefaultFun.IfThenElse)), test),
                    Term.delay(handler.apply(h))), Term.delay(dispatch)));
        }
        return Term.lam("#d", Term.apply(Term.lam("#x", dispatch), Term.apply(Term.builtin(DefaultFun.UnIData), Term.var(1))));
    }

    private static Term let(String name, Term value, Term body) {
        return Term.apply(Term.lam(name, body), value);
    }

    private static Term add(Term a, Term b) {
        return Term.apply(Term.apply(Term.builtin(DefaultFun.AddInteger), a), b);
    }

    private static Term integer(long value) {
        return Term.const_(Constant.integer(BigInteger.valueOf(value)));
    }

    private static Term var(String name, int index) {
        return new Term.Var(new NamedDeBruijn(name, index));
    }

    private static HandlerBindings candidates(String... names) {
        return new HandlerBindings(new LinkedHashSet<>(List.of(names)), 2);
    }

    /** Each binding of {@code A.m} in {@code term}. */
    private static int bindings(Term term, String name) {
        int count = 0;
        var pending = new ArrayList<Term>();
        pending.add(term);
        while (!pending.isEmpty()) {
            switch (pending.removeLast()) {
                case Term.Apply(var function, var argument) -> {
                    if (function instanceof Term.Lam(var binder, var _) && binder.equals(name)) count++;
                    pending.add(function);
                    pending.add(argument);
                }
                case Term.Lam(var _, var body) -> pending.add(body);
                case Term.Force(var inner) -> pending.add(inner);
                case Term.Delay(var inner) -> pending.add(inner);
                default -> {
                }
            }
        }
        return count;
    }

    /** The same results and traces on every input, and the same budget on those a handler takes. */
    private static void assertSameRuns(Term before, Term after) {
        assertEquals(InlinedMethods.flat(before), InlinedMethods.flat(after));
        for (int input = -1; input <= 2; input++) {
            var a = CompilerTestVm.pv11().evaluateWithArgs(Program.plutusV3(before), List.of(PlutusData.integer(input)));
            var b = CompilerTestVm.pv11().evaluateWithArgs(Program.plutusV3(after), List.of(PlutusData.integer(input)));
            assertEquals(a.isSuccess(), b.isSuccess(), "input " + input);
            assertEquals(a.traces(), b.traces(), "input " + input);
            if (a instanceof EvalResult.Success success) {
                assertEquals(success.resultTerm(), ((EvalResult.Success) b).resultTerm(), "input " + input);
            }
            if (input == 0 || input == 1) assertEquals(a.budgetConsumed(), b.budgetConsumed(), "input " + input);
        }
    }

    @Test
    void aValueThatTracesIsNotShared() {
        // let A.m = trace "boom" (\p. p + 1) in A.m x: the same in both handlers, but evaluating it traces.
        Term traced = Term.apply(Term.apply(Term.force(Term.builtin(DefaultFun.Trace)), Term.const_(Constant.string("boom"))),
                Term.lam("#p", add(Term.var(1), integer(1))));
        Term term = twoHandlers(h -> let("A.m", traced, Term.apply(Term.var(1), Term.var(2))));
        assertSame(term, HandlerBindingSharing.share(term, candidates("A.m")));
    }

    @Test
    void aFixpointReadFromTheSharedZCombinatorIsShared() {
        // The program as closed-value sharing leaves it: Z bound once at the root as #z, each recursive method its
        // fixpoint, [#z (\A.f. \n. ...)]. Inside the handler, #z is variable 3 (#x, #d, #z).
        Function<Integer, Term> handler = h -> let("A.f",
                Term.apply(var("#z", 3), Term.lam("A.f", Term.lam("#n", add(Term.var(1), integer(1))))),
                Term.apply(Term.var(1), Term.var(2)));
        Term withZ = Term.apply(Term.lam("#z", twoHandlers(handler)), ClosedValueSharing.Z);
        Term shared = HandlerBindingSharing.share(withZ, candidates("A.f"));
        assertEquals(1, bindings(shared, "A.f"));
        assertSameRuns(withZ, shared);

        // The same shape over a binder that is not the Z combinator is not a value known to be total.
        Term withOther = Term.apply(Term.lam("#z", twoHandlers(handler)), Term.lam("#f", Term.error()));
        assertSame(withOther, HandlerBindingSharing.share(withOther, candidates("A.f")));

        // Codex round 7: let #z = (let #z = Z in \f. trace "boom" (#z f)) in ...: the inner #z is Z and has the outer's
        // name and depth, but the handlers read the outer, which traces. A binder is known by its declaration.
        Term wrapper = Term.apply(Term.lam("#z", Term.lam("#f", Term.apply(Term.apply(Term.force(Term.builtin(DefaultFun.Trace)),
                Term.const_(Constant.string("boom"))), Term.apply(Term.var(2), Term.var(1))))), ClosedValueSharing.Z);
        Term shadowed = Term.apply(Term.lam("#z", twoHandlers(handler)), wrapper);
        assertSame(shadowed, HandlerBindingSharing.share(shadowed, candidates("A.f")));
    }

    @Test
    void aBindingInsideAnotherMethodsValueMovesWithItAndIsNeverSharedOutOfIt() {
        // let A.m = delay (let A.k = \q. q + 2 in \p. A.k p) in force A.m x: A.k is bound inside A.m's value, which runs
        // each time it is forced. A.m is shared, its value whole; A.k stays inside it.
        Term delayed = Term.delay(let("A.k", Term.lam("#q", add(Term.var(1), integer(2))),
                Term.lam("#p", Term.apply(Term.var(2), Term.var(1)))));
        Term term = twoHandlers(h -> let("A.m", delayed, Term.apply(Term.force(Term.var(1)), Term.var(2))));
        Term shared = HandlerBindingSharing.share(term, candidates("A.m", "A.k"));
        assertEquals(1, bindings(shared, "A.m"));
        assertEquals(1, bindings(shared, "A.k"));
        assertSameRuns(term, shared);
        assertSame(term, HandlerBindingSharing.share(term, candidates("A.k")));

        // When A.m cannot move, its value traces, A.k is not taken out of it either.
        Term traced = Term.apply(Term.apply(Term.force(Term.builtin(DefaultFun.Trace)), Term.const_(Constant.string("t"))),
                delayed);
        Term held = twoHandlers(h -> let("A.m", traced, Term.apply(Term.force(Term.var(1)), Term.var(2))));
        assertSame(held, HandlerBindingSharing.share(held, candidates("A.m", "A.k")));
    }

    @Test
    void anOpenTermIsLeftAsItIs() {
        // A handler that reads a variable nothing binds: the pass declines rather than rebuilding it.
        Term open = twoHandlers(h -> let("A.m", Term.lam("#p", add(Term.var(1), integer(1))), Term.apply(Term.var(1), Term.var(9))));
        assertSame(open, HandlerBindingSharing.share(open, candidates("A.m")));
    }

    @Test
    void aBindingUnderALambdaThatIsNotALetsIsNotShared() {
        // #apply0 = \h. h 0, bound above the dispatch; handler 0 is [#apply0 (\#u. let A.m = \p. p + 1 in A.m x)], so
        // its A.m is bound each time #apply0 calls the lambda. Handler 1 binds A.m at its start.
        Term value = Term.lam("#p", add(Term.var(1), integer(1)));
        Term term = Term.apply(Term.lam("#apply0", twoHandlers(h -> h == 0
                        ? Term.apply(var("#apply0", 3), Term.lam("#u", let("A.m", value, Term.apply(Term.var(1), Term.var(3)))))
                        : let("A.m", value, Term.apply(Term.var(1), Term.var(2))))),
                Term.lam("#h", Term.apply(Term.var(1), integer(0))));
        assertSame(term, HandlerBindingSharing.share(term, candidates("A.m")));
    }

    @Test
    void aLongChainIsDecidedQuickly() {
        int methods = 4_000;
        var names = new LinkedHashSet<String>();
        for (int m = 0; m < methods; m++) names.add("A.m" + m);
        // Each handler: let A.m0 = \p. p + 1 in let A.m1 = \p. A.m0 p + 1 in ... in A.m_last x.
        Function<Integer, Term> handler = h -> {
            Term body = Term.apply(Term.var(1), Term.var(methods + 1));
            for (int m = methods - 1; m >= 0; m--) {
                Term value = m == 0 ? Term.lam("#p", add(Term.var(1), integer(1)))
                        : Term.lam("#p", add(Term.apply(Term.var(2), Term.var(1)), integer(1)));
                body = let("A.m" + m, value, body);
            }
            return body;
        };
        Term term = twoHandlers(handler);
        Term shared = assertTimeoutPreemptively(Duration.ofSeconds(60),
                () -> HandlerBindingSharing.share(term, new HandlerBindings(Set.copyOf(names), 2)));
        for (String name : List.of("A.m0", "A.m" + (methods / 2), "A.m" + (methods - 1))) {
            assertEquals(1, bindings(shared, name), name);
        }
    }
}
