package com.bloxbean.julc.cli.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Scans project source directories for validator (.java) and JRL (.jrl) files
 * in a single directory walk.
 */
public final class ProjectScanner {

    private static final Pattern VALIDATOR_PATTERN = Pattern.compile(
            "@(Validator|SpendingValidator|MintingPolicy|MintingValidator|" +
            "WithdrawValidator|CertifyingValidator|VotingValidator|ProposingValidator|MultiValidator)");

    private ProjectScanner() {}

    public record ScanResult(
            Map<String, String> validators,  // simpleName -> source (.java validators)
            Map<String, String> libraries,   // simpleName -> source (.java libraries)
            Map<String, String> jrlFiles     // simpleName -> source (.jrl files)
    ) {}

    /**
     * Scan a directory for .java and .jrl files in a single walk.
     * Java files are split into validators (with annotations) and libraries (without).
     */
    public static ScanResult scan(Path srcDir) throws IOException {
        var validators = new LinkedHashMap<String, String>();
        var libraries = new LinkedHashMap<String, String>();
        var jrlFiles = new LinkedHashMap<String, String>();

        if (!Files.isDirectory(srcDir)) {
            return new ScanResult(validators, libraries, jrlFiles);
        }

        try (Stream<Path> paths = Files.walk(srcDir)) {
            paths.filter(Files::isRegularFile)
                    .forEach(p -> {
                        try {
                            String fileName = p.getFileName().toString();
                            if (fileName.endsWith(".java")) {
                                String source = Files.readString(p);
                                String simpleName = fileName.replace(".java", "");
                                if (VALIDATOR_PATTERN.matcher(source).find()) {
                                    validators.put(simpleName, source);
                                } else {
                                    libraries.put(simpleName, source);
                                }
                            } else if (fileName.endsWith(".jrl")) {
                                String source = Files.readString(p);
                                String simpleName = fileName.replace(".jrl", "");
                                jrlFiles.put(simpleName, source);
                            }
                        } catch (IOException e) {
                            throw new RuntimeException("Failed to read " + p, e);
                        }
                    });
        }

        return new ScanResult(validators, libraries, jrlFiles);
    }

    /**
     * Determine the script type from validator annotations.
     */
    public static String resolveScriptType(String source) {
        if (source.contains("@MintingPolicy") || source.contains("@MintingValidator"))
            return "PlutusScriptV3-Minting";
        if (source.contains("@WithdrawValidator")) return "PlutusScriptV3-Withdraw";
        if (source.contains("@CertifyingValidator")) return "PlutusScriptV3-Certifying";
        if (source.contains("@VotingValidator")) return "PlutusScriptV3-Voting";
        if (source.contains("@ProposingValidator")) return "PlutusScriptV3-Proposing";
        return "PlutusScriptV3";
    }
}
