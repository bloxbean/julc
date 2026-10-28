package org.julclang.difftest.gen;

import java.util.List;

/**
 * A sequence of choices that a generator decodes into a program (ADR-063 D5, D8). Every
 * decision reads the next number; an exhausted sequence answers 0, the simplest choice. jqwik
 * generates and shrinks the sequence itself, so a shorter sequence with smaller numbers decodes
 * to a smaller program, and every decoded program is valid by construction.
 */
public final class Choices {

    private final List<Integer> values;
    private int next;

    public Choices(List<Integer> values) {
        this.values = List.copyOf(values);
    }

    /** A number in {@code [0, bound)}. */
    public int below(int bound) {
        if (bound <= 0) throw new IllegalArgumentException("bound must be positive");
        if (next >= values.size()) return 0;
        return Math.floorMod(values.get(next++), bound);
    }

    /** True with roughly one chance in {@code outOf}, and false once the sequence is exhausted. */
    public boolean oneIn(int outOf) {
        return below(outOf) == outOf - 1;
    }
}
