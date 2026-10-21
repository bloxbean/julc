package org.julclang.compiler.backend;

import org.julclang.core.BuiltinSemantics;
import org.julclang.core.DefaultFun;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

/** Anchors the verifier's builtin signatures to the VM-checked {@link BuiltinSemantics}. */
class BuiltinTypingTest {

    @ParameterizedTest
    @EnumSource(DefaultFun.class)
    void everyBuiltinHasASignatureMatchingItsArity(DefaultFun fun) {
        var semantics = BuiltinSemantics.find(fun);
        assertNotNull(semantics, "BuiltinSemantics lacks " + fun);
        var monomorphic = BuiltinTyping.monomorphic(fun);
        var polymorphic = BuiltinTyping.polymorphicArity(fun);
        assertTrue(monomorphic == null ^ polymorphic == null,
                fun + " must have exactly one verifier signature form");
        if (monomorphic != null) {
            assertEquals(0, semantics.typeArity(), fun + " is monomorphic in the VM table");
            assertEquals(semantics.valueArity(), monomorphic.parameters().size(), fun + " arity");
        } else {
            assertTrue(semantics.typeArity() > 0, fun + " is polymorphic in the VM table");
            assertEquals(semantics.valueArity(), polymorphic, fun + " arity");
        }
    }
}
