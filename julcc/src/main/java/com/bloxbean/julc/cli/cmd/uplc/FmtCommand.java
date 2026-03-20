package com.bloxbean.julc.cli.cmd.uplc;

import com.bloxbean.cardano.julc.core.text.UplcParser;
import com.bloxbean.cardano.julc.core.text.UplcPrinter;
import com.bloxbean.julc.cli.output.AnsiColors;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;

@Command(name = "fmt", description = "Format a UPLC file")
public class FmtCommand implements Runnable {

    @Parameters(index = "0", description = "UPLC text file")
    private Path file;

    @Override
    public void run() {
        try {
            String text = Files.readString(file);
            var program = UplcParser.parseProgram(text);
            System.out.println(UplcPrinter.print(program));
        } catch (Exception e) {
            System.err.println(AnsiColors.red("Error: " + e.getMessage()));
            System.exit(1);
        }
    }
}
