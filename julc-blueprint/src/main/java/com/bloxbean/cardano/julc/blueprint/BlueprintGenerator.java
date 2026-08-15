package com.bloxbean.cardano.julc.blueprint;

import com.bloxbean.cardano.julc.clientlib.JulcScriptAdapter;
import com.bloxbean.cardano.julc.compiler.CompileResult;
import com.bloxbean.cardano.julc.compiler.DataBoundarySemantics;
import com.bloxbean.cardano.julc.compiler.schema.ContractSchema;
import com.bloxbean.cardano.julc.core.PlutusTarget;
import com.bloxbean.cardano.julc.core.Program;

import java.util.ArrayList;
import java.util.HashSet;
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
        var emittedTitles = new HashSet<String>();

        for (var cv : compiledValidators) {
            var program = cv.result().program();
            var hash = scriptHash(program);
            var cborHex = compiledCode(program);
            var sizeBytes = cv.result().scriptSizeBytes();

            var interfaces = cv.contractSchema().interfaces();
            String namespace = compiledValidators.size() > 1 || cv.contractSchema().purposeIndexed()
                    ? cv.name()
                    : null;
            for (var validatorInterface : interfaces) {
                String purpose = cip57Purpose(validatorInterface);
                String title = cv.contractSchema().purposeIndexed()
                        ? cv.name() + "." + purpose
                        : cv.name();
                if (!emittedTitles.add(title)) {
                    throw new SchemaGenerator.SchemaGenerationException(
                            "Duplicate generated validator title '" + title + "'");
                }

                var schema = SchemaGenerator.from(
                        cv.contractSchema(), validatorInterface, namespace,
                        cv.contractSchema().purposeIndexed());
                mergeDefinitions(allDefinitions, schema.definitions());

                var datum = argument(schema.datum(), purpose);
                var redeemer = argument(schema.redeemer(), purpose);
                List<Blueprint.Argument> parameters = schema.parameters().isEmpty()
                        ? null
                        : schema.parameters().stream()
                                .map(parameter -> argument(parameter, purpose))
                                .toList();
                entries.add(new Blueprint.ValidatorEntry(
                        title, cborHex, hash, sizeBytes,
                        datum, redeemer, parameters));
            }
        }

        var blueprint = new Blueprint(
                preamble, entries, allDefinitions.isEmpty() ? null : allDefinitions);
        BlueprintValidator.validate(blueprint.toJson());
        return blueprint;
    }

    private static Blueprint.Argument argument(
            SchemaGenerator.Schema schema, String purpose) {
        return schema == null
                ? null
                : new Blueprint.Argument(schema.title(), purpose, schema.untitled());
    }

    private static void mergeDefinitions(
            LinkedHashMap<String, SchemaGenerator.Schema> target,
            java.util.Map<String, SchemaGenerator.Schema> additions) {
        for (var definition : additions.entrySet()) {
            var previous = target.putIfAbsent(definition.getKey(), definition.getValue());
            if (previous != null && !previous.equals(definition.getValue())) {
                throw new SchemaGenerator.SchemaGenerationException(
                        "Conflicting schema definition '" + definition.getKey()
                                + "' across purpose interfaces or compiled validators");
            }
        }
    }

    private static String cip57Purpose(
            ContractSchema.ValidatorInterface validatorInterface) {
        String purpose = switch (validatorInterface.purpose()) {
            case SPEND -> "spend";
            case MINT -> "mint";
            case WITHDRAW -> "withdraw";
            // CIP-57's "publish" is the name used by Aiken for the ledger
            // certificate purpose (RedeemerTag.Cert / ScriptInfo.Certifying).
            case CERTIFY -> "publish";
            case VOTE, PROPOSE -> null;
        };
        if (purpose != null) return purpose;

        String location = validatorInterface.sourceLocation() == null
                ? ""
                : " " + validatorInterface.sourceLocation();
        throw new SchemaGenerator.SchemaGenerationException(
                "Cannot publish @Entrypoint '" + validatorInterface.entrypointName()
                        + "' with purpose " + validatorInterface.purpose()
                        + ": pinned CIP-57 revision " + BlueprintValidator.CIP_REVISION
                        + " has no truthful purpose vocabulary. Use --no-blueprint "
                        + "to compile without interface metadata" + location);
    }
}
