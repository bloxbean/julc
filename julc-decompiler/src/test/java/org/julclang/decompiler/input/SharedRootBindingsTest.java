package org.julclang.decompiler.input;

import org.julclang.core.DefaultFun;
import org.julclang.core.Program;
import org.julclang.core.Term;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** ADR-076: the decompiler reads a script with shared root bindings as it was before the sharing. */
class SharedRootBindingsTest {

    private static final Term HEAD = Term.force(Term.builtin(DefaultFun.HeadList));
    private static final Term TAIL = Term.force(Term.builtin(DefaultFun.TailList));

    /** {@code \datum \ctx. headList (tailList ctx)} with the forced builtins written out, or read from root binders. */
    private static Term validator(Term head, Term tail) {
        return Term.lam("datum", Term.lam("ctx", Term.apply(head, Term.apply(tail, Term.var(1)))));
    }

    @Test
    void sharedRootBindingsAreSubstitutedBack() {
        Term original = validator(HEAD, TAIL);
        // Root binders: #headList outermost, #tailList inside it; under the two lambdas they are indices 4 and 3.
        Term shared = Term.apply(Term.lam("#headList", Term.apply(Term.lam("#tailList",
                validator(Term.var(4), Term.var(3))), TAIL)), HEAD);
        var program = Program.plutusV3(shared);
        assertEquals(original, SharedRootBindings.inline(program).term());
        assertEquals(2, ScriptAnalyzer.analyze(SharedRootBindings.inline(program)).estimatedArity());
        assertEquals(0, ScriptAnalyzer.analyze(program).estimatedArity(), "the arity the decompiler saw before");
    }

    @Test
    void aScriptWithoutSharedBindingsIsUnchanged() {
        var program = Program.plutusV3(validator(HEAD, TAIL));
        assertSame(program, SharedRootBindings.inline(program));
        // A root let of something other than a shared value is not touched either.
        var other = Program.plutusV3(Term.apply(Term.lam("x", validator(HEAD, TAIL)), Term.builtin(DefaultFun.AddInteger)));
        assertSame(other, SharedRootBindings.inline(other));
    }
}
