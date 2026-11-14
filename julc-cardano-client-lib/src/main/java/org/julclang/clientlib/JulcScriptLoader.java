package org.julclang.clientlib;

import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import org.julclang.core.Program;
import org.julclang.core.source.SourceMap;
import org.julclang.core.source.SourceMapSerializer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;
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
 * copy, which validators in different packages share, so the loader takes a simple-name copy only when it records the
 * class, or records none and sits in the jar or directory that holds the class (issue #326, ADR-073). Otherwise it
 * refuses rather than return another validator's script. One case it cannot tell apart: a jar that merges two
 * same-named validators compiled before JuLC 0.1.0-pre21 keeps one simple-name copy, which both classes then read.
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
     *                                  or if no compiled script of the class's own is found (see {@link #loadOutput})
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
     * @throws IllegalArgumentException if the param count doesn't match the validator's requirements, or if no
     *                                  compiled script of the class's own is found (see {@link #loadOutput})
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
     * @throws IllegalArgumentException if the validator is parameterized, or if no compiled script of the class's own
     *                                  is found (see {@link #loadOutput})
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
     * @throws IllegalArgumentException if no compiled script of the class's own is found, or if the class path holds
     *                                  more than one different script for it
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

    /**
     * One copy of a class path resource.
     *
     * @param url      where it is
     * @param content  its text
     * @param recorded the class it records, or "" when it records none (written before issue #326) or is unreadable
     */
    private record Copy(URL url, String content, String recorded) {
        /** Whether this copy is {@code path} under the class path root {@code root} (a decoded URL). */
        boolean isUnder(String root, String path) {
            return root != null && decode(url.toString()).equals(root + path);
        }
    }

    /**
     * The class's script (issue #326, ADR-073):
     * <ol>
     *   <li>its qualified copy, when there is one;</li>
     *   <li>else a simple-name copy that records the class;</li>
     *   <li>else a simple-name copy that records no class (written before #326), only when every class path root
     *       (directory or jar) that holds the class's {@code .class} file holds an identical one, and no qualified copy
     *       shows that the class was relocated from another package.</li>
     * </ol>
     * A copy that records another class is never taken, and two different copies at the chosen step are refused.
     * Among identical copies, the one beside the class is preferred, so that its source map is the class's own.
     */
    private static LocatedScript locate(Class<?> validatorClass) {
        ClassLoader loader = classLoader(validatorClass);
        String className = validatorClass.getName();
        String qualifiedPath = ScriptResourcePaths.qualified(className, SCRIPT_SUFFIX);
        String simplePath = ScriptResourcePaths.simpleName(validatorClass.getSimpleName(), SCRIPT_SUFFIX);
        List<String> roots = classRoots(loader, className);
        String root = roots.isEmpty() ? null : roots.getFirst();

        if (!qualifiedPath.equals(simplePath)) {
            List<Copy> qualified = copies(loader, qualifiedPath, className);
            if (!qualified.isEmpty()) {
                requireOneScript(qualified, className, qualifiedPath,
                        "Keep only one build of " + className + " on the class path.");
                Copy copy = preferUnder(qualified, root, qualifiedPath);
                if (!copy.recorded().isEmpty() && !copy.recorded().equals(className)) {
                    throw new IllegalArgumentException(
                            "The compiled Plutus script at " + copy.url() + " was written for " + copy.recorded()
                                    + ", not for " + className + ". If " + className + " was relocated, for example"
                                    + " by a shading plugin, exclude its package from relocation, or recompile it in"
                                    + " its new package.");
                }
                return located(copy, qualifiedPath, className);
            }
        }

        // Every validator writes the simple-name copy, so only one that records this class, or an old one beside it,
        // is this class's own.
        List<Copy> simple = copies(loader, simplePath, className);
        List<Copy> recorded = simple.stream().filter(copy -> copy.recorded().equals(className)).toList();
        if (!recorded.isEmpty()) {
            requireOneScript(recorded, className, simplePath,
                    "Keep only one build of " + className + " on the class path.");
            return located(preferUnder(recorded, root, simplePath), simplePath, className);
        }
        String relocatedFrom = relocatedFrom(loader, className, simple);
        if (relocatedFrom != null) {
            throw new IllegalArgumentException(
                    "No compiled Plutus script for " + className + " is on the class path, but one for "
                            + relocatedFrom + " is: " + className + " looks relocated, for example by a shading"
                            + " plugin. Exclude its package from relocation, or recompile it in its new package.");
        }
        Copy legacy = besideEveryClassFile(simple, roots, simplePath);
        if (legacy != null) {
            return located(legacy, simplePath, className);
        }

        if (simple.isEmpty()) {
            throw new IllegalArgumentException(
                    "No compiled Plutus script found for " + className
                            + ". Ensure the annotation processor ran during compilation. "
                            + "Expected resource: " + qualifiedPath);
        }
        String copies = simple.stream().map(JulcScriptLoader::describe).collect(Collectors.joining(", "));
        throw new IllegalArgumentException(
                "No compiled Plutus script for " + className + " is on the class path (expected resource: "
                        + qualifiedPath + "). The copies of " + simplePath + " that are there are not its own: "
                        + copies + ". A copy that names a class belongs to that class. One that names none was"
                        + " compiled before JuLC 0.1.0-pre21, and is used only from the jar or directory that holds "
                        + className + ". Ensure the annotation processor ran when " + className
                        + " was compiled, and recompile it with JuLC 0.1.0-pre21 or later.");
    }

    /**
     * The copy that records no class and lies beside the class's {@code .class} file in every root that holds it, all
     * of them identical; null if there is none. When the class is on the class path more than once, its loader may have
     * defined it from any of those roots, so each must carry the same script.
     */
    private static Copy besideEveryClassFile(List<Copy> copies, List<String> roots, String path) {
        Copy first = null;
        for (String root : roots) {
            Copy beside = copies.stream()
                    .filter(copy -> copy.recorded().isEmpty() && copy.isUnder(root, path))
                    .findFirst().orElse(null);
            if (beside == null || (first != null && !first.content().equals(beside.content()))) {
                return null;
            }
            if (first == null) first = beside;
        }
        return first;
    }

    /**
     * The original name of a relocated class: the longest proper suffix of {@code className} that still has a package,
     * such as {@code com.b.Vault} for {@code shaded.com.b.Vault}, and names a script on the class path through its
     * qualified copy or a simple-name copy recording it. Null if there is none. A bare simple name is not a candidate:
     * its qualified path is the shared simple-name path.
     */
    private static String relocatedFrom(ClassLoader loader, String className, List<Copy> simple) {
        int last = className.lastIndexOf('.');
        for (int dot = className.indexOf('.'); dot >= 0 && dot < last; dot = className.indexOf('.', dot + 1)) {
            String original = className.substring(dot + 1);
            if (loader.getResource(ScriptResourcePaths.qualified(original, SCRIPT_SUFFIX)) != null
                    || simple.stream().anyMatch(copy -> copy.recorded().equals(original))) {
                return original;
            }
        }
        return null;
    }

    /** Refuses when {@code copies} hold more than one different script. */
    private static void requireOneScript(List<Copy> copies, String className, String path, String fix) {
        var distinct = new LinkedHashMap<String, Copy>();
        for (Copy copy : copies) distinct.putIfAbsent(copy.content(), copy);
        if (distinct.size() > 1) {
            String urls = distinct.values().stream().map(JulcScriptLoader::describe)
                    .collect(Collectors.joining(", "));
            throw new IllegalArgumentException(
                    "Found " + distinct.size() + " different compiled Plutus scripts for " + className + " at "
                            + path + ": " + urls + ". " + fix);
        }
    }

    /** The copy under the class's own root, if any is, else the first. */
    private static Copy preferUnder(List<Copy> copies, String root, String path) {
        for (Copy copy : copies) {
            if (copy.isUnder(root, path)) return copy;
        }
        return copies.getFirst();
    }

    private static LocatedScript located(Copy copy, String path, String className) {
        ValidatorOutput output;
        try {
            output = ValidatorOutput.fromJson(copy.content());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "Could not read the compiled Plutus script for " + className + " at " + copy.url()
                            + ": " + e.getMessage(), e);
        }
        return new LocatedScript(output, path, copy.url());
    }

    /** A copy's location, and the class it records. */
    private static String describe(Copy copy) {
        return copy.url() + (copy.recorded().isEmpty() ? " (names no class)" : " (" + copy.recorded() + ")");
    }

    /** Every copy of {@code path} on the class path, in class path order. */
    private static List<Copy> copies(ClassLoader loader, String path, String className) {
        var copies = new ArrayList<Copy>();
        try {
            for (URL url : Collections.list(loader.getResources(path))) {
                try (InputStream is = open(url)) {
                    String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    copies.add(new Copy(url, content, recordedClass(content)));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read compiled script for " + className, e);
        }
        return copies;
    }

    private static String recordedClass(String content) {
        try {
            return ValidatorOutput.fromJson(content).validator();
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** A multi-release jar's version directory, {@code jar:file:/app.jar!/META-INF/versions/25/}: its root is the jar's. */
    private static final Pattern VERSIONED_ROOT = Pattern.compile("^(.*!/)META-INF/versions/\\d+/$");

    /**
     * The class path roots that hold the class's {@code .class} file, in class path order, such as
     * {@code file:/app/classes/} or {@code jar:file:/app.jar!/}: each URL without the class's own path (and a multi-release
     * jar's version directory), percent-decoded.
     * Empty when the loader does not serve the file.
     */
    private static List<String> classRoots(ClassLoader loader, String className) {
        String classPath = className.replace('.', '/') + ".class";
        var roots = new ArrayList<String>();
        try {
            for (URL url : Collections.list(loader.getResources(classPath))) {
                String text = decode(url.toString());
                if (text.endsWith(classPath)) {
                    roots.add(VERSIONED_ROOT.matcher(text.substring(0, text.length() - classPath.length()))
                            .replaceFirst("$1"));
                }
            }
        } catch (IOException e) {
            return List.of();
        }
        return roots;
    }

    /**
     * A URL's text with its {@code %XX} escapes decoded as UTF-8, so that it compares with a resource name. Unlike
     * {@code URLDecoder}, it keeps {@code +}.
     */
    static String decode(String url) {
        if (url.indexOf('%') < 0) return url;
        var bytes = new ByteArrayOutputStream();
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '%' && i + 2 < url.length() && Character.digit(url.charAt(i + 1), 16) >= 0
                    && Character.digit(url.charAt(i + 2), 16) >= 0) {
                bytes.write(Integer.parseInt(url, i + 1, i + 3, 16));
                i += 2;
            } else {
                int codePoint = url.codePointAt(i);
                bytes.writeBytes(Character.toString(codePoint).getBytes(StandardCharsets.UTF_8));
                i += Character.charCount(codePoint) - 1;
            }
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    /** Opens a resource without the JVM's jar cache, as {@code getResourceAsStream} does: a jar is not left open. */
    private static InputStream open(URL url) throws IOException {
        URLConnection connection = url.openConnection();
        connection.setUseCaches(false);
        return connection.getInputStream();
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
     * @throws IllegalArgumentException if the validator is parameterized, or if no compiled script of the class's own
     *                                  is found (see {@link #loadOutput})
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
                try (InputStream is = open(url)) {
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
