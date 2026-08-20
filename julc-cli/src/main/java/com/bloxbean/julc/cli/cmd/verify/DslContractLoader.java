package com.bloxbean.julc.cli.cmd.verify;

import com.bloxbean.cardano.julc.clientlib.JulcScriptAdapter;
import com.bloxbean.cardano.julc.compiler.CompilerException;
import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.compiler.schema.ContractSchema;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import com.bloxbean.julc.cli.cmd.BuildCommand;
import com.bloxbean.julc.cli.cmd.blueprint.ArtifactCommand;
import com.bloxbean.julc.cli.project.ProjectLayout;
import com.bloxbean.julc.cli.project.ProjectScanner;
import com.bloxbean.julc.cli.project.ProjectSourceResolver;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;

/** Shared observational compile for experimental DSL commands. */
final class DslContractLoader {
    private DslContractLoader() { }

    static Loaded load(Path projectDirectory, String validatorTitle) throws Exception {
        return load(projectDirectory, validatorTitle, null);
    }

    static Loaded load(
            Path projectDirectory,
            String validatorTitle,
            VerificationPurpose requestedPurpose) throws Exception {
        Path project = projectDirectory.toAbsolutePath().normalize();
        if (!Files.isRegularFile(ProjectLayout.tomlFile(project))) {
            throw new IllegalArgumentException("Not a JuLC project: " + project);
        }
        if (new CommandLine(new BuildCommand()).execute(project.toString()) != 0) {
            throw new IllegalStateException("Ordinary JuLC build failed; DSL preparation stopped");
        }
        var scan = ProjectScanner.scan(ProjectLayout.srcDir(project));
        String source = scan.validators().get(validatorTitle);
        if (source == null) {
            throw new IllegalArgumentException("Unknown validator '" + validatorTitle
                    + "'; available: " + scan.validators().keySet());
        }
        var libraries = ProjectSourceResolver.resolve(
                source, ProjectSourceResolver.buildPool(scan.libraries()));
        var compiled = new JulcCompiler(StdlibRegistry.defaultRegistry())
                .compileContract(source, libraries);
        if (compiled.compileResult().hasErrors()) {
            throw new CompilerException(compiled.compileResult().diagnostics());
        }
        Path blueprint = ProjectLayout.plutusDir(project).resolve("plutus.json");
        ContractSchema fullSchema = compiled.contractSchema();
        VerificationPurpose selectedPurpose;
        if (fullSchema.interfaces().size() == 1) {
            selectedPurpose = purpose(fullSchema.purpose());
            if (requestedPurpose != null && requestedPurpose != selectedPurpose) {
                throw new IllegalArgumentException("Validator '" + validatorTitle
                        + "' has purpose " + selectedPurpose.userName() + ", not "
                        + requestedPurpose.userName());
            }
        } else {
            if (requestedPurpose == null) {
                throw new IllegalArgumentException("Validator '" + validatorTitle
                        + "' has multiple interfaces "
                        + fullSchema.interfaces().stream().map(value -> value.purpose().name())
                        .toList() + "; select one with --purpose");
            }
            selectedPurpose = requestedPurpose;
        }
        ContractSchema selectedSchema = fullSchema.select(selectedPurpose.compilerPurpose());
        var resolved = ArtifactCommand.inspectForPurpose(
                blueprint, validatorTitle, selectedPurpose.cip57Name());
        var artifact = resolved.artifact();
        String compiledCode = JulcScriptAdapter
                .fromProgram(compiled.compileResult().program()).getCborHex();
        if (!artifact.compiledCode().equalsIgnoreCase(compiledCode)
                || !artifact.cardanoScriptHash().equalsIgnoreCase(
                        JulcScriptAdapter.scriptHash(compiled.compileResult().program()))) {
            throw new IllegalStateException(
                    "Observational DSL compile does not match exact blueprint artifact");
        }
        return new Loaded(project, blueprint, artifact.artifactId(), source,
                selectedSchema, selectedPurpose, resolved.blueprintEntryTitle());
    }

    private static VerificationPurpose purpose(ContractSchema.Purpose purpose) {
        return switch (purpose) {
            case SPEND -> VerificationPurpose.SPENDING;
            case MINT -> VerificationPurpose.MINTING;
            case WITHDRAW -> VerificationPurpose.REWARDING;
            default -> throw new IllegalArgumentException(
                    "Typed DSL does not support " + purpose + " interfaces");
        };
    }

    record Loaded(
            Path project,
            Path blueprint,
            String artifactId,
            String source,
            ContractSchema schema,
            VerificationPurpose purpose,
            String blueprintEntryTitle) { }
}
