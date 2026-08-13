package com.bloxbean.julc.cli.cmd.verify;

import com.bloxbean.cardano.julc.clientlib.JulcScriptAdapter;
import com.bloxbean.cardano.julc.compiler.CompilerException;
import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import com.bloxbean.cardano.julc.verification.RequiresSignerProperty;
import com.bloxbean.cardano.julc.verification.RequiresSignerResolver;
import com.bloxbean.cardano.julc.verification.StatefulSpendingProperty;
import com.bloxbean.cardano.julc.verification.StatefulSpendingResolver;
import com.bloxbean.cardano.julc.verification.VerificationProperty;
import com.bloxbean.cardano.julc.verification.ControlledMintProperty;
import com.bloxbean.cardano.julc.verification.ControlledMintResolver;
import com.bloxbean.julc.cli.cmd.BuildCommand;
import com.bloxbean.julc.cli.cmd.blueprint.ArtifactCommand;
import com.bloxbean.julc.cli.project.ProjectLayout;
import com.bloxbean.julc.cli.project.ProjectScanner;
import com.bloxbean.julc.cli.project.ProjectSourceResolver;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;

/** Orchestrates supported Java property profiles from build through managed proof. */
final class AnnotatedVerificationWorkflow {

    Execution run(
            Path projectDirectory,
            String validatorTitle,
            VerificationBackendKind backend,
            Path requestedOutput,
            int fuel,
            int recursiveDepth,
            boolean force) throws Exception {
        Path project = projectDirectory.toAbsolutePath().normalize();
        if (!Files.isRegularFile(ProjectLayout.tomlFile(project))) {
            throw new IllegalArgumentException("Not a JuLC project: " + project);
        }
        int buildExit = new CommandLine(new BuildCommand()).execute(project.toString());
        if (buildExit != 0) {
            throw new IllegalStateException("Ordinary JuLC build failed; verification was not run");
        }

        var scan = ProjectScanner.scan(ProjectLayout.srcDir(project));
        String source = scan.validators().get(validatorTitle);
        if (source == null) {
            throw new IllegalArgumentException("Expected exactly one Java validator named '"
                    + validatorTitle + "'; available: " + scan.validators().keySet());
        }
        var pool = ProjectSourceResolver.buildPool(scan.libraries());
        var resolvedLibraries = ProjectSourceResolver.resolve(source, pool);
        var compiled = new JulcCompiler(StdlibRegistry.defaultRegistry())
                .compileContract(source, resolvedLibraries);
        if (compiled.compileResult().hasErrors()) {
            throw new CompilerException(compiled.compileResult().diagnostics());
        }
        String sourceFile = sourceFileName(project, validatorTitle);
        VerificationProperty property = ControlledMintResolver.resolve(
                        source, sourceFile, validatorTitle, compiled.contractSchema())
                .<VerificationProperty>map(value -> value)
                .orElseGet(() -> StatefulSpendingResolver.resolve(
                        source, sourceFile, validatorTitle, compiled.contractSchema())
                .<VerificationProperty>map(value -> value)
                .orElseGet(() -> RequiresSignerResolver.resolve(
                                source, sourceFile, validatorTitle,
                                compiled.contractSchema())
                        .orElseThrow(() -> new IllegalArgumentException("Validator '"
                                + validatorTitle + "' has no supported verification profile"))));

        Path blueprint = ProjectLayout.plutusDir(project).resolve("plutus.json");
        var artifact = ArtifactCommand.inspect(blueprint, validatorTitle);
        String observedCompiledCode = JulcScriptAdapter
                .fromProgram(compiled.compileResult().program()).getCborHex();
        if (!artifact.compiledCode().equalsIgnoreCase(observedCompiledCode)) {
            throw new IllegalStateException("Observational metadata compile does not match the "
                    + "exact blueprint compiledCode for " + validatorTitle);
        }
        String observedScriptHash = JulcScriptAdapter.scriptHash(
                compiled.compileResult().program());
        if (!artifact.cardanoScriptHash().equalsIgnoreCase(observedScriptHash)) {
            throw new IllegalStateException("Observational metadata compile script hash mismatch");
        }

        Path output = requestedOutput == null
                ? project.resolve("verification").resolve(artifact.artifactId())
                : requestedOutput.toAbsolutePath().normalize();
        if (property instanceof ControlledMintProperty controlled) {
            VerificationProjectGenerator.generateControlledMint(
                    blueprint, controlled, fuel, recursiveDepth, output, force);
        } else if (property instanceof StatefulSpendingProperty stateful) {
            VerificationProjectGenerator.generateStatefulSpending(
                    blueprint, stateful, fuel, recursiveDepth, output, force);
        } else {
            VerificationProjectGenerator.generateRequiresSigner(
                    blueprint, (RequiresSignerProperty) property,
                    fuel, recursiveDepth, output, force);
        }
        var run = new VerificationRunner().run(output, backend);
        return new Execution(output, property, run);
    }

    private static String sourceFileName(Path project, String validatorTitle) {
        Path sources = ProjectLayout.srcDir(project);
        try (var paths = Files.walk(sources)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString()
                            .equals(validatorTitle + ".java"))
                    .findFirst()
                    .map(path -> project.relativize(path).toString())
                    .orElse(validatorTitle + ".java");
        } catch (java.io.IOException ignored) {
            return validatorTitle + ".java";
        }
    }

    record Execution(
            Path workspace,
            VerificationProperty property,
            VerificationRunner.RunExecution run) { }
}
