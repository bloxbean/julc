package com.bloxbean.cardano.julc.gradle;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared utilities for scanning validator and library source files.
 */
final class SourceScanner {

    private SourceScanner() {}

    /** Recursively find all .java files under a directory. */
    static List<File> findJavaFiles(File dir) {
        List<File> files = new ArrayList<>();
        if (!dir.exists()) return files;
        collectJavaFiles(dir, files);
        return files;
    }

    private static void collectJavaFiles(File dir, List<File> result) {
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

    /** Check if source contains a validator annotation. */
    static boolean isValidatorSource(String source) {
        return source.contains("@Validator") || source.contains("@MintingPolicy")
                || source.contains("@SpendingValidator") || source.contains("@MintingValidator")
                || source.contains("@WithdrawValidator") || source.contains("@CertifyingValidator")
                || source.contains("@VotingValidator") || source.contains("@ProposingValidator");
    }

    /** Check if source is an on-chain library (should be compiled alongside validators). */
    static boolean isLibrarySource(String source) {
        return source.contains("@OnchainLibrary");
    }
}
