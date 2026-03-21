package com.bloxbean.julc.cli.cmd;

import com.bloxbean.cardano.julc.vm.JulcVm;
import com.bloxbean.julc.cli.JulcVersionProvider;
import picocli.CommandLine.Command;

@Command(name = "version", description = "Show version information")
public class VersionCommand implements Runnable {

    @Override
    public void run() {
        System.out.println("julc " + JulcVersionProvider.VERSION);
        System.out.println("  Plutus: " + JulcVersionProvider.PLUTUS_VERSION);
        System.out.println("  Java:   " + Runtime.version().feature());

        try {
            var providers = JulcVm.availableProviders();
            System.out.println("  VM:     " + (providers.isEmpty() ? "none" : String.join(", ", providers)));
        } catch (Exception e) {
            System.out.println("  VM:     " + e.getMessage());
        }
    }
}
