package com.bloxbean.cardano.julc.vm.java;

import java.util.List;

/**
 * Continuation frames for the CEK machine.
 * <p>
 * These represent "what to do next" after a sub-evaluation completes.
 */
public sealed interface CekFrame {

    /** Waiting to force a value (evaluating the term inside Force). */
    record ForceFrame() implements CekFrame {}

    /** We have a function value; waiting for the argument to be evaluated. */
    record ApplyArgFrame(CekValue function) implements CekFrame {}

    /**
     * We need to evaluate an argument term (in a saved env) after a function has been evaluated.
     * The function result will be captured when this frame is popped.
     */
    record ComputeArgFrame(com.bloxbean.cardano.julc.core.Term term,
                           CekEnvironment env) implements CekFrame {}

    /**
     * An already-evaluated value to apply as an argument (e.g., Case constructor fields).
     */
    record ValueArgFrame(CekValue value) implements CekFrame {}

    /**
     * Building a Constr value — evaluating remaining fields.
     *
     * @param tag             the constructor tag
     * @param evaluatedFields fields already evaluated (in order)
     * @param remainingTerms  field terms still to evaluate
     * @param env             the environment for remaining terms
     */
    record ConstrFrame(long tag, List<CekValue> evaluatedFields,
                       List<com.bloxbean.cardano.julc.core.Term> remainingTerms,
                       CekEnvironment env) implements CekFrame {}

    /**
     * Case expression — waiting for scrutinee to evaluate to a Constr.
     *
     * @param branches the case branches
     * @param env      the environment for the branches
     */
    record CaseFrame(List<com.bloxbean.cardano.julc.core.Term> branches,
                     CekEnvironment env) implements CekFrame {}
}
