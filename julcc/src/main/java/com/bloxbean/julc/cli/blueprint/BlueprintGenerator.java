package com.bloxbean.julc.cli.blueprint;

import com.bloxbean.cardano.julc.clientlib.JulcScriptAdapter;
import com.bloxbean.cardano.julc.compiler.CompileResult;
import com.bloxbean.julc.cli.JulccVersionProvider;
import com.bloxbean.julc.cli.project.JulcToml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Generates CIP-57 blueprint JSON from compile results.
 */
public final class BlueprintGenerator {

    private BlueprintGenerator() {}

    public record CompiledValidator(String name, String source, CompileResult result) {}

    /**
     * Generate a CIP-57 blueprint from compiled validators.
     */
    public static Blueprint generate(JulcToml config, List<CompiledValidator> compiledValidators) {
        var preamble = new Blueprint.Preamble(
                config.name(),
                config.version(),
                "v3",
                new Blueprint.Compiler("julcc", JulccVersionProvider.VERSION)
        );

        var allDefinitions = new LinkedHashMap<String, SchemaGenerator.Schema>();
        var entries = new ArrayList<Blueprint.ValidatorEntry>();

        for (var cv : compiledValidators) {
            var program = cv.result().program();
            var script = JulcScriptAdapter.fromProgram(program);
            var hash = JulcScriptAdapter.scriptHash(program);
            var cborHex = script.getCborHex();
            var sizeBytes = cv.result().scriptSizeBytes();

            // Extract schema from source
            SchemaGenerator.Schema datum = null;
            SchemaGenerator.Schema redeemer = null;
            List<SchemaGenerator.Schema> parameters = null;

            try {
                var schema = SchemaGenerator.extract(cv.source());
                if (schema != null) {
                    datum = schema.datum();
                    redeemer = schema.redeemer();
                    if (schema.parameters() != null && !schema.parameters().isEmpty()) {
                        parameters = schema.parameters();
                    }
                    allDefinitions.putAll(schema.definitions());
                }
            } catch (Exception e) {
                // Schema extraction is best-effort — don't fail the build
            }

            entries.add(new Blueprint.ValidatorEntry(
                    cv.name(), cborHex, hash, sizeBytes,
                    datum, redeemer, parameters
            ));
        }

        return new Blueprint(preamble, entries, allDefinitions.isEmpty() ? null : allDefinitions);
    }
}
