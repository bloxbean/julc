package com.bloxbean.cardano.plutus.vm.scalus;

import com.bloxbean.cardano.plutus.core.Constant;
import com.bloxbean.cardano.plutus.core.PlutusData;
import com.bloxbean.cardano.plutus.core.Program;
import com.bloxbean.cardano.plutus.core.Term;
import com.bloxbean.cardano.plutus.core.flat.UplcFlatEncoder;
import com.bloxbean.cardano.plutus.vm.*;
import scalus.uplc.ProgramFlatCodec$;
import scalus.uplc.eval.CountingBudgetSpender;
import scalus.uplc.eval.Log;
import scalus.uplc.eval.PlutusVM;

import java.util.List;

/**
 * {@link PlutusVmProvider} implementation backed by the Scalus CEK machine.
 * <p>
 * Uses FLAT serialization as the bridge between plutus-java and Scalus types.
 * Our {@link Program} is FLAT-encoded, then decoded by Scalus into its own
 * {@link DeBruijnedProgram} for evaluation. This avoids all Scala/Java interop
 * issues with collections, reserved keywords, and type conversions.
 * <p>
 * Discovered via {@link java.util.ServiceLoader} when {@code plutus-vm-scalus}
 * is on the classpath.
 */
public class ScalusVmProvider implements PlutusVmProvider {

    @Override
    public EvalResult evaluate(Program program, PlutusLanguage language, ExBudget budget) {
        return evaluateInternal(program, language);
    }

    @Override
    public EvalResult evaluateWithArgs(Program program, PlutusLanguage language,
                                       List<PlutusData> args, ExBudget budget) {
        // Apply each argument as a Data constant to the program term
        Term applied = program.term();
        for (var arg : args) {
            applied = Term.apply(applied, Term.const_(Constant.data(arg)));
        }
        var appliedProgram = new Program(program.major(), program.minor(), program.patch(), applied);
        return evaluateInternal(appliedProgram, language);
    }

    private EvalResult evaluateInternal(Program program, PlutusLanguage language) {
        try {
            // FLAT-encode our Program to bytes
            byte[] flatBytes = UplcFlatEncoder.encodeProgram(program);

            // Decode via Scalus FLAT codec -> DeBruijnedProgram
            var dbProgram = ProgramFlatCodec$.MODULE$.decodeFlat(flatBytes);

            // Create the appropriate VM
            PlutusVM vm = createVm(language);

            // Set up budget tracking and logging
            var budgetSpender = new CountingBudgetSpender();
            var logger = new Log();

            // Evaluate using evaluateDeBruijnedTerm (general evaluation,
            // does not enforce script return-value semantics like evaluateScriptDebug)
            scalus.uplc.Term scalusResult = vm.evaluateDeBruijnedTerm(
                    dbProgram.term(), budgetSpender, logger);

            // Convert result
            Term resultTerm = TermConverter.fromScalus(scalusResult);
            var budget = budgetSpender.getSpentBudget();
            var consumed = new ExBudget(budget.steps(), budget.memory());
            var traces = List.of(logger.getLogs());

            return new EvalResult.Success(resultTerm, consumed, traces);
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new EvalResult.Failure(errorMsg, ExBudget.ZERO, List.of());
        }
    }

    private PlutusVM createVm(PlutusLanguage language) {
        return switch (language) {
            case PLUTUS_V1 -> PlutusVM.makePlutusV1VM();
            case PLUTUS_V2 -> PlutusVM.makePlutusV2VM();
            case PLUTUS_V3 -> PlutusVM.makePlutusV3VM();
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
