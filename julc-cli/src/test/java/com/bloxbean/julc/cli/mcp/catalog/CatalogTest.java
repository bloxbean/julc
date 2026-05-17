package com.bloxbean.julc.cli.mcp.catalog;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CatalogTest {

    @Test
    void stdlib_lists_all_known_libraries() {
        var libs = StdlibCatalog.listLibraries();
        assertTrue(libs.size() >= 10, "should have most stdlib libs on classpath: " + libs.size());
        var names = libs.stream().map(m -> (String) m.get("name")).toList();
        assertTrue(names.contains("ListsLib"));
        assertTrue(names.contains("ValuesLib"));
        assertTrue(names.contains("ContextsLib"));
    }

    @Test
    void stdlib_describe_returns_methods_for_known_lib() {
        var info = StdlibCatalog.describeLibrary("ListsLib", null);
        assertNotNull(info);
        assertEquals("ListsLib", info.get("name"));
        @SuppressWarnings("unchecked")
        var methods = (List<Map<String, Object>>) info.get("methods");
        assertFalse(methods.isEmpty(), "ListsLib should expose methods");
        // Verify a known method is present.
        var names = methods.stream().map(m -> (String) m.get("name")).toList();
        assertTrue(names.contains("head") || names.contains("isEmpty"),
                "ListsLib should have head/isEmpty: " + names);
    }

    @Test
    void stdlib_describe_filters_by_method_name() {
        var info = StdlibCatalog.describeLibrary("ListsLib", "head");
        @SuppressWarnings("unchecked")
        var methods = (List<Map<String, Object>>) info.get("methods");
        assertTrue(methods.stream().allMatch(m -> "head".equals(m.get("name"))),
                "filtered result must only contain 'head': " + methods);
    }

    @Test
    void stdlib_unknown_library_returns_null() {
        assertNull(StdlibCatalog.describeLibrary("NonExistentLib", null));
    }

    @Test
    void stdlib_filters_known_internal_helpers() {
        // Codex review finding 2: ValuesLib internal flatten/geq helpers
        // were leaking into the catalog and teaching agents to call
        // checkPolicyGeq / flattenStep instead of geqMultiAsset / flatten.
        var info = StdlibCatalog.describeLibrary("ValuesLib", null);
        if (info == null) return; // ValuesLib not on test classpath — skip.
        @SuppressWarnings("unchecked")
        var methods = (List<Map<String, Object>>) info.get("methods");
        var names = methods.stream().map(m -> (String) m.get("name")).toList();
        for (var helper : List.of(
                "checkPolicyGeq", "flattenStep", "flattenPolicy",
                "adjustOuterForAdd", "adjustInnerForAdd",
                "extraOuterEntries", "extraInnerEntries")) {
            assertFalse(names.contains(helper),
                    "internal helper '" + helper + "' must not appear in catalog: " + names);
        }
    }

    @Test
    void stdlib_filters_internal_helpers() {
        // _assetOf, etc. should be filtered.
        var info = StdlibCatalog.describeLibrary("ValuesLib", null);
        if (info == null) return; // ValuesLib not on test classpath — skip.
        @SuppressWarnings("unchecked")
        var methods = (List<Map<String, Object>>) info.get("methods");
        assertTrue(methods.stream().noneMatch(m -> ((String) m.get("name")).startsWith("_")),
                "underscore-prefixed methods must be hidden: " +
                methods.stream().map(m -> m.get("name")).toList());
    }

    @Test
    void builtins_lists_known_methods() {
        var methods = StdlibCatalog.listBuiltins();
        var names = methods.stream().map(m -> (String) m.get("name")).toList();
        assertTrue(names.contains("equalsByteString"),
                "Builtins must include equalsByteString: " + names);
    }

    @Test
    void ledger_lists_all_known_types() {
        var types = LedgerCatalog.listTypes();
        assertTrue(types.size() >= 25, "should have most ledger types: " + types.size());
        var names = types.stream().map(m -> (String) m.get("name")).toList();
        assertTrue(names.contains("TxOut"));
        assertTrue(names.contains("Credential"));
        assertTrue(names.contains("ScriptContext"));
    }

    @Test
    void ledger_describe_returns_record_fields() {
        var info = LedgerCatalog.describeType("TxOut");
        assertNotNull(info);
        assertEquals("record", info.get("kind"));
        @SuppressWarnings("unchecked")
        var fields = (List<Map<String, Object>>) info.get("fields");
        assertFalse(fields.isEmpty());
        var fieldNames = fields.stream().map(m -> (String) m.get("name")).toList();
        assertTrue(fieldNames.contains("address"));
        assertTrue(fieldNames.contains("value"));
    }

    @Test
    void ledger_describe_returns_sealed_variants() {
        var info = LedgerCatalog.describeType("Credential");
        assertNotNull(info);
        assertEquals("sealed", info.get("kind"));
        @SuppressWarnings("unchecked")
        var variants = (List<Map<String, Object>>) info.get("variants");
        assertFalse(variants.isEmpty());
        var variantNames = variants.stream().map(m -> (String) m.get("name")).toList();
        assertTrue(variantNames.contains("PubKeyCredential"));
        assertTrue(variantNames.contains("ScriptCredential"));
    }

    @Test
    void ledger_describe_returns_explicit_methods_on_value() {
        // Value has lovelaceOf, assetOf, isEmpty as explicit instance methods.
        var info = LedgerCatalog.describeType("Value");
        if (info == null) return;
        @SuppressWarnings("unchecked")
        var methods = (List<Map<String, Object>>) info.get("methods");
        if (methods == null) return;
        var names = methods.stream().map(m -> (String) m.get("name")).toList();
        assertTrue(names.contains("lovelaceOf") || names.contains("isEmpty"),
                "Value should expose lovelaceOf/isEmpty: " + names);
    }

    @Test
    void ledger_unknown_type_returns_null() {
        assertNull(LedgerCatalog.describeType("NonExistentType"));
    }

    @Test
    void ledger_record_accessors_filtered_out_of_methods() {
        // For records, the auto-generated accessor for each component should
        // NOT appear in the methods list (it's redundant with the field).
        var info = LedgerCatalog.describeType("TxOut");
        @SuppressWarnings("unchecked")
        var fields = (List<Map<String, Object>>) info.get("fields");
        @SuppressWarnings("unchecked")
        var methods = (List<Map<String, Object>>) info.getOrDefault("methods", List.of());
        var fieldNames = fields.stream().map(m -> (String) m.get("name")).toList();
        for (var m : methods) {
            assertFalse(fieldNames.contains(m.get("name")),
                    "method '" + m.get("name") + "' is also a record field; should be filtered");
        }
    }
}
