package com.bloxbean.cardano.julc.vm.scalus;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.core.flat.UplcFlatEncoder;
import com.bloxbean.cardano.julc.vm.*;
import scalus.cardano.ledger.CostModels;
import scalus.cardano.ledger.Language;
import scalus.cardano.ledger.MajorProtocolVersion;
import scalus.uplc.ProgramFlatCodec$;
import scalus.uplc.eval.CountingBudgetSpender;
import scalus.uplc.eval.Log;
import scalus.uplc.eval.MachineParams;
import scalus.uplc.eval.PlutusVM;

import java.util.List;

/**
 * {@link JulcVmProvider} implementation backed by the Scalus CEK machine.
 * <p>
 * Uses FLAT serialization as the bridge between plutus-java and Scalus types.
 * Our {@link Program} is FLAT-encoded, then decoded by Scalus into its own
 * {@link DeBruijnedProgram} for evaluation. This avoids all Scala/Java interop
 * issues with collections, reserved keywords, and type conversions.
 * <p>
 * Discovered via {@link java.util.ServiceLoader} when {@code plutus-vm-scalus}
 * is on the classpath.
 */
public class ScalusVmProvider implements JulcVmProvider {

    // Package-private for testing
    volatile MachineParams machineParams;
    volatile MajorProtocolVersion protocolVersion;

    @Override
    public void setCostModelParams(long[] costModelValues, PlutusLanguage language,
                                   int protocolMajorVersion, int protocolMinorVersion) {
        // Map protocol major version to Scalus MajorProtocolVersion
        MajorProtocolVersion pv = protocolMajorVersion >= 10
                ? MajorProtocolVersion.plominPV()
                : MajorProtocolVersion.changPV();
        this.protocolVersion = pv;

        // Only V3 supports custom MachineParams in Scalus; V1/V2 use built-in defaults
        if (language != PlutusLanguage.PLUTUS_V3) {
            return;
        }

        // Convert long[] to Scala IndexedSeq<Long> (boxed)
        var builder = scala.collection.immutable.Vector$.MODULE$.<Object>newBuilder();
        for (long v : costModelValues) {
            builder.addOne(Long.valueOf(v));
        }
        scala.collection.immutable.IndexedSeq<Object> indexedSeq = builder.result();

        // Build CostModels map: Language.PlutusV3 -> indexedSeq
        scala.collection.immutable.Map<Object, scala.collection.immutable.IndexedSeq<Object>> map =
                scala.collection.immutable.Map$.MODULE$.<Object, scala.collection.immutable.IndexedSeq<Object>>empty()
                        .updated(Language.PlutusV3, indexedSeq);

        CostModels costModels = new CostModels(map);
        this.machineParams = MachineParams.fromCostModels(costModels, Language.PlutusV3, pv);
    }

    @Override
    public EvalResult evaluate(Program program, PlutusLanguage language, ExBudget budget) {
        return evaluateInternal(program, language);
    }

    @Override
    public EvalResult evaluateWithArgs(Program program, PlutusLanguage language,
                                       List<PlutusData> args, ExBudget budget) {
        try {
            // FLAT-encode the base program (without args) to bridge to Scalus types
            byte[] flatBytes = UplcFlatEncoder.encodeProgram(program);
            var dbProgram = ProgramFlatCodec$.MODULE$.decodeFlat(flatBytes);

            // Apply each argument directly as a Scalus Data constant.
            // This bypasses CBOR encoding, avoiding Scalus's 64-byte bytestring limit.
            scalus.uplc.Term scalusTerm = dbProgram.term();
            for (var arg : args) {
                scalus.uplc.builtin.Data scalusData = DataConverter.toScalus(arg);
                scalus.uplc.Constant dataConst = new scalus.uplc.Constant.Data(scalusData);
                var emptyAnn = scalus.uplc.UplcAnnotation.empty();
                scalusTerm = scalus.uplc.Term.Apply.apply(
                        scalusTerm,
                        scalus.uplc.Term.Const.apply(dataConst, emptyAnn),
                        emptyAnn);
            }

            return evaluateScalusTerm(scalusTerm, language);
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new EvalResult.Failure(errorMsg, ExBudget.ZERO, List.of());
        }
    }

    private EvalResult evaluateInternal(Program program, PlutusLanguage language) {
        try {
            // FLAT-encode our Program to bytes
            byte[] flatBytes = UplcFlatEncoder.encodeProgram(program);

            // Decode via Scalus FLAT codec -> DeBruijnedProgram
            var dbProgram = ProgramFlatCodec$.MODULE$.decodeFlat(flatBytes);

            return evaluateScalusTerm(dbProgram.term(), language);
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new EvalResult.Failure(errorMsg, ExBudget.ZERO, List.of());
        }
    }

    private EvalResult evaluateScalusTerm(scalus.uplc.Term scalusTerm, PlutusLanguage language) {
        // Create budget/logger outside try so we can capture partial budget on error
        var budgetSpender = new CountingBudgetSpender();
        var logger = new Log();
        try {
            // Create the appropriate VM
            PlutusVM vm = createVm(language);

            // Evaluate using evaluateDeBruijnedTerm (general evaluation,
            // does not enforce script return-value semantics like evaluateScriptDebug)
            scalus.uplc.Term scalusResult = vm.evaluateDeBruijnedTerm(
                    scalusTerm, budgetSpender, logger);

            // Convert result
            Term resultTerm = TermConverter.fromScalus(scalusResult);
            var budget = budgetSpender.getSpentBudget();
            var consumed = new ExBudget(budget.steps(), budget.memory());
            var traces = List.of(logger.getLogs());

            return new EvalResult.Success(resultTerm, consumed, traces);
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            var budget = budgetSpender.getSpentBudget();
            var consumed = new ExBudget(budget.steps(), budget.memory());
            var traces = List.of(logger.getLogs());
            return new EvalResult.Failure(errorMsg, consumed, traces);
        }
    }

    private PlutusVM createVm(PlutusLanguage language) {
        return switch (language) {
            case PLUTUS_V1 -> PlutusVM.makePlutusV1VM();
            case PLUTUS_V2 -> PlutusVM.makePlutusV2VM();
            case PLUTUS_V3 -> {
                if (machineParams != null && protocolVersion != null) {
                    yield PlutusVM.makePlutusV3VM(machineParams, protocolVersion);
                } else {
                    yield PlutusVM.makePlutusV3VM();
                }
            }
        };
    }

    @Override
    public String name() {
        return "Scalus";
    }

    @Override
    public int priority() {
        return 50;
    }
}
