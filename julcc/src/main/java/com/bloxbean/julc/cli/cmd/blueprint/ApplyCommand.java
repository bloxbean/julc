package com.bloxbean.julc.cli.cmd.blueprint;

import com.bloxbean.cardano.julc.clientlib.JulcScriptAdapter;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.julc.cli.cmd.EvalCommand;
import com.bloxbean.julc.cli.output.AnsiColors;
import com.bloxbean.julc.cli.project.ProjectLayout;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

@Command(name = "apply", description = "Apply parameters to a parameterized validator")
public class ApplyCommand implements Runnable {

    @Parameters(index = "0", defaultValue = ".", description = "Project directory")
    private Path projectDir;

    @Option(names = {"--int"}, description = "Integer parameter value")
    private BigInteger intParam;

    @Option(names = {"--bytes"}, description = "Bytes parameter value (hex)")
    private String bytesParam;

    @Override
    public void run() {
        try {
            Path blueprintFile = ProjectLayout.plutusDir(projectDir.toAbsolutePath()).resolve("plutus.json");
            if (!Files.exists(blueprintFile)) {
                System.err.println(AnsiColors.red("No blueprint found. Run 'julcc build' first."));
                System.exit(1);
            }

            String json = Files.readString(blueprintFile);
            String hex = EvalCommand.extractCompiledCode(json);
            var program = JulcScriptAdapter.toProgram(hex);

            // Build parameter
            PlutusData param;
            if (intParam != null) {
                param = new PlutusData.IntData(intParam);
            } else if (bytesParam != null) {
                param = new PlutusData.BytesData(HexFormat.of().parseHex(bytesParam));
            } else {
                System.err.println(AnsiColors.red("Specify a parameter: --int <value> or --bytes <hex>"));
                System.exit(1);
                return;
            }

            var applied = program.applyParams(param);
            var script = JulcScriptAdapter.fromProgram(applied);
            var hash = JulcScriptAdapter.scriptHash(applied);

            System.out.println("Applied parameter. New script hash: " + hash);
            System.out.println("CBOR hex: " + script.getCborHex());
        } catch (Exception e) {
            System.err.println(AnsiColors.red("Error: " + e.getMessage()));
            System.exit(1);
        }
    }
}
