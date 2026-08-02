package com.bloxbean.cardano.julc.vm.java;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.vm.*;
import com.bloxbean.cardano.julc.vm.java.cost.CostModelParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolPropagationTest {

    @Test
    void legacyApiDefaultsExplicitlyToPv10() {
        var provider = new JavaVmProvider();
        assertEquals(LedgerEvaluationTarget.pv10(PlutusLanguage.PLUTUS_V1),
                provider.compatibilityTargetFor(PlutusLanguage.PLUTUS_V1));
        assertEquals(LedgerEvaluationTarget.pv10(PlutusLanguage.PLUTUS_V3),
                provider.compatibilityTargetFor(PlutusLanguage.PLUTUS_V3));
    }

    @Test
    void setCostModelParamsRetainsMajorAndMinorVersion() {
        var provider = new JavaVmProvider();
        provider.setCostModelParams(CostModelParser.defaultToFlatArray(11),
                PlutusLanguage.PLUTUS_V3, 11, 2);

        assertEquals(new LedgerEvaluationTarget(
                        PlutusLanguage.PLUTUS_V3, new ProtocolVersion(11, 2)),
                provider.compatibilityTargetFor(PlutusLanguage.PLUTUS_V3));
    }

    @Test
    void explicitTargetRejectsConfiguredModelFromAnotherProtocol() {
        var provider = new JavaVmProvider();
        provider.setCostModelParams(CostModelParser.defaultToFlatArray(11),
                PlutusLanguage.PLUTUS_V3, 11, 0);

        var result = provider.evaluate(unitProgram(),
                LedgerEvaluationTarget.pv10(PlutusLanguage.PLUTUS_V3), null);

        var failure = assertInstanceOf(EvalResult.Failure.class, result);
        assertTrue(failure.error().contains("Configured cost model targets PLUTUS_V3/PV11.0"));
        assertEquals(ExBudget.ZERO, failure.budgetConsumed());
    }

    @Test
    void configuredModelIgnoresProtocolMinorForPlutusSemantics() {
        var provider = new JavaVmProvider();
        provider.setCostModelParams(CostModelParser.defaultToFlatArray(11),
                PlutusLanguage.PLUTUS_V3, 11, 0);

        var result = provider.evaluate(unitProgram(), new LedgerEvaluationTarget(
                PlutusLanguage.PLUTUS_V3, new ProtocolVersion(11, 7)), null);

        assertInstanceOf(EvalResult.Success.class, result);
    }

    @Test
    void explicitTargetValidatesProgramVersionBeforeCekExecution() {
        var provider = new JavaVmProvider();
        var uplc11 = new Program(1, 1, 0, Term.const_(Constant.unit()));

        var pv10 = provider.evaluate(uplc11,
                LedgerEvaluationTarget.pv10(PlutusLanguage.PLUTUS_V1), null);
        var failure = assertInstanceOf(EvalResult.Failure.class, pv10);
        assertTrue(failure.error().contains("UPLC 1.1.0 is not available"));
        assertEquals(ExBudget.ZERO, failure.budgetConsumed());

        var pv11 = provider.evaluate(uplc11,
                LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V1), null);
        assertInstanceOf(EvalResult.Success.class, pv11);
    }

    @Test
    void applyArgsPreservesProgramVersionForValidation() {
        var provider = new JavaVmProvider();
        var identity = new Program(1, 1, 0,
                Term.lam("x", Term.var(1)));

        var result = provider.evaluateWithArgs(identity,
                LedgerEvaluationTarget.pv10(PlutusLanguage.PLUTUS_V2),
                java.util.List.of(new com.bloxbean.cardano.julc.core.PlutusData.IntData(
                        java.math.BigInteger.ONE)), null);

        var failure = assertInstanceOf(EvalResult.Failure.class, result);
        assertTrue(failure.error().contains("UPLC 1.1.0 is not available"));
    }

    private static Program unitProgram() {
        return Program.plutusV3(Term.const_(Constant.unit()));
    }
}
