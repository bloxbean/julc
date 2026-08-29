package com.bloxbean.julc.cli.cmd;

import com.bloxbean.cardano.julc.clientlib.JulcScriptAdapter;
import com.bloxbean.cardano.julc.compiler.CompileResult;
import com.bloxbean.cardano.julc.compiler.CompilerException;
import com.bloxbean.cardano.julc.compiler.CompilerOptions;
import com.bloxbean.cardano.julc.compiler.CompilerTargetRegistry;
import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.compiler.OptimizationConfiguration;
import com.bloxbean.cardano.julc.core.text.UplcPrinter;
import com.bloxbean.cardano.julc.jrl.JrlCompiler;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import com.bloxbean.cardano.julc.blueprint.BlueprintConfig;
import com.bloxbean.cardano.julc.blueprint.BlueprintFileWriter;
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
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Callable;

@Command(name = "build", description = "Compile validators to UPLC")
public class BuildCommand implements Callable<Integer> {

    private record CompiledOutput(String name, CompileResult result) {}

    @Parameters(index = "0", defaultValue = ".", description = "Project directory")
    private Path projectDir;

    @Option(names = {"-v", "--verbose"}, description = "Verbose output")
    private boolean verbose;

    @Option(names = {"--no-blueprint", "--skip-blueprint"},
            description = "Compile raw artifacts without generating build/plutus/plutus.json")
    private boolean noBlueprint;

    @Option(names = "--target",
            defaultValue = "plutus-v3-pv11-uplc-1.1.0",
            description = "Exact compiler target profile ID (default: ${DEFAULT-VALUE})")
    private String targetProfile;

    @Option(names = "--optimization",
            defaultValue = "baseline",
            description = "Exact optimizer rollout ID: none, baseline, pv11-safe, or pv11-costed (default: ${DEFAULT-VALUE})")
    private String optimizationProfile;

    @Option(names = "--cost-profile",
            description = "Exact pinned cost profile ID; required by pv11-costed")
    private String costProfile;

    @Override
    public Integer call() {
        try {
            Path root = projectDir.toAbsolutePath();
            Path tomlFile = ProjectLayout.tomlFile(root);
            if (!Files.exists(tomlFile)) {
                System.err.println(AnsiColors.red("Not a julc project (no julc.toml found)"));
                return 1;
            }

            var config = TomlParser.parse(tomlFile);
            var compilerTarget = CompilerTargetRegistry.targetForProfileId(targetProfile);
            var options = OptimizationConfiguration.apply(
                    new CompilerOptions().setTarget(compilerTarget).setVerbose(verbose),
                    optimizationProfile,
                    costProfile);
            System.out.println("Building " + AnsiColors.bold(config.name())
                    + " for " + compilerTarget.profileId()
                    + " (optimization: " + options.getOptimizationLevel().profileId() + ") ...");

            // Scan sources (single walk for .java + .jrl)
            var scanResult = ProjectScanner.scan(ProjectLayout.srcDir(root));
            Path plutusDir = ProjectLayout.plutusDir(root);
            Files.createDirectories(plutusDir);
            if (scanResult.validators().isEmpty() && scanResult.jrlFiles().isEmpty()) {
                cleanupGeneratedArtifacts(plutusDir, Set.of());
                Files.deleteIfExists(plutusDir.resolve("plutus.json"));
                System.err.println(AnsiColors.yellow("No validators found in src/"));
                return 0;
            }

            // Build library pool
            var pool = ProjectSourceResolver.buildPool(scanResult.libraries());

            // Compile each validator
            var stdlib = StdlibRegistry.defaultRegistry();
            var compiledValidators = new ArrayList<BlueprintGenerator.CompiledValidator>();
            var compiledOutputs = new ArrayList<CompiledOutput>();
            int errorCount = 0;
            int compiledCount = 0;

            for (var entry : scanResult.validators().entrySet()) {
                String name = entry.getKey();
                String source = entry.getValue();

                System.out.print("  Compiling " + name + " ... ");

                try {
                    var resolvedLibs = ProjectSourceResolver.resolve(source, pool);
                    var compiler = new JulcCompiler(stdlib, options);
                    CompileResult result;
                    com.bloxbean.cardano.julc.compiler.schema.ContractSchema contractSchema = null;
                    if (noBlueprint) {
                        result = compiler.compile(source, resolvedLibs);
                    } else {
                        var compiled = compiler.compileContract(source, resolvedLibs);
                        result = compiled.compileResult();
                        contractSchema = compiled.contractSchema();
                    }

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

                    if (!noBlueprint) {
                        compiledValidators.add(new BlueprintGenerator.CompiledValidator(
                                name, result, contractSchema));
                    }
                    compiledOutputs.add(new CompiledOutput(name, result));
                    printCompiledStatus(result);
                    compiledCount++;
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

            // Compile JRL files
            var jrlCompiler = new JrlCompiler(stdlib, options);
            for (var entry : scanResult.jrlFiles().entrySet()) {
                String name = entry.getKey();
                String jrlSource = entry.getValue();

                System.out.print("  Compiling " + name + ".jrl ... ");

                var jrlResult = noBlueprint
                        ? jrlCompiler.compile(jrlSource, name + ".jrl")
                        : jrlCompiler.compileContract(jrlSource, name + ".jrl");

                if (jrlResult.hasErrors()) {
                    System.out.println(AnsiColors.red("FAILED"));
                    for (var diag : jrlResult.jrlDiagnostics()) {
                        if (diag.isError()) {
                            System.err.println("  " + diag);
                        }
                    }
                    errorCount++;
                    continue;
                }

                var result = jrlResult.compileResult();
                if (!noBlueprint) {
                    compiledValidators.add(new BlueprintGenerator.CompiledValidator(
                            name, result, jrlResult.contractSchema()));
                }
                compiledOutputs.add(new CompiledOutput(name, result));
                printCompiledStatus(result);
                compiledCount++;
            }

            String blueprintJson = null;
            if (!noBlueprint && errorCount == 0 && !compiledValidators.isEmpty()) {
                try {
                    var blueprintConfig = new BlueprintConfig(config.name(), config.version());
                    var blueprint = BlueprintGenerator.generate(blueprintConfig, compiledValidators);
                    blueprintJson = blueprint.toJson();
                } catch (IllegalArgumentException e) {
                    System.err.println(AnsiColors.red("Blueprint generation failed: " + e.getMessage()));
                    errorCount++;
                }
            }

            // Summary
            System.out.println();
            if (errorCount == 0) {
                Set<String> currentNames = new LinkedHashSet<>();
                for (var output : compiledOutputs) {
                    writeCompiledOutput(output.name(), output.result(), plutusDir);
                    currentNames.add(output.name());
                }
                cleanupGeneratedArtifacts(plutusDir, currentNames);
                if (noBlueprint) {
                    Files.deleteIfExists(plutusDir.resolve("plutus.json"));
                } else {
                    BlueprintFileWriter.writeAtomically(
                            plutusDir.resolve("plutus.json"), blueprintJson);
                }
                System.out.println(AnsiColors.green("Build successful: "
                        + compiledCount + " validator(s) compiled for "
                        + compilerTarget.profileId() + " with "
                        + options.getOptimizationLevel().profileId()
                        + " optimization to build/plutus/"));
                return 0;
            } else {
                System.out.println(AnsiColors.red("Build failed: " + errorCount + " error(s)"));
                return 1;
            }
        } catch (CompilerException e) {
            if (!e.diagnostics().isEmpty()) {
                System.err.println(DiagnosticFormatter.formatAll(e.diagnostics()));
            } else {
                System.err.println(AnsiColors.red("Build error: " + e.getMessage()));
            }
            return 1;
        } catch (IOException e) {
            System.err.println(AnsiColors.red("Build error: " + e.getMessage()));
            return 1;
        }
    }

    private void writeCompiledOutput(String name, CompileResult result, Path plutusDir)
            throws IOException {
        String uplcText = UplcPrinter.print(result.program());
        Files.writeString(plutusDir.resolve(name + ".uplc"), uplcText);
        String hash = JulcScriptAdapter.scriptHash(result.program());
        var script = JulcScriptAdapter.fromProgram(result.program());
        Files.writeString(plutusDir.resolve(name + ".compiledCode.hex"),
                script.getCborHex() + System.lineSeparator());
        Files.writeString(plutusDir.resolve(name + ".script-hash"),
                hash + System.lineSeparator());
    }

    private void printCompiledStatus(CompileResult result) {
        String hash = JulcScriptAdapter.scriptHash(result.program());
        String sizeStr = result.scriptSizeFormatted();
        System.out.println(AnsiColors.green("OK") + " "
                + AnsiColors.dim("[" + result.target().profileId() + ", "
                        + result.optimizationReport().level().profileId() + ", "
                        + sizeStr + ", " + hash.substring(0, 8) + "...]"));
    }

    private void cleanupGeneratedArtifacts(Path plutusDir, Set<String> currentNames)
            throws IOException {
        if (!Files.isDirectory(plutusDir)) return;
        try (var files = Files.list(plutusDir)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                String base = generatedArtifactBase(name);
                if (base != null && !currentNames.contains(base)) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    private String generatedArtifactBase(String name) {
        for (String suffix : new String[]{".compiledCode.hex", ".script-hash", ".uplc"}) {
            if (name.endsWith(suffix)) return name.substring(0, name.length() - suffix.length());
        }
        return null;
    }
}
