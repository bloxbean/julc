package com.bloxbean.cardano.plutus.processor;

import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Loads pre-compiled Plutus scripts from the classpath.
 * <p>
 * The annotation processor writes compiled scripts to
 * {@code META-INF/plutus/<ClassName>.plutus.json} during javac.
 * This loader reads them at runtime.
 * <p>
 * Usage:
 * <pre>{@code
 * PlutusV3Script script = PlutusScriptLoader.load(MyValidator.class);
 * String hash = PlutusScriptLoader.scriptHash(MyValidator.class);
 * }</pre>
 */
public final class PlutusScriptLoader {

    private PlutusScriptLoader() {}

    /**
     * Load a pre-compiled PlutusV3Script from the classpath.
     *
     * @param validatorClass the validator class annotated with @Validator or @MintingPolicy
     * @return the compiled PlutusV3Script ready for use with cardano-client-lib
     * @throws IllegalArgumentException if no compiled script is found for the class
     */
    public static PlutusV3Script load(Class<?> validatorClass) {
        var output = loadOutput(validatorClass);
        return PlutusV3Script.builder()
                .cborHex(output.cborHex())
                .build();
    }

    /**
     * Get the script hash of a pre-compiled validator.
     *
     * @param validatorClass the validator class annotated with @Validator or @MintingPolicy
     * @return the script hash as hex string (28 bytes / 56 hex chars)
     * @throws IllegalArgumentException if no compiled script is found for the class
     */
    public static String scriptHash(Class<?> validatorClass) {
        return loadOutput(validatorClass).hash();
    }

    /**
     * Load the full ValidatorOutput metadata for a pre-compiled validator.
     *
     * @param validatorClass the validator class annotated with @Validator or @MintingPolicy
     * @return the ValidatorOutput containing type, description, cborHex, and hash
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
}
