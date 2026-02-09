package com.bloxbean.cardano.plutus.gradle;

import com.bloxbean.cardano.plutus.clientlib.PlutusScriptAdapter;
import com.bloxbean.cardano.plutus.compiler.PlutusCompiler;
import com.bloxbean.cardano.plutus.stdlib.StdlibRegistry;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.SkipWhenEmpty;
import org.gradle.api.tasks.TaskAction;

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
public abstract class CompilePlutusTask extends DefaultTask {

    @InputDirectory
    @SkipWhenEmpty
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getSourceDir();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    @TaskAction
    public void compile() throws IOException {
        File srcDir = getSourceDir().get().getAsFile();
        File outDir = getOutputDir().get().getAsFile();
        outDir.mkdirs();

        var stdlib = StdlibRegistry.defaultRegistry();
        var compiler = new PlutusCompiler(stdlib::lookup);

        List<File> javaFiles = findJavaFiles(srcDir);

        // Separate validators from library files
        var validatorFiles = new ArrayList<File>();
        var libraryFiles = new ArrayList<File>();

        for (File javaFile : javaFiles) {
            String source = Files.readString(javaFile.toPath());
            if (source.contains("@Validator") || source.contains("@MintingPolicy")) {
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
            var script = PlutusScriptAdapter.fromProgram(program);
            String scriptHash = PlutusScriptAdapter.scriptHash(program);
            String validatorName = validatorFile.getName().replace(".java", "");

            String scriptType = validatorSource.contains("@MintingPolicy")
                    ? "PlutusScriptV3-Minting" : "PlutusScriptV3";

            var output = new ValidatorOutput(scriptType, validatorName,
                    script.getCborHex(), scriptHash);

            File outputFile = new File(outDir, validatorName + ".json");
            Files.writeString(outputFile.toPath(), output.toJson());
            getLogger().lifecycle("Compiled {} → {} (hash: {})",
                    validatorFile.getName(), outputFile.getName(), scriptHash);
            compiled++;
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
}
