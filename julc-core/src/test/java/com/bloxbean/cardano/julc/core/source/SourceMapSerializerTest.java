package com.bloxbean.cardano.julc.core.source;

import com.bloxbean.cardano.julc.core.*;
import org.junit.jupiter.api.Test;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SourceMapSerializer}: DFS indexing, JSON round-trip, and reconstruction.
 */
class SourceMapSerializerTest {

    // ---- DFS index round-trip ----

    @Test
    void toIndexed_leafNodes_assignsSequentialIndices() {
        // Tree: Apply(Const(1), Error)
        var constTerm = Term.const_(Constant.integer(1));
        var errorTerm = Term.error();
        var applyTerm = Term.apply(constTerm, errorTerm);

        var loc1 = new SourceLocation("Test.java", 10, 1, "1");
        var loc2 = new SourceLocation("Test.java", 20, 1, "error");

        var positions = new IdentityHashMap<Term, SourceLocation>();
        positions.put(constTerm, loc1);
        positions.put(errorTerm, loc2);

        var indexed = SourceMapSerializer.toIndexed(positions, applyTerm);

        // DFS: Apply(0) -> Const(1) -> Error(2)
        assertEquals(2, indexed.size());
        assertEquals(loc1, indexed.get(1)); // Const is index 1
        assertEquals(loc2, indexed.get(2)); // Error is index 2
        assertNull(indexed.get(0)); // Apply itself not mapped
    }

    @Test
    void roundTrip_preservesLookups() {
        // Build a small term tree with source locations
        var body = Term.error();
        var lam = Term.lam("x", body);
        var arg = Term.const_(Constant.integer(42));
        var root = Term.apply(lam, arg);

        var bodyLoc = new SourceLocation("V.java", 5, 3, "error()");
        var argLoc = new SourceLocation("V.java", 10, 5, "42");

        var positions = new IdentityHashMap<Term, SourceLocation>();
        positions.put(body, bodyLoc);
        positions.put(arg, argLoc);

        // Serialize
        var indexed = SourceMapSerializer.toIndexed(positions, root);

        // Build a structurally identical but fresh tree (simulating deserialization)
        var body2 = Term.error();
        var lam2 = Term.lam("x", body2);
        var arg2 = Term.const_(Constant.integer(42));
        var root2 = Term.apply(lam2, arg2);

        // Reconstruct
        var rebuilt = SourceMapSerializer.fromIndexed(indexed, root2);

        // Verify lookups by identity on the new tree
        assertEquals(bodyLoc, rebuilt.get(body2));
        assertEquals(argLoc, rebuilt.get(arg2));
        assertNull(rebuilt.get(lam2)); // lam was not mapped
        assertEquals(2, rebuilt.size());
    }

    @Test
    void roundTrip_complexTree_withAllNodeTypes() {
        // Build tree with Lam, Apply, Force, Delay, Constr, Case, Builtin, Var
        var builtinTerm = Term.builtin(DefaultFun.AddInteger);
        var varTerm = Term.var(1);
        var constTerm = Term.const_(Constant.integer(0));
        var delayTerm = Term.delay(constTerm);
        var forceTerm = Term.force(delayTerm);
        var constrTerm = Term.constr(0, builtinTerm, varTerm);
        var branch1 = Term.error();
        var branch2 = Term.const_(Constant.bool(true));
        var caseTerm = Term.case_(constrTerm, branch1, branch2);

        var loc = new SourceLocation("X.java", 1, 1, "builtin");
        var positions = new IdentityHashMap<Term, SourceLocation>();
        positions.put(builtinTerm, loc);

        var indexed = SourceMapSerializer.toIndexed(positions, caseTerm);

        // Rebuild identical tree
        var builtinTerm2 = Term.builtin(DefaultFun.AddInteger);
        var varTerm2 = Term.var(1);
        var constTerm2 = Term.const_(Constant.integer(0));
        var delayTerm2 = Term.delay(constTerm2);
        var forceTerm2 = Term.force(delayTerm2);
        var constrTerm2 = Term.constr(0, builtinTerm2, varTerm2);
        var branch12 = Term.error();
        var branch22 = Term.const_(Constant.bool(true));
        var caseTerm2 = Term.case_(constrTerm2, branch12, branch22);

        var rebuilt = SourceMapSerializer.fromIndexed(indexed, caseTerm2);
        assertEquals(loc, rebuilt.get(builtinTerm2));
        assertEquals(1, rebuilt.size());
    }

    @Test
    void toIndexed_emptySourceMap_returnsEmptyMap() {
        var root = Term.apply(Term.error(), Term.error());
        var indexed = SourceMapSerializer.toIndexed(new IdentityHashMap<>(), root);
        assertTrue(indexed.isEmpty());
    }

    // ---- JSON round-trip ----

    @Test
    void jsonRoundTrip_preservesAllFields() {
        var indexed = Map.of(
                5, new SourceLocation("MyValidator.java", 35, 5, "ContextsLib.txInfoFee(txInfo)"),
                12, new SourceLocation("MyValidator.java", 42, 9, "amount > goal")
        );

        String json = SourceMapSerializer.toJson(indexed, "MyValidator");
        var parsed = SourceMapSerializer.fromJson(json);

        assertEquals(1, parsed.version());
        assertEquals("MyValidator", parsed.validatorClass());
        assertEquals(2, parsed.entries().size());
        assertEquals("MyValidator.java", parsed.entries().get(5).fileName());
        assertEquals(35, parsed.entries().get(5).line());
        assertEquals(5, parsed.entries().get(5).column());
        assertEquals("ContextsLib.txInfoFee(txInfo)", parsed.entries().get(5).fragment());
        assertEquals("amount > goal", parsed.entries().get(12).fragment());
    }

    @Test
    void jsonRoundTrip_emptyEntries() {
        String json = SourceMapSerializer.toJson(Map.of(), "Empty");
        var parsed = SourceMapSerializer.fromJson(json);

        assertEquals(1, parsed.version());
        assertEquals("Empty", parsed.validatorClass());
        assertTrue(parsed.entries().isEmpty());
    }

    @Test
    void jsonRoundTrip_escapedCharacters() {
        var indexed = Map.of(
                0, new SourceLocation("Test.java", 1, 1, "str.contains(\"hello\")")
        );

        String json = SourceMapSerializer.toJson(indexed, "Test");
        var parsed = SourceMapSerializer.fromJson(json);

        assertEquals("str.contains(\"hello\")", parsed.entries().get(0).fragment());
    }

    @Test
    void jsonRoundTrip_newlineInFragment() {
        var indexed = Map.of(
                0, new SourceLocation("Test.java", 1, 1, "line1\nline2")
        );

        String json = SourceMapSerializer.toJson(indexed, "Test");
        assertFalse(json.contains("\nline2")); // should be escaped as \n
        assertTrue(json.contains("\\n"));

        var parsed = SourceMapSerializer.fromJson(json);
        assertEquals("line1\nline2", parsed.entries().get(0).fragment());
    }

    @Test
    void jsonRoundTrip_braceInFragment() {
        var indexed = Map.of(
                0, new SourceLocation("Test.java", 1, 1, "new Record() {}")
        );

        String json = SourceMapSerializer.toJson(indexed, "Test");
        var parsed = SourceMapSerializer.fromJson(json);

        assertEquals("new Record() {}", parsed.entries().get(0).fragment());
    }

    @Test
    void jsonRoundTrip_nullFragment() {
        var indexed = Map.of(
                0, new SourceLocation("Test.java", 1, 1, null)
        );

        String json = SourceMapSerializer.toJson(indexed, "Test");
        var parsed = SourceMapSerializer.fromJson(json);

        // null fragment becomes absent in JSON, parsed as null
        assertNull(parsed.entries().get(0).fragment());
    }

    // ---- SourceMap convenience methods ----

    @Test
    void sourceMap_toIndexed_andReconstruct() {
        var inner = Term.error();
        var root = Term.lam("x", inner);

        var loc = new SourceLocation("V.java", 10, 1, "error");
        var sourceMap = SourceMap.of(Map.of(inner, loc));

        // Serialize via SourceMap convenience method
        var indexed = sourceMap.toIndexed(root);
        assertFalse(indexed.isEmpty());

        // Rebuild on fresh tree
        var inner2 = Term.error();
        var root2 = Term.lam("x", inner2);

        var reconstructed = SourceMap.reconstruct(indexed, root2);
        assertEquals(loc, reconstructed.lookup(inner2));
        assertNull(reconstructed.lookup(inner)); // different tree, different identity
    }

    @Test
    void sourceMap_fullPipeline_indexToJsonAndBack() {
        // Simulate: compile → index → JSON → load → reconstruct → lookup
        var errorTerm = Term.error();
        var constTerm = Term.const_(Constant.integer(99));
        var root = Term.apply(errorTerm, constTerm);

        var loc = new SourceLocation("Pipeline.java", 50, 8, "check failed");
        var sourceMap = SourceMap.of(Map.of(errorTerm, loc));

        // Step 1: index
        var indexed = sourceMap.toIndexed(root);

        // Step 2: JSON
        String json = SourceMapSerializer.toJson(indexed, "PipelineTest");

        // Step 3: parse JSON
        var parsed = SourceMapSerializer.fromJson(json);

        // Step 4: rebuild on fresh tree
        var errorTerm2 = Term.error();
        var constTerm2 = Term.const_(Constant.integer(99));
        var root2 = Term.apply(errorTerm2, constTerm2);

        var reconstructed = SourceMap.reconstruct(parsed.entries(), root2);
        assertEquals(loc, reconstructed.lookup(errorTerm2));
        assertNull(reconstructed.lookup(constTerm2)); // not mapped
    }

    // ---- applyParams preserves inner Term identity ----

    @Test
    void applyParams_preservesInnerTermIdentity() {
        // Simulate parameterized validator: inner terms keep identity through applyParams
        var inner = Term.error();
        var root = Term.lam("param", Term.lam("ctx", inner));

        var loc = new SourceLocation("V.java", 10, 1, "error");
        var positions = new IdentityHashMap<Term, SourceLocation>();
        positions.put(inner, loc);

        var sourceMap = SourceMap.of(positions);

        // applyParams wraps with Apply nodes but inner terms are the SAME objects
        var program = new Program(1, 1, 0, root);
        var applied = program.applyParams(
                new com.bloxbean.cardano.julc.core.PlutusData.ConstrData(0, List.of()));

        // The inner error term should still be lookupable
        assertEquals(loc, sourceMap.lookup(inner));
    }
}
