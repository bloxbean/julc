package com.bloxbean.cardano.julc.analyzer.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves input to a CBOR hex string from various sources.
 */
public final class InputReader {

    private InputReader() {}

    /**
     * Read CBOR hex from a file, stripping whitespace and optional 0x prefix.
     */
    public static String readFile(Path path) throws IOException {
        if (!Files.exists(path)) {
            throw new IOException("File not found: " + path);
        }
        var content = Files.readString(path);
        return normalize(content);
    }

    /**
     * Read CBOR hex from a string argument.
     * If the value is "-", reads from stdin.
     */
    public static String readHex(String value) throws IOException {
        if ("-".equals(value)) {
            return readStdin();
        }
        return normalize(value);
    }

    /**
     * Read CBOR hex from stdin until EOF.
     */
    public static String readStdin() throws IOException {
        var sb = new StringBuilder();
        try (var reader = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line.strip());
            }
        }
        return normalize(sb.toString());
    }

    static String normalize(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Input is null");
        }
        var hex = raw.strip();
        if (hex.startsWith("0x") || hex.startsWith("0X")) {
            hex = hex.substring(2);
        }
        if (hex.isEmpty()) {
            throw new IllegalArgumentException("Input is empty");
        }
        // Remove any embedded whitespace
        hex = hex.replaceAll("\\s+", "");
        // Validate hex characters
        if (!hex.matches("[0-9a-fA-F]+")) {
            throw new IllegalArgumentException("Input contains non-hex characters");
        }
        return hex;
    }
}
