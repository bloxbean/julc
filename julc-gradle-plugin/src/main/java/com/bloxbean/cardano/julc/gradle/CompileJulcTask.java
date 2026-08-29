package com.bloxbean.cardano.julc.gradle;

import com.bloxbean.cardano.julc.blueprint.BlueprintConfig;
import com.bloxbean.cardano.julc.blueprint.BlueprintFileWriter;
import com.bloxbean.cardano.julc.blueprint.BlueprintGenerator;
import com.bloxbean.cardano.julc.clientlib.JulcScriptAdapter;
import com.bloxbean.cardano.julc.compiler.CompilerOptions;
import com.bloxbean.cardano.julc.compiler.CompilerTargetRegistry;
import com.bloxbean.cardano.julc.compiler.JavaSourceIntrospector;
import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.compiler.ScriptPurposeMetadata;
import com.bloxbean.cardano.julc.compiler.OptimizationConfiguration;
import com.bloxbean.cardano.julc.core.source.SourceMapSerializer;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Gradle task that compiles Plutus validator Java source files to UPLC scripts.
 * <p>
 * For each {@code .java} file in {@code sourceDir} annotated with a supported
 * JuLC validator annotation, produces a JSON file in {@code outputDir}
 * containing the compiled CBOR hex and script hash.
 * <p>
 * Non-validator {@code .java} files (including those annotated with {@code @OnchainLibrary})
 * are automatically treated as library sources and compiled alongside each validator.
 */
public abstract class CompileJulcTask extends DefaultTask {

    @InputDirectory
    @Optional
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getSourceDir();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    @Input
    @Optional
    public abstract Property<Boolean> getSourceMap();

    @Input
    @Optional
    public abstract Property<Boolean> getBlueprint();

    @Input
    public abstract Property<String> getTarget();

    @Input
    public abstract Property<String> getOptimization();

    @Input
    @Optional
    public abstract Property<String> getCostProfile();

    @TaskAction
    public void compile() throws IOException {
        File srcDir = getSourceDir().get().getAsFile();
        File outDir = getOutputDir().get().getAsFile();
        outDir.mkdirs();
        boolean blueprintEnabled = Boolean.TRUE.equals(getBlueprint().getOrElse(true));
        boolean sourceMapEnabled = Boolean.TRUE.equals(getSourceMap().getOrElse(false));
        var compilerTarget = CompilerTargetRegistry.targetForProfileId(getTarget().get());

        var stdlib = StdlibRegistry.defaultRegistry();
        var options = OptimizationConfiguration.apply(
                new CompilerOptions().setTarget(compilerTarget),
                getOptimization().get(),
                getCostProfile().getOrNull());
        if (sourceMapEnabled) {
            options.setSourceMapEnabled(true);
        }
        var compiler = new JulcCompiler(stdlib, options);

        List<File> javaFiles = findJavaFiles(srcDir);

        // Separate validators from library files
        var validatorFiles = new ArrayList<ValidatorFile>();
        var libraryFiles = new ArrayList<File>();

        for (File javaFile : javaFiles) {
            String source = Files.readString(javaFile.toPath());
            if (!JavaSourceIntrospector.mightContainValidatorAnnotation(source)) {
                libraryFiles.add(javaFile);
                continue;
            }

            JavaSourceIntrospector.SourceInfo sourceInfo = inspect(javaFile, source);
            rejectRoleConflicts(sourceInfo);
            if (sourceInfo.legacyValidatorType().isPresent()) {
                throw new GradleException(JavaSourceIntrospector.legacyAnnotationMigrationMessage(
                        sourceInfo.legacyValidatorType().get()));
            }
            if (sourceInfo.validatorType().isPresent()) {
                validatorFiles.add(new ValidatorFile(javaFile,
                        sourceInfo.scriptPurpose().orElseThrow()));
            } else {
                getLogger().info("Treating {} as a library source: text matched validator annotation but no real validator annotation was found",
                        javaFile);
                libraryFiles.add(javaFile);
            }
        }

        // Read library sources once
        var librarySources = new ArrayList<String>();
        for (File libFile : libraryFiles) {
            librarySources.add(Files.readString(libFile.toPath()));
        }

        int compiled = 0;
        var compiledList = new ArrayList<BlueprintGenerator.CompiledValidator>();
        var pendingOutputs = new ArrayList<PendingOutput>();

        for (ValidatorFile validatorFile : validatorFiles) {
            String validatorSource = Files.readString(validatorFile.file().toPath());

            // Compile with multi-file support
            var contractResult = blueprintEnabled
                    ? compiler.compileContract(validatorSource, librarySources)
                    : null;
            var result = blueprintEnabled
                    ? contractResult.compileResult()
                    : compiler.compile(validatorSource, librarySources);
            if (result.hasErrors()) {
                throw new GradleException("Compilation failed for " + validatorFile.file().getName()
                        + ": " + result.diagnostics());
            }

            // Generate output
            var program = result.program();
            var script = JulcScriptAdapter.fromProgram(program);
            String scriptHash = JulcScriptAdapter.scriptHash(program);
            String validatorName = validatorFile.file().getName().replace(".java", "");

            int sizeBytes = result.scriptSizeBytes();
            String sizeStr = result.scriptSizeFormatted();

            var output = new ValidatorOutput(ScriptPurposeMetadata.textEnvelopeType(),
                    ScriptPurposeMetadata.jsonPurpose(validatorFile.scriptPurpose()), validatorName,
                    script.getCborHex(), scriptHash, sizeBytes);

            String sourceMapJson = null;
            if (sourceMapEnabled && result.hasSourceMap()) {
                var indexed = result.sourceMap().toIndexed(program.term());
                sourceMapJson = SourceMapSerializer.toJson(indexed, validatorName);
            }
            pendingOutputs.add(new PendingOutput(
                    validatorName, validatorFile.file().getName(), output.toJson(),
                    sourceMapJson, scriptHash, sizeStr));

            if (blueprintEnabled) {
                compiledList.add(new BlueprintGenerator.CompiledValidator(
                        validatorName, result, contractResult.contractSchema()));
            }
            compiled++;
        }

        // Validate the aggregate before publishing any per-validator output.
        String blueprintJson = null;
        if (blueprintEnabled && !compiledList.isEmpty()) {
            var config = new BlueprintConfig(
                    getProject().getName(), getProject().getVersion().toString());
            var blueprint = BlueprintGenerator.generate(config, compiledList);
            blueprintJson = blueprint.toJson();
        }

        // All compilation and schema validation succeeded. Publish this build and
        // remove validator outputs that no longer belong to the source set.
        for (PendingOutput pending : pendingOutputs) {
            BlueprintFileWriter.writeAtomically(
                    outDir.toPath().resolve(pending.validatorName() + ".json"),
                    pending.outputJson());
            if (pending.sourceMapJson() != null) {
                BlueprintFileWriter.writeAtomically(
                        outDir.toPath().resolve(pending.validatorName() + ".sourcemap.json"),
                        pending.sourceMapJson());
            }
            getLogger().lifecycle("Compiled {} → {}.json (target: {}, optimization: {}, hash: {}, size: {})",
                    pending.sourceFileName(), pending.validatorName(),
                    compilerTarget.profileId(), options.getOptimizationLevel().profileId(),
                    pending.scriptHash(), pending.size());
        }
        cleanupStaleOutputs(outDir, pendingOutputs);

        if (blueprintEnabled && blueprintJson != null) {
            BlueprintFileWriter.writeAtomically(
                    outDir.toPath().resolve("plutus.json"), blueprintJson);
            getLogger().lifecycle("Generated CIP-57 blueprint: plutus.json ({} validator(s))",
                    compiledList.size());
        } else if (!blueprintEnabled || compiled == 0) {
            Files.deleteIfExists(outDir.toPath().resolve("plutus.json"));
        }

        if (compiled == 0) {
            getLogger().lifecycle("No validators found in {}", srcDir);
        } else if (!libraryFiles.isEmpty()) {
            getLogger().lifecycle("Included {} library file(s) in compilation",
                    libraryFiles.size());
        }
    }

    private List<File> findJavaFiles(File dir) {
        List<File> files = new ArrayList<>();
        if (!dir.exists()) return files;
        collectJavaFiles(dir, files);
        return files;
    }

    private void collectJavaFiles(File dir, List<File> result) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                collectJavaFiles(child, result);
            } else if (child.getName().endsWith(".java")) {
                result.add(child);
            }
        }
    }

    private record ValidatorFile(File file, JulcCompiler.ScriptPurpose scriptPurpose) {}

    private record PendingOutput(
            String validatorName,
            String sourceFileName,
            String outputJson,
            String sourceMapJson,
            String scriptHash,
            String size) {}

    private void cleanupStaleOutputs(File outDir, List<PendingOutput> pendingOutputs)
            throws IOException {
        Set<String> validatorNames = new HashSet<>();
        Set<String> sourceMapNames = new HashSet<>();
        for (PendingOutput pending : pendingOutputs) {
            validatorNames.add(pending.validatorName());
            if (pending.sourceMapJson() != null) {
                sourceMapNames.add(pending.validatorName());
            }
        }
        try (var files = Files.list(outDir.toPath())) {
            for (var file : files.toList()) {
                String name = file.getFileName().toString();
                if (name.equals("plutus.json")) continue;
                if (name.endsWith(".sourcemap.json")) {
                    String base = name.substring(0, name.length() - ".sourcemap.json".length());
                    if (!sourceMapNames.contains(base)) Files.deleteIfExists(file);
                } else if (name.endsWith(".json")) {
                    String base = name.substring(0, name.length() - ".json".length());
                    if (!validatorNames.contains(base)) Files.deleteIfExists(file);
                }
            }
        }
    }

    private static JavaSourceIntrospector.SourceInfo inspect(File javaFile, String source) {
        try {
            return JavaSourceIntrospector.inspect(source);
        } catch (JavaSourceIntrospector.SourceParseException e) {
            throw new GradleException("Could not parse validator candidate " + javaFile
                    + ": " + e.problems(), e);
        }
    }

    private static void rejectRoleConflicts(JavaSourceIntrospector.SourceInfo sourceInfo) {
        sourceInfo.firstRoleConflict()
                .ifPresent(conflict -> {
                    throw new GradleException(conflict.message());
                });
    }
}
