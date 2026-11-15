package org.julclang.compiler;

import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.julclang.core.Program;
import org.julclang.core.Term;
import org.julclang.core.flat.UplcFlatDecoder;
import org.julclang.core.flat.UplcFlatEncoder;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The order-tolerant byte oracle accepts a reordered run of {@code let}s and nothing else (ADR-075). */
class LibraryOrderEquivalenceTest {

    private static Term integer(long value) {
        return Term.const_(Constant.integer(BigInteger.valueOf(value)));
    }

    private static Term add(Term a, Term b) {
        return Term.apply(Term.apply(Term.builtin(DefaultFun.AddInteger), a), b);
    }

    /** {@code \x. let f = \y. y + 1 in let g = \y. y + 2 in f (g x)}, or with the two lets the other way round. */
    private static Program program(boolean fFirst, long one, long two) {
        Term f = Term.lam("y", add(Term.var(1), integer(one)));
        Term g = Term.lam("y", add(Term.var(1), integer(two)));
        // Inside both lets: x is 3, the inner binder 1 and the outer 2.
        Term body = fFirst
                ? Term.apply(Term.var(2), Term.apply(Term.var(1), Term.var(3)))
                : Term.apply(Term.var(1), Term.apply(Term.var(2), Term.var(3)));
        Term lets = fFirst
                ? Term.apply(Term.lam("f", Term.apply(Term.lam("g", body), g)), f)
                : Term.apply(Term.lam("g", Term.apply(Term.lam("f", body), f)), g);
        // Round-trip through FLAT, as a captured golden is, so no binder keeps its name.
        return UplcFlatDecoder.decodeProgram(UplcFlatEncoder.encodeProgram(Program.plutusV3(Term.lam("x", lets))));
    }

    @Test
    void aReorderedRunOfLetsIsTheSame() {
        assertTrue(LibraryOrderEquivalence.sameUpToLibraryOrder(program(true, 1, 2), program(false, 1, 2)));
    }

    @Test
    void aChangedConstantIsNot() {
        assertFalse(LibraryOrderEquivalence.sameUpToLibraryOrder(program(true, 1, 2), program(false, 1, 3)));
        assertFalse(LibraryOrderEquivalence.sameUpToLibraryOrder(program(true, 1, 2), program(true, 1, 3)));
    }

    @Test
    void readingTheOtherBindingIsNot() {
        // f (g x) against g (f x): the same lets, the uses swapped.
        Term f = Term.lam("y", add(Term.var(1), integer(1)));
        Term g = Term.lam("y", add(Term.var(1), integer(2)));
        Term swapped = Term.apply(Term.var(1), Term.apply(Term.var(2), Term.var(3)));
        var other = Program.plutusV3(Term.lam("x",
                Term.apply(Term.lam("f", Term.apply(Term.lam("g", swapped), g)), f)));
        assertFalse(LibraryOrderEquivalence.sameUpToLibraryOrder(program(true, 1, 2), other));
    }

    @Test
    void aBindingThatDependsOnAnotherKeepsThatDependency() {
        // let a = 1 in let b = a + 1 in b, against let a = 1 in let b = 1 + 1 in b: b no longer reads a.
        Term reads = Term.apply(Term.lam("a", Term.apply(Term.lam("b", Term.var(1)), add(Term.var(1), integer(1)))), integer(1));
        Term inlined = Term.apply(Term.lam("a", Term.apply(Term.lam("b", Term.var(1)), add(integer(1), integer(1)))), integer(1));
        assertFalse(LibraryOrderEquivalence.sameUpToLibraryOrder(Program.plutusV3(reads), Program.plutusV3(inlined)));
    }

    @Test
    void bindingsInDifferentScopesAreNotTheSame() {
        // \x. let a = x in \y. let b = y in a, against the same returning b: 10 and 20 on (10, 20).
        Term returnsA = Term.lam("x", Term.apply(Term.lam("a", Term.lam("y",
                Term.apply(Term.lam("b", Term.var(3)), Term.var(1)))), Term.var(1)));
        Term returnsB = Term.lam("x", Term.apply(Term.lam("a", Term.lam("y",
                Term.apply(Term.lam("b", Term.var(1)), Term.var(1)))), Term.var(1)));
        assertFalse(LibraryOrderEquivalence.sameUpToLibraryOrder(Program.plutusV3(returnsA), Program.plutusV3(returnsB)));
    }

    @Test
    void bindingsThatTraceKeepTheirOrder() {
        // let a = trace "A" 0 in let b = trace "B" 0 in a + b, against the two lets the other way round.
        Term traceA = Term.apply(Term.apply(Term.force(Term.builtin(DefaultFun.Trace)),
                Term.const_(Constant.string("A"))), integer(0));
        Term traceB = Term.apply(Term.apply(Term.force(Term.builtin(DefaultFun.Trace)),
                Term.const_(Constant.string("B"))), integer(0));
        Term ab = Term.apply(Term.lam("a", Term.apply(Term.lam("b", add(Term.var(2), Term.var(1))), traceB)), traceA);
        Term ba = Term.apply(Term.lam("b", Term.apply(Term.lam("a", add(Term.var(1), Term.var(2))), traceA)), traceB);
        assertFalse(LibraryOrderEquivalence.sameUpToLibraryOrder(Program.plutusV3(ab), Program.plutusV3(ba)));
    }
}
