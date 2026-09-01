package com.bloxbean.cardano.julc.vm.scalus;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.core.DefaultUni;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.core.flat.UplcFlatEncoder;
import com.bloxbean.cardano.julc.vm.ExBudget;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import scalus.cardano.ledger.CardanoInfo;
import scalus.cardano.ledger.CostModels;
import scalus.cardano.ledger.Language;
import scalus.cardano.ledger.MajorProtocolVersion;
import scalus.uplc.ProgramFlatCodec$;
import scalus.uplc.eval.CountingBudgetSpender;
import scalus.uplc.eval.Log;
import scalus.uplc.eval.MachineParams;
import scalus.uplc.eval.PlutusVM;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Milestone-7 evidence for the V1/V2 profiles that ADR-033 leaves uncertified.
 * These probes call Scalus directly to characterize which entries its 1.1.0
 * parameter adapters consume or substitute; provider-path live-cost coverage
 * is asserted separately in {@link ScalusConfigurationTest}.
 */
class ScalusV1V2EvidenceTest {

    private static final int ADD_INTEGER_CPU_INTERCEPT = 0;
    // These are V3-schema positions deliberately placed beyond the 166/185
    // fields consumed by PlutusV1Params/PlutusV2Params.fromSeq. They prove that
    // appended values in a 332-entry supplied array are ignored; they are not
    // asserted to be V1/V2 ledger-layout indices.
    private static final int CEK_CONSTR_CPU = 193;
    private static final int CEK_CONSTR_MEMORY = 194;
    private static final int CEK_CASE_CPU = 195;
    private static final int CEK_CASE_MEMORY = 196;
    private static final int DROP_LIST_CPU_INTERCEPT = 302;
    private static final long PERTURBATION = 12_345L;

    @ParameterizedTest(name = "{0}/PV{1} takes supplied AddInteger cost from {2} parameters")
    @MethodSource("v1V2Profiles")
    void suppliedLegacyBuiltinCostMovesBudget(
            Language language, int protocolMajor, int parameterCount) {
        var baselineValues = bundledPrefix(language, parameterCount);
        var changedValues = baselineValues.clone();
        changedValues[ADD_INTEGER_CPU_INTERCEPT] += PERTURBATION;

        var baseline = evaluate(language, protocolMajor, baselineValues, addIntegerProgram());
        var changed = evaluate(language, protocolMajor, changedValues, addIntegerProgram());

        assertEquals(PERTURBATION, changed.cpuSteps() - baseline.cpuSteps());
        assertEquals(baseline.memoryUnits(), changed.memoryUnits());
    }

    @ParameterizedTest(name = "{0}/PV11 reference-fills DropList despite supplied index 302")
    @MethodSource("v1V2Languages")
    void suppliedPv11NewBuiltinCostIsReferenceFilled(Language language) {
        var baselineValues = bundledPrefix(language, 332);
        var changedValues = baselineValues.clone();
        changedValues[DROP_LIST_CPU_INTERCEPT] += PERTURBATION;

        var baseline = evaluate(language, 11, baselineValues, dropListProgram());
        var changed = evaluate(language, 11, changedValues, dropListProgram());

        assertEquals(baseline, changed,
                "Scalus V1/V2 adapters ignore the supplied PV11-only parameter and "
                        + "MachineParams.fromCostModels uses vanRossemReferenceD");
    }

    @ParameterizedTest(name = "{0}/PV11 ignores supplied V3 schema position {1}")
    @MethodSource("ignoredMachineCostPositions")
    void suppliedConstrAndCasePositionsAreBudgetNeutral(
            Language language, int parameterIndex, Program program) {
        // This directly exercises parameter plumbing inside Scalus. Constr/Case
        // in a Program.plutusV3 term evaluated by a V1/V2 VM is not claimed to
        // be a ledger-legal V1/V2 program.
        var baselineValues = bundledPrefix(language, 332);
        var changedValues = baselineValues.clone();
        changedValues[parameterIndex] += PERTURBATION;

        var baseline = evaluate(language, 11, baselineValues, program);
        var changed = evaluate(language, 11, changedValues, program);

        assertEquals(baseline, changed,
                "Scalus V1/V2 parameter classes do not carry the supplied Constr/Case cost");
    }

    @ParameterizedTest(name = "{0}/PV10 matches the pinned Java budget")
    @MethodSource("v1V2Languages")
    void pv10LegacyBuiltinBudgetMatchesJavaReference(Language language) {
        int parameterCount = language == Language.PlutusV1 ? 166 : 185;
        var actual = evaluate(language, 10, bundledPrefix(language, parameterCount),
                addIntegerProgram());

        // Copied from julc-vm-java's pinned V3/C addInteger-01 conformance
        // result. AddInteger and CEK machine costs are identical under B/C,
        // and the bundled V1/V2 vectors carry those same shared parameters:
        // cpu 181,308; memory 602.
        assertEquals(new ExBudget(181_308, 602), actual);
    }

    private static Stream<Arguments> v1V2Profiles() {
        return Stream.of(
                Arguments.of(Language.PlutusV1, 10, 166),
                Arguments.of(Language.PlutusV1, 11, 332),
                Arguments.of(Language.PlutusV2, 10, 185),
                Arguments.of(Language.PlutusV2, 11, 332));
    }

    private static Stream<Language> v1V2Languages() {
        return Stream.of(Language.PlutusV1, Language.PlutusV2);
    }

    private static Stream<Arguments> ignoredMachineCostPositions() {
        var constr = Program.plutusV3(new Term.Constr(0, List.of()));
        var caseTerm = Program.plutusV3(new Term.Case(
                new Term.Constr(0, List.of()),
                List.of(Term.const_(Constant.integer(42)))));
        return v1V2Languages().flatMap(language -> Stream.of(
                Arguments.of(language, CEK_CONSTR_CPU, constr),
                Arguments.of(language, CEK_CONSTR_MEMORY, constr),
                Arguments.of(language, CEK_CASE_CPU, caseTerm),
                Arguments.of(language, CEK_CASE_MEMORY, caseTerm)));
    }

    private static Program addIntegerProgram() {
        var term = Term.apply(
                Term.apply(Term.builtin(DefaultFun.AddInteger),
                        Term.const_(Constant.integer(1))),
                Term.const_(Constant.integer(1)));
        return Program.plutusV3(term);
    }

    private static Program dropListProgram() {
        var list = new Constant.ListConst(DefaultUni.INTEGER,
                List.of(Constant.integer(11), Constant.integer(22)));
        var term = Term.apply(
                Term.apply(Term.force(Term.builtin(DefaultFun.DropList)),
                        Term.const_(Constant.integer(1))),
                Term.const_(list));
        return Program.plutusV3(term);
    }

    private static ExBudget evaluate(
            Language language, int protocolMajor, long[] values, Program program) {
        var protocol = new MajorProtocolVersion(protocolMajor);
        var params = MachineParams.fromCostModels(
                costModels(language, values), language, protocol);
        var vm = createVm(language, params, protocol);
        var decoded = ProgramFlatCodec$.MODULE$.decodeFlat(
                UplcFlatEncoder.encodeProgram(program));
        var spender = new CountingBudgetSpender();

        vm.evaluateDeBruijnedTerm(decoded.term(), spender, new Log(), false);

        var spent = spender.getSpentBudget();
        return new ExBudget(spent.steps(), spent.memory());
    }

    private static long[] bundledPrefix(Language language, int count) {
        var models = CardanoInfo.mainnet().protocolParams().costModels().models();
        @SuppressWarnings("unchecked")
        var values = (scala.collection.immutable.IndexedSeq<Object>) models.apply(
                Integer.valueOf(language.languageId()));
        var result = new long[count];
        for (int i = 0; i < Math.min(count, values.length()); i++) {
            result[i] = ((Number) values.apply(i)).longValue();
        }
        if (values.length() < count) {
            Arrays.fill(result, values.length(), count, 1L);
        }
        return result;
    }

    private static CostModels costModels(Language language, long[] values) {
        var builder = scala.collection.immutable.Vector$.MODULE$.<Object>newBuilder();
        for (long value : values) {
            builder.addOne(Long.valueOf(value));
        }
        scala.collection.immutable.IndexedSeq<Object> sequence = builder.result();
        scala.collection.immutable.Map<Object, scala.collection.immutable.IndexedSeq<Object>> map =
                scala.collection.immutable.Map$.MODULE$
                        .<Object, scala.collection.immutable.IndexedSeq<Object>>empty()
                        .updated(language.languageId(), sequence);
        return new CostModels(map);
    }

    private static PlutusVM createVm(
            Language language, MachineParams params, MajorProtocolVersion protocol) {
        if (language == Language.PlutusV1) {
            return PlutusVM.makePlutusV1VM(params, protocol);
        }
        if (language == Language.PlutusV2) {
            return PlutusVM.makePlutusV2VM(params, protocol);
        }
        throw new IllegalArgumentException("Unsupported test language: " + language);
    }
}
