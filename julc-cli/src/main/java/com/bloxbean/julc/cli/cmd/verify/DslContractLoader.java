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
        var artifact = ArtifactCommand.inspect(blueprint, validatorTitle);
        String compiledCode = JulcScriptAdapter
                .fromProgram(compiled.compileResult().program()).getCborHex();
        if (!artifact.compiledCode().equalsIgnoreCase(compiledCode)
                || !artifact.cardanoScriptHash().equalsIgnoreCase(
                        JulcScriptAdapter.scriptHash(compiled.compileResult().program()))) {
            throw new IllegalStateException(
                    "Observational DSL compile does not match exact blueprint artifact");
        }
        return new Loaded(project, blueprint, artifact.artifactId(), source,
                compiled.contractSchema());
    }

    record Loaded(
            Path project,
            Path blueprint,
            String artifactId,
            String source,
            ContractSchema schema) { }
}
