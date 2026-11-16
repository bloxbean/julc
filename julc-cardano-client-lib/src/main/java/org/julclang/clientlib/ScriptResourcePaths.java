package org.julclang.clientlib;

import java.util.List;

/**
 * Where JuLC's annotation processor writes a validator's compiled script and source map, and where
 * {@link JulcScriptLoader} reads them (issue #326).
 * <p>
 * Each file is written twice:
 * <ul>
 *   <li>under the validator's binary name, {@code META-INF/plutus/com/b/VaultValidator.plutus.json}, which no other
 *       class in a package shares, and</li>
 *   <li>under its simple name, {@code META-INF/plutus/VaultValidator.plutus.json}, for loaders older than this
 *       layout. Validators in different packages can share this name.</li>
 * </ul>
 * For a class in the unnamed package the two paths are the same, and the file is written once.
 */
public final class ScriptResourcePaths {

    /** The directory, relative to the class path root, that holds the compiled scripts. */
    public static final String DIRECTORY = "META-INF/plutus/";

    /** The suffix of a compiled script. */
    public static final String SCRIPT_SUFFIX = ".plutus.json";

    /** The suffix of a script's source map. */
    public static final String SOURCE_MAP_SUFFIX = ".sourcemap.json";

    private ScriptResourcePaths() {}

    /**
     * The path of a validator's file under its binary name.
     *
     * @param binaryName the validator's binary name, as {@link Class#getName()} gives it, such as
     *                   {@code com.b.VaultValidator} or {@code com.b.Outer$VaultValidator}
     * @param suffix     {@link #SCRIPT_SUFFIX} or {@link #SOURCE_MAP_SUFFIX}
     * @return the path, such as {@code META-INF/plutus/com/b/VaultValidator.plutus.json}
     */
    public static String qualified(String binaryName, String suffix) {
        return DIRECTORY + binaryName.replace('.', '/') + suffix;
    }

    /**
     * The path of a validator's file under its simple name, as JuLC wrote it before issue #326.
     *
     * @param simpleName the validator's simple name, as {@link Class#getSimpleName()} gives it
     * @param suffix     {@link #SCRIPT_SUFFIX} or {@link #SOURCE_MAP_SUFFIX}
     * @return the path, such as {@code META-INF/plutus/VaultValidator.plutus.json}
     */
    public static String simpleName(String simpleName, String suffix) {
        return DIRECTORY + simpleName + suffix;
    }

    /**
     * The paths a validator's file is written to: the qualified path first, then the simple-name path when it differs.
     *
     * @param binaryName the validator's binary name
     * @param simpleName the validator's simple name
     * @param suffix     {@link #SCRIPT_SUFFIX} or {@link #SOURCE_MAP_SUFFIX}
     * @return one or two distinct paths
     */
    public static List<String> written(String binaryName, String simpleName, String suffix) {
        String qualified = qualified(binaryName, suffix);
        String simple = simpleName(simpleName, suffix);
        return qualified.equals(simple) ? List.of(qualified) : List.of(qualified, simple);
    }
}
