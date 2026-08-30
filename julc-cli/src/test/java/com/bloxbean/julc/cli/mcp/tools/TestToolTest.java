package com.bloxbean.julc.cli.mcp.tools;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.McpJsonMapperSupplier;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.*;

class TestToolTest {

    private static McpJsonMapper jsonMapper;

    @BeforeAll
    static void init() {
        jsonMapper = ServiceLoader.load(McpJsonMapperSupplier.class).stream()
                .map(ServiceLoader.Provider::get)
                .map(McpJsonMapperSupplier::get)
                .findFirst().orElseThrow();
    }

    private static McpSchema.CallToolResult call(String src, String filterMethod) {
        var args = filterMethod == null
                ? Map.<String, Object>of("source", src)
                : Map.<String, Object>of("source", src, "method", filterMethod);
        return TestTool.handle(new McpSchema.CallToolRequest("julc_test", args), jsonMapper);
    }

    @Test
    void runs_passing_test_methods() {
        String src = """
                import com.bloxbean.cardano.julc.stdlib.test.Test;

                public class Sanity {
                    @Test
                    public static boolean truthy() { return 1 + 1 == 2; }

                    @Test
                    public static boolean also_truthy() { return true; }
                }
                """;
        var res = call(src, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) res.structuredContent();
        assertEquals(Boolean.TRUE, body.get("ok"), "all-passing run should be ok=true: " + body);
        assertEquals(2, ((Number) body.get("passed")).intValue());
        assertEquals(0, ((Number) body.get("failed")).intValue());
    }

    @Test
    void evaluatesDefaultPv11CaseBoolLoweringUnderCompiledTarget() {
        String src = """
                import com.bloxbean.cardano.julc.stdlib.test.Test;

                public class Pv11Branch {
                    @Test
                    public static boolean branches() {
                        if (1 + 1 == 2) return true;
                        return false;
                    }
                }
                """;

        var res = call(src, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) res.structuredContent();
        assertEquals(Boolean.TRUE, body.get("ok"), body::toString);
        assertEquals(1, ((Number) body.get("passed")).intValue());
    }

    @Test
    void surfaces_failing_test() {
        String src = """
                import com.bloxbean.cardano.julc.stdlib.test.Test;

                public class HasFailing {
                    @Test
                    public static boolean wrong() { return 1 + 1 == 3; }
                }
                """;
        var res = call(src, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) res.structuredContent();
        assertEquals(Boolean.FALSE, body.get("ok"));
        assertEquals(0, ((Number) body.get("passed")).intValue());
        assertEquals(1, ((Number) body.get("failed")).intValue());
        @SuppressWarnings("unchecked")
        var results = (List<Map<String, Object>>) body.get("results");
        assertEquals("wrong", results.get(0).get("method"));
        assertEquals(Boolean.FALSE, results.get(0).get("passed"));
    }

    @Test
    void method_filter_runs_only_named() {
        String src = """
                import com.bloxbean.cardano.julc.stdlib.test.Test;
                public class Many {
                    @Test public static boolean a() { return true; }
                    @Test public static boolean b() { return true; }
                    @Test public static boolean c() { return true; }
                }
                """;
        var res = call(src, "b");
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) res.structuredContent();
        @SuppressWarnings("unchecked")
        var results = (List<Map<String, Object>>) body.get("results");
        assertEquals(1, results.size());
        assertEquals("b", results.get(0).get("method"));
    }

    @Test
    void returns_warning_for_missing_filter_method() {
        String src = """
                import com.bloxbean.cardano.julc.stdlib.test.Test;
                public class T { @Test public static boolean a() { return true; } }
                """;
        var res = call(src, "no_such_method");
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) res.structuredContent();
        assertNotNull(body.get("warning"));
        assertEquals(0, ((Number) body.get("passed")).intValue());
        assertEquals(0, ((Number) body.get("failed")).intValue());
    }

    @Test
    void no_test_methods_returns_warning_not_error() {
        String src = """
                public class NotATest {
                    public static boolean go() { return true; }  // no @Test annotation
                }
                """;
        var res = call(src, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) res.structuredContent();
        assertEquals(Boolean.TRUE, body.get("ok"));
        assertNotNull(body.get("warning"));
    }

    @Test
    void rejects_missing_source() {
        var res = TestTool.handle(
                new McpSchema.CallToolRequest("julc_test", Map.of()),
                jsonMapper);
        assertEquals(Boolean.TRUE, res.isError());
    }

    @Test
    void rejects_oversized_source() {
        String huge = "/*".repeat(McpLimits.MAX_SOURCE_BYTES / 2 + 1);
        var res = TestTool.handle(
                new McpSchema.CallToolRequest("julc_test", Map.of("source", huge)),
                jsonMapper);
        assertEquals(Boolean.TRUE, res.isError());
    }

    @Test
    void rejects_oversized_library_source() {
        String hugeLibrary = "/*".repeat(McpLimits.MAX_LIBRARY_SOURCE_BYTES / 2 + 1);
        var res = TestTool.handle(
                new McpSchema.CallToolRequest("julc_test", Map.of(
                        "source", "class X {}",
                        "librarySources", List.of(hugeLibrary)
                )),
                jsonMapper);
        assertEquals(Boolean.TRUE, res.isError());
    }

    @Test
    void discovers_same_line_at_test_annotation() {
        // AI-generated tests often put @Test on the same line as the method.
        String src = """
                import com.bloxbean.cardano.julc.stdlib.test.Test;
                public class OneLiners {
                    @Test public static boolean a() { return true; }
                    @Test public static boolean b() { return true; }
                }
                """;
        var found = TestTool.discoverTestMethods(src);
        assertEquals(List.of("a", "b"), found,
                "should discover both single-line @Test methods");
    }

    @Test
    void discoverTestMethods_finds_annotated_static_booleans() {
        String src = """
                @Test
                public static boolean alpha() { return true; }

                @Test
                public static boolean beta() { return false; }

                public static boolean notATest() { return true; }
                """;
        var found = TestTool.discoverTestMethods(src);
        assertEquals(List.of("alpha", "beta"), found);
    }
}
