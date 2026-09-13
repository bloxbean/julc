package org.julclang.cli.cmd.uplc;

import org.julclang.core.flat.UplcFlatDecoder;
import org.julclang.core.text.UplcPrinter;
import org.julclang.cli.output.AnsiColors;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;

@Command(name = "decode", description = "Decode FLAT binary to UPLC text")
public class DecodeCommand implements Runnable {

    @Parameters(index = "0", description = "FLAT binary file")
    private Path file;

    @Override
    public void run() {
        try {
            byte[] bytes = Files.readAllBytes(file);
            var program = UplcFlatDecoder.decodeProgram(bytes);
            System.out.println(UplcPrinter.print(program));
        } catch (Exception e) {
            System.err.println(AnsiColors.red("Error: " + e.getMessage()));
            System.exit(1);
        }
    }
}
