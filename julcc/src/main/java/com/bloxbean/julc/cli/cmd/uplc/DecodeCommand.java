package com.bloxbean.julc.cli.cmd.uplc;

import com.bloxbean.cardano.julc.core.flat.UplcFlatDecoder;
import com.bloxbean.cardano.julc.core.text.UplcPrinter;
import com.bloxbean.julc.cli.output.AnsiColors;
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
