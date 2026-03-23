package com.bloxbean.cardano.julc.clientlib;

import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.source.SourceMap;
import com.bloxbean.cardano.julc.core.source.SourceMapSerializer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * Loads pre-compiled Plutus scripts from the classpath.
 * <p>
 * The annotation processor writes compiled scripts to
 * {@code META-INF/plutus/<ClassName>.plutus.json} during javac.
 * This loader reads them at runtime.
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
     * @param validatorClass the validator class annotated with @Validator or @MintingPolicy
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
     * @param validatorClass the validator class annotated with @Validator or @MintingPolicy
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
        var coreParams = new com.bloxbean.cardano.julc.core.PlutusData[params.length];
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
     * @param validatorClass the validator class annotated with @Validator or @MintingPolicy
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
     * @param validatorClass the validator class annotated with @Validator or @MintingPolicy
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
     * @param validatorClass the validator class annotated with @Validator or @MintingPolicy
     * @return the ValidatorOutput containing type, description, cborHex, hash, and params
     * @throws IllegalArgumentException if no compiled script is found for the class
     */
    public static ValidatorOutput loadOutput(Class<?> validatorClass) {
        String resourcePath = "META-INF/plutus/" + validatorClass.getSimpleName() + ".plutus.json";
        try (InputStream is = validatorClass.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IllegalArgumentException(
                        "No compiled Plutus script found for " + validatorClass.getName()
                                + ". Ensure the annotation processor ran during compilation. "
                                + "Expected resource: " + resourcePath);
            }
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return ValidatorOutput.fromJson(json);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read compiled script for " + validatorClass.getName(), e);
        }
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
     * @param validatorClass the validator class annotated with @Validator or @MintingPolicy
     * @return LoadResult containing script, source map (may be null), and program
     * @throws IllegalArgumentException if the validator is parameterized or no compiled script is found
     */
    public static LoadResult loadWithSourceMap(Class<?> validatorClass) {
        var output = loadOutput(validatorClass);
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
        SourceMap sourceMap = loadSourceMapFromClasspath(validatorClass, program);
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
     * @param validatorClass the validator class annotated with @Validator or @MintingPolicy
     * @param params         the parameter values to apply (as cardano-client-lib PlutusData)
     * @return LoadResult containing parameterized script, source map (may be null), and program
     */
    public static LoadResult loadWithSourceMap(Class<?> validatorClass,
            com.bloxbean.cardano.client.plutus.spec.PlutusData... params) {
        if (params.length == 0) {
            return loadWithSourceMap(validatorClass);
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

        // Decode base program and reconstruct source map from it
        var baseProgram = JulcScriptAdapter.toProgram(output.cborHex());
        SourceMap sourceMap = loadSourceMapFromClasspath(validatorClass, baseProgram);

        // Apply params — inner terms keep identity
        var coreParams = new com.bloxbean.cardano.julc.core.PlutusData[params.length];
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
        var output = loadOutput(validatorClass);
        var program = JulcScriptAdapter.toProgram(output.cborHex());
        return loadSourceMapFromClasspath(validatorClass, program);
    }

    /**
     * Try to load a .sourcemap.json from classpath and reconstruct the SourceMap.
     * Returns null if no sourcemap file exists.
     */
    private static SourceMap loadSourceMapFromClasspath(Class<?> validatorClass, Program program) {
        String smPath = "META-INF/plutus/" + validatorClass.getSimpleName() + ".sourcemap.json";
        try (InputStream is = validatorClass.getClassLoader().getResourceAsStream(smPath)) {
            if (is == null) {
                return null;
            }
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            var indexed = SourceMapSerializer.fromJson(json);
            return SourceMap.reconstruct(indexed.entries(), program.term());
        } catch (IOException e) {
            return null;
        }
    }
}
