package org.julclang.cli.cmd.verify;

import org.julclang.clientlib.JulcScriptAdapter;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.schema.ContractSchema;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.verification.RequiresSignerProperty;
import org.julclang.verification.RequiresSignerResolver;
import org.julclang.verification.StatefulSpendingProperty;
import org.julclang.verification.StatefulSpendingResolver;
import org.julclang.verification.VerificationProperty;
import org.julclang.verification.ControlledMintProperty;
import org.julclang.verification.ControlledMintResolver;
import org.julclang.cli.cmd.BuildCommand;
import org.julclang.cli.cmd.blueprint.ArtifactCommand;
import org.julclang.cli.project.ProjectLayout;
import org.julclang.cli.project.ProjectScanner;
import org.julclang.cli.project.ProjectSourceResolver;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;

/** Orchestrates supported Java property profiles from build through managed proof. */
final class AnnotatedVerificationWorkflow {

    Execution run(
            Path projectDirectory,
            String validatorTitle,
            VerificationPurpose requestedPurpose,
            VerificationBackendKind backend,
            Path requestedOutput,
            int fuel,
            int recursiveDepth,
            boolean force,
            VerificationProgress progress) throws Exception {
        Path project = projectDirectory.toAbsolutePath().normalize();
        if (!Files.isRegularFile(ProjectLayout.tomlFile(project))) {
            throw new IllegalArgumentException("Not a JuLC project: " + project);
        }
        int buildExit = new CommandLine(new BuildCommand()).execute(project.toString());
        if (buildExit != 0) {
            throw new IllegalStateException("Ordinary JuLC build failed; verification was not run");
        }

        progress.heading("Preparing formal verification for " + validatorTitle + " ...");
        Path blueprint = ProjectLayout.plutusDir(project).resolve("plutus.json");
        VerificationProperty property;
        ArtifactCommand.ArtifactMetadata artifact;
        try (var task = progress.start("Resolving property and exact script artifact")) {
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
            ContractSchema selectedSchema = selectSchema(
                    compiled.contractSchema(), requestedPurpose, validatorTitle);
            String sourceFile = sourceFileName(project, validatorTitle);
            property = ControlledMintResolver.resolve(
                            source, sourceFile, validatorTitle, selectedSchema)
                    .<VerificationProperty>map(value -> value)
                    .orElseGet(() -> StatefulSpendingResolver.resolve(
                            source, sourceFile, validatorTitle, selectedSchema)
                    .<VerificationProperty>map(value -> value)
                    .orElseGet(() -> RequiresSignerResolver.resolve(
                                    source, sourceFile, validatorTitle,
                                    selectedSchema)
                            .orElseThrow(() -> new IllegalArgumentException("Validator '"
                                    + validatorTitle + "' has no supported verification profile"))));

            var propertyPurpose = VerificationPurpose.fromUserName(property.scriptPurpose());
            artifact = ArtifactCommand.inspectForPurpose(
                    blueprint, validatorTitle, propertyPurpose.cip57Name()).artifact();
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
            task.succeed(property.template());
        }

        Path output = requestedOutput == null
                ? project.resolve("verification").resolve(artifact.artifactId())
                : requestedOutput.toAbsolutePath().normalize();
        try (var task = progress.start("Generating hash-bound verification workspace")) {
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
            task.succeed(output.toString());
        }
        progress.heading("Running verification ...");
        var run = new VerificationRunner().run(output, backend, progress);
        return new Execution(output, property, run);
    }

    private static ContractSchema selectSchema(
            ContractSchema schema,
            VerificationPurpose requestedPurpose,
            String validatorTitle) {
        if (schema.interfaces().size() == 1) {
            if (requestedPurpose != null
                    && schema.purpose() != requestedPurpose.compilerPurpose()) {
                throw new IllegalArgumentException("Validator '" + validatorTitle
                        + "' has purpose " + schema.purpose()
                        + ", not " + requestedPurpose.userName());
            }
            return schema;
        }
        if (requestedPurpose == null) {
            throw new IllegalArgumentException("Validator '" + validatorTitle
                    + "' exposes multiple purpose interfaces; pass --purpose");
        }
        return schema.select(requestedPurpose.compilerPurpose());
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
