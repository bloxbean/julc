package com.bloxbean.cardano.julc.clientlib.eval;

import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.julc.clientlib.JulcScriptAdapter;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.core.source.SourceLocation;
import com.bloxbean.cardano.julc.core.source.SourceMap;
import com.bloxbean.cardano.julc.core.source.SourceMapSerializer;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for source map integration across JulcScriptLoader and JulcTransactionEvaluator.
 */
class SourceMapIntegrationTest {

    // ---- LoadResult ----

    @Test
    void loadResult_recordFields() {
        var program = Program.plutusV3(Term.error());
        var script = JulcScriptAdapter.fromProgram(program);
        var sourceMap = SourceMap.EMPTY;

        var result = new JulcScriptLoader.LoadResult(script, sourceMap, program);
        assertSame(script, result.script());
        assertSame(sourceMap, result.sourceMap());
        assertSame(program, result.program());
    }

    @Test
    void loadResult_nullSourceMap() {
        var program = Program.plutusV3(Term.error());
        var script = JulcScriptAdapter.fromProgram(program);

        var result = new JulcScriptLoader.LoadResult(script, null, program);
        assertNull(result.sourceMap());
        assertNotNull(result.script());
        assertNotNull(result.program());
    }

    // ---- registerScript ----

    @Test
    void registerScript_storesSourceMapAndProgram() {
        var errorTerm = Term.error();
        var root = Term.lam("ctx", errorTerm);
        var program = Program.plutusV3(root);
        var script = JulcScriptAdapter.fromProgram(program);

        var loc = new SourceLocation("Test.java", 10, 1, "error()");
        var sourceMap = SourceMap.of(Map.of(errorTerm, loc));

        var loadResult = new JulcScriptLoader.LoadResult(script, sourceMap, program);
        var evaluator = createEvaluator();

        evaluator.registerScript("abc123", loadResult);
        assertDoesNotThrow(() -> evaluator.registerScript("def456", loadResult));
    }

    @Test
    void registerScript_withNullSourceMap_noException() {
        var program = Program.plutusV3(Term.error());
        var script = JulcScriptAdapter.fromProgram(program);
        var loadResult = new JulcScriptLoader.LoadResult(script, null, program);

        var evaluator = createEvaluator();
        assertDoesNotThrow(() -> evaluator.registerScript("abc123", loadResult));
    }

    // ---- setSourceMap / setProgram / enableTracing ----

    @Test
    void setSourceMap_andSetProgram_noException() {
        var evaluator = createEvaluator();

        var sourceMap = SourceMap.EMPTY;
        var program = Program.plutusV3(Term.error());

        assertDoesNotThrow(() -> evaluator.setSourceMap("hash1", sourceMap));
        assertDoesNotThrow(() -> evaluator.setProgram("hash1", program));
        assertDoesNotThrow(() -> evaluator.enableTracing(true));
        assertDoesNotThrow(() -> evaluator.enableTracing(false));
    }

    // ---- Source map DFS + JSON pipeline with real FLAT encode/decode ----

    @Test
    void sourceMapSurvivesFlatRoundTrip() {
        // Build a program with source-mapped terms
        var errorTerm = Term.error();
        var constTerm = Term.const_(Constant.integer(42));
        var bodyTerm = Term.apply(errorTerm, constTerm);
        var root = Term.lam("ctx", bodyTerm);
        var program = Program.plutusV3(root);

        var loc = new SourceLocation("Validator.java", 15, 5, "check()");

        var positions = new IdentityHashMap<Term, SourceLocation>();
        positions.put(errorTerm, loc);
        var sourceMap = SourceMap.of(positions);

        // Serialize source map to indexed JSON
        var indexed = sourceMap.toIndexed(program.term());
        String json = SourceMapSerializer.toJson(indexed, "Validator");

        // FLAT encode → CBOR double-wrap → PlutusV3Script → CBOR hex
        PlutusV3Script script = JulcScriptAdapter.fromProgram(program);
        String cborHex = script.getCborHex();

        // Now simulate loading: CBOR hex → FLAT decode → new Program
        var loadedProgram = JulcScriptAdapter.toProgram(cborHex);

        // Parse JSON and reconstruct source map on the loaded program's term tree
        var parsedIndexed = SourceMapSerializer.fromJson(json);
        var reconstructed = SourceMap.reconstruct(parsedIndexed.entries(), loadedProgram.term());

        assertFalse(reconstructed.isEmpty());
        assertEquals(1, reconstructed.size());

        // Verify: root = Lam("ctx", Apply(Error, Const(42)))
        Term loadedRoot = loadedProgram.term();
        assertInstanceOf(Term.Lam.class, loadedRoot);
        Term loadedBody = ((Term.Lam) loadedRoot).body();
        assertInstanceOf(Term.Apply.class, loadedBody);
        Term loadedError = ((Term.Apply) loadedBody).function();
        assertInstanceOf(Term.Error.class, loadedError);

        assertEquals(loc, reconstructed.lookup(loadedError));
    }

    @Test
    void sourceMapSurvivesApplyParams() {
        // Parameterized validator: inner terms keep identity through applyParams
        var errorTerm = Term.error();
        var inner = Term.lam("ctx", errorTerm);
        var root = Term.lam("param", inner);
        var program = Program.plutusV3(root);

        var loc = new SourceLocation("V.java", 20, 3, "error");
        var positions = new IdentityHashMap<Term, SourceLocation>();
        positions.put(errorTerm, loc);

        var sourceMap = SourceMap.of(positions);
        var indexed = sourceMap.toIndexed(program.term());
        String json = SourceMapSerializer.toJson(indexed, "V");

        // FLAT round-trip
        var script = JulcScriptAdapter.fromProgram(program);
        var loadedProgram = JulcScriptAdapter.toProgram(script.getCborHex());

        // Reconstruct source map from base program
        var parsed = SourceMapSerializer.fromJson(json);
        var reconstructed = SourceMap.reconstruct(parsed.entries(), loadedProgram.term());

        // Apply params (wraps with Apply, but inner terms keep same identity)
        var paramData = new com.bloxbean.cardano.julc.core.PlutusData.ConstrData(0, List.of());
        var applied = loadedProgram.applyParams(paramData);

        // Navigate: applied.term() = Apply(Lam("param", Lam("ctx", Error)), Const(Data))
        Term appliedRoot = applied.term();
        assertInstanceOf(Term.Apply.class, appliedRoot);
        Term baseRoot = ((Term.Apply) appliedRoot).function();
        assertInstanceOf(Term.Lam.class, baseRoot);
        Term innerLam = ((Term.Lam) baseRoot).body();
        assertInstanceOf(Term.Lam.class, innerLam);
        Term innerError = ((Term.Lam) innerLam).body();
        assertInstanceOf(Term.Error.class, innerError);

        assertEquals(loc, reconstructed.lookup(innerError));
    }

    // ---- Helper ----

    private JulcTransactionEvaluator createEvaluator() {
        UtxoSupplier utxoSupplier = new UtxoSupplier() {
            @Override
            public List<Utxo> getPage(String address, Integer nrOfItems, Integer page,
                                       com.bloxbean.cardano.client.api.common.OrderEnum order) {
                return List.of();
            }

            @Override
            public Optional<Utxo> getTxOutput(String txHash, int outputIndex) {
                return Optional.empty();
            }
        };

        ProtocolParamsSupplier protocolParamsSupplier = () -> {
            var params = new ProtocolParams();
            params.setMaxTxExMem("14000000");
            params.setMaxTxExSteps("10000000000");
            return params;
        };

        return new JulcTransactionEvaluator(utxoSupplier, protocolParamsSupplier, null);
    }
}
