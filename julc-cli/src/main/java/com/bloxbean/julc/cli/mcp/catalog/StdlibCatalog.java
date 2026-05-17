package com.bloxbean.julc.cli.mcp.catalog;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reflection-backed view of the JuLC stdlib API surface.
 *
 * <p>Drives the {@code julc_stdlib_list} and {@code julc_stdlib_method} MCP
 * tools. Method signatures are introspected from the actual runtime classes
 * via {@link Class#getMethods()}, so the catalog is always in sync with
 * what's on the classpath. Javadoc is NOT available at runtime — for rich
 * documentation, agents should fetch
 * <a href="https://julc.dev/ai/catalog.json">/ai/catalog.json</a>, which the
 * docs build generates from source.
 */
public final class StdlibCatalog {

    /** Stdlib library class names — these live in {@code com.bloxbean.cardano.julc.stdlib.lib}. */
    private static final List<String> LIB_CLASSES = List.of(
            "AddressLib", "BitwiseLib", "BlsLib", "ByteStringLib",
            "ContextsLib", "CryptoLib", "IntervalLib", "ListsLib",
            "MapLib", "MathLib", "NativeValueLib", "OutputLib", "ValuesLib"
    );

    private static final String STDLIB_PKG = "com.bloxbean.cardano.julc.stdlib.lib.";
    private static final String BUILTINS_FQCN = "com.bloxbean.cardano.julc.stdlib.Builtins";

    /** Names always excluded — Object inherited members + (de)serialization plumbing. */
    private static final Set<String> SKIP_METHODS = Set.of(
            "equals", "hashCode", "toString", "wait", "notify", "notifyAll", "getClass",
            "toPlutusData", "fromPlutusData"
    );

    /**
     * Builtins-specific exclusions: methods on {@code Builtins} that aren't
     * actual on-chain operations (test/setup plumbing). Phase D review
     * (Codex P2#6) — without this, {@code setCryptoProvider} pollutes the
     * builtins listing while legitimate raw-data builtins like {@code tailList}
     * were being filtered out by the stdlib-helper rule.
     */
    private static final Set<String> SKIP_BUILTINS = Set.of(
            "setCryptoProvider"
    );

    /**
     * Stdlib internal helper methods that are public-static for compilation
     * reasons but are NOT user-facing API. They appear in the source files
     * because the JuLC compiler inlines them, but agents must call the
     * canonical public method (e.g. {@code geqMultiAsset} / {@code flatten})
     * rather than reaching into the helper.
     *
     * <p>Codex review finding 2 — leaking these teaches agents the wrong
     * surface. Naming convention: helper methods are prefixed with
     * {@code _} OR follow one of these explicit names.
     */
    private static final Set<String> SKIP_STDLIB_HELPERS = Set.of(
            // ValuesLib internal flatten/geq helpers
            "checkPolicyGeq", "flattenStep", "flattenPolicy",
            "adjustOuterForAdd", "adjustInnerForAdd",
            "extraOuterEntries", "extraInnerEntries"
    );

    private StdlibCatalog() {}

    /** All known stdlib library names (with their FQCN) for {@code julc_stdlib_list}. */
    public static List<Map<String, Object>> listLibraries() {
        var out = new ArrayList<Map<String, Object>>();
        for (String name : LIB_CLASSES) {
            String fqcn = STDLIB_PKG + name;
            try {
                Class<?> cls = Class.forName(fqcn);
                int methodCount = (int) collectMethods(cls).size();
                out.add(Map.of(
                        "name", name,
                        "fqcn", fqcn,
                        "methodCount", methodCount
                ));
            } catch (ClassNotFoundException e) {
                // Library not on classpath — skip silently.
            }
        }
        return out;
    }

    /**
     * Look up a single library + method (or all methods if {@code method} is null).
     * Returns {@code null} if the library is unknown.
     */
    public static Map<String, Object> describeLibrary(String libName, String methodFilter) {
        String fqcn = STDLIB_PKG + libName;
        Class<?> cls;
        try {
            cls = Class.forName(fqcn);
        } catch (ClassNotFoundException e) {
            return null;
        }
        var methods = collectMethods(cls);
        if (methodFilter != null && !methodFilter.isBlank()) {
            methods = methods.stream()
                    .filter(m -> methodFilter.equals(m.get("name")))
                    .toList();
        }
        return Map.of(
                "name", libName,
                "fqcn", fqcn,
                "methodCount", methods.size(),
                "methods", methods
        );
    }

    /** All Plutus builtins exposed via {@code Builtins} — for {@code julc_builtins_list}. */
    public static List<Map<String, Object>> listBuiltins() {
        try {
            Class<?> cls = Class.forName(BUILTINS_FQCN);
            return collectBuiltinMethods(cls);
        } catch (ClassNotFoundException e) {
            return List.of();
        }
    }

    /**
     * Builtins use a different filter than stdlib lib classes: they
     * legitimately speak in raw {@code PlutusData} and its subtypes (that's
     * the whole point of {@code unBData}, {@code mkCons}, etc.), so we do
     * NOT apply the stdlib internal-helper filter here. Only the
     * {@link #SKIP_BUILTINS} setup-plumbing exclusion list applies.
     */
    private static List<Map<String, Object>> collectBuiltinMethods(Class<?> cls) {
        var out = new ArrayList<Map<String, Object>>();
        for (Method m : cls.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers())) continue;
            if (!Modifier.isStatic(m.getModifiers())) continue;
            if (m.isSynthetic() || m.isBridge()) continue;
            String name = m.getName();
            if (SKIP_METHODS.contains(name)) continue;
            if (SKIP_BUILTINS.contains(name)) continue;
            out.add(renderMethod(m));
        }
        out.sort(Comparator.comparing(map -> (String) map.get("name")));
        return out;
    }

    /**
     * Collect all public-static methods on a class, sorted by name. Skips
     * {@link #SKIP_METHODS} and any inherited methods (we want only the
     * library's own surface).
     */
    private static List<Map<String, Object>> collectMethods(Class<?> cls) {
        var out = new ArrayList<Map<String, Object>>();
        for (Method m : cls.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers())) continue;
            if (!Modifier.isStatic(m.getModifiers())) continue;
            if (m.isSynthetic() || m.isBridge()) continue;
            String name = m.getName();
            if (SKIP_METHODS.contains(name)) continue;
            // Skip internal/raw helper conventions:
            //   * leading underscore (e.g. _assetOf)
            //   * any param or return naming PlutusData$<subtype>
            //   * known stdlib internal helpers (see SKIP_STDLIB_HELPERS)
            if (name.startsWith("_")) continue;
            if (SKIP_STDLIB_HELPERS.contains(name)) continue;
            if (touchesRawPlutusDataSubtype(m)) continue;
            out.add(renderMethod(m));
        }
        out.sort(Comparator.comparing(map -> (String) map.get("name")));
        return out;
    }

    private static boolean touchesRawPlutusDataSubtype(Method m) {
        if (typeNameMentionsRawSubtype(m.getGenericReturnType())) return true;
        for (Type t : m.getGenericParameterTypes()) {
            if (typeNameMentionsRawSubtype(t)) return true;
        }
        return false;
    }

    private static boolean typeNameMentionsRawSubtype(Type t) {
        // PlutusData$ConstrData / IntData / BytesData / MapData / ListData
        String name = t.getTypeName();
        return name.contains("PlutusData$ConstrData")
                || name.contains("PlutusData$IntData")
                || name.contains("PlutusData$BytesData")
                || name.contains("PlutusData$MapData")
                || name.contains("PlutusData$ListData")
                || name.contains("PlutusData.ConstrData")
                || name.contains("PlutusData.IntData")
                || name.contains("PlutusData.BytesData")
                || name.contains("PlutusData.MapData")
                || name.contains("PlutusData.ListData");
    }

    private static Map<String, Object> renderMethod(Method m) {
        var params = new ArrayList<Map<String, Object>>();
        Parameter[] reflected = m.getParameters();
        Type[] generic = m.getGenericParameterTypes();
        for (int i = 0; i < reflected.length; i++) {
            String type = friendlyTypeName(generic[i]);
            String name = reflected[i].isNamePresent() ? reflected[i].getName() : "arg" + i;
            params.add(Map.of("name", name, "type", type));
        }
        String returns = friendlyTypeName(m.getGenericReturnType());
        var sigParams = new StringBuilder();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sigParams.append(", ");
            sigParams.append(params.get(i).get("type")).append(' ').append(params.get(i).get("name"));
        }
        String signature = returns + " " + m.getName() + "(" + sigParams + ")";
        var entry = new LinkedHashMap<String, Object>();
        entry.put("name", m.getName());
        entry.put("signature", signature);
        entry.put("params", params);
        entry.put("returns", returns);
        return entry;
    }

    /**
     * Render a generic type using its short class name where possible, so
     * {@code java.math.BigInteger} prints as {@code BigInteger} and
     * {@code java.util.List<com.bloxbean.cardano.julc.core.PlutusData>}
     * prints as {@code List<PlutusData>}.
     */
    private static String friendlyTypeName(Type t) {
        String full = t.getTypeName();
        // Replace fully-qualified names with simple names.
        return full.replaceAll("(?:[a-zA-Z_][a-zA-Z0-9_]*\\.)+([A-Z][A-Za-z0-9_$]*)", "$1")
                .replace('$', '.');
    }
}
