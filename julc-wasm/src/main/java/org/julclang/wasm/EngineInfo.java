package org.julclang.wasm;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What an engine image tells JavaScript about itself (ADR-064). {@link JsApi#install} publishes the API as
 * {@code globalThis[global]}; {@code features()} reports the variant and groups, and {@code version()} the version.
 * JuLC's images use {@code julc}; an image built on this module for another language names its own.
 *
 * @param global  the global the synchronous API is installed as, a JavaScript identifier
 * @param variant the image variant: {@code full} and {@code vm} are JuLC's images, another language names its own
 * @param version the version {@code version()} reports
 * @param groups  the capability groups {@code features()} advertises; the bundle's {@code engine.json} must list
 *                the same groups, which the JavaScript client checks when it starts
 */
public record EngineInfo(String global, String variant, String version, List<String> groups) {
    static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
    private static final Pattern VARIANT = Pattern.compile("[a-z][a-z0-9-]*");
    /** Members of the client API objects, and of every JavaScript object, which a group would replace or extend. */
    static final Set<String> RESERVED_GROUPS = Set.of("version", "features", "dispose", "rest", "__schemas",
            "__proto__", "constructor", "hasOwnProperty", "isPrototypeOf", "propertyIsEnumerable", "toString",
            "toLocaleString", "valueOf", "__defineGetter__", "__defineSetter__", "__lookupGetter__",
            "__lookupSetter__");
    /** Globals the runtime, worker and Web Image launcher define. */
    private static final Set<String> RESERVED_GLOBALS = Set.of("JulcRuntime", "JulcRestAdapter", "onJulcReady",
            "onJulcExit", "JULC_WASM_PATH", "GraalVM", "__proto__");

    public EngineInfo {
        if (global == null || !IDENTIFIER.matcher(global).matches() || RESERVED_GLOBALS.contains(global)) {
            throw new IllegalArgumentException("Engine global must be an unreserved JavaScript identifier: " + global);
        }
        if (variant == null || !VARIANT.matcher(variant).matches()) {
            throw new IllegalArgumentException("Engine variant must match [a-z][a-z0-9-]*: " + variant);
        }
        if (version == null || version.isBlank()) throw new IllegalArgumentException("Engine version is required");
        groups = List.copyOf(groups);
        if (groups.isEmpty()) throw new IllegalArgumentException("Engine groups must not be empty");
        for (String group : groups) {
            if (!IDENTIFIER.matcher(group).matches() || RESERVED_GROUPS.contains(group)) {
                throw new IllegalArgumentException(
                        "Engine group must be an unreserved JavaScript identifier: " + group);
            }
        }
    }

    /** Fails unless every operation of {@code registry} is in an advertised group. */
    void requireAdvertises(ApiRegistry registry) {
        for (String group : registry.groups()) {
            if (!groups.contains(group)) {
                throw new IllegalArgumentException("Operations of group " + group + " are registered but the engine "
                        + "does not advertise it: " + groups);
            }
        }
    }
}
