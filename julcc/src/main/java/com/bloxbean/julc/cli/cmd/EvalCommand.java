package com.bloxbean.julc.cli.cmd;

import com.bloxbean.cardano.julc.clientlib.JulcScriptAdapter;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.flat.UplcFlatDecoder;
import com.bloxbean.cardano.julc.core.text.UplcParser;
import com.bloxbean.cardano.julc.core.text.UplcPrinter;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.JulcVm;
import com.bloxbean.julc.cli.output.AnsiColors;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;

@Command(name = "eval", description = "Evaluate a UPLC program")
public class EvalCommand implements Runnable {

    @Parameters(index = "0", description = "Script file (.plutus, .uplc, or .flat)")
    private Path file;

    @Override
    public void run() {
        try {
            Program program = loadProgram(file);
            JulcVm vm = JulcVm.create();
            EvalResult result = vm.evaluate(program);

            switch (result) {
                case EvalResult.Success s -> {
                    System.out.println(AnsiColors.green("Success"));
                    System.out.println("  Result: " + UplcPrinter.print(s.resultTerm()));
                    System.out.println("  CPU:    " + s.consumed().cpuSteps());
                    System.out.println("  Memory: " + s.consumed().memoryUnits());
                    if (!s.traces().isEmpty()) {
                        System.out.println("  Traces:");
                        s.traces().forEach(t -> System.out.println("    " + t));
                    }
                }
                case EvalResult.Failure f -> {
                    System.out.println(AnsiColors.red("Failure: " + f.error()));
                    System.out.println("  CPU:    " + f.consumed().cpuSteps());
                    System.out.println("  Memory: " + f.consumed().memoryUnits());
                    if (!f.traces().isEmpty()) {
                        System.out.println("  Traces:");
                        f.traces().forEach(t -> System.out.println("    " + t));
                    }
                    System.exit(1);
                }
                case EvalResult.BudgetExhausted b -> {
                    System.out.println(AnsiColors.red("Budget exhausted"));
                    System.out.println("  CPU:    " + b.consumed().cpuSteps());
                    System.out.println("  Memory: " + b.consumed().memoryUnits());
                    System.exit(1);
                }
            }
        } catch (Exception e) {
            System.err.println(AnsiColors.red("Error: " + e.getMessage()));
            System.exit(1);
        }
    }

    public static Program loadProgram(Path file) throws Exception {
        String name = file.getFileName().toString();
        if (name.endsWith(".plutus")) {
            // CIP-57 JSON — extract compiledCode field
            String json = Files.readString(file);
            String hex = extractCompiledCode(json);
            return JulcScriptAdapter.toProgram(hex);
        } else if (name.endsWith(".uplc")) {
            String text = Files.readString(file);
            return UplcParser.parseProgram(text);
        } else if (name.endsWith(".flat")) {
            byte[] bytes = Files.readAllBytes(file);
            return UplcFlatDecoder.decodeProgram(bytes);
        } else {
            // Try UPLC text first, fall back to FLAT
            String text = Files.readString(file);
            if (text.stripLeading().startsWith("(program")) {
                return UplcParser.parseProgram(text);
            }
            return UplcFlatDecoder.decodeProgram(Files.readAllBytes(file));
        }
    }

    /**
     * Extract the first "compiledCode" value from a CIP-57 JSON blueprint.
     * Minimal JSON parsing to avoid adding a JSON library.
     */
    public static String extractCompiledCode(String json) {
        String key = "\"compiledCode\"";
        int idx = json.indexOf(key);
        if (idx < 0) throw new RuntimeException("No 'compiledCode' field in blueprint JSON");
        idx = json.indexOf('"', idx + key.length());
        if (idx < 0) throw new RuntimeException("Invalid JSON: missing value for compiledCode");
        idx++; // skip opening quote
        int end = json.indexOf('"', idx);
        if (end < 0) throw new RuntimeException("Invalid JSON: unterminated compiledCode string");
        return json.substring(idx, end);
    }
}
