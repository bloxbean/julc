package com.bloxbean.julc.cli.cmd;

import com.bloxbean.cardano.julc.clientlib.JulcScriptAdapter;
import com.bloxbean.cardano.julc.compiler.CompileResult;
import com.bloxbean.cardano.julc.compiler.CompilerException;
import com.bloxbean.cardano.julc.compiler.CompilerOptions;
import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.core.text.UplcPrinter;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import com.bloxbean.cardano.julc.blueprint.BlueprintConfig;
import com.bloxbean.cardano.julc.blueprint.BlueprintGenerator;
import com.bloxbean.julc.cli.output.AnsiColors;
import com.bloxbean.julc.cli.output.DiagnosticFormatter;
import com.bloxbean.julc.cli.project.*;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

@Command(name = "build", description = "Compile validators to UPLC")
public class BuildCommand implements Runnable {

    @Parameters(index = "0", defaultValue = ".", description = "Project directory")
    private Path projectDir;

    @Option(names = {"-v", "--verbose"}, description = "Verbose output")
    private boolean verbose;

    @Override
    public void run() {
        try {
            Path root = projectDir.toAbsolutePath();
            Path tomlFile = ProjectLayout.tomlFile(root);
            if (!Files.exists(tomlFile)) {
                System.err.println(AnsiColors.red("Not a julcc project (no julc.toml found)"));
                System.exit(1);
            }

            var config = TomlParser.parse(tomlFile);
            System.out.println("Building " + AnsiColors.bold(config.name()) + " ...");

            // Scan sources
            var scanResult = ProjectScanner.scan(ProjectLayout.srcDir(root));
            if (scanResult.validators().isEmpty()) {
                System.err.println(AnsiColors.yellow("No validators found in src/"));
                System.exit(0);
            }

            // Build library pool
            var pool = ProjectSourceResolver.buildPool(scanResult.libraries());

            // Compile each validator
            var options = new CompilerOptions().setVerbose(verbose);
            var compiledValidators = new ArrayList<BlueprintGenerator.CompiledValidator>();
            int errorCount = 0;

            Path plutusDir = ProjectLayout.plutusDir(root);
            Files.createDirectories(plutusDir);

            for (var entry : scanResult.validators().entrySet()) {
                String name = entry.getKey();
                String source = entry.getValue();

                System.out.print("  Compiling " + name + " ... ");

                try {
                    var resolvedLibs = ProjectSourceResolver.resolve(source, pool);
                    var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(), options);
                    CompileResult result = compiler.compile(source, resolvedLibs);

                    if (result.hasErrors()) {
                        System.out.println(AnsiColors.red("FAILED"));
                        System.err.println(DiagnosticFormatter.formatAll(result.diagnostics()));
                        errorCount++;
                        continue;
                    }

                    // Print warnings
                    var warnings = result.diagnostics().stream()
                            .filter(d -> !d.isError()).toList();
                    if (!warnings.isEmpty()) {
                        System.err.print(DiagnosticFormatter.formatAll(warnings));
                    }

                    compiledValidators.add(new BlueprintGenerator.CompiledValidator(name, source, result));

                    // Write UPLC text
                    String uplcText = UplcPrinter.print(result.program());
                    Files.writeString(plutusDir.resolve(name + ".uplc"), uplcText);

                    // Script info
                    String hash = JulcScriptAdapter.scriptHash(result.program());
                    String sizeStr = result.scriptSizeFormatted();
                    System.out.println(AnsiColors.green("OK") + " "
                            + AnsiColors.dim("[" + sizeStr + ", " + hash.substring(0, 8) + "...]"));
                } catch (CompilerException e) {
                    System.out.println(AnsiColors.red("FAILED"));
                    if (!e.diagnostics().isEmpty()) {
                        System.err.println(DiagnosticFormatter.formatAll(e.diagnostics()));
                    } else {
                        System.err.println("  " + e.getMessage());
                    }
                    errorCount++;
                }
            }

            // Generate CIP-57 blueprint
            if (!compiledValidators.isEmpty()) {
                var blueprintConfig = new BlueprintConfig(config.name(), config.version());
                var blueprint = BlueprintGenerator.generate(blueprintConfig, compiledValidators);
                Files.writeString(plutusDir.resolve("plutus.json"), blueprint.toJson());
            }

            // Summary
            System.out.println();
            if (errorCount == 0) {
                System.out.println(AnsiColors.green("Build successful: "
                        + compiledValidators.size() + " validator(s) compiled to build/plutus/"));
            } else {
                System.out.println(AnsiColors.red("Build failed: " + errorCount + " error(s)"));
                System.exit(1);
            }
        } catch (IOException e) {
            System.err.println(AnsiColors.red("Build error: " + e.getMessage()));
            System.exit(1);
        }
    }
}
