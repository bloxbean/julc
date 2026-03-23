package com.bloxbean.cardano.julc.gradle;

import com.bloxbean.cardano.julc.blueprint.BlueprintConfig;
import com.bloxbean.cardano.julc.blueprint.BlueprintGenerator;
import com.bloxbean.cardano.julc.clientlib.JulcScriptAdapter;
import com.bloxbean.cardano.julc.compiler.CompilerOptions;
import com.bloxbean.cardano.julc.compiler.JulcCompiler;
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
import java.util.List;

/**
 * Gradle task that compiles Plutus validator Java source files to UPLC scripts.
 * <p>
 * For each {@code .java} file in {@code sourceDir} annotated with {@code @Validator} or
 * {@code @MintingPolicy}, produces a JSON file in {@code outputDir} containing the
 * compiled CBOR hex and script hash.
 * <p>
 * Non-validator {@code .java} files (including those annotated with {@code @OnchainLibrary})
 * are automatically treated as library sources and compiled alongside each validator.
 */
public abstract class CompileJulcTask extends DefaultTask {

    @InputDirectory
    @SkipWhenEmpty
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getSourceDir();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    @Input
    @Optional
    public abstract Property<Boolean> getSourceMap();

    @TaskAction
    public void compile() throws IOException {
        File srcDir = getSourceDir().get().getAsFile();
        File outDir = getOutputDir().get().getAsFile();
        outDir.mkdirs();

        var stdlib = StdlibRegistry.defaultRegistry();
        var options = new CompilerOptions();
        if (Boolean.TRUE.equals(getSourceMap().getOrElse(false))) {
            options.setSourceMapEnabled(true);
        }
        var compiler = new JulcCompiler(stdlib::lookup, options);

        List<File> javaFiles = findJavaFiles(srcDir);

        // Separate validators from library files
        var validatorFiles = new ArrayList<File>();
        var libraryFiles = new ArrayList<File>();

        for (File javaFile : javaFiles) {
            String source = Files.readString(javaFile.toPath());
            if (isValidatorSource(source)) {
                validatorFiles.add(javaFile);
            } else {
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

        for (File validatorFile : validatorFiles) {
            String validatorSource = Files.readString(validatorFile.toPath());

            // Compile with multi-file support
            var result = compiler.compile(validatorSource, librarySources);
            if (result.hasErrors()) {
                throw new GradleException("Compilation failed for " + validatorFile.getName()
                        + ": " + result.diagnostics());
            }

            // Generate output
            var program = result.program();
            var script = JulcScriptAdapter.fromProgram(program);
            String scriptHash = JulcScriptAdapter.scriptHash(program);
            String validatorName = validatorFile.getName().replace(".java", "");

            String scriptType = resolveScriptType(validatorSource);

            int sizeBytes = result.scriptSizeBytes();
            String sizeStr = result.scriptSizeFormatted();

            var output = new ValidatorOutput(scriptType, validatorName,
                    script.getCborHex(), scriptHash, sizeBytes);

            File outputFile = new File(outDir, validatorName + ".json");
            Files.writeString(outputFile.toPath(), output.toJson());
            getLogger().lifecycle("Compiled {} → {} (hash: {}, size: {})",
                    validatorFile.getName(), outputFile.getName(), scriptHash, sizeStr);

            // Write source map if enabled and available
            if (Boolean.TRUE.equals(getSourceMap().getOrElse(false)) && result.hasSourceMap()) {
                var indexed = result.sourceMap().toIndexed(program.term());
                String sourceMapJson = SourceMapSerializer.toJson(indexed, validatorName);
                File smFile = new File(outDir, validatorName + ".sourcemap.json");
                Files.writeString(smFile.toPath(), sourceMapJson);
                getLogger().lifecycle("Generated source map: {} ({} entries)",
                        smFile.getName(), indexed.size());
            }

            compiledList.add(new BlueprintGenerator.CompiledValidator(
                    validatorName, validatorSource, result));
            compiled++;
        }

        // Generate CIP-57 blueprint
        if (!compiledList.isEmpty()) {
            var config = new BlueprintConfig(
                    getProject().getName(), getProject().getVersion().toString());
            var blueprint = BlueprintGenerator.generate(config, compiledList);
            Files.writeString(new File(outDir, "plutus.json").toPath(), blueprint.toJson());
            getLogger().lifecycle("Generated CIP-57 blueprint: plutus.json ({} validator(s))",
                    compiledList.size());
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

    private static boolean isValidatorSource(String source) {
        return source.contains("@Validator") || source.contains("@MintingPolicy")
                || source.contains("@SpendingValidator") || source.contains("@MintingValidator")
                || source.contains("@WithdrawValidator") || source.contains("@CertifyingValidator")
                || source.contains("@VotingValidator") || source.contains("@ProposingValidator");
    }

    private static String resolveScriptType(String source) {
        if (source.contains("@MintingPolicy") || source.contains("@MintingValidator"))
            return "PlutusScriptV3-Minting";
        if (source.contains("@WithdrawValidator")) return "PlutusScriptV3-Withdraw";
        if (source.contains("@CertifyingValidator")) return "PlutusScriptV3-Certifying";
        if (source.contains("@VotingValidator")) return "PlutusScriptV3-Voting";
        if (source.contains("@ProposingValidator")) return "PlutusScriptV3-Proposing";
        return "PlutusScriptV3";
    }
}
