package org.julclang.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Terms for the stack-safety tests of issue #327: deep spines through every child position, and random shallow terms.
 * Names follow the FLAT decoder's ({@code i0} for every binder, {@code i<index>} for a variable), so a decoded term
 * equals the one encoded.
 */
public final class DeepTerms {

    private DeepTerms() {
    }

    /** The child position a spine runs through. */
    public enum Position {
        LAM_BODY, APPLY_FUNCTION, APPLY_ARGUMENT, FORCE, DELAY, CONSTR_FIELD, CASE_SCRUTINEE, CASE_BRANCH
    }

    /** A term {@code depth} levels deep, each level holding the rest at {@code position}, with small siblings. */
    public static Term spine(int depth, Position position) {
        Term term = var(1);
        for (int i = 0; i < depth; i++) {
            Term small = new Term.Const(Constant.integer(BigInteger.valueOf(i % 5)));
            term = switch (position) {
                case LAM_BODY -> new Term.Lam("i0", term);
                case APPLY_FUNCTION -> new Term.Apply(term, small);
                case APPLY_ARGUMENT -> new Term.Apply(new Term.Builtin(DefaultFun.IData), term);
                case FORCE -> new Term.Force(term);
                case DELAY -> new Term.Delay(term);
                case CONSTR_FIELD -> new Term.Constr(i % 3, List.of(small, term, new Term.Error()));
                case CASE_SCRUTINEE -> new Term.Case(term, List.of(small, var(2)));
                case CASE_BRANCH -> new Term.Case(var(1), List.of(small, term, new Term.Error()));
            };
        }
        return term;
    }

    public static Term var(int index) {
        return new Term.Var(new NamedDeBruijn("i" + index, index));
    }

    /** A random term of about {@code budget} nodes. */
    public static Term random(Random random, int budget) {
        if (budget <= 1) return leaf(random);
        return switch (random.nextInt(8)) {
            case 0 -> leaf(random);
            case 1 -> new Term.Lam("i0", random(random, budget - 1));
            case 2 -> new Term.Apply(random(random, budget / 2), random(random, budget / 2));
            case 3 -> new Term.Force(random(random, budget - 1));
            case 4 -> new Term.Delay(random(random, budget - 1));
            case 5 -> new Term.Constr(random.nextInt(4), list(random, budget));
            case 6 -> new Term.Case(random(random, budget / 2), list(random, budget / 2));
            default -> new Term.Apply(new Term.Lam("i0", random(random, budget / 2)), random(random, budget / 2));
        };
    }

    private static List<Term> list(Random random, int budget) {
        int size = random.nextInt(4);
        var terms = new ArrayList<Term>();
        for (int i = 0; i < size; i++) terms.add(random(random, Math.max(1, budget / Math.max(1, size)) - 1));
        return terms;
    }

    private static Term leaf(Random random) {
        return switch (random.nextInt(6)) {
            case 0 -> var(random.nextInt(6));
            case 1 -> new Term.Const(Constant.integer(BigInteger.valueOf(random.nextInt(2001) - 1000)));
            case 2 -> new Term.Const(Constant.byteString(new byte[]{(byte) random.nextInt(256), 7}));
            case 3 -> new Term.Const(Constant.bool(random.nextBoolean()));
            case 4 -> new Term.Builtin(DefaultFun.values()[random.nextInt(DefaultFun.values().length)]);
            default -> new Term.Error();
        };
    }

    /** Runs {@code work} on a thread with a 256 KB stack and returns its result, failing on any throwable. */
    public static <T> T onSmallStack(Supplier<T> work) {
        var result = new AtomicReference<T>();
        var failure = new AtomicReference<Throwable>();
        var thread = new Thread(null, () -> {
            try {
                result.set(work.get());
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "small-stack", 256 * 1024);
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
        if (failure.get() != null) throw new AssertionError("failed on a 256 KB stack", failure.get());
        return result.get();
    }
}
