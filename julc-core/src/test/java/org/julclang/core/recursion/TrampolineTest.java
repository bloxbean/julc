package org.julclang.core.recursion;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrampolineTest {

    sealed interface Tree permits Leaf, Node {}

    record Leaf(int value) implements Tree {}

    record Node(String label, List<Tree> children) implements Tree {}

    /** The reference: a plain recursive walk that logs a pre- and post-order visit and sums the leaves. */
    static int recursiveSum(Tree tree, List<String> log) {
        return switch (tree) {
            case Leaf leaf -> {
                log.add("leaf " + leaf.value());
                yield leaf.value();
            }
            case Node node -> {
                log.add("enter " + node.label());
                int sum = 0;
                for (var child : node.children()) sum += recursiveSum(child, log);
                log.add("exit " + node.label());
                yield sum;
            }
        };
    }

    static Trampoline<Integer> sum(Tree tree, List<String> log) {
        return Trampoline.defer(() -> switch (tree) {
            case Leaf leaf -> {
                log.add("leaf " + leaf.value());
                yield Trampoline.done(leaf.value());
            }
            case Node node -> {
                log.add("enter " + node.label());
                yield Trampoline.each(node.children(), child -> sum(child, log)).map(sums -> {
                    log.add("exit " + node.label());
                    return sums.stream().mapToInt(Integer::intValue).sum();
                });
            }
        });
    }

    static Tree random(Random random, int budget) {
        if (budget <= 1 || random.nextInt(4) == 0) return new Leaf(random.nextInt(100) - 50);
        int arity = random.nextInt(4);
        var children = new ArrayList<Tree>();
        for (int i = 0; i < arity; i++) children.add(random(random, budget / Math.max(1, arity) - 1 + random.nextInt(2)));
        return new Node("n" + random.nextInt(1000), children);
    }

    /** A spine of {@code depth} nodes, each with its deep child at {@code position} among three. */
    static Tree spine(int depth, int position) {
        Tree tree = new Leaf(1);
        for (int i = 0; i < depth; i++) {
            var children = new ArrayList<Tree>(List.of(new Leaf(i % 7), new Leaf(-(i % 3))));
            children.add(position, tree);
            tree = new Node("s" + i, children);
        }
        return tree;
    }

    static <T> T onSmallStack(Supplier<T> work) throws InterruptedException {
        var result = new AtomicReference<T>();
        var failure = new AtomicReference<Throwable>();
        var thread = new Thread(null, () -> {
            try {
                result.set(work.get());
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "small-stack", 128 * 1024);
        thread.start();
        thread.join();
        if (failure.get() != null) throw new AssertionError(failure.get());
        return result.get();
    }

    @Test
    void agreesWithTheRecursiveWalkOnRandomTrees() {
        var random = new Random(327);
        for (int i = 0; i < 2_000; i++) {
            var tree = random(random, 1 + random.nextInt(200));
            var expectedLog = new ArrayList<String>();
            var actualLog = new ArrayList<String>();
            int expected = recursiveSum(tree, expectedLog);
            assertEquals(expected, sum(tree, actualLog).run(), "sum of tree " + i);
            assertEquals(expectedLog, actualLog, "visit order of tree " + i);
        }
    }

    @Test
    void deepSpinesInEveryChildPositionRunOnASmallStack() throws InterruptedException {
        for (int position = 0; position < 3; position++) {
            var tree = spine(200_000, position);
            int expected = 1;
            for (int i = 0; i < 200_000; i++) expected += i % 7 - i % 3;
            int finalPosition = position;
            int actual = onSmallStack(() -> sum(tree, new ArrayList<>()).run());
            assertEquals(expected, actual, "spine with its deep child at " + finalPosition);
        }
    }

    @Test
    void longChainsOfThenAndMapRunOnASmallStack() throws InterruptedException {
        // then-chains nested to the left, as a loop that keeps extending one computation builds them.
        Trampoline<Integer> left = Trampoline.done(0);
        for (int i = 0; i < 200_000; i++) left = left.map(x -> x + 1);
        Trampoline<Integer> leftChain = left;
        assertEquals(200_000, onSmallStack(leftChain::run));
        // then-chains nested to the right, as recursion builds them.
        assertEquals(200_000, onSmallStack(() -> countDown(200_000).run()));
    }

    static Trampoline<Integer> countDown(int n) {
        return Trampoline.defer(() -> n == 0 ? Trampoline.done(0) : countDown(n - 1).map(x -> x + 1));
    }

    @Test
    void bothRunsTheFirstBeforeTheSecond() {
        var log = new ArrayList<String>();
        var result = Trampoline.both(
                Trampoline.defer(() -> { log.add("first"); return Trampoline.done(2); }),
                Trampoline.defer(() -> { log.add("second"); return Trampoline.done(3); }),
                (a, b) -> a * 10 + b).run();
        assertEquals(23, result);
        assertEquals(List.of("first", "second"), log);
    }

    @Test
    void anyAndAllStopWhereTheirOperatorsWould() {
        var tested = new ArrayList<Integer>();
        assertTrue(Trampoline.<Integer>any(List.of(1, 2, 3, 4), x -> {
            tested.add(x);
            return Trampoline.done(x == 2);
        }).run());
        assertEquals(List.of(1, 2), tested);
        tested.clear();
        assertFalse(Trampoline.<Integer>all(List.of(1, 2, 3, 4), x -> {
            tested.add(x);
            return Trampoline.done(x < 3);
        }).run());
        assertEquals(List.of(1, 2, 3), tested);
        assertFalse(Trampoline.<Integer>any(List.of(), x -> Trampoline.done(true)).run());
        assertTrue(Trampoline.<Integer>all(List.of(), x -> Trampoline.done(false)).run());
    }

    @Test
    void eachCallsAStepOnlyWhenItsTurnComes() {
        var log = new ArrayList<String>();
        var computation = Trampoline.each(List.of("a", "b"), item -> {
            log.add("start " + item);
            return Trampoline.defer(() -> {
                log.add("finish " + item);
                return Trampoline.done(item.toUpperCase());
            });
        });
        assertTrue(log.isEmpty(), "describing the computation runs nothing");
        assertEquals(List.of("A", "B"), computation.run());
        assertEquals(List.of("start a", "finish a", "start b", "finish b"), log);
        assertThrows(UnsupportedOperationException.class, () -> computation.run().add("C"));
    }

    @Test
    void aComputationRunsAgainFromTheStart() {
        var computation = Trampoline.each(List.of(1, 2, 3), x -> Trampoline.done(x * 2));
        assertEquals(List.of(2, 4, 6), computation.run());
        assertEquals(List.of(2, 4, 6), computation.run());
    }

    @Test
    void nullIsAResult() {
        assertNull(Trampoline.<String>done(null).run());
        assertEquals("was null", Trampoline.<String>done(null).map(x -> x == null ? "was null" : x).run());
        var list = Trampoline.each(List.of(1, 2), x -> Trampoline.<String>done(null)).run();
        assertEquals(2, list.size());
        assertNull(list.get(0));
    }

    @Test
    void anExceptionFromAStepEndsTheRun() {
        var thrown = new IllegalStateException("boom");
        var ran = new ArrayList<String>();
        var computation = Trampoline.<Integer>defer(() -> { throw thrown; })
                .map(x -> { ran.add("after"); return x; });
        assertSame(thrown, assertThrows(IllegalStateException.class, computation::run));
        assertTrue(ran.isEmpty());
    }

    @Test
    void aStepThatReturnsNoComputationIsReported() {
        assertThrows(NullPointerException.class, () -> Trampoline.<Integer>defer(() -> null).run());
        assertThrows(NullPointerException.class, () -> Trampoline.done(1).<Integer>then(x -> null).run());
    }
}
