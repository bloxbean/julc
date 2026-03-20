package com.bloxbean.cardano.julc.blueprint;

/**
 * Shared version constant loaded from {@code julc-version.properties} on the classpath.
 */
public final class JulcVersion {

    public static final String VERSION;

    static {
        String v = "dev";
        try (var is = JulcVersion.class.getClassLoader()
                .getResourceAsStream("julc-version.properties")) {
            if (is != null) {
                var props = new java.util.Properties();
                props.load(is);
                v = props.getProperty("version", "dev");
            }
        } catch (Exception _) {
            // fallback to "dev"
        }
        VERSION = v;
    }

    private JulcVersion() {}
}
