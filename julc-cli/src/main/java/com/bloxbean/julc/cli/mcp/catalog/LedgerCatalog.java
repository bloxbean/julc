package com.bloxbean.julc.cli.mcp.catalog;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reflection-backed view of the JuLC ledger types ({@code com.bloxbean.cardano.julc.ledger}).
 * Returns record fields, sealed-interface variants, and method signatures.
 *
 * <p>For Javadoc-bearing rich descriptions, agents should fetch
 * <a href="https://julc.dev/ai/catalog.json">/ai/catalog.json</a> from the
 * docs build. This catalog gives the agent everything it needs to write
 * type-correct code without making up names.
 */
public final class LedgerCatalog {

    private static final String LEDGER_PKG = "com.bloxbean.cardano.julc.ledger.";

    /**
     * Authoritative list of ledger types. Kept explicit (not a classpath
     * scan) so the catalog is stable: adding a type to the package without
     * also adding it here is a deliberate choice the maintainer makes.
     */
    private static final List<String> TYPES = List.of(
            "Address", "Committee", "Credential", "DRep", "DatumHash",
            "Delegatee", "GovernanceAction", "GovernanceActionId", "Interval",
            "IntervalBound", "IntervalBoundType", "OutputDatum", "PolicyId",
            "ProposalProcedure", "ProtocolVersion", "PubKeyHash", "Rational",
            "ScriptContext", "ScriptHash", "ScriptInfo", "ScriptPurpose",
            "StakingCredential", "TokenName", "TxCert", "TxId", "TxInInfo",
            "TxInfo", "TxOut", "TxOutRef", "ValidatorHash", "Value", "Vote", "Voter"
    );

    private static final Set<String> SKIP_METHODS = Set.of(
            "equals", "hashCode", "toString", "wait", "notify", "notifyAll", "getClass",
            "toPlutusData", "fromPlutusData"
    );

    private LedgerCatalog() {}

    /** Light listing for {@code julc_ledger_list}: name + kind (record/sealed/newtype). */
    public static List<Map<String, Object>> listTypes() {
        var out = new ArrayList<Map<String, Object>>();
        for (String name : TYPES) {
            try {
                Class<?> cls = Class.forName(LEDGER_PKG + name);
                String kind = cls.isSealed() ? "sealed"
                        : cls.isRecord() ? "record"
                        : "interface";
                out.add(Map.of(
                        "name", name,
                        "fqcn", cls.getName(),
                        "kind", kind
                ));
            } catch (ClassNotFoundException e) {
                // Type missing from the classpath — skip silently.
            }
        }
        return out;
    }

    /**
     * Full description of a single ledger type for {@code julc_ledger_type}.
     * Returns {@code null} if the type is unknown.
     *
     * <p>Phase D review (Codex P1#4): only {@link #TYPES} entries are
     * resolvable. Without this guard, {@code Class.forName} would happily
     * load internal helpers like {@code PlutusDataHelper} and present them
     * as legitimate "interface"-kind ledger types — misleading the agent
     * and leaking implementation detail.
     */
    public static Map<String, Object> describeType(String typeName) {
        if (!TYPES.contains(typeName)) {
            return null;
        }
        Class<?> cls;
        try {
            cls = Class.forName(LEDGER_PKG + typeName);
        } catch (ClassNotFoundException e) {
            return null;
        }
        var entry = new LinkedHashMap<String, Object>();
        entry.put("name", typeName);
        entry.put("fqcn", cls.getName());
        if (cls.isRecord()) {
            entry.put("kind", "record");
            entry.put("fields", renderRecordFields(cls));
            var methods = collectInstanceMethods(cls);
            if (!methods.isEmpty()) entry.put("methods", methods);
        } else if (cls.isSealed()) {
            entry.put("kind", "sealed");
            entry.put("variants", renderSealedVariants(cls));
        } else {
            entry.put("kind", "interface");
        }
        return entry;
    }

    private static List<Map<String, Object>> renderRecordFields(Class<?> recordCls) {
        var out = new ArrayList<Map<String, Object>>();
        for (RecordComponent rc : recordCls.getRecordComponents()) {
            out.add(Map.of(
                    "name", rc.getName(),
                    "type", friendlyTypeName(rc.getGenericType())
            ));
        }
        return out;
    }

    private static List<Map<String, Object>> renderSealedVariants(Class<?> sealedCls) {
        var out = new ArrayList<Map<String, Object>>();
        Class<?>[] permitted = sealedCls.getPermittedSubclasses();
        if (permitted == null) return out;
        for (Class<?> sub : permitted) {
            var v = new LinkedHashMap<String, Object>();
            v.put("name", sub.getSimpleName());
            v.put("fqcn", sub.getName());
            if (sub.isRecord()) {
                v.put("fields", renderRecordFields(sub));
            }
            out.add(v);
        }
        return out;
    }

    private static List<Map<String, Object>> collectInstanceMethods(Class<?> cls) {
        // For records: skip the auto-generated component accessors (their
        // names match a record component name). Surface only explicitly-
        // declared methods like Value.lovelaceOf, Value.assetOf, etc.
        Set<String> componentNames = new java.util.HashSet<>();
        for (RecordComponent rc : cls.getRecordComponents()) {
            componentNames.add(rc.getName());
        }
        var out = new ArrayList<Map<String, Object>>();
        for (Method m : cls.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers())) continue;
            if (m.isSynthetic() || m.isBridge()) continue;
            String name = m.getName();
            if (SKIP_METHODS.contains(name)) continue;
            if (componentNames.contains(name)) continue; // accessor — already in fields
            out.add(renderMethod(m));
        }
        return out;
    }

    private static Map<String, Object> renderMethod(Method m) {
        var params = new ArrayList<Map<String, Object>>();
        var reflected = m.getParameters();
        var generic = m.getGenericParameterTypes();
        for (int i = 0; i < reflected.length; i++) {
            params.add(Map.of(
                    "name", reflected[i].isNamePresent() ? reflected[i].getName() : "arg" + i,
                    "type", friendlyTypeName(generic[i])
            ));
        }
        String returns = friendlyTypeName(m.getGenericReturnType());
        var sb = new StringBuilder(returns).append(' ').append(m.getName()).append('(');
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(params.get(i).get("type")).append(' ').append(params.get(i).get("name"));
        }
        sb.append(')');
        return Map.of(
                "name", m.getName(),
                "signature", sb.toString(),
                "params", params,
                "returns", returns,
                "static", Modifier.isStatic(m.getModifiers())
        );
    }

    private static String friendlyTypeName(Type t) {
        return t.getTypeName()
                .replaceAll("(?:[a-zA-Z_][a-zA-Z0-9_]*\\.)+([A-Z][A-Za-z0-9_$]*)", "$1")
                .replace('$', '.');
    }
}
