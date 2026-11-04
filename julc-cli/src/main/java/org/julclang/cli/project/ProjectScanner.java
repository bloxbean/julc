package org.julclang.cli.project;

import org.julclang.compiler.JavaSourceIntrospector;
import org.julclang.compiler.LibrarySourceResolver;
import org.julclang.compiler.ScriptPurposeMetadata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Scans project source directories for Java validator and library files
 * in a single directory walk.
 */
public final class ProjectScanner {

    private ProjectScanner() {}

    public record ScanResult(
            Map<String, String> validators,  // simpleName -> source (.java validators)
            Map<String, String> libraries,   // FQCN -> source (.java libraries)
            int ignoredFiles                 // .jrl files: JRL support was removed, so they are not compiled
    ) {}

    /**
     * Scan a directory for .java files in a single walk.
     * Java files are split into validators (with annotations) and libraries (without).
     */
    public static ScanResult scan(Path srcDir) throws IOException {
        var validators = new LinkedHashMap<String, String>();
        var libraries = new LinkedHashMap<String, String>();
        var ignored = new int[1];

        if (!Files.isDirectory(srcDir)) {
            return new ScanResult(validators, libraries, 0);
        }

        try (Stream<Path> paths = Files.walk(srcDir)) {
            paths.filter(Files::isRegularFile)
                    .sorted()
                    .forEach(p -> {
                        try {
                            String fileName = p.getFileName().toString();
                            if (fileName.endsWith(".java")) {
                                String source = Files.readString(p);
                                String simpleName = fileName.replace(".java", "");
                                var sourceInfo = JavaSourceIntrospector.mightContainValidatorAnnotation(source)
                                        ? inspect(p, source) : null;
                                if (sourceInfo != null) {
                                    rejectRoleConflicts(sourceInfo);
                                    if (sourceInfo.legacyValidatorType().isPresent()) {
                                        throw new IllegalArgumentException(
                                                JavaSourceIntrospector.legacyAnnotationMigrationMessage(
                                                        sourceInfo.legacyValidatorType().get()));
                                    }
                                    if (sourceInfo.validatorType().isPresent()) {
                                        validators.put(sourceInfo.validatorType().get().simpleName(), source);
                                    } else {
                                        libraries.put(libraryKey(simpleName, source), source);
                                    }
                                } else {
                                    libraries.put(libraryKey(simpleName, source), source);
                                }
                            } else if (fileName.endsWith(".jrl")) {
                                ignored[0]++;
                            }
                        } catch (IOException e) {
                            throw new RuntimeException("Failed to read " + p, e);
                        }
                    });
        }

        return new ScanResult(validators, libraries, ignored[0]);
    }

    /**
     * Determine the script text-envelope type from validator annotations.
     */
    public static String resolveScriptType(String source) {
        if (!JavaSourceIntrospector.mightContainValidatorAnnotation(source)) {
            return ScriptPurposeMetadata.textEnvelopeType();
        }
        var sourceInfo = JavaSourceIntrospector.inspect(source);
        rejectRoleConflicts(sourceInfo);
        if (sourceInfo.legacyValidatorType().isPresent()) {
            throw new IllegalArgumentException(JavaSourceIntrospector.legacyAnnotationMigrationMessage(
                    sourceInfo.legacyValidatorType().get()));
        }
        return sourceInfo.scriptPurpose()
                .map(purpose -> ScriptPurposeMetadata.textEnvelopeType())
                .orElse(ScriptPurposeMetadata.textEnvelopeType());
    }

    private static void rejectRoleConflicts(JavaSourceIntrospector.SourceInfo sourceInfo) {
        sourceInfo.firstRoleConflict()
                .ifPresent(conflict -> {
                    throw new IllegalArgumentException(conflict.message());
                });
    }

    /** A validator candidate JuLC cannot read: its file and text, so that a host can report javac's diagnostic. */
    public static final class UnreadableCandidateException extends IllegalArgumentException {
        private final Path file;
        private final String source;

        UnreadableCandidateException(Path file, String source, JavaSourceIntrospector.SourceParseException cause) {
            super("Could not parse validator candidate " + file + ": " + cause.problems(), cause);
            this.file = file;
            this.source = source;
        }

        public Path file() {
            return file;
        }

        public String source() {
            return source;
        }
    }

    /** The source's info, or null for a candidate JuLC cannot read that plainly declares no JuLC annotation. */
    private static JavaSourceIntrospector.SourceInfo inspect(Path path, String source) {
        try {
            return JavaSourceIntrospector.inspect(source);
        } catch (JavaSourceIntrospector.SourceParseException e) {
            if (JavaSourceIntrospector.declaresNoJulcRole(source)) return null;
            throw new UnreadableCandidateException(path, source, e);
        }
    }

    private static String libraryKey(String simpleName, String source) {
        String packageName = LibrarySourceResolver.extractPackageName(source);
        String className = LibrarySourceResolver.extractTopLevelTypeName(source).orElse(simpleName);
        return packageName.isBlank() ? className : packageName + "." + className;
    }
}
