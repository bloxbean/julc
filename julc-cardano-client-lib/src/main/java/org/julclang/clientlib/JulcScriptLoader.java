package org.julclang.clientlib;

import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import org.julclang.core.Program;
import org.julclang.core.source.SourceMap;
import org.julclang.core.source.SourceMapSerializer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

import static org.julclang.clientlib.ScriptResourcePaths.SCRIPT_SUFFIX;
import static org.julclang.clientlib.ScriptResourcePaths.SOURCE_MAP_SUFFIX;

/**
 * Loads pre-compiled Plutus scripts from the classpath.
 * <p>
 * The annotation processor writes each compiled script during javac to
 * {@code META-INF/plutus/<package path>/<ClassName>.plutus.json}, and to
 * {@code META-INF/plutus/<ClassName>.plutus.json} for older loaders (see {@link ScriptResourcePaths}).
 * This loader reads them at runtime.
 * <p>
 * A class's script is the one under its qualified name. A class compiled before that layout has only the simple-name
 * copy, which validators in different packages share, so the loader uses that copy only when it is the single one
 * on the class path and was not recorded for another class (issue #326). Otherwise it refuses: it never returns
 * another validator's script.
 * <p>
 * Usage:
 * <pre>{@code
 * // Non-parameterized
 * PlutusV3Script script = JulcScriptLoader.load(MyValidator.class);
 *
 * // Parameterized — apply CCL PlutusData params in one call
 * PlutusV3Script script = JulcScriptLoader.load(VestingValidator.class,
 *     BytesPlutusData.of(ownerPkh),
 *     BigIntPlutusData.of(deadline));
 * }</pre>
 */
public final class JulcScriptLoader {

    private JulcScriptLoader() {}

    /**
     * Load a pre-compiled PlutusV3Script from the classpath.
     * <p>
     * For non-parameterized validators, returns the script directly.
     * For parameterized validators, throws with a helpful message listing required params.
     *
     * @param validatorClass the validator class annotated with a supported JuLC validator annotation
     * @return the compiled PlutusV3Script ready for use with cardano-client-lib
     * @throws IllegalArgumentException if the validator is parameterized (use the overload with params)
     *                                  or if no compiled script is found for the class
     */
    public static PlutusV3Script load(Class<?> validatorClass) {
        var output = loadOutput(validatorClass);
        if (output.isParameterized()) {
            var paramDesc = output.paramList().stream()
                    .map(p -> p.name() + " (" + p.type() + ")")
                    .collect(Collectors.joining(", "));
            throw new IllegalArgumentException(
                    validatorClass.getSimpleName() + " is parameterized and requires "
                            + output.paramList().size() + " param(s): " + paramDesc
                            + ". Use load(Class, PlutusData...) to apply parameters.");
        }
        return PlutusV3Script.builder()
                .cborHex(output.cborHex())
                .build();
    }

    /**
     * Load a pre-compiled PlutusV3Script from the classpath, applying the given parameters.
     * <p>
     * Each parameter is converted from cardano-client-lib {@code PlutusData} to plutus-core
     * {@code PlutusData}, then applied via UPLC partial application.
     *
     * @param validatorClass the validator class annotated with a supported JuLC validator annotation
     * @param params         the parameter values to apply (as cardano-client-lib PlutusData)
     * @return the compiled PlutusV3Script with parameters applied
     * @throws IllegalArgumentException if the param count doesn't match the validator's requirements
     */
    public static PlutusV3Script load(Class<?> validatorClass,
            com.bloxbean.cardano.client.plutus.spec.PlutusData... params) {
        if (params.length == 0) {
            return load(validatorClass);
        }
        var output = loadOutput(validatorClass);
        if (!output.isParameterized()) {
            throw new IllegalArgumentException(validatorClass.getSimpleName()
                    + " is not parameterized, but " + params.length + " param(s) were provided");
        }
        if (params.length != output.paramList().size()) {
            throw new IllegalArgumentException(validatorClass.getSimpleName()
                    + " requires " + output.paramList().size() + " param(s), but "
                    + params.length + " were provided");
        }
        // Convert CCL PlutusData → plutus-core PlutusData
        var coreParams = new org.julclang.core.PlutusData[params.length];
        for (int i = 0; i < params.length; i++) {
            coreParams[i] = PlutusDataAdapter.fromClientLib(params[i]);
        }
        // Decode → apply → re-encode
        var program = JulcScriptAdapter.toProgram(output.cborHex());
        var concrete = program.applyParams(coreParams);
        return JulcScriptAdapter.fromProgram(concrete);
    }

    /**
     * Get the script hash of a pre-compiled validator.
     *
     * @param validatorClass the validator class annotated with a supported JuLC validator annotation
     * @return the script hash as hex string (28 bytes / 56 hex chars)
     * @throws IllegalArgumentException if the validator is parameterized or no compiled script is found
     */
    public static String scriptHash(Class<?> validatorClass) {
        var output = loadOutput(validatorClass);
        if (output.isParameterized()) {
            throw new IllegalArgumentException(validatorClass.getSimpleName()
                    + " is parameterized. Use scriptHash(Class, PlutusData...) to get the hash.");
        }
        return output.hash();
    }

    /**
     * Get the script hash of a pre-compiled parameterized validator with parameters applied.
     *
     * @param validatorClass the validator class annotated with a supported JuLC validator annotation
     * @param params         the parameter values to apply (as cardano-client-lib PlutusData)
     * @return the script hash as hex string (28 bytes / 56 hex chars)
     */
    public static String scriptHash(Class<?> validatorClass,
            com.bloxbean.cardano.client.plutus.spec.PlutusData... params) {
        var script = load(validatorClass, params);
        try {
            var hashBytes = script.getScriptHash();
            var sb = new StringBuilder(hashBytes.length * 2);
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute script hash", e);
        }
    }

    /**
     * Load the full ValidatorOutput metadata for a pre-compiled validator.
     *
     * @param validatorClass the validator class annotated with a supported JuLC validator annotation
     * @return the ValidatorOutput containing type, description, cborHex, hash, and params
     * @throws IllegalArgumentException if no compiled script is found for the class
     */
    public static ValidatorOutput loadOutput(Class<?> validatorClass) {
        return locate(validatorClass).output();
    }

    /**
     * A validator's compiled script, and where it was found.
     *
     * @param output       the script's metadata
     * @param resourcePath the class path resource it was read from
     * @param url          the copy of that resource it was read from
     */
    private record LocatedScript(ValidatorOutput output, String resourcePath, URL url) {}

    /** One copy of a class path resource. */
    private record Copy(URL url, String content) {}

    /**
     * The class's script: the qualified copy when there is one, else the simple-name copy. Refuses when the chosen
     * path holds more than one different copy, or when the copy records another class.
     */
    private static LocatedScript locate(Class<?> validatorClass) {
        ClassLoader loader = classLoader(validatorClass);
        String className = validatorClass.getName();
        String qualifiedPath = ScriptResourcePaths.qualified(className, SCRIPT_SUFFIX);
        String simplePath = ScriptResourcePaths.simpleName(validatorClass.getSimpleName(), SCRIPT_SUFFIX);

        String path = qualifiedPath;
        List<Copy> copies = distinctCopies(loader, qualifiedPath, className);
        if (copies.isEmpty() && !simplePath.equals(qualifiedPath)) {
            path = simplePath;
            copies = distinctCopies(loader, simplePath, className);
        }
        if (copies.isEmpty()) {
            throw new IllegalArgumentException(
                    "No compiled Plutus script found for " + className
                            + ". Ensure the annotation processor ran during compilation. "
                            + "Expected resource: " + qualifiedPath);
        }
        if (copies.size() > 1) {
            String urls = copies.stream().map(JulcScriptLoader::describe).collect(Collectors.joining(", "));
            String fix = path.equals(simplePath)
                    ? " Validators in different packages with the simple name " + validatorClass.getSimpleName()
                    + " share this resource. Recompile them with this version of JuLC, which also writes "
                    + qualifiedPath + ", or keep only one of them on the class path."
                    : " Keep only one copy of " + className + " on the class path.";
            throw new IllegalArgumentException(
                    "Found " + copies.size() + " different compiled Plutus scripts for " + className + " at "
                            + path + ": " + urls + "." + fix);
        }
        Copy copy = copies.getFirst();
        ValidatorOutput output = parse(copy, className);
        if (!output.validator().isEmpty() && !output.validator().equals(className)) {
            throw new IllegalArgumentException(
                    "The compiled Plutus script at " + copy.url() + " belongs to " + output.validator()
                            + ", not to " + className + ". No script for " + className
                            + " is on the class path: ensure the annotation processor ran when it was compiled. "
                            + "Expected resource: " + qualifiedPath);
        }
        return new LocatedScript(output, path, copy.url());
    }

    private static ValidatorOutput parse(Copy copy, String className) {
        try {
            return ValidatorOutput.fromJson(copy.content());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "Could not read the compiled Plutus script for " + className + " at " + copy.url()
                            + ": " + e.getMessage(), e);
        }
    }

    /** A copy's location, and the class it records when it records one. */
    private static String describe(Copy copy) {
        String recorded;
        try {
            recorded = ValidatorOutput.fromJson(copy.content()).validator();
        } catch (RuntimeException e) {
            recorded = "";
        }
        return recorded.isEmpty() ? copy.url().toString() : copy.url() + " (" + recorded + ")";
    }

    /** The copies of {@code path} on the class path, in class path order, one per distinct content. */
    private static List<Copy> distinctCopies(ClassLoader loader, String path, String className) {
        var byContent = new LinkedHashMap<String, URL>();
        try {
            for (URL url : Collections.list(loader.getResources(path))) {
                try (InputStream is = url.openStream()) {
                    byContent.putIfAbsent(new String(is.readAllBytes(), StandardCharsets.UTF_8), url);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read compiled script for " + className, e);
        }
        var copies = new ArrayList<Copy>();
        byContent.forEach((content, url) -> copies.add(new Copy(url, content)));
        return copies;
    }

    private static ClassLoader classLoader(Class<?> validatorClass) {
        ClassLoader loader = validatorClass.getClassLoader();
        return loader != null ? loader : ClassLoader.getSystemClassLoader();
    }

    // ---- Source map support ----

    /**
     * Result of loading a script with its associated source map and program.
     *
     * @param script    the PlutusV3Script ready for use with cardano-client-lib
     * @param sourceMap the source map for debugging (null if not available)
     * @param program   the deserialized UPLC program (needed for Term identity)
     */
    public record LoadResult(PlutusV3Script script, SourceMap sourceMap, Program program) {}

    /**
     * Load a pre-compiled PlutusV3Script with its source map from the classpath.
     * <p>
     * If a source map file ({@code .sourcemap.json}) exists alongside the script,
     * it is loaded and reconstructed against the deserialized term tree, preserving
     * Term object identity for runtime lookups.
     *
     * @param validatorClass the validator class annotated with a supported JuLC validator annotation
     * @return LoadResult containing script, source map (may be null), and program
     * @throws IllegalArgumentException if the validator is parameterized or no compiled script is found
     */
    public static LoadResult loadWithSourceMap(Class<?> validatorClass) {
        var located = locate(validatorClass);
        var output = located.output();
        if (output.isParameterized()) {
            var paramDesc = output.paramList().stream()
                    .map(p -> p.name() + " (" + p.type() + ")")
                    .collect(Collectors.joining(", "));
            throw new IllegalArgumentException(
                    validatorClass.getSimpleName() + " is parameterized and requires "
                            + output.paramList().size() + " param(s): " + paramDesc
                            + ". Use loadWithSourceMap(Class, PlutusData...) to apply parameters.");
        }

        var program = JulcScriptAdapter.toProgram(output.cborHex());
        SourceMap sourceMap = loadSourceMapFromClasspath(validatorClass, located, program);
        PlutusV3Script script = JulcScriptAdapter.fromProgram(program);

        return new LoadResult(script, sourceMap, program);
    }

    /**
     * Load a pre-compiled PlutusV3Script with its source map, applying the given parameters.
     * <p>
     * The source map is reconstructed from the base (pre-param) program term. Inner terms
     * preserve object identity through {@code applyParams()}, which only wraps with new
     * outer Apply nodes.
     *
     * @param validatorClass the validator class annotated with a supported JuLC validator annotation
     * @param params         the parameter values to apply (as cardano-client-lib PlutusData)
     * @return LoadResult containing parameterized script, source map (may be null), and program
     */
    public static LoadResult loadWithSourceMap(Class<?> validatorClass,
            com.bloxbean.cardano.client.plutus.spec.PlutusData... params) {
        if (params.length == 0) {
            return loadWithSourceMap(validatorClass);
        }
        var located = locate(validatorClass);
        var output = located.output();
        if (!output.isParameterized()) {
            throw new IllegalArgumentException(validatorClass.getSimpleName()
                    + " is not parameterized, but " + params.length + " param(s) were provided");
        }
        if (params.length != output.paramList().size()) {
            throw new IllegalArgumentException(validatorClass.getSimpleName()
                    + " requires " + output.paramList().size() + " param(s), but "
                    + params.length + " were provided");
        }

        // Decode base program and reconstruct source map from it
        var baseProgram = JulcScriptAdapter.toProgram(output.cborHex());
        SourceMap sourceMap = loadSourceMapFromClasspath(validatorClass, located, baseProgram);

        // Apply params — inner terms keep identity
        var coreParams = new org.julclang.core.PlutusData[params.length];
        for (int i = 0; i < params.length; i++) {
            coreParams[i] = PlutusDataAdapter.fromClientLib(params[i]);
        }
        var paramApplied = baseProgram.applyParams(coreParams);
        PlutusV3Script script = JulcScriptAdapter.fromProgram(paramApplied);

        return new LoadResult(script, sourceMap, paramApplied);
    }

    /**
     * Load just the source map for a validator (for cases where the script is loaded separately).
     *
     * @param validatorClass the validator class
     * @return the reconstructed SourceMap, or null if no source map file exists
     */
    public static SourceMap loadSourceMap(Class<?> validatorClass) {
        var located = locate(validatorClass);
        var program = JulcScriptAdapter.toProgram(located.output().cborHex());
        return loadSourceMapFromClasspath(validatorClass, located, program);
    }

    /**
     * The source map written beside the script that was loaded, reconstructed against its program: only that copy's
     * own {@code .sourcemap.json}, never another class's. Returns null if it has none.
     */
    private static SourceMap loadSourceMapFromClasspath(Class<?> validatorClass, LocatedScript located,
                                                        Program program) {
        String scriptPath = located.resourcePath();
        String scriptUrl = located.url().toString();
        if (!scriptPath.endsWith(SCRIPT_SUFFIX) || !scriptUrl.endsWith(SCRIPT_SUFFIX)) {
            return null;
        }
        String smPath = scriptPath.substring(0, scriptPath.length() - SCRIPT_SUFFIX.length()) + SOURCE_MAP_SUFFIX;
        String smUrl = scriptUrl.substring(0, scriptUrl.length() - SCRIPT_SUFFIX.length()) + SOURCE_MAP_SUFFIX;
        try {
            for (URL url : Collections.list(classLoader(validatorClass).getResources(smPath))) {
                if (!url.toString().equals(smUrl)) {
                    continue;
                }
                try (InputStream is = url.openStream()) {
                    String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    var indexed = SourceMapSerializer.fromJson(json);
                    return SourceMap.reconstruct(indexed.entries(), program.term());
                }
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }
}
