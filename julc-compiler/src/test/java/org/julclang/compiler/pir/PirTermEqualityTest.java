package org.julclang.compiler.pir;

import org.julclang.core.Constant;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #327: {@link PirTerm} records compare structurally without recursion, and give the answer a record's own
 * {@code equals} gave.
 */
class PirTermEqualityTest {

    private static final PirType INT = new PirType.IntegerType();

    /** A record's {@code equals}, written out recursively: every component, in order. */
    static boolean reference(PirTerm a, PirTerm b) {
        if (a == b) return true;
        if (a.getClass() != b.getClass()) return false;
        return switch (a) {
            case PirTerm.Let x -> {
                var y = (PirTerm.Let) b;
                yield Objects.equals(x.name(), y.name()) && reference(x.value(), y.value()) && reference(x.body(), y.body());
            }
            case PirTerm.LetRec x -> {
                var y = (PirTerm.LetRec) b;
                if (x.bindings().size() != y.bindings().size()) yield false;
                for (int i = 0; i < x.bindings().size(); i++) {
                    if (!Objects.equals(x.bindings().get(i).name(), y.bindings().get(i).name())
                            || !reference(x.bindings().get(i).value(), y.bindings().get(i).value())) yield false;
                }
                yield reference(x.body(), y.body());
            }
            case PirTerm.Lam x -> {
                var y = (PirTerm.Lam) b;
                yield Objects.equals(x.param(), y.param()) && Objects.equals(x.paramType(), y.paramType())
                        && reference(x.body(), y.body());
            }
            case PirTerm.App x -> {
                var y = (PirTerm.App) b;
                yield reference(x.function(), y.function()) && reference(x.argument(), y.argument());
            }
            case PirTerm.IfThenElse x -> {
                var y = (PirTerm.IfThenElse) b;
                yield reference(x.cond(), y.cond()) && reference(x.thenBranch(), y.thenBranch())
                        && reference(x.elseBranch(), y.elseBranch());
            }
            case PirTerm.IntegerCase x -> {
                var y = (PirTerm.IntegerCase) b;
                yield reference(x.scrutinee(), y.scrutinee()) && all(x.branches(), y.branches());
            }
            case PirTerm.PairMatch x -> {
                var y = (PirTerm.PairMatch) b;
                yield reference(x.scrutinee(), y.scrutinee()) && x.pairType().equals(y.pairType())
                        && x.firstName().equals(y.firstName()) && x.secondName().equals(y.secondName())
                        && reference(x.body(), y.body());
            }
            case PirTerm.ListMatch x -> {
                var y = (PirTerm.ListMatch) b;
                yield reference(x.scrutinee(), y.scrutinee()) && x.headName().equals(y.headName())
                        && x.tailName().equals(y.tailName()) && reference(x.nilBranch(), y.nilBranch())
                        && reference(x.consBranch(), y.consBranch());
            }
            case PirTerm.DataConstr x -> {
                var y = (PirTerm.DataConstr) b;
                yield x.tag() == y.tag() && Objects.equals(x.dataType(), y.dataType()) && all(x.fields(), y.fields());
            }
            case PirTerm.DataMatch x -> {
                var y = (PirTerm.DataMatch) b;
                if (!reference(x.scrutinee(), y.scrutinee()) || x.branches().size() != y.branches().size()) yield false;
                for (int i = 0; i < x.branches().size(); i++) {
                    var p = x.branches().get(i);
                    var q = y.branches().get(i);
                    if (!Objects.equals(p.constructorName(), q.constructorName()) || !p.bindings().equals(q.bindings())
                            || !p.bindingTypes().equals(q.bindingTypes()) || !Objects.equals(p.patternVar(), q.patternVar())
                            || !reference(p.body(), q.body())) yield false;
                }
                yield true;
            }
            case PirTerm.Trace x -> {
                var y = (PirTerm.Trace) b;
                yield reference(x.message(), y.message()) && reference(x.body(), y.body());
            }
            case PirTerm.Var _, PirTerm.Const _, PirTerm.Builtin _, PirTerm.Error _ -> a.equals(b);
        };
    }

    private static boolean all(List<PirTerm> a, List<PirTerm> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!reference(a.get(i), b.get(i))) return false;
        return true;
    }

    @Test
    void agreesWithTheRecordEqualityOnRandomTerms() {
        var random = new Random(3270);
        int equalPairs = 0;
        for (int i = 0; i < 20_000; i++) {
            var a = PirDeepTermTest.random(random, 4);
            // A copy rebuilt from the same seed is equal; an independent term mostly is not.
            long seed = random.nextLong();
            var b = random.nextBoolean() ? PirDeepTermTest.random(new Random(seed), 4) : a;
            var c = PirDeepTermTest.random(new Random(seed), 4);
            for (var pair : List.of(List.of(a, b), List.of(b, c), List.of(a, c), List.of(a, mutate(a, random)))) {
                boolean expected = reference(pair.get(0), pair.get(1));
                assertEquals(expected, pair.get(0).equals(pair.get(1)), () -> pair.get(0) + " vs " + pair.get(1));
                assertEquals(expected, pair.get(1).equals(pair.get(0)), "symmetric");
                if (expected) {
                    equalPairs++;
                    assertEquals(pair.get(0).hashCode(), pair.get(1).hashCode(), "equal terms hash alike");
                }
            }
        }
        assertTrue(equalPairs > 10_000, "the random pairs exercise both answers");
    }

    /** A copy of {@code term} with one leaf in a random place replaced, so that it is equal unless the leaf differed. */
    private static PirTerm mutate(PirTerm term, Random random) {
        var children = PirHelpers.children(term);
        if (children.isEmpty()) return random.nextBoolean() ? term : new PirTerm.Var("mutated", INT);
        int target = random.nextInt(children.size());
        int[] index = {0};
        return PirHelpers.mapChildren(term, child -> index[0]++ == target ? mutate(child, random) : child);
    }

    @Test
    void deepTwinsCompareOnASmallStack() throws InterruptedException {
        var a = twin(100_000, 0);
        var b = twin(100_000, 0);
        var c = twin(100_000, 1);
        assertTrue(PirDeepTermTest.onSmallStack(() -> a.equals(b)));
        assertFalse(PirDeepTermTest.onSmallStack(() -> a.equals(c)));
        assertNotEquals(a, new PirTerm.Var("x", INT));
    }

    /** A spine of every binding form, its leaf differing by {@code leaf}. */
    private static PirTerm twin(int depth, int leaf) {
        PirTerm term = new PirTerm.Const(Constant.integer(leaf));
        var pair = new PirType.PairType(INT, INT);
        for (int i = 0; i < depth; i++) {
            var var = new PirTerm.Var("v" + (i % 5), INT);
            term = switch (i % 8) {
                case 0 -> new PirTerm.Let("x", var, term);
                case 1 -> new PirTerm.IfThenElse(var, var, term);
                case 2 -> new PirTerm.App(var, term);
                case 3 -> new PirTerm.Lam("y", INT, term);
                case 4 -> new PirTerm.LetRec(List.of(new PirTerm.Binding("f", var), new PirTerm.Binding("g", term)), var);
                case 5 -> new PirTerm.DataMatch(var, List.of(new PirTerm.MatchBranch("C", List.of("a"), term)));
                case 6 -> new PirTerm.PairMatch(var, pair, "p", "q", term);
                default -> new PirTerm.DataConstr(0, new PirType.DataType(), new ArrayList<>(List.of(var, term)));
            };
        }
        return term;
    }

    /** A record compares a null component as Objects.equals does: equal only to null (issue #327 review). */
    @Test
    void aNullComponentIsEqualOnlyToNull() {
        var type = new PirType.IntegerType();
        var one = new PirTerm.Const(Constant.integer(BigInteger.ONE));
        assertEquals(new PirTerm.Lam("x", type, null), new PirTerm.Lam("x", type, null));
        assertNotEquals(new PirTerm.Lam("x", type, null), new PirTerm.Lam("x", type, one));
        assertNotEquals(new PirTerm.Lam("x", type, one), new PirTerm.Lam("x", type, null));
        assertEquals(new PirTerm.Let("x", null, one), new PirTerm.Let("x", null, one));
        assertNotEquals(new PirTerm.Let("a", null, one), new PirTerm.Let("b", null, one));
        assertEquals(new PirTerm.App(null, null), new PirTerm.App(null, null));
        assertNotEquals(new PirTerm.App(one, null), new PirTerm.App(null, one));
        assertEquals(new PirTerm.IfThenElse(one, null, one), new PirTerm.IfThenElse(one, null, one));
        assertEquals(new PirTerm.Trace(null, one), new PirTerm.Trace(null, one));
        assertEquals(new PirTerm.Let(null, one, one), new PirTerm.Let(null, one, one));
        assertEquals(
                new PirTerm.LetRec(List.of(new PirTerm.Binding("f", null)), one),
                new PirTerm.LetRec(List.of(new PirTerm.Binding("f", null)), one));
        assertNotEquals(
                new PirTerm.LetRec(List.of(new PirTerm.Binding("f", null)), one),
                new PirTerm.LetRec(List.of(new PirTerm.Binding("f", one)), one));
        // A null child below a deep spine is compared without recursion too.
        PirTerm left = new PirTerm.Lam("x", type, null);
        PirTerm right = new PirTerm.Lam("x", type, null);
        for (int i = 0; i < 100_000; i++) {
            left = new PirTerm.Let("v", one, left);
            right = new PirTerm.Let("v", one, right);
        }
        assertEquals(left, right);
    }
}
