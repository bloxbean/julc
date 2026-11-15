package org.julclang.core.recursion;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A recursive computation whose pending work is kept on the heap, not on the Java stack (issue #327, ADR-074).
 * <p>
 * A term nests as deeply as a program is long: every helper method a validator binds, every statement of a block and
 * every element of a list literal adds a level. A walk that recursed on the Java stack failed once the term was deep
 * enough for the host's stack, and in a GraalVM Web Image the overflow reached JavaScript as an opaque
 * {@code TypeError}. A walk written with this class uses a bounded amount of Java stack at any depth.
 * <p>
 * A recursive method returns a {@code Trampoline} instead of its result, and starts with {@link #defer}, so that calling
 * it only describes the work:
 * <pre>{@code
 * Trampoline<Integer> size(Tree tree) {
 *     return Trampoline.defer(() -> switch (tree) {
 *         case Leaf _ -> Trampoline.done(1);
 *         case Node node -> size(node.left()).then(left -> size(node.right()).map(right -> left + right + 1));
 *     });
 * }
 * int n = size(tree).run();
 * }</pre>
 * Steps run in the order the code describes them, one after another, so a walk that reads or updates state as it goes
 * behaves as its recursive form did: {@code a.then(x -> b)} runs all of {@code a} before any of {@code b}. An exception
 * thrown by a step ends {@link #run} with that exception.
 * <p>
 * Only one {@link #run} drives a recursion. A step may call {@code run()} on an unrelated, independent computation,
 * but must not call it on a computation that recurses back into the walk it is part of: that nests the Java stack
 * again, once per level.
 *
 * @param <T> the result type; {@code null} is a result like any other
 */
public abstract sealed class Trampoline<T> {

    private Trampoline() {
    }

    /** A computation that is already finished, with {@code value}. */
    public static <T> Trampoline<T> done(T value) {
        return new Done<>(value);
    }

    /**
     * A computation whose first step is {@code step}, run from {@link #run}'s loop rather than from the caller's stack.
     * A recursive method wraps its body in this.
     */
    public static <T> Trampoline<T> defer(Supplier<Trampoline<T>> step) {
        return new Defer<>(step);
    }

    /** This computation, then {@code next} applied to its result. */
    public final <R> Trampoline<R> then(Function<? super T, Trampoline<R>> next) {
        return new Then<>(this, next);
    }

    /** This computation, with {@code map} applied to its result. */
    public final <R> Trampoline<R> map(Function<? super T, ? extends R> map) {
        return new Then<>(this, value -> new Done<>(map.apply(value)));
    }

    /** {@code first}, then {@code second}, then their results combined. */
    public static <A, B, R> Trampoline<R> both(Trampoline<A> first, Trampoline<B> second,
                                               BiFunction<? super A, ? super B, ? extends R> combine) {
        return first.then(a -> second.map(b -> combine.apply(a, b)));
    }

    /**
     * {@code step} applied to each item, in order, each finishing before the next starts; the results in an unmodifiable
     * list of the same order. {@code step} is called when the item's turn comes, not before, and must itself return a
     * computation that has not yet done its work (one that starts with {@link #defer}), or the walk is not stack-safe.
     * The items are read through one iterator per run, so any {@code List} is walked in linear time.
     */
    public static <A, R> Trampoline<List<R>> each(List<? extends A> items, Function<? super A, Trampoline<R>> step) {
        return defer(() -> eachFrom(items.iterator(), step, new ArrayList<>(items.size())));
    }

    private static <A, R> Trampoline<List<R>> eachFrom(Iterator<? extends A> items,
                                                        Function<? super A, Trampoline<R>> step, List<R> results) {
        if (!items.hasNext()) return done(Collections.unmodifiableList(results));
        return step.apply(items.next()).then(result -> {
            results.add(result);
            return eachFrom(items, step, results);
        });
    }

    /**
     * Whether {@code test} holds for some item, tested in order and stopping at the first that holds, as {@code ||} does.
     */
    public static <A> Trampoline<Boolean> any(List<? extends A> items, Function<? super A, Trampoline<Boolean>> test) {
        return defer(() -> anyFrom(items.iterator(), test));
    }

    private static <A> Trampoline<Boolean> anyFrom(Iterator<? extends A> items,
                                                   Function<? super A, Trampoline<Boolean>> test) {
        if (!items.hasNext()) return done(false);
        return test.apply(items.next()).then(holds -> holds ? done(true) : anyFrom(items, test));
    }

    /**
     * Whether {@code test} holds for every item, tested in order and stopping at the first that fails, as {@code &&} does.
     */
    public static <A> Trampoline<Boolean> all(List<? extends A> items, Function<? super A, Trampoline<Boolean>> test) {
        return defer(() -> allFrom(items.iterator(), test));
    }

    private static <A> Trampoline<Boolean> allFrom(Iterator<? extends A> items,
                                                   Function<? super A, Trampoline<Boolean>> test) {
        if (!items.hasNext()) return done(true);
        return test.apply(items.next()).then(holds -> holds ? allFrom(items, test) : done(false));
    }

    /** Runs the computation to its result, using a bounded amount of Java stack whatever its depth. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public final T run() {
        Trampoline current = this;
        var pending = new ArrayDeque<Function>();
        while (true) {
            switch (current) {
                case Done done -> {
                    if (pending.isEmpty()) return (T) done.value;
                    current = (Trampoline) pending.pop().apply(done.value);
                }
                case Defer defer -> current = (Trampoline) defer.step.get();
                case Then then -> {
                    pending.push(then.next);
                    current = then.source;
                }
            }
            if (current == null) throw new NullPointerException("a step returned no Trampoline");
        }
    }

    private static final class Done<T> extends Trampoline<T> {
        private final T value;

        private Done(T value) {
            this.value = value;
        }
    }

    private static final class Defer<T> extends Trampoline<T> {
        private final Supplier<Trampoline<T>> step;

        private Defer(Supplier<Trampoline<T>> step) {
            this.step = step;
        }
    }

    private static final class Then<S, T> extends Trampoline<T> {
        private final Trampoline<S> source;
        private final Function<? super S, Trampoline<T>> next;

        private Then(Trampoline<S> source, Function<? super S, Trampoline<T>> next) {
            this.source = source;
            this.next = next;
        }
    }
}
