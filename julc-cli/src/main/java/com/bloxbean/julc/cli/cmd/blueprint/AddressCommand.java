package com.bloxbean.julc.cli.cmd.blueprint;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.spec.Script;
import com.bloxbean.cardano.julc.clientlib.JulcScriptAdapter;
import com.bloxbean.julc.cli.cmd.EvalCommand;
import com.bloxbean.julc.cli.output.AnsiColors;
import com.bloxbean.julc.cli.project.ProjectLayout;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;

@Command(name = "address", description = "Get script address from blueprint")
public class AddressCommand implements Runnable {

    @Parameters(index = "0", defaultValue = ".", description = "Project directory")
    private Path projectDir;

    @Option(names = {"--network", "-n"}, defaultValue = "testnet", description = "Network: mainnet or testnet")
    private String network;

    @Override
    public void run() {
        try {
            Path blueprintFile = ProjectLayout.plutusDir(projectDir.toAbsolutePath()).resolve("plutus.json");
            if (!Files.exists(blueprintFile)) {
                System.err.println(AnsiColors.red("No blueprint found. Run 'julc build' first."));
                System.exit(1);
            }

            String json = Files.readString(blueprintFile);
            String hex = EvalCommand.extractCompiledCode(json);

            var program = JulcScriptAdapter.toProgram(hex);
            Script script = JulcScriptAdapter.fromProgram(program);
            Network net = "mainnet".equalsIgnoreCase(network) ? Networks.mainnet() : Networks.testnet();
            String address = AddressProvider.getEntAddress(script, net).toBech32();

            System.out.println(address);
        } catch (Exception e) {
            System.err.println(AnsiColors.red("Error: " + e.getMessage()));
            System.exit(1);
        }
    }
}
