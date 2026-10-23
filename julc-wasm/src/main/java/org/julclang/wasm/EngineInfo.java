package org.julclang.wasm;

import java.util.List;
import java.util.regex.Pattern;

/**
 * What an engine image tells JavaScript about itself (ADR-064). {@link JsApi#install} publishes the API as
 * {@code globalThis[global]}; {@code features()} reports the variant and groups, and {@code version()} the version.
 * JuLC's images use {@code julc}; an image built on this module for another language names its own.
 *
 * @param global  the global the synchronous API is installed as, a JavaScript identifier
 * @param variant the image variant, for example {@code full} or {@code vm}
 * @param version the version {@code version()} reports
 * @param groups  the capability groups {@code features()} advertises; the bundle's {@code engine.json} must list
 *                the same groups, which the JavaScript client checks when it starts
 */
public record EngineInfo(String global, String variant, String version, List<String> groups) {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
    private static final Pattern VARIANT = Pattern.compile("[a-z][a-z0-9-]*");

    public EngineInfo {
        if (global == null || !IDENTIFIER.matcher(global).matches()) {
            throw new IllegalArgumentException("Engine global must be a JavaScript identifier: " + global);
        }
        if (variant == null || !VARIANT.matcher(variant).matches()) {
            throw new IllegalArgumentException("Engine variant must match [a-z][a-z0-9-]*: " + variant);
        }
        if (version == null || version.isBlank()) throw new IllegalArgumentException("Engine version is required");
        groups = List.copyOf(groups);
        if (groups.isEmpty()) throw new IllegalArgumentException("Engine groups must not be empty");
        for (String group : groups) {
            if (!IDENTIFIER.matcher(group).matches()) {
                throw new IllegalArgumentException("Engine group must be a JavaScript identifier: " + group);
            }
        }
    }
}
