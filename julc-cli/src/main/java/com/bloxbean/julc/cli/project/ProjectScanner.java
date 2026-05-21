package com.bloxbean.julc.cli.project;

import com.bloxbean.cardano.julc.compiler.JavaSourceIntrospector;
import com.bloxbean.cardano.julc.compiler.LibrarySourceResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Scans project source directories for validator (.java) and JRL (.jrl) files
 * in a single directory walk.
 */
public final class ProjectScanner {

    private ProjectScanner() {}

    public record ScanResult(
            Map<String, String> validators,  // simpleName -> source (.java validators)
            Map<String, String> libraries,   // FQCN -> source (.java libraries)
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
                                if (JavaSourceIntrospector.mightContainValidatorAnnotation(source)) {
                                    var sourceInfo = inspect(p, source);
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
        if (!JavaSourceIntrospector.mightContainValidatorAnnotation(source)) {
            return "PlutusScriptV3";
        }
        var sourceInfo = JavaSourceIntrospector.inspect(source);
        rejectRoleConflicts(sourceInfo);
        if (sourceInfo.legacyValidatorType().isPresent()) {
            throw new IllegalArgumentException(JavaSourceIntrospector.legacyAnnotationMigrationMessage(
                    sourceInfo.legacyValidatorType().get()));
        }
        return sourceInfo.scriptType().orElse("PlutusScriptV3");
    }

    private static void rejectRoleConflicts(JavaSourceIntrospector.SourceInfo sourceInfo) {
        sourceInfo.firstRoleConflict()
                .ifPresent(conflict -> {
                    throw new IllegalArgumentException(conflict.message());
                });
    }

    private static JavaSourceIntrospector.SourceInfo inspect(Path path, String source) {
        try {
            return JavaSourceIntrospector.inspect(source);
        } catch (JavaSourceIntrospector.SourceParseException e) {
            throw new IllegalArgumentException("Could not parse validator candidate "
                    + path + ": " + e.problems(), e);
        }
    }

    private static String libraryKey(String simpleName, String source) {
        String packageName = LibrarySourceResolver.extractPackageName(source);
        String className = LibrarySourceResolver.extractTopLevelTypeName(source).orElse(simpleName);
        return packageName.isBlank() ? className : packageName + "." + className;
    }
}
