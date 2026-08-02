package com.bloxbean.cardano.julc.vm.truffle;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.vm.*;
import com.bloxbean.cardano.julc.vm.java.cost.CostModelParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolPropagationTest {

    @Test
    void legacyApiDefaultsToPv10AndRetainsConfiguredTarget() {
        var provider = new TruffleVmProvider();
        assertEquals(LedgerEvaluationTarget.pv10(PlutusLanguage.PLUTUS_V3),
                provider.compatibilityTargetFor(PlutusLanguage.PLUTUS_V3));

        provider.setCostModelParams(CostModelParser.defaultToFlatArray(11),
                PlutusLanguage.PLUTUS_V3, 11, 3);
        assertEquals(new LedgerEvaluationTarget(
                        PlutusLanguage.PLUTUS_V3, new ProtocolVersion(11, 3)),
                provider.compatibilityTargetFor(PlutusLanguage.PLUTUS_V3));
    }

    @Test
    void explicitTargetRejectsConfiguredModelFromAnotherProtocol() {
        var provider = new TruffleVmProvider();
        provider.setCostModelParams(CostModelParser.defaultToFlatArray(11),
                PlutusLanguage.PLUTUS_V3, 11, 0);

        var result = provider.evaluate(Program.plutusV3(Term.const_(Constant.unit())),
                LedgerEvaluationTarget.pv10(PlutusLanguage.PLUTUS_V3), null);

        var failure = assertInstanceOf(EvalResult.Failure.class, result);
        assertTrue(failure.error().contains("Configured cost model targets PLUTUS_V3/PV11.0"));
        assertEquals(ExBudget.ZERO, failure.budgetConsumed());
    }

    @Test
    void configuredModelIgnoresProtocolMinorForPlutusSemantics() {
        var provider = new TruffleVmProvider();
        provider.setCostModelParams(CostModelParser.defaultToFlatArray(11),
                PlutusLanguage.PLUTUS_V3, 11, 0);

        var result = provider.evaluate(
                Program.plutusV3(Term.const_(Constant.unit())),
                new LedgerEvaluationTarget(
                        PlutusLanguage.PLUTUS_V3, new ProtocolVersion(11, 7)),
                null);

        assertInstanceOf(EvalResult.Success.class, result);
    }

    @Test
    void explicitTargetValidatesProgramVersionBeforeExecution() {
        var provider = new TruffleVmProvider();
        var program = new Program(1, 1, 0, Term.const_(Constant.unit()));

        var result = provider.evaluate(program,
                LedgerEvaluationTarget.pv10(PlutusLanguage.PLUTUS_V2), null);

        var failure = assertInstanceOf(EvalResult.Failure.class, result);
        assertTrue(failure.error().contains("UPLC 1.1.0 is not available"));
        assertEquals(ExBudget.ZERO, failure.budgetConsumed());
    }
}
