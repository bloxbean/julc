package com.bloxbean.cardano.julc.compiler.uplc;

import com.bloxbean.cardano.julc.compiler.CompilationContext;
import com.bloxbean.cardano.julc.compiler.CompilerException;
import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.core.DefaultUni;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.Term;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UplcTargetValidatorTest {

    private final CompilationContext context = CompilationContext.pv11Defaults();

    @Test
    void acceptsEveryReleasedPv11BuiltinAndCurrentTermForms() {
        var builtinTerms = Arrays.stream(DefaultFun.values())
                .filter(context.resolvedTarget().featureProfile()::isBuiltinAvailable)
                .map(Term::builtin)
                .toList();
        var program = Program.plutusV3(new Term.Case(
                new Term.Constr(0, builtinTerms),
                List.of(Term.lam("x", Term.var(1)))));

        assertDoesNotThrow(() -> UplcTargetValidator.validate(
                program, context, "test lowering"));
    }

    @Test
    void acceptsAllCurrentPv11ConstantUniverses() {
        var constants = List.<Term>of(
                Term.const_(new Constant.Bls12_381_G1Element(new byte[0])),
                Term.const_(new Constant.Bls12_381_G2Element(new byte[0])),
                Term.const_(new Constant.Bls12_381_MlResult(new byte[0])),
                Term.const_(new Constant.ArrayConst(
                        DefaultUni.INTEGER,
                        List.of(Constant.integer(1)))),
                Term.const_(new Constant.ValueConst(List.of())));
        var program = Program.plutusV3(new Term.Constr(0, constants));

        assertDoesNotThrow(() -> UplcTargetValidator.validate(
                program, context, "constant lowering"));
    }

    @Test
    void rejectsFutureBuiltinSurvivingAnOptimizerPass() {
        var program = Program.plutusV3(Term.builtin(DefaultFun.MultiIndexArray));

        var error = assertThrows(CompilerException.class,
                () -> UplcTargetValidator.validate(
                        program, context, "test-illegal-optimizer-pass"));

        assertEquals("JULC0035", error.diagnostics().getFirst().code());
        assertTrue(error.getMessage().contains("test-illegal-optimizer-pass"));
        assertTrue(error.getMessage().contains("MultiIndexArray"));
        assertTrue(error.getMessage().contains(context.target().profileId()));
    }

    @Test
    void rejectsProgramVersionDifferentFromExactCompilerTarget() {
        var program = Program.plutusV1(Term.const_(Constant.unit()));

        var error = assertThrows(CompilerException.class,
                () -> UplcTargetValidator.validate(program, context, "program construction"));

        assertEquals("JULC0034", error.diagnostics().getFirst().code());
        assertTrue(error.getMessage().contains("1.0.0"));
        assertTrue(error.getMessage().contains("1.1.0"));
    }

    @Test
    void optimizerReportsStablePassIdentitiesForInvariantDiagnostics() {
        var input = Term.force(Term.delay(Term.const_(Constant.integer(1))));

        var result = new UplcOptimizer(context).optimizeWithReport(input);

        assertEquals(Term.const_(Constant.integer(1)), result.term());
        assertEquals(List.of("force-delay-cancel"), result.appliedPasses());
    }
}
