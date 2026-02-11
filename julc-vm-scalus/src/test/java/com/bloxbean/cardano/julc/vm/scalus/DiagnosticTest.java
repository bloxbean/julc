package com.bloxbean.cardano.julc.vm.scalus;

import com.bloxbean.cardano.julc.core.*;
import com.bloxbean.cardano.julc.core.flat.UplcFlatEncoder;
import com.bloxbean.cardano.julc.vm.*;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;

class DiagnosticTest {

    @Test
    void diagnoseDelayForce() {
        var provider = new ScalusVmProvider();
        var term = Term.force(Term.delay(Term.const_(Constant.integer(99))));
        var program = Program.plutusV3(term);

        byte[] flatBytes = UplcFlatEncoder.encodeProgram(program);
        System.out.println("FLAT bytes (hex): " + HexFormat.of().formatHex(flatBytes));

        try {
            var dbProgram = scalus.uplc.ProgramFlatCodec$.MODULE$.decodeFlat(flatBytes);
            System.out.println("Scalus decoded: " + dbProgram);
        } catch (Exception e) {
            System.out.println("FLAT decode FAILED: " + e.getMessage());
        }

        var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);
        if (result instanceof EvalResult.Success s) {
            System.out.println("Success: " + s.resultTerm());
        } else if (result instanceof EvalResult.Failure f) {
            System.out.println("Failure: " + f.error());
        }
    }

    @Test
    void diagnoseDataConstant() {
        var provider = new ScalusVmProvider();
        var program = Program.plutusV3(Term.const_(Constant.data(PlutusData.integer(42))));

        byte[] flatBytes = UplcFlatEncoder.encodeProgram(program);
        System.out.println("FLAT bytes (hex): " + HexFormat.of().formatHex(flatBytes));

        try {
            var dbProgram = scalus.uplc.ProgramFlatCodec$.MODULE$.decodeFlat(flatBytes);
            System.out.println("Scalus decoded: " + dbProgram);
        } catch (Exception e) {
            System.out.println("FLAT decode FAILED: " + e.getMessage());
            e.printStackTrace(System.out);
        }

        var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);
        if (result instanceof EvalResult.Success s) {
            System.out.println("Success: " + s.resultTerm());
        } else if (result instanceof EvalResult.Failure f) {
            System.out.println("Failure: " + f.error());
        }
    }

    @Test
    void diagnoseErrorInBranch() {
        var provider = new ScalusVmProvider();
        var term = Term.apply(
                Term.apply(
                        Term.apply(
                                Term.force(Term.builtin(DefaultFun.IfThenElse)),
                                Term.const_(Constant.bool(true))),
                        Term.const_(Constant.integer(1))),
                Term.error());
        var program = Program.plutusV3(term);

        var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);
        if (result instanceof EvalResult.Success s) {
            System.out.println("Success: " + s.resultTerm());
        } else if (result instanceof EvalResult.Failure f) {
            System.out.println("Failure: " + f.error());
        }
    }

    @Test
    void diagnoseSimpleConstant() {
        var provider = new ScalusVmProvider();
        // Simplest possible program: just a constant
        var program = Program.plutusV3(Term.const_(Constant.integer(42)));

        System.out.println("Program: " + program);
        System.out.println("Term class: " + program.term().getClass().getSimpleName());

        // Check FLAT encoding
        byte[] flatBytes = UplcFlatEncoder.encodeProgram(program);
        System.out.println("FLAT bytes (hex): " + HexFormat.of().formatHex(flatBytes));
        System.out.println("FLAT bytes length: " + flatBytes.length);

        // Try to decode with Scalus
        try {
            var dbProgram = scalus.uplc.ProgramFlatCodec$.MODULE$.decodeFlat(flatBytes);
            System.out.println("Scalus decoded program: " + dbProgram);
            System.out.println("Scalus decoded term: " + dbProgram.term());
        } catch (Exception e) {
            System.out.println("Scalus FLAT decode FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace(System.out);
        }

        var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);
        System.out.println("Result type: " + result.getClass().getSimpleName());
        if (result instanceof EvalResult.Failure f) {
            System.out.println("Failure error: " + f.error());
        } else if (result instanceof EvalResult.Success s) {
            System.out.println("Success term: " + s.resultTerm());
            System.out.println("Budget: " + s.consumed());
        }
    }

    @Test
    void diagnoseAddition() {
        var provider = new ScalusVmProvider();
        var term = Term.apply(
                Term.apply(
                        Term.builtin(DefaultFun.AddInteger),
                        Term.const_(Constant.integer(2))),
                Term.const_(Constant.integer(3)));
        var program = Program.plutusV3(term);

        System.out.println("Program: " + program);

        byte[] flatBytes = UplcFlatEncoder.encodeProgram(program);
        System.out.println("FLAT bytes (hex): " + HexFormat.of().formatHex(flatBytes));

        try {
            var dbProgram = scalus.uplc.ProgramFlatCodec$.MODULE$.decodeFlat(flatBytes);
            System.out.println("Scalus decoded program: " + dbProgram);
        } catch (Exception e) {
            System.out.println("Scalus FLAT decode FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace(System.out);
        }

        var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);
        System.out.println("Result type: " + result.getClass().getSimpleName());
        if (result instanceof EvalResult.Failure f) {
            System.out.println("Failure error: " + f.error());
        }
    }
}
