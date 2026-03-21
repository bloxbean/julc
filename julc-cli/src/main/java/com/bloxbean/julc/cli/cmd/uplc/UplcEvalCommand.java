package com.bloxbean.julc.cli.cmd.uplc;

import com.bloxbean.cardano.julc.core.text.UplcPrinter;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.JulcVm;
import com.bloxbean.julc.cli.cmd.EvalCommand;
import com.bloxbean.julc.cli.output.AnsiColors;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;

@Command(name = "eval", description = "Evaluate a UPLC file")
public class UplcEvalCommand implements Runnable {

    @Parameters(index = "0", description = "UPLC file (.uplc or .flat)")
    private Path file;

    @Override
    public void run() {
        try {
            var program = EvalCommand.loadProgram(file);
            JulcVm vm = JulcVm.create();
            EvalResult result = vm.evaluate(program);

            switch (result) {
                case EvalResult.Success s -> {
                    System.out.println(UplcPrinter.print(s.resultTerm()));
                    System.err.println("CPU: " + s.consumed().cpuSteps() + ", Memory: " + s.consumed().memoryUnits());
                }
                case EvalResult.Failure f -> {
                    System.err.println(AnsiColors.red("Error: " + f.error()));
                    System.exit(1);
                }
                case EvalResult.BudgetExhausted b -> {
                    System.err.println(AnsiColors.red("Budget exhausted"));
                    System.exit(1);
                }
            }
        } catch (Exception e) {
            System.err.println(AnsiColors.red("Error: " + e.getMessage()));
            System.exit(1);
        }
    }
}
