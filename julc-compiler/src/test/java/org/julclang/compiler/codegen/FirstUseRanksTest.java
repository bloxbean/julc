package org.julclang.compiler.codegen;

import org.julclang.compiler.codegen.FirstUseRanks.Roots;
import org.julclang.compiler.codegen.FirstUseRanks.Rule;
import org.julclang.compiler.codegen.FirstUseRanks.Selection;
import org.julclang.compiler.codegen.FirstUseRanks.TieChoice;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.resolve.LibraryMethodRegistry.LibraryMethod;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-078 D1 and D2: library methods with equal content keys are bound in the order the program first uses them,
 * and a name decides only in D1b's fallback (instrumented through {@link FirstUseRanks.Selection}).
 */
class FirstUseRanksTest {

    private static final PirType INT = new PirType.IntegerType();
    private static final PirType FUN = new PirType.FunType(INT, INT);

    /** {@code \x. calls(... (x + constant))}, the same body whatever its name. */
    private static PirTerm body(int constant, List<String> calls) {
        PirTerm body = new PirTerm.App(new PirTerm.App(new PirTerm.Builtin(DefaultFun.AddInteger),
                new PirTerm.Var("x", INT)), new PirTerm.Const(Constant.integer(BigInteger.valueOf(constant))));
        for (String call : calls) body = new PirTerm.App(new PirTerm.Var(call, FUN), body);
        return new PirTerm.Lam("x", INT, body);
    }

    private static LibraryMethod method(String qualified, int constant, List<String> calls) {
        int dot = qualified.lastIndexOf('.');
        return new LibraryMethod(qualified.substring(0, dot), qualified.substring(dot + 1), FUN, body(constant, calls));
    }

    /** A term that calls each of {@code names}, in order. */
    private static PirTerm calls(String... names) {
        PirTerm term = new PirTerm.Const(Constant.integer(BigInteger.ONE));
        for (int i = names.length - 1; i >= 0; i--) {
            term = new PirTerm.App(new PirTerm.App(new PirTerm.Builtin(DefaultFun.AddInteger),
                    new PirTerm.App(new PirTerm.Var(names[i], FUN), new PirTerm.Const(Constant.integer(BigInteger.TWO)))), term);
        }
        return term;
    }

    private static Roots entries(PirTerm... entries) {
        return new Roots(List.of(), List.of(entries), List.of());
    }

    private static List<String> names(List<LibraryMethod> order) {
        return order.stream().map(LibraryMethod::qualifiedName).toList();
    }

    private static List<Selection> selections(List<LibraryMethod> methods, Roots roots) {
        var selections = new ArrayList<Selection>();
        LibraryBindingOrder.innermostFirst(methods, roots, selections::add);
        return selections;
    }

    @Test
    void twinsAreBoundInTheOrderOfFirstUseNotOfName() {
        var twins = List.of(method("q.L.alpha", 1, List.of()), method("q.L.beta", 1, List.of()));
        // Innermost first: the first-used twin is bound outermost, so it comes last.
        assertEquals(List.of("q.L.alpha", "q.L.beta"), names(LibraryBindingOrder.innermostFirst(twins, entries(calls("q.L.beta", "q.L.alpha")), null)));
        assertEquals(List.of("q.L.beta", "q.L.alpha"), names(LibraryBindingOrder.innermostFirst(twins, entries(calls("q.L.alpha", "q.L.beta")), null)));
        // Renamed so that the name order flips: the order follows first use, as before.
        var renamed = List.of(method("q.L.zeta", 1, List.of()), method("q.L.beta", 1, List.of()));
        assertEquals(List.of("q.L.beta", "q.L.zeta"), names(LibraryBindingOrder.innermostFirst(renamed, entries(calls("q.L.zeta", "q.L.beta")), null)));
    }

    @Test
    void handlersAndStaticInitialisersAreWalkedInOrder() {
        var twins = List.of(method("q.L.alpha", 1, List.of()), method("q.L.beta", 1, List.of()));
        // The first handler uses beta, the second alpha: beta is first used.
        assertEquals("q.L.beta", names(LibraryBindingOrder.innermostFirst(twins,
                entries(calls("q.L.beta"), calls("q.L.alpha")), null)).getLast());
        // A static initialiser comes before the handlers.
        assertEquals("q.L.beta", names(LibraryBindingOrder.innermostFirst(twins,
                new Roots(List.of(calls("q.L.beta")), List.of(calls("q.L.alpha")), List.of()), null)).getLast());
    }

    /** C22: a helper and a library copy of one name are two bindings; references resolve lexically. */
    @Test
    void aHelperAndItsLibraryCopyAreTwoBindings() {
        var library = List.of(method("q.H.g", 1, List.of()), method("q.H.f", 2, List.of("q.H.g")));
        var helpers = List.of(new ValidatorAssembly.Method("q.H.g", body(1, List.of())),
                new ValidatorAssembly.Method("q.H.f", body(2, List.of("q.H.g"))));
        var selections = selections(library, new Roots(List.of(), List.of(calls("q.H.f")), helpers));
        // The root's read of q.H.f is the helper's, and the helper's read of q.H.g is the helper's too.
        assertEquals(new Selection("q.H.f", true, 0, Rule.FIRST_USE), selections.get(0));
        assertEquals(new Selection("q.H.g", true, 1, Rule.FIRST_USE), selections.get(1));
        // The library copies are read by nothing the root reaches: D1b ranks the copy of f, and walking it reaches the
        // copy of g, whose read there is the library's (helpers are not in scope in a library body).
        assertEquals(List.of(new Selection("q.H.f", false, 2, Rule.UNREAD_BY_KEY), new Selection("q.H.g", false, 3, Rule.FIRST_USE)),
                selections.subList(2, 4));
        // A static initialiser is bound outside the helpers: its read of q.H.g is the library copy's.
        var fromStatic = selections(library, new Roots(List.of(calls("q.H.g")), List.of(), helpers));
        assertEquals(new Selection("q.H.g", false, 0, Rule.FIRST_USE), fromStatic.getFirst());
    }

    /** C11, D1a′: a helper the root does not reach ranks the library methods it reads before D1b does. */
    @Test
    void anUnreadHelperRanksWhatItReads() {
        var twins = List.of(method("q.L.alpha", 1, List.of()), method("q.L.beta", 1, List.of()));
        var helpers = List.of(new ValidatorAssembly.Method("v.T.unused", body(5, List.of("q.L.beta"))));
        var selections = selections(twins, new Roots(List.of(), List.of(calls()), helpers));
        assertEquals(List.of(new Selection("v.T.unused", true, 0, Rule.UNREAD_HELPER),
                new Selection("q.L.beta", false, 1, Rule.FIRST_USE),
                new Selection("q.L.alpha", false, 2, Rule.UNREAD_BY_KEY)), selections);
    }

    @Test
    void aLocalShadowsAMethodOfTheSameName() {
        var twins = List.of(method("q.L.alpha", 1, List.of()), method("q.L.beta", 1, List.of()));
        var shadowed = new PirTerm.Lam("q.L.alpha", FUN, calls("q.L.alpha", "q.L.beta"));
        var selections = selections(twins, entries(shadowed));
        assertEquals(new Selection("q.L.beta", false, 0, Rule.FIRST_USE), selections.getFirst());
    }

    @Test
    void anUnreadCycleTerminatesThroughTheFallback() {
        var cycle = List.of(method("q.M.even", 1, List.of("q.M.odd")), method("q.M.odd", 2, List.of("q.M.even")));
        var selections = selections(cycle, Roots.NONE);
        assertEquals(2, selections.size());
        assertEquals(Rule.CYCLE, selections.getFirst().rule());
    }

    @Test
    void selfRecursionIsNotACycle() {
        var self = List.of(method("q.M.sum", 1, List.of("q.M.sum")));
        assertEquals(List.of(new Selection("q.M.sum", false, 0, Rule.UNREAD_BY_KEY)), selections(self, Roots.NONE));
    }

    /** A planted D1b residual: two unread methods with equal keys and different callees; the name decides. */
    @Test
    void aPlantedResidualIsDecidedByName() {
        var methods = List.of(method("q.L.t1", 1, List.of()), method("q.L.t2", 1, List.of()),
                method("q.L.u1", 2, List.of("q.L.t1")), method("q.L.u2", 2, List.of("q.L.t2")));
        var selections = selections(methods, Roots.NONE);
        assertEquals(new Selection("q.L.u1", false, 0, Rule.UNREAD_BY_NAME), selections.getFirst());
    }

    /**
     * Lemma 1 and the instrumentation: on random libraries with twin groups and random roots, a name decides only in
     * D1b's fallback; where it never does, a rename that reverses every name's order keeps the binding order.
     */
    @Test
    void aNameDecidesOnlyInTheFallback() {
        var random = new Random(78);
        int withoutFallback = 0;
        for (int trial = 0; trial < 500; trial++) {
            int count = 3 + random.nextInt(10);
            var constants = new ArrayList<Integer>();
            var calls = new ArrayList<List<Integer>>();
            for (int i = 0; i < count; i++) {
                constants.add(random.nextInt(3));
                var callees = new ArrayList<Integer>();
                for (int j = i + 1; j < count; j++) if (random.nextInt(4) == 0) callees.add(j);
                calls.add(callees);
            }
            var rootCalls = new ArrayList<Integer>();
            for (int i = 0; i < count; i++) if (random.nextInt(2) == 0) rootCalls.add(i);
            var forward = library(constants, calls, i -> "q.L.m" + (char) ('a' + i));
            var backward = library(constants, calls, i -> "q.L.m" + (char) ('z' - i));
            var selections = new ArrayList<Selection>();
            var orderForward = LibraryBindingOrder.innermostFirst(forward,
                    entries(calls(rootCalls.stream().map(i -> "q.L.m" + (char) ('a' + i)).toArray(String[]::new))), selections::add);
            var orderBackward = LibraryBindingOrder.innermostFirst(backward,
                    entries(calls(rootCalls.stream().map(i -> "q.L.m" + (char) ('z' - i)).toArray(String[]::new))), null);
            for (var selection : selections) {
                assertTrue(selection.rule() != Rule.CYCLE, "trial " + trial + ": no cycle was planted");
            }
            if (selections.stream().noneMatch(s -> s.rule() == Rule.UNREAD_BY_NAME)) {
                withoutFallback++;
                assertEquals(indexes(orderForward, forward), indexes(orderBackward, backward), "trial " + trial);
            }
        }
        assertTrue(withoutFallback > 100, "trials that never needed the name: " + withoutFallback);
    }

    /** Lemma 2: adding methods nothing reaches does not move the methods the root reaches. */
    @Test
    void whatIsReachedIsOrderedIndependentlyOfWhatIsNot() {
        var reached = List.of(method("q.L.a", 1, List.of()), method("q.L.b", 1, List.of()), method("q.L.c", 2, List.of("q.L.b")));
        var roots = entries(calls("q.L.c", "q.L.a"));
        var alone = names(LibraryBindingOrder.innermostFirst(reached, roots, null));
        var more = new ArrayList<>(reached);
        more.add(method("q.L.x", 1, List.of()));
        more.add(method("q.L.y", 2, List.of("q.L.x")));
        var together = names(LibraryBindingOrder.innermostFirst(more, roots, null)).stream().filter(alone::contains).toList();
        assertEquals(alone, together);
        assertFalse(alone.isEmpty());
    }

    /** {@code \param. param}: a method whose only variable is its own parameter, named {@code param}. */
    private static LibraryMethod identity(String qualified, String param) {
        int dot = qualified.lastIndexOf('.');
        return new LibraryMethod(qualified.substring(0, dot), qualified.substring(dot + 1), FUN,
                new PirTerm.Lam(param, INT, new PirTerm.Var(param, INT)));
    }

    /**
     * Codex M1a C1: a local that spells a library method's name is not a call. Both methods have equal keys and nothing
     * calls either, so D1b's name decides; with a spelling search, b's parameter named q.M.a counted as a call of a.
     */
    @Test
    void aLocalSpelledLikeAMethodIsNotACall() {
        var selections = selections(List.of(identity("q.M.a", "x"), identity("q.M.b", "q.M.a")), Roots.NONE);
        assertEquals(new Selection("q.M.a", false, 0, Rule.UNREAD_BY_NAME), selections.getFirst());
        // Each one's local spells the other: no edge either way, so no cycle.
        var crossed = selections(List.of(identity("q.M.a", "q.M.b"), identity("q.M.b", "q.M.a")), Roots.NONE);
        assertTrue(crossed.stream().noneMatch(s -> s.rule() == Rule.CYCLE), crossed.toString());
        assertEquals(new Selection("q.M.a", false, 0, Rule.UNREAD_BY_NAME), crossed.getFirst());
    }

    /**
     * Codex M1a C6: at every choice of Kahn's algorithm between ready methods with equal keys, the lowest rank wins;
     * α-equal twins nothing calls, with the same callees, lie in one run of equal-key methods (Lemma 3's block; a
     * member of the run that is not identical to them can sit between them); and a dependent of one twin can be bound
     * between two reached twins (Lemma 4).
     */
    @Test
    void equalKeyChoicesTakeTheLowestRankAndTheLemmasHold() {
        var random = new Random(781);
        int choices = 0, adjacentPairs = 0, interleaved = 0;
        for (int trial = 0; trial < 500; trial++) {
            int count = 4 + random.nextInt(10);
            var constants = new ArrayList<Integer>();
            var calls = new ArrayList<List<Integer>>();
            for (int i = 0; i < count; i++) {
                constants.add(random.nextInt(2));
                var callees = new ArrayList<Integer>();
                for (int j = i + 1; j < count; j++) if (random.nextInt(5) == 0) callees.add(j);
                calls.add(callees);
            }
            var rootCalls = new ArrayList<String>();
            for (int i = 0; i < count; i++) if (random.nextInt(3) == 0) rootCalls.add("q.L.m" + (char) ('a' + i));
            var methods = library(constants, calls, i -> "q.L.m" + (char) ('a' + i));
            var tieChoices = new ArrayList<TieChoice>();
            var order = names(LibraryBindingOrder.innermostFirst(methods, entries(calls(rootCalls.toArray(String[]::new))),
                    new FirstUseRanks.Observer() {
                        @Override
                        public void selected(Selection selection) {
                        }

                        @Override
                        public void chose(TieChoice choice) {
                            tieChoices.add(choice);
                        }
                    }));
            for (var choice : tieChoices) {
                choices++;
                int lowest = choice.contenders().values().stream().mapToInt(Integer::intValue).min().orElseThrow();
                assertEquals(lowest, choice.contenders().get(choice.winner()), "trial " + trial + ": " + choice);
            }
            // Lemma 3 and 4, over pairs of twins with the same callees.
            var callers = new HashSet<String>();
            for (int i = 0; i < count; i++) for (int callee : calls.get(i)) callers.add("q.L.m" + (char) ('a' + callee));
            for (int i = 0; i < count; i++) {
                for (int j = i + 1; j < count; j++) {
                    if (!constants.get(i).equals(constants.get(j)) || !calls.get(i).equals(calls.get(j))) continue;
                    String a = "q.L.m" + (char) ('a' + i), b = "q.L.m" + (char) ('a' + j);
                    int distance = Math.abs(order.indexOf(a) - order.indexOf(b));
                    boolean unread = !callers.contains(a) && !callers.contains(b) && !rootCalls.contains(a) && !rootCalls.contains(b);
                    if (unread) {
                        // They lie in one run of equal-key methods: whatever is bound between them has their key.
                        int from = Math.min(order.indexOf(a), order.indexOf(b)), to = Math.max(order.indexOf(a), order.indexOf(b));
                        for (int k = from + 1; k < to; k++) {
                            int between = order.get(k).charAt(order.get(k).length() - 1) - 'a';
                            assertEquals(constants.get(i), constants.get(between), "trial " + trial + ": " + order.get(k)
                                    + " between unread twins " + a + ", " + b + " in " + order);
                            assertEquals(calls.get(i).size(), calls.get(between).size(), "trial " + trial + ": " + order);
                        }
                        adjacentPairs++;
                    } else if (distance > 1) {
                        interleaved++;
                    }
                }
            }
        }
        assertTrue(choices > 100, "equal-key choices: " + choices);
        assertTrue(adjacentPairs > 20, "unread twin pairs: " + adjacentPairs);
        assertTrue(interleaved > 0, "no dependent was ever bound between twins");
    }

    /** Codex M1a C7: a wide recursive group binds its names once, not once per part: the walk stays linear. */
    @Test
    void aWideRecursiveGroupIsWalkedInLinearTime() {
        long[] times = new long[3];
        int[] widths = {1_024, 2_048, 4_096};
        for (int round = 0; round < 3; round++) {
            for (int w = 0; w < widths.length; w++) {
                var bindings = new ArrayList<PirTerm.Binding>();
                for (int i = 0; i < widths[w]; i++) {
                    bindings.add(new PirTerm.Binding("r" + i, new PirTerm.Lam("x", INT, new PirTerm.Var("r" + ((i + 1) % widths[w]), FUN))));
                }
                var group = new PirTerm.LetRec(bindings, calls("q.L.alpha"));
                var twins = List.of(method("q.L.alpha", 1, List.of()), method("q.L.beta", 1, List.of()));
                long start = System.nanoTime();
                LibraryBindingOrder.innermostFirst(twins, entries(group), null);
                long elapsed = System.nanoTime() - start;
                times[w] = round == 0 ? elapsed : Math.min(times[w], elapsed);
            }
        }
        // Binding all names for each part would grow with the square of the width: sixteen times from 1,024 to 4,096.
        assertTrue(times[2] < 8 * times[0] + 20_000_000L, "1,024: " + times[0] + " ns, 4,096: " + times[2] + " ns");
    }

    private interface Namer {
        String name(int index);
    }

    private static List<LibraryMethod> library(List<Integer> constants, List<List<Integer>> calls, Namer namer) {
        var methods = new ArrayList<LibraryMethod>();
        for (int i = 0; i < constants.size(); i++) {
            methods.add(method(namer.name(i), constants.get(i), calls.get(i).stream().map(namer::name).toList()));
        }
        return methods;
    }

    private static List<Integer> indexes(List<LibraryMethod> order, List<LibraryMethod> methods) {
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < methods.size(); i++) index.put(methods.get(i).qualifiedName(), i);
        return order.stream().map(m -> index.get(m.qualifiedName())).toList();
    }
}
