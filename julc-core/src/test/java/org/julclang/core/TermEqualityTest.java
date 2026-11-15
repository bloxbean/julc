package org.julclang.core;

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

/** {@link Term#equals} is a record's structural equality, computed without recursion (issue #327). */
class TermEqualityTest {

    /** The reference: structural equality written as plain recursion over the record components. */
    static boolean recursiveEqual(Term a, Term b) {
        return switch (a) {
            case Term.Lam l -> b instanceof Term.Lam o && l.paramName().equals(o.paramName()) && recursiveEqual(l.body(), o.body());
            case Term.Apply x -> b instanceof Term.Apply o && recursiveEqual(x.function(), o.function())
                    && recursiveEqual(x.argument(), o.argument());
            case Term.Force f -> b instanceof Term.Force o && recursiveEqual(f.term(), o.term());
            case Term.Delay d -> b instanceof Term.Delay o && recursiveEqual(d.term(), o.term());
            case Term.Constr c -> b instanceof Term.Constr o && c.tag() == o.tag() && listEqual(c.fields(), o.fields());
            case Term.Case c -> b instanceof Term.Case o && recursiveEqual(c.scrutinee(), o.scrutinee())
                    && listEqual(c.branches(), o.branches());
            case Term.Var v -> b instanceof Term.Var o && Objects.equals(v.name(), o.name());
            case Term.Const c -> b instanceof Term.Const o && Objects.equals(c.value(), o.value());
            case Term.Builtin f -> b instanceof Term.Builtin o && f.fun() == o.fun();
            case Term.Error _ -> b instanceof Term.Error;
        };
    }

    private static boolean listEqual(List<Term> a, List<Term> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!recursiveEqual(a.get(i), b.get(i))) return false;
        return true;
    }

    @Test
    void agreesWithRecursiveEqualityOnRandomTerms() {
        var random = new Random(32701);
        int equalPairs = 0;
        int unequalPairs = 0;
        for (int i = 0; i < 20_000; i++) {
            long seed = random.nextLong();
            int budget = 1 + random.nextInt(40);
            var a = DeepTerms.random(new Random(seed), budget);
            var b = switch (random.nextInt(3)) {
                case 0 -> DeepTerms.random(new Random(seed), budget); // equal, not identical
                case 1 -> mutate(a, random); // equal but for one node, usually
                default -> DeepTerms.random(new Random(random.nextLong()), 1 + random.nextInt(40));
            };
            boolean expected = recursiveEqual(a, b);
            assertEquals(expected, a.equals(b), "pair " + i);
            assertEquals(expected, b.equals(a), "pair " + i + " reversed");
            if (expected) equalPairs++;
            else unequalPairs++;
        }
        assertTrue(equalPairs > 3000 && unequalPairs > 3000, "both kinds sampled: " + equalPairs + "/" + unequalPairs);
    }

    /** A copy of {@code term} with, at one random point, a different leaf. */
    private static Term mutate(Term term, Random random) {
        if (random.nextInt(4) == 0) return random.nextBoolean() ? DeepTerms.var(7) : new Term.Error();
        return switch (term) {
            case Term.Lam l -> new Term.Lam(l.paramName(), mutate(l.body(), random));
            case Term.Apply x -> random.nextBoolean() ? new Term.Apply(mutate(x.function(), random), x.argument())
                    : new Term.Apply(x.function(), mutate(x.argument(), random));
            case Term.Force f -> new Term.Force(mutate(f.term(), random));
            case Term.Delay d -> new Term.Delay(mutate(d.term(), random));
            case Term.Constr c -> c.fields().isEmpty() ? new Term.Constr(c.tag() + 1, c.fields())
                    : new Term.Constr(c.tag(), mutateOne(c.fields(), random));
            case Term.Case c -> c.branches().isEmpty() || random.nextBoolean()
                    ? new Term.Case(mutate(c.scrutinee(), random), c.branches())
                    : new Term.Case(c.scrutinee(), mutateOne(c.branches(), random));
            default -> DeepTerms.var(9);
        };
    }

    private static List<Term> mutateOne(List<Term> terms, Random random) {
        var copy = new ArrayList<>(terms);
        int index = random.nextInt(copy.size());
        copy.set(index, mutate(copy.get(index), random));
        return copy;
    }

    @Test
    void deepTwinsCompareOnASmallStack() {
        for (var position : DeepTerms.Position.values()) {
            var a = DeepTerms.spine(100_000, position);
            var b = DeepTerms.spine(100_000, position);
            assertTrue(DeepTerms.onSmallStack(() -> a.equals(b)), position + ": equal twins");
        }
    }

    @Test
    void deepTermsThatDifferAtTheBottomAreUnequal() {
        for (var position : DeepTerms.Position.values()) {
            var a = DeepTerms.spine(100_000, position);
            var b = replaceBottom(DeepTerms.spine(100_000, position));
            assertFalse(DeepTerms.onSmallStack(() -> a.equals(b)), position + ": different bottoms");
        }
    }

    /** The same spine with its innermost variable replaced by another index. */
    private static Term replaceBottom(Term spine) {
        // Rebuild the spine bottom-up: count its depth, then build a twin whose bottom differs.
        var position = positionOf(spine);
        int depth = 0;
        Term cursor = spine;
        while (!(cursor instanceof Term.Var)) {
            cursor = child(cursor, position);
            depth++;
        }
        Term term = DeepTerms.var(3);
        for (int i = 0; i < depth; i++) {
            Term small = new Term.Const(Constant.integer(BigInteger.valueOf(i % 5)));
            term = switch (position) {
                case LAM_BODY -> new Term.Lam("i0", term);
                case APPLY_FUNCTION -> new Term.Apply(term, small);
                case APPLY_ARGUMENT -> new Term.Apply(new Term.Builtin(DefaultFun.IData), term);
                case FORCE -> new Term.Force(term);
                case DELAY -> new Term.Delay(term);
                case CONSTR_FIELD -> new Term.Constr(i % 3, List.of(small, term, new Term.Error()));
                case CASE_SCRUTINEE -> new Term.Case(term, List.of(small, DeepTerms.var(2)));
                case CASE_BRANCH -> new Term.Case(DeepTerms.var(1), List.of(small, term, new Term.Error()));
            };
        }
        return term;
    }

    private static DeepTerms.Position positionOf(Term spine) {
        for (var position : DeepTerms.Position.values()) {
            if (DeepTerms.spine(2, position).getClass() == spine.getClass()
                    && sameShape(DeepTerms.spine(2, position), spine, position)) return position;
        }
        throw new AssertionError("not a spine");
    }

    private static boolean sameShape(Term model, Term spine, DeepTerms.Position position) {
        return switch (position) {
            case APPLY_FUNCTION -> ((Term.Apply) spine).argument() instanceof Term.Const;
            case APPLY_ARGUMENT -> ((Term.Apply) spine).function() instanceof Term.Builtin;
            case CASE_SCRUTINEE -> ((Term.Case) spine).branches().size() == 2;
            case CASE_BRANCH -> ((Term.Case) spine).branches().size() == 3;
            default -> true;
        };
    }

    private static Term child(Term term, DeepTerms.Position position) {
        return switch (position) {
            case LAM_BODY -> ((Term.Lam) term).body();
            case APPLY_FUNCTION -> ((Term.Apply) term).function();
            case APPLY_ARGUMENT -> ((Term.Apply) term).argument();
            case FORCE -> ((Term.Force) term).term();
            case DELAY -> ((Term.Delay) term).term();
            case CONSTR_FIELD -> ((Term.Constr) term).fields().get(1);
            case CASE_SCRUTINEE -> ((Term.Case) term).scrutinee();
            case CASE_BRANCH -> ((Term.Case) term).branches().get(1);
        };
    }

    @Test
    void comparesComponentsThatHoldNoTerm() {
        assertNotEquals(new Term.Lam("x", DeepTerms.var(1)), new Term.Lam("y", DeepTerms.var(1)));
        assertNotEquals(new Term.Constr(1, List.of()), new Term.Constr(2, List.of()));
        assertNotEquals(new Term.Constr(1, List.of(DeepTerms.var(1))), new Term.Constr(1, List.of()));
        assertNotEquals(new Term.Case(DeepTerms.var(1), List.of()), new Term.Case(DeepTerms.var(1), List.of(DeepTerms.var(1))));
        assertNotEquals(new Term.Force(DeepTerms.var(1)), new Term.Delay(DeepTerms.var(1)));
        assertNotEquals(new Term.Apply(DeepTerms.var(1), DeepTerms.var(2)), "not a term");
        assertFalse(new Term.Delay(DeepTerms.var(1)).equals(null));
        assertEquals(new Term.Apply(new Term.Error(), DeepTerms.var(2)), new Term.Apply(new Term.Error(), DeepTerms.var(2)));
        // Equal terms keep equal hash codes: hashCode is still the record's.
        var a = DeepTerms.random(new Random(5), 30);
        var b = DeepTerms.random(new Random(5), 30);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
