package com.bloxbean.julc.cli.cmd.blueprint;

import com.bloxbean.cardano.julc.clientlib.JulcScriptAdapter;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.vm.LedgerEvaluationTarget;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;
import com.bloxbean.julc.cli.project.ProjectLayout;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;

/** Export and independently validate one exact blueprint artifact. */
@Command(name = "artifact",
        description = "Validate and export one blueprint validator artifact")
public class ArtifactCommand implements Callable<Integer> {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @Parameters(index = "0", defaultValue = ".", description = "Project directory")
    private Path projectDir;

    @Option(names = "--validator", required = true,
            description = "Exact validator title from plutus.json")
    private String validatorTitle;

    @Option(names = "--out-dir",
            description = "Write compiledCode hex and metadata JSON to this directory")
    private Path outputDir;

    @Override
    public Integer call() {
        try {
            Path root = projectDir.toAbsolutePath().normalize();
            Path blueprintFile = ProjectLayout.plutusDir(root).resolve("plutus.json");
            ArtifactMetadata metadata = inspect(blueprintFile, validatorTitle);
            String metadataJson = JSON.writeValueAsString(metadata) + System.lineSeparator();

            if (outputDir == null) {
                System.out.print(metadataJson);
            } else {
                Path out = outputDir.toAbsolutePath().normalize();
                Files.createDirectories(out);
                Files.writeString(out.resolve(metadata.artifactId() + ".compiledCode.hex"),
                        metadata.compiledCode() + System.lineSeparator());
                Files.writeString(out.resolve(metadata.artifactId() + ".metadata.json"),
                        metadataJson);
                System.out.println("Exported " + metadata.title() + " to " + out);
            }
            return 0;
        } catch (Exception e) {
            System.err.println("Artifact validation failed: " + e.getMessage());
            return 1;
        }
    }

    static ArtifactMetadata inspect(Path blueprintFile, String title) throws Exception {
        if (!Files.isRegularFile(blueprintFile)) {
            throw new IllegalArgumentException("Blueprint not found: " + blueprintFile);
        }
        JsonNode root = JSON.readTree(blueprintFile.toFile());
        JsonNode validators = root.path("validators");
        if (!validators.isArray()) {
            throw new IllegalArgumentException("Blueprint validators must be an array");
        }

        var matches = new ArrayList<JsonNode>();
        validators.forEach(node -> {
            if (title.equals(node.path("title").asText(null))) {
                matches.add(node);
            }
        });
        if (matches.size() != 1) {
            throw new IllegalArgumentException("Expected exactly one validator titled '"
                    + title + "', found " + matches.size());
        }

        JsonNode entry = matches.getFirst();
        String compiledCode = requiredText(entry, "compiledCode");
        String blueprintHash = requiredText(entry, "hash");
        if ((compiledCode.length() & 1) != 0
                || !compiledCode.matches("[0-9a-fA-F]+")) {
            throw new IllegalArgumentException("compiledCode is not even-length hexadecimal");
        }

        byte[] outerCbor = HexFormat.of().parseHex(compiledCode);
        Program program = JulcScriptAdapter.toProgram(compiledCode,
                LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3));
        String recomputedHash = JulcScriptAdapter.scriptHash(program);
        if (!blueprintHash.equalsIgnoreCase(recomputedHash)) {
            throw new IllegalArgumentException("Script hash mismatch: blueprint="
                    + blueprintHash + ", recomputed=" + recomputedHash);
        }

        List<BuiltinUse> builtins = collectBuiltins(program.term()).stream()
                .sorted(Comparator.comparingInt(fun -> fun.flatCode()))
                .map(fun -> new BuiltinUse(fun.name(), fun.flatCode()))
                .toList();

        return new ArtifactMetadata(
                artifactId(title), title, compiledCode.toLowerCase(),
                sha256Hex(outerCbor), recomputedHash.toLowerCase(),
                program.versionString(), builtins);
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Blueprint validator is missing " + field);
        }
        return value;
    }

    private static List<DefaultFun> collectBuiltins(Term root) {
        var result = new ArrayList<DefaultFun>();
        collectBuiltins(root, result);
        return result.stream().distinct().toList();
    }

    private static void collectBuiltins(Term term, List<DefaultFun> result) {
        switch (term) {
            case Term.Builtin builtin -> result.add(builtin.fun());
            case Term.Lam lam -> collectBuiltins(lam.body(), result);
            case Term.Apply apply -> {
                collectBuiltins(apply.function(), result);
                collectBuiltins(apply.argument(), result);
            }
            case Term.Force force -> collectBuiltins(force.term(), result);
            case Term.Delay delay -> collectBuiltins(delay.term(), result);
            case Term.Constr constr ->
                    constr.fields().forEach(field -> collectBuiltins(field, result));
            case Term.Case caseTerm -> {
                collectBuiltins(caseTerm.scrutinee(), result);
                caseTerm.branches().forEach(branch -> collectBuiltins(branch, result));
            }
            case Term.Var ignored -> { }
            case Term.Const ignored -> { }
            case Term.Error ignored -> { }
        }
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String artifactId(String title) {
        String normalized = title.replaceAll("([a-z0-9])([A-Z])", "$1-$2")
                .replaceAll("[^A-Za-z0-9]+", "-")
                .replaceAll("^-|-$", "")
                .toLowerCase();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Validator title cannot form an artifact id");
        }
        return normalized;
    }

    record BuiltinUse(String name, int flatTag) { }

    record ArtifactMetadata(
            String artifactId,
            String title,
            String compiledCode,
            String compiledCodeSha256,
            String cardanoScriptHash,
            String uplcVersion,
            List<BuiltinUse> builtins) { }
}
