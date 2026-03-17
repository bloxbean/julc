package com.bloxbean.cardano.julc.vm.java;

/**
 * Immutable environment for the CEK machine — a linked list of bindings.
 * <p>
 * Variables are looked up by De Bruijn index (1-based). Index 1 is the most
 * recently bound variable (head of the list).
 */
public final class CekEnvironment {

    /** The empty environment. */
    public static final CekEnvironment EMPTY = new CekEnvironment(null, null);

    private final CekValue head;
    private final CekEnvironment tail;

    private CekEnvironment(CekValue head, CekEnvironment tail) {
        this.head = head;
        this.tail = tail;
    }

    /**
     * Extend this environment with a new binding.
     *
     * @param value the value to bind
     * @return a new environment with the value at index 1
     */
    public CekEnvironment extend(CekValue value) {
        return new CekEnvironment(value, this);
    }

    /**
     * Look up a variable by De Bruijn index (1-based).
     *
     * @param index the De Bruijn index (1 = most recent binding)
     * @return the bound value
     * @throws CekEvaluationException if the index is out of range
     */
    public CekValue lookup(int index) {
        CekEnvironment current = this;
        for (int i = 1; i < index; i++) {
            if (current.tail == null) {
                throw new CekEvaluationException("Variable index out of range: " + index);
            }
            current = current.tail;
        }
        if (current.head == null) {
            throw new CekEvaluationException("Variable index out of range: " + index);
        }
        return current.head;
    }
}
