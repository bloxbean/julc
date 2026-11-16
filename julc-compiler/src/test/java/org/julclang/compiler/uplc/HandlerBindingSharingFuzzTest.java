package org.julclang.compiler.uplc;

import org.julclang.compiler.Adr076Sharing;
import org.julclang.compiler.CompilerTestVm;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.julclang.core.NamedDeBruijn;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.core.Term;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.vm.EvalResult;
import org.julclang.vm.ExBudget;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-076 D1 on random programs shaped as a multi-validator: a dispatch on an integer over two to four handlers and a
 * last branch that fails, as for a purpose with no handler. Each handler binds the same methods in the same order, some
 * of them differently in one handler, with a binder of its own somewhere in the chain that some methods read. A method
 * is a lambda or a partial builtin application. In some programs one handler binds part of its chain under a lambda
 * that is not a {@code let}'s, or behind a {@code force (delay ...)}. For each program,
 * {@link HandlerBindingSharing#share}:
 * <ul>
 *   <li>shares exactly the methods the generator knows to be the same function in every handler, reading nothing bound
 *       in a handler and no method that is not shared;</li>
 *   <li>only moves bindings: with every method binding inlined, the programs are the same, as FLAT;</li>
 *   <li>never grows the script; and</li>
 *   <li>keeps every run: the same result and traces, the same budget on every input a handler takes, and on the input
 *       no handler takes, exactly the steps of the shared bindings: an application and a lambda each, and its value's
 *       own (one for a lambda, three for a partial application).</li>
 * </ul>
 */
class HandlerBindingSharingFuzzTest {

    private static final int PROGRAMS = 3000;

    /** Where one handler puts part of its chain: nowhere else, under a lambda that is not a let's, or behind a force. */
    private enum Misplaced { NONE, UNDER_LAMBDA, BEHIND_FORCE }

    /** A program: each method's body (or its constant, for a partial application), and how each handler binds them. */
    private record Shape(List<Expr> bodies, boolean[] partial, long[] constants, boolean[][] differs, int[] localAt,
                         Misplaced misplaced, int misplacedHandler, int misplacedAt) {
        int methods() {
            return bodies.size();
        }
    }

    /** A method body: an integer expression of its parameter {@code p}, the input {@code x}, a handler's own binder. */
    private sealed interface Expr {
        record Parameter() implements Expr {}
        record Input() implements Expr {}
        record Local() implements Expr {}
        record Constant(long value) implements Expr {}
        record Add(Expr a, Expr b) implements Expr {}
        record Multiply(Expr a, Expr b) implements Expr {}
        record Call(int method, Expr argument) implements Expr {}
        record Trace(String message, Expr then) implements Expr {}
        record IfLess(Expr a, Expr b, Expr then, Expr otherwise) implements Expr {}
    }

    @Test
    void randomMultiValidatorsShareExactlyTheSameFunctionsAndKeepEveryRun() {
        var random = new Random(20261115L);
        int sharedSomething = 0;
        int sharedNothing = 0;
        for (int program = 0; program < PROGRAMS; program++) {
            int handlers = 2 + random.nextInt(3);
            int methods = 1 + random.nextInt(5);
            var bodies = new ArrayList<Expr>();
            var partial = new boolean[methods];
            var constants = new long[methods];
            for (int m = 0; m < methods; m++) {
                bodies.add(expr(random, m, 3));
                // The last method is the one each handler calls on its input; any other may be a partial application.
                partial[m] = m < methods - 1 && random.nextInt(6) == 0;
                constants[m] = random.nextInt(9);
            }
            // Per handler: which methods differ there, and where its own binder sits in the chain.
            var differs = new boolean[handlers][methods];
            var localAt = new int[handlers];
            for (int h = 0; h < handlers; h++) {
                localAt[h] = random.nextInt(methods + 1);
                for (int m = 0; m < methods; m++) differs[h][m] = random.nextInt(8) == 0;
            }
            var misplaced = Misplaced.values()[random.nextInt(10) < 6 ? 0 : 1 + random.nextInt(2)];
            var shape = new Shape(bodies, partial, constants, differs, localAt, misplaced, random.nextInt(handlers),
                    random.nextInt(methods));
            String label = "program " + program + " " + misplaced;

            var expected = expectedShared(shape);
            Term term = program(shape, handlers);
            var names = new LinkedHashSet<String>();
            for (int m = 0; m < methods; m++) names.add(name(m));
            Term shared = HandlerBindingSharing.share(term, new HandlerBindings(names, handlers));

            var bound = bindings(shared);
            var sharedNames = new TreeMap<String, Boolean>();
            for (int m = 0; m < methods; m++) sharedNames.put(name(m), bound.getOrDefault(name(m), 0) == 1);
            for (int m = 0; m < methods; m++) {
                assertEquals(expected.contains(m), sharedNames.get(name(m)), label + " " + name(m) + " " + sharedNames
                        + " bodies=" + bodies + " partial=" + Arrays.toString(partial) + " differs="
                        + Arrays.deepToString(differs) + " localAt=" + Arrays.toString(localAt) + " misplaced at "
                        + shape.misplacedHandler() + "/" + shape.misplacedAt());
            }
            if (expected.isEmpty()) {
                assertEquals(term, shared, label);
                sharedNothing++;
                continue;
            }
            sharedSomething++;
            assertEquals(InlinedMethods.flat(term), InlinedMethods.flat(shared), label);
            assertTrue(UplcFlatEncoder.encodeProgram(Program.plutusV3(shared)).length
                    < UplcFlatEncoder.encodeProgram(Program.plutusV3(term)).length, label);
            for (int input = -1; input <= handlers; input++) {
                String run = label + " input " + input;
                var before = CompilerTestVm.pv11().evaluateWithArgs(Program.plutusV3(term), List.of(PlutusData.integer(input)));
                var after = CompilerTestVm.pv11().evaluateWithArgs(Program.plutusV3(shared), List.of(PlutusData.integer(input)));
                assertEquals(before.isSuccess(), after.isSuccess(), run + " " + before + " / " + after);
                assertEquals(before.traces(), after.traces(), run);
                if (before instanceof EvalResult.Success success) {
                    assertEquals(success.resultTerm(), ((EvalResult.Success) after).resultTerm(), run);
                }
                boolean handled = input >= 0 && input < handlers;
                // A binding is an application, a lambda and its value: one step for a lambda, three for a partial
                // application of a builtin to a constant.
                int steps = 0;
                if (!handled) for (int m : expected) steps += partial[m] ? 5 : 3;
                assertEquals(add(before.budgetConsumed(), steps), after.budgetConsumed(), run);
            }
        }
        // The generator reaches both outcomes often.
        assertTrue(sharedSomething > PROGRAMS / 4, "shared something in " + sharedSomething);
        assertTrue(sharedNothing > PROGRAMS / 20, "shared nothing in " + sharedNothing);
    }

    /**
     * The methods that must be shared, from what the generator knows: the same body in every handler (changed in all
     * or in none, since the change is the same), reading no handler's own binder, and calling only methods that are
     * shared. A body that names the handler's binder reads it only where it is already bound, and a constant elsewhere.
     * Then the misplacement: a method bound under a lambda that is not a let's is not shared, nor anything that reads
     * it; behind a force, one handler's chain is two spines, and only the methods on the first shared method's side are.
     */
    private static Set<Integer> expectedShared(Shape shape) {
        var shared = new java.util.TreeSet<Integer>();
        for (int m = 0; m < shape.methods(); m++) {
            boolean same = true;
            boolean readsABinderOfAHandler = false;
            for (int h = 0; h < shape.differs().length; h++) {
                same &= shape.differs()[h][m] == shape.differs()[0][m];
                readsABinderOfAHandler |= !shape.partial()[m] && shape.localAt()[h] <= m && readsLocal(shape.bodies().get(m));
            }
            if (!same || readsABinderOfAHandler) continue;
            if (shape.misplaced() == Misplaced.UNDER_LAMBDA && m >= shape.misplacedAt()) continue;
            boolean callsShared = true;
            if (!shape.partial()[m]) for (int callee : callees(shape.bodies().get(m))) callsShared &= shared.contains(callee);
            if (callsShared) shared.add(m);
        }
        if (shape.misplaced() == Misplaced.BEHIND_FORCE && !shared.isEmpty()) {
            boolean before = shared.first() < shape.misplacedAt();
            shared.removeIf(m -> (m < shape.misplacedAt()) != before);
        }
        return shared;
    }

    /**
     * {@code \d. let x = unIData d in if x == 0 then H0 else if x == 1 then H1 ... else error}, where each handler is
     * {@code let m0 = ... in let m1 = ... in ... in m_last x}, its own binder {@code #local = x + 7} placed at
     * {@code localAt[h]} in the chain.
     */
    private static Term program(Shape shape, int handlers) {
        var scope = new ArrayList<String>();
        scope.addFirst("#d");
        scope.addFirst("#x");
        Term dispatch = Term.error();
        for (int h = handlers - 1; h >= 0; h--) {
            Term handler = chain(shape, h, 0, scope, false, false);
            Term test = Term.apply(Term.apply(Term.builtin(DefaultFun.EqualsInteger), var(scope, "#x")), integer(h));
            dispatch = Term.force(Term.apply(Term.apply(Term.apply(Term.force(Term.builtin(DefaultFun.IfThenElse)), test),
                    Term.delay(handler)), Term.delay(dispatch)));
        }
        Term unI = Term.apply(Term.builtin(DefaultFun.UnIData), Term.var(1));
        return Term.lam("#d", Term.apply(Term.lam("#x", dispatch), unI));
    }

    /** Handler {@code h}'s chain from method {@code position} on, under {@code scope}. */
    private static Term chain(Shape shape, int h, int position, List<String> scope, boolean localBound, boolean misplacedDone) {
        if (position == shape.localAt()[h] && !localBound) {
            var inner = new ArrayList<>(scope);
            inner.addFirst("#local");
            Term value = Term.apply(Term.apply(Term.builtin(DefaultFun.AddInteger), var(scope, "#x")), integer(7));
            return Term.apply(Term.lam("#local", chain(shape, h, position, inner, true, misplacedDone)), value);
        }
        if (position == shape.methods()) return Term.apply(var(scope, name(position - 1)), var(scope, "#x"));
        if (h == shape.misplacedHandler() && position == shape.misplacedAt() && !misplacedDone) {
            switch (shape.misplaced()) {
                case UNDER_LAMBDA -> {
                    // [(\#g. [#g 0]) (\#u. rest)]: the rest is bound in a lambda that is a let's value, called once.
                    var inner = new ArrayList<>(scope);
                    inner.addFirst("#u");
                    return Term.apply(Term.lam("#g", Term.apply(Term.var(1), integer(0))),
                            Term.lam("#u", chain(shape, h, position, inner, localBound, true)));
                }
                case BEHIND_FORCE -> {
                    return Term.force(Term.delay(chain(shape, h, position, scope, localBound, true)));
                }
                case NONE -> {
                }
            }
        }
        boolean changed = shape.differs()[h][position];
        Term value;
        if (shape.partial()[position]) {
            value = Term.apply(Term.builtin(DefaultFun.AddInteger), integer(shape.constants()[position] + (changed ? 1 : 0)));
        } else {
            var body = changed ? mutate(shape.bodies().get(position)) : shape.bodies().get(position);
            var inner = new ArrayList<>(scope);
            inner.addFirst("#p");
            value = Term.lam("#p", lower(body, inner));
        }
        var inner = new ArrayList<>(scope);
        inner.addFirst(name(position));
        return Term.apply(Term.lam(name(position), chain(shape, h, position + 1, inner, localBound, misplacedDone)), value);
    }

    private static Term lower(Expr expr, List<String> scope) {
        return switch (expr) {
            case Expr.Parameter _ -> var(scope, "#p");
            case Expr.Input _ -> var(scope, "#x");
            case Expr.Local _ -> scope.contains("#local") ? var(scope, "#local") : integer(11);
            case Expr.Constant(var value) -> integer(value);
            case Expr.Add(var a, var b) -> Term.apply(Term.apply(Term.builtin(DefaultFun.AddInteger), lower(a, scope)), lower(b, scope));
            case Expr.Multiply(var a, var b) ->
                    Term.apply(Term.apply(Term.builtin(DefaultFun.MultiplyInteger), lower(a, scope)), lower(b, scope));
            case Expr.Call(var method, var argument) -> Term.apply(var(scope, name(method)), lower(argument, scope));
            case Expr.Trace(var message, var then) -> Term.apply(Term.apply(Term.force(Term.builtin(DefaultFun.Trace)),
                    Term.const_(Constant.string(message))), lower(then, scope));
            case Expr.IfLess(var a, var b, var then, var otherwise) -> Term.force(Term.apply(Term.apply(Term.apply(
                    Term.force(Term.builtin(DefaultFun.IfThenElse)),
                    Term.apply(Term.apply(Term.builtin(DefaultFun.LessThanInteger), lower(a, scope)), lower(b, scope))),
                    Term.delay(lower(then, scope))), Term.delay(lower(otherwise, scope))));
        };
    }

    /** {@code expr} changed so that it lowers to a different term. */
    private static Expr mutate(Expr expr) {
        return new Expr.Add(expr, new Expr.Constant(1));
    }

    private static Expr expr(Random random, int method, int depth) {
        int choice = random.nextInt(depth == 0 ? 4 : 9);
        return switch (choice) {
            case 0 -> new Expr.Parameter();
            case 1 -> new Expr.Input();
            case 2 -> random.nextInt(4) == 0 ? new Expr.Local() : new Expr.Parameter();
            case 3 -> new Expr.Constant(random.nextInt(20) - 5);
            case 4 -> new Expr.Add(expr(random, method, depth - 1), expr(random, method, depth - 1));
            case 5 -> new Expr.Multiply(expr(random, method, depth - 1), new Expr.Constant(random.nextInt(3)));
            case 6 -> method == 0 ? new Expr.Parameter() : new Expr.Call(random.nextInt(method), expr(random, method, depth - 1));
            case 7 -> new Expr.Trace("m" + method, expr(random, method, depth - 1));
            default -> new Expr.IfLess(expr(random, method, depth - 1), new Expr.Constant(random.nextInt(10)),
                    expr(random, method, depth - 1), expr(random, method, depth - 1));
        };
    }

    private static boolean readsLocal(Expr expr) {
        return switch (expr) {
            case Expr.Local _ -> true;
            case Expr.Add(var a, var b) -> readsLocal(a) || readsLocal(b);
            case Expr.Multiply(var a, var b) -> readsLocal(a) || readsLocal(b);
            case Expr.Call(var _, var argument) -> readsLocal(argument);
            case Expr.Trace(var _, var then) -> readsLocal(then);
            case Expr.IfLess(var a, var b, var then, var otherwise) ->
                    readsLocal(a) || readsLocal(b) || readsLocal(then) || readsLocal(otherwise);
            default -> false;
        };
    }

    private static Set<Integer> callees(Expr expr) {
        var callees = new HashSet<Integer>();
        var pending = new ArrayList<Expr>();
        pending.add(expr);
        while (!pending.isEmpty()) {
            switch (pending.removeLast()) {
                case Expr.Call(var method, var argument) -> {
                    callees.add(method);
                    pending.add(argument);
                }
                case Expr.Add(var a, var b) -> {
                    pending.add(a);
                    pending.add(b);
                }
                case Expr.Multiply(var a, var b) -> {
                    pending.add(a);
                    pending.add(b);
                }
                case Expr.Trace(var _, var then) -> pending.add(then);
                case Expr.IfLess(var a, var b, var then, var otherwise) -> {
                    pending.add(a);
                    pending.add(b);
                    pending.add(then);
                    pending.add(otherwise);
                }
                default -> {
                }
            }
        }
        return callees;
    }

    /** How many times {@code term} binds each method. */
    private static TreeMap<String, Integer> bindings(Term term) {
        var bindings = new TreeMap<String, Integer>();
        var pending = new ArrayList<Term>();
        pending.add(term);
        while (!pending.isEmpty()) {
            switch (pending.removeLast()) {
                case Term.Apply(var function, var argument) -> {
                    if (function instanceof Term.Lam(var name, var _) && name.contains(".")) bindings.merge(name, 1, Integer::sum);
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
        return bindings;
    }

    private static String name(int method) {
        return "Fuzz.m" + method;
    }

    private static Term var(List<String> scope, String name) {
        int index = scope.indexOf(name);
        if (index < 0) throw new IllegalStateException(name + " is not in scope " + scope);
        return new Term.Var(new NamedDeBruijn(name, index + 1));
    }

    private static Term integer(long value) {
        return Term.const_(Constant.integer(BigInteger.valueOf(value)));
    }

    private static ExBudget add(ExBudget budget, int steps) {
        return new ExBudget(budget.cpuSteps() + steps * Adr076Sharing.STEP.cpuSteps(),
                budget.memoryUnits() + steps * Adr076Sharing.STEP.memoryUnits());
    }
}
