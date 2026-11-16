package org.julclang.tools.model;

import org.julclang.tools.model.MockTransaction.DataInput;
import org.julclang.tools.model.UplcModels.ScriptInput;

import java.util.List;

/** Request and response of {@code vm.applyParams} (ADR-077 D6). */
public final class ApplyParamsModels {

    private ApplyParamsModels() {}

    /**
     * @param language    {@code V1 | V2 | V3}, required and never inferred
     * @param script      CBOR hex (single or double wrapped), FLAT hex, a CIP-57 blueprint or a text envelope; not UPLC
     *                    text. {@code script.validator} selects a blueprint's validator by its exact title;
     *                    {@code script.language} and {@code script.params} must be empty
     * @param params      the values, in order, each with an explicit format ({@code json}, {@code cbor} or
     *                    {@code uplc})
     * @param partial     allow fewer values than the blueprint declares (default false)
     * @param checkSchema check each value against its declared schema (default true)
     */
    public record Request(String language, ScriptInput script, List<DataInput> params, Boolean partial,
                         Boolean checkSchema) {}

    /**
     * @param compiledCode    the applied script wrapped twice in CBOR, as a JuLC blueprint or a text envelope holds it
     * @param cborHex         the applied script wrapped once, as an Aiken blueprint holds it
     * @param scriptHash      blake2b-224 of the language tag and the once-wrapped script
     * @param language        {@code V1 | V2 | V3}
     * @param paramsApplied   the number of values applied
     * @param remainingParams declared minus applied; {@code null} when the input declares no parameter list. Only 0
     *                        means the script is complete
     * @param schemaChecked   true only when every value was checked against a declared schema
     */
    public record Response(String compiledCode, String cborHex, String scriptHash, String language, int paramsApplied,
                           Integer remainingParams, boolean schemaChecked) {}
}
