package com.bloxbean.cardano.julc.blueprint;

import com.bloxbean.cardano.julc.clientlib.JulcScriptAdapter;
import com.bloxbean.cardano.julc.compiler.CompileResult;
import com.bloxbean.cardano.julc.compiler.DataBoundarySemantics;
import com.bloxbean.cardano.julc.compiler.schema.ContractSchema;
import com.bloxbean.cardano.julc.core.PlutusTarget;
import com.bloxbean.cardano.julc.core.Program;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Generates CIP-57 blueprint JSON from compile results.
 */
public final class BlueprintGenerator {

    private BlueprintGenerator() {}

    public record CompiledValidator(String name, CompileResult result, ContractSchema contractSchema) {}

    /** Serialize a compiled program without requiring a blueprint schema. */
    public static String compiledCode(Program program) {
        return JulcScriptAdapter.fromProgram(program).getCborHex();
    }

    /** Calculate the script hash without requiring a blueprint schema. */
    public static String scriptHash(Program program) {
        return JulcScriptAdapter.scriptHash(program);
    }

    /**
     * Generate a CIP-57 blueprint from compiled validators.
     */
    public static Blueprint generate(BlueprintConfig config, List<CompiledValidator> compiledValidators) {
        var preamble = new Blueprint.Preamble(
                config.projectName(),
                config.projectVersion(),
                PlutusTarget.CURRENT.languageVersion(),
                new Blueprint.Compiler("julc",
                        DataBoundarySemantics.compilerIdentityVersion(JulcVersion.VERSION))
        );

        var allDefinitions = new LinkedHashMap<String, SchemaGenerator.Schema>();
        var entries = new ArrayList<Blueprint.ValidatorEntry>();

        for (var cv : compiledValidators) {
            var program = cv.result().program();
            var hash = scriptHash(program);
            var cborHex = compiledCode(program);
            var sizeBytes = cv.result().scriptSizeBytes();

            String namespace = compiledValidators.size() > 1 ? cv.name() : null;
            var schema = SchemaGenerator.from(cv.contractSchema(), namespace);
            var datum = schema.datum();
            var redeemer = schema.redeemer();
            List<SchemaGenerator.Schema> parameters = schema.parameters().isEmpty()
                    ? null
                    : schema.parameters();
            for (var definition : schema.definitions().entrySet()) {
                var previous = allDefinitions.putIfAbsent(
                        definition.getKey(), definition.getValue());
                if (previous != null && !previous.equals(definition.getValue())) {
                    throw new SchemaGenerator.SchemaGenerationException(
                            "Conflicting schema definition '" + definition.getKey()
                                    + "' across compiled validators");
                }
            }

            entries.add(new Blueprint.ValidatorEntry(
                    cv.name(), cborHex, hash, sizeBytes,
                    datum, redeemer, parameters
            ));
        }

        var blueprint = new Blueprint(
                preamble, entries, allDefinitions.isEmpty() ? null : allDefinitions);
        BlueprintValidator.validate(blueprint.toJson());
        return blueprint;
    }
}
