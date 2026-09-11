package org.julclang.cli.cmd.uplc;

import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.core.text.UplcParser;
import org.julclang.cli.output.AnsiColors;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;

@Command(name = "encode", description = "Encode UPLC text to FLAT binary")
public class EncodeCommand implements Runnable {

    @Parameters(index = "0", description = "UPLC text file")
    private Path file;

    @Option(names = {"-o", "--out"}, description = "Output .flat file")
    private Path outFile;

    @Override
    public void run() {
        try {
            String text = Files.readString(file);
            var program = UplcParser.parseProgram(text);
            byte[] flat = UplcFlatEncoder.encodeProgram(program);

            Path output = outFile != null ? outFile
                    : Path.of(file.toString().replaceAll("\\.uplc$", "") + ".flat");
            Files.write(output, flat);
            System.out.println("Encoded " + flat.length + " bytes to " + output);
        } catch (Exception e) {
            System.err.println(AnsiColors.red("Error: " + e.getMessage()));
            System.exit(1);
        }
    }
}
