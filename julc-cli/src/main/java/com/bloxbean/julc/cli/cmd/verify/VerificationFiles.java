package com.bloxbean.julc.cli.cmd.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

final class VerificationFiles {

    static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final Pattern ADMISSION = Pattern.compile(
            "(?<![A-Za-z0-9_'.])(sorry|admit|axiom|unsafe|partial)(?![A-Za-z0-9_'.])");

    private VerificationFiles() {
    }

    static Path containedRegularFile(Path workspace, String relative, boolean executable)
            throws IOException {
        Path candidate = containedPath(workspace, relative);
        Path root = workspace.toRealPath();
        if (!Files.isRegularFile(candidate) || Files.isSymbolicLink(candidate)) {
            throw new IOException("Workspace file is missing, non-regular, or a symlink: " + relative);
        }
        Path real = candidate.toRealPath();
        if (!real.startsWith(root)) {
            throw new IOException("Workspace file resolves outside the workspace: " + relative);
        }
        if (executable && !Files.isExecutable(real)) {
            throw new IOException("Workspace command is not executable: " + relative);
        }
        return real;
    }

    static Path containedPath(Path workspace, String relative) throws IOException {
        if (relative == null || relative.isBlank()) {
            throw new IOException("Workspace path must not be empty");
        }
        Path raw = Path.of(relative);
        if (raw.isAbsolute()) {
            throw new IOException("Workspace path must be relative: " + relative);
        }
        Path root = workspace.toRealPath();
        Path candidate = root.resolve(raw).normalize();
        if (!candidate.startsWith(root)) {
            throw new IOException("Workspace path escapes the workspace: " + relative);
        }
        Path current = root;
        for (Path part : root.relativize(candidate)) {
            current = current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && Files.isSymbolicLink(current)) {
                throw new IOException("Workspace path traverses a symlink: " + relative);
            }
        }
        return candidate;
    }

    static List<String> findAdmissions(Path workspace) throws IOException {
        return projectLeanFiles(workspace).stream()
                .flatMap(path -> matchingLines(workspace, path).stream())
                .toList();
    }

    static List<Path> projectLeanFiles(Path workspace) throws IOException {
        Path root = workspace.toRealPath();
        List<Path> leanFiles;
        try (var paths = Files.walk(root)) {
            leanFiles = paths
                    .filter(path -> path.getFileName().toString().endsWith(".lean"))
                    .filter(path -> !containsSegment(root.relativize(path), ".lake"))
                    .filter(path -> !containsSegment(root.relativize(path),
                            "verification-results"))
                    .sorted()
                    .toList();
        }
        for (Path path : leanFiles) {
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path)
                    || !path.toRealPath().startsWith(root)) {
                throw new IOException("Project Lean source is not a contained regular file: "
                        + root.relativize(path));
            }
        }
        return leanFiles;
    }

    private static boolean containsSegment(Path path, String segment) {
        for (Path item : path) {
            if (item.toString().equals(segment)) return true;
        }
        return false;
    }

    private static List<String> matchingLines(Path workspace, Path path) {
        try {
            var result = new java.util.ArrayList<String>();
            String source = Files.readString(path, StandardCharsets.UTF_8);
            String[] lines = leanCodeOnly(source).split("\\R", -1);
            for (int i = 0; i < lines.length; i++) {
                if (ADMISSION.matcher(lines[i]).find()) {
                    result.add(workspace.relativize(path) + ":" + (i + 1));
                }
            }
            return result;
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /**
     * Replaces Lean comments, strings, and character literals with spaces while preserving
     * newlines. Lean block comments nest, so a regular expression is not sufficient here.
     * The result is used only to find forbidden declaration/tactic tokens; it is not a parser.
     */
    static String leanCodeOnly(String source) {
        var result = new StringBuilder(source.length());
        int blockDepth = 0;
        boolean lineComment = false;
        boolean string = false;
        boolean character = false;
        boolean escaped = false;

        for (int i = 0; i < source.length(); i++) {
            char current = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';

            if (current == '\n' || current == '\r') {
                result.append(current);
                lineComment = false;
                if (string || character) escaped = false;
                continue;
            }
            if (lineComment) {
                result.append(' ');
                continue;
            }
            if (blockDepth > 0) {
                if (current == '/' && next == '-') {
                    blockDepth++;
                    result.append("  ");
                    i++;
                } else if (current == '-' && next == '/') {
                    blockDepth--;
                    result.append("  ");
                    i++;
                } else {
                    result.append(' ');
                }
                continue;
            }
            if (string || character) {
                result.append(' ');
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if ((string && current == '"') || (character && current == '\'')) {
                    string = false;
                    character = false;
                }
                continue;
            }
            if (current == '-' && next == '-') {
                lineComment = true;
                result.append("  ");
                i++;
            } else if (current == '/' && next == '-') {
                blockDepth = 1;
                result.append("  ");
                i++;
            } else if (current == '"') {
                string = true;
                result.append(' ');
            } else if (current == '\'' && looksLikeCharacterLiteral(source, i)) {
                character = true;
                result.append(' ');
            } else {
                result.append(current);
            }
        }
        return result.toString();
    }

    private static boolean looksLikeCharacterLiteral(String source, int quote) {
        if (quote + 2 >= source.length()) return false;
        int closing = source.charAt(quote + 1) == '\\' ? quote + 3 : quote + 2;
        return closing < source.length() && source.charAt(closing) == '\'';
    }

    static String sha256(Path path) throws IOException {
        return sha256(Files.readAllBytes(path));
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    static void writeJsonAtomically(Path target, Object value) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(value);
        writeAtomically(target, bytes);
    }

    static void writeAtomically(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.toAbsolutePath().normalize().getParent());
        Path temp = Files.createTempFile(target.getParent(), "." + target.getFileName(), ".tmp");
        try {
            Files.write(temp, bytes);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
