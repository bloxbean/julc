package org.julclang.tools.params;

import com.fasterxml.jackson.databind.JsonNode;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.tools.model.ApplyParamsModels.Request;
import org.julclang.tools.model.ApplyParamsModels.Response;
import org.julclang.tools.model.MockTransaction.DataInput;
import org.julclang.tools.model.UplcModels.ScriptInput;
import org.julclang.tools.service.ServiceResult;
import org.julclang.tools.uplc.ScriptDecoder;
import org.julclang.vm.PlutusLanguage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * {@code vm.applyParams} (ADR-077 D6, D6a, D6b): applies a validator's parameters and gives the bytes and hash of the
 * result, without guessing anything.
 * <ul>
 *   <li>The language is stated, never inferred, and cross-checked against what the input says about it.</li>
 *   <li>A blueprint's validator is chosen by exact title, and its hash must match the script before application.</li>
 *   <li>The script's bytes must be canonical at every layer, so the output keeps the input's bytes.</li>
 *   <li>Values are read strictly, counted against the declaration, and checked against their schemas.</li>
 * </ul>
 * Application itself is {@code ScriptDecoder.decode}'s: {@link Program#applyParams}, which wraps each value as a Plutus
 * {@code Data} constant in its canonical encoding, then the same hash.
 */
public final class ApplyParams {

    private static final Pattern HEX = Pattern.compile("(?:[0-9a-fA-F]{2})+");
    private static final Pattern HASH = Pattern.compile("[0-9a-fA-F]{56}");
    private static final HexFormat HEX_FORMAT = HexFormat.of();

    private ApplyParams() {}

    /** The engine operation: the response, or the refusal with its status and code. */
    public static ServiceResult<?> run(Request request) {
        try {
            return ServiceResult.ok(apply(request));
        } catch (ApplyParamsException e) {
            return ServiceResult.status(e.kind().status(), errorBody(e));
        }
    }

    /** Applies the parameters, or throws the refusal. */
    public static Response apply(Request request) {
        return apply(request, DataSchemaChecker.DEFAULT_WORK_BOUND);
    }

    static Response apply(Request request, long workBound) {
        if (request == null) throw ApplyParamsException.invalidRequest("The request is empty");
        PlutusLanguage language = language(request.language());
        Source source = source(request.script(), language);
        CanonicalScript script = CanonicalScript.read(source.bytes());
        if (source.hash() != null) checkHash(source, script, language);
        // ScriptDecoder's warnings never pass: the language is stated, trailing bytes and any other non-canonical
        // encoding are refused by CanonicalScript, and a blueprint hash that does not match is refused above.

        List<DataInput> given = request.params() == null ? List.of() : request.params();
        boolean partial = Boolean.TRUE.equals(request.partial());
        boolean checkSchema = request.checkSchema() == null || request.checkSchema();
        checkCount(source, given.size(), partial);

        List<Parameter> declared = source.parameters();
        var checker = declared == null ? null : new DataSchemaChecker(source.definitions(), workBound);
        var compiled = new ArrayList<DataSchemaChecker.Schema>();
        if (declared != null) {
            // D6a: a builtin type at the top of an applied parameter's schema is refused whatever checkSchema says.
            for (int i = 0; i < given.size(); i++) {
                String builtin = builtinType(checker.resolveTop(declared.get(i).schema(), label(source, i)));
                if (builtin != null) {
                    throw ApplyParamsException.invalidRequest(label(source, i) + ": its schema is the builtin type "
                            + builtin + ", not Plutus data. vm.applyParams applies only Plutus Data constants, so it"
                            + " cannot apply this parameter.");
                }
            }
            if (checkSchema) {
                // D6b: every schema fault is found before any value is read, in every declared parameter's schema.
                // A parameter left for later may be a builtin type: that is not a fault of the schema.
                for (int i = 0; i < declared.size(); i++) {
                    JsonNode schema = declared.get(i).schema();
                    if (i >= given.size() && builtinType(checker.resolveTop(schema, label(source, i))) != null) {
                        compiled.add(null);
                        continue;
                    }
                    compiled.add(checker.compile(schema, label(source, i)));
                }
            }
        }

        var values = new PlutusData[given.size()];
        for (int i = 0; i < values.length; i++) {
            try {
                values[i] = StrictDataReader.read(given.get(i));
            } catch (ApplyParamsException e) {
                throw new ApplyParamsException(e.kind(), label(source, i) + ": " + e.getMessage());
            }
        }

        boolean schemaChecked = false;
        if (declared != null && checkSchema) {
            for (int i = 0; i < values.length; i++) checker.check(compiled.get(i), values[i], label(source, i));
            schemaChecked = true;
        }

        byte[] singleCbor = values.length == 0 ? script.singleCbor()
                : CanonicalScript.ofProgram(script.program().applyParams(values)).singleCbor();
        byte[] hash = ScriptDecoder.scriptHash(language, singleCbor);
        Integer remaining = source.parameters() == null ? null : source.parameters().size() - values.length;
        return new Response(HEX_FORMAT.formatHex(CanonicalScript.wrap(singleCbor)), HEX_FORMAT.formatHex(singleCbor),
                HEX_FORMAT.formatHex(hash), request.language(), values.length, remaining, schemaChecked);
    }

    // ---- language ----

    private static PlutusLanguage language(String language) {
        if (language == null) {
            throw ApplyParamsException.invalidRequest("The request must state language: V1, V2 or V3. It is never"
                    + " inferred, because the script's bytes cannot tell it and the hash depends on it.");
        }
        return switch (language) {
            case "V1" -> PlutusLanguage.PLUTUS_V1;
            case "V2" -> PlutusLanguage.PLUTUS_V2;
            case "V3" -> PlutusLanguage.PLUTUS_V3;
            default -> throw ApplyParamsException.invalidRequest("Unknown language '" + language
                    + "'; give exactly V1, V2 or V3");
        };
    }

    private static String languageName(PlutusLanguage language) {
        return language.name().substring("PLUTUS_".length());
    }

    // ---- the script's source ----

    /** A declared parameter: its title, if any, and its schema. */
    private record Parameter(String title, JsonNode schema) {}

    /**
     * Where the script came from.
     *
     * @param parameters  the selected validator's declaration, or {@code null} when the input has none
     * @param definitions the blueprint's definitions, or {@code null}
     * @param hash        the blueprint's hash for the validator, or {@code null}
     * @param validator   the selected validator's title, or {@code null}
     */
    private record Source(byte[] bytes, List<Parameter> parameters, JsonNode definitions, String hash,
                          String validator) {}

    private static Source source(ScriptInput input, PlutusLanguage language) {
        if (input == null || input.script() == null || input.script().isBlank()) {
            throw ApplyParamsException.invalidRequest("The request has no script");
        }
        if (input.language() != null && !input.language().isEmpty()) {
            throw ApplyParamsException.invalidRequest("script.language must be empty: the language is stated once,"
                    + " in the request's language field, so the two cannot disagree");
        }
        if (input.params() != null && !input.params().isEmpty()) {
            throw ApplyParamsException.invalidRequest("script.params must be empty: give the values in params, so"
                    + " that they cannot be applied twice");
        }
        String text = input.script().strip();
        if (text.startsWith("{")) return json(text, input.validator(), language);
        if (input.validator() != null) {
            throw ApplyParamsException.invalidRequest("script.validator selects a blueprint's validator, but the"
                    + " script is not a blueprint");
        }
        if (text.startsWith("(")) {
            throw ApplyParamsException.invalidRequest("UPLC text is not accepted: it has no bytes to keep. Give the"
                    + " compiled script as CBOR hex, a CIP-57 blueprint or a text envelope.");
        }
        return new Source(hex(text, "The script"), null, null, null, null);
    }

    private static Source json(String text, String validator, PlutusLanguage language) {
        JsonNode root = StrictJson.read(text, "The script JSON");
        if (!root.isObject()) {
            throw ApplyParamsException.invalidRequest("The script JSON must be a CIP-57 blueprint or a text envelope");
        }
        boolean blueprint = root.has("validators");
        boolean envelope = root.has("cborHex");
        if (blueprint && envelope) {
            throw ApplyParamsException.invalidRequest("The script JSON has both validators and cborHex; it must be"
                    + " either a CIP-57 blueprint or a text envelope");
        }
        if (envelope) {
            if (validator != null) {
                throw ApplyParamsException.invalidRequest("script.validator selects a blueprint's validator, but the"
                        + " script is a text envelope");
            }
            if (root.has("type")) {
                JsonNode type = root.get("type");
                String stated = !type.isTextual() ? null : switch (type.textValue()) {
                    case "PlutusScriptV1" -> "V1";
                    case "PlutusScriptV2" -> "V2";
                    case "PlutusScriptV3" -> "V3";
                    default -> null;
                };
                if (stated == null) {
                    throw ApplyParamsException.invalidRequest("The text envelope's type " + type + " is not"
                            + " recognised; it must be exactly PlutusScriptV1, PlutusScriptV2 or PlutusScriptV3");
                }
                if (!stated.equals(languageName(language))) {
                    throw ApplyParamsException.invalidRequest("The request states language " + languageName(language)
                            + ", but the text envelope's type is " + type.textValue());
                }
            }
            JsonNode cborHex = root.get("cborHex");
            if (!cborHex.isTextual()) throw ApplyParamsException.invalidRequest("The envelope's cborHex must be a string");
            return new Source(hex(cborHex.textValue(), "The envelope's cborHex"), null, null, null, null);
        }
        if (!blueprint) {
            throw ApplyParamsException.invalidRequest("The script JSON must be a CIP-57 blueprint (with validators)"
                    + " or a text envelope (with cborHex)");
        }
        if (root.has("preamble")) {
            JsonNode preamble = root.get("preamble");
            if (!preamble.isObject()) throw ApplyParamsException.invalidRequest("The blueprint's preamble must be an object");
            if (preamble.has("plutusVersion")) {
                JsonNode version = preamble.get("plutusVersion");
                String stated = !version.isTextual() ? null : switch (version.textValue()) {
                    case "v1" -> "V1";
                    case "v2" -> "V2";
                    case "v3" -> "V3";
                    default -> null;
                };
                if (stated == null) {
                    throw ApplyParamsException.invalidRequest("The blueprint's preamble.plutusVersion " + version
                            + " is not recognised; it must be exactly v1, v2 or v3");
                }
                if (!stated.equals(languageName(language))) {
                    throw ApplyParamsException.invalidRequest("The request states language " + languageName(language)
                            + ", but the blueprint's preamble.plutusVersion is " + version.textValue());
                }
            }
        }
        JsonNode chosen = select(root.get("validators"), validator);
        String title = chosen.get("title").textValue();
        JsonNode code = chosen.get("compiledCode");
        if (code == null || !code.isTextual()) {
            throw ApplyParamsException.invalidRequest("Validator '" + title + "' has no compiledCode string");
        }
        String hash = null;
        if (chosen.has("hash")) {
            JsonNode given = chosen.get("hash");
            if (!given.isTextual() || !HASH.matcher(given.textValue()).matches()) {
                throw ApplyParamsException.invalidRequest("Validator '" + title + "' has a hash that is not 28 bytes"
                        + " of hex: " + given);
            }
            hash = given.textValue();
        }
        JsonNode definitions = root.get("definitions");
        if (definitions != null && !definitions.isObject()) {
            throw ApplyParamsException.invalidRequest("The blueprint's definitions must be an object");
        }
        return new Source(hex(code.textValue(), "Validator '" + title + "''s compiledCode"),
                parameters(chosen, title), definitions, hash, title);
    }

    /** The validator entry with exactly the requested title; a single entry needs no selection. */
    private static JsonNode select(JsonNode validators, String requested) {
        if (validators == null || !validators.isArray() || validators.isEmpty()) {
            throw ApplyParamsException.invalidRequest("The blueprint's validators must be a non-empty array");
        }
        var titles = new ArrayList<String>();
        var seen = new HashSet<String>();
        for (JsonNode entry : validators) {
            if (!entry.isObject() || !entry.path("title").isTextual()) {
                throw ApplyParamsException.invalidRequest("Every validator entry in the blueprint must have a title");
            }
            String title = entry.get("title").textValue();
            if (!seen.add(title)) {
                throw ApplyParamsException.invalidRequest("The blueprint has two validators titled '" + title
                        + "'; a selection by title would be ambiguous");
            }
            titles.add(title);
        }
        if (requested == null) {
            if (validators.size() == 1) return validators.get(0);
            throw ApplyParamsException.invalidRequest("The blueprint has " + validators.size() + " validators; select"
                    + " one with script.validator, by its exact title: " + titles);
        }
        int index = titles.indexOf(requested);
        if (index < 0) {
            throw ApplyParamsException.invalidRequest("The blueprint has no validator titled '" + requested
                    + "' (the selection is an exact title, never an index); its titles: " + titles);
        }
        return validators.get(index);
    }

    /** The validator's declared parameters; a validator without a {@code parameters} field declares none. */
    private static List<Parameter> parameters(JsonNode validator, String title) {
        if (!validator.has("parameters")) return List.of();
        JsonNode parameters = validator.get("parameters");
        if (!parameters.isArray()) {
            throw ApplyParamsException.invalidRequest("Validator '" + title + "''s parameters must be an array");
        }
        var result = new ArrayList<Parameter>();
        for (int i = 0; i < parameters.size(); i++) {
            JsonNode parameter = parameters.get(i);
            if (!parameter.isObject() || !parameter.path("schema").isObject()) {
                throw ApplyParamsException.invalidRequest("Validator '" + title + "''s parameter " + (i + 1)
                        + " must be an object with a schema");
            }
            if (parameter.has("title") && !parameter.get("title").isTextual()) {
                throw ApplyParamsException.invalidRequest("Validator '" + title + "''s parameter " + (i + 1)
                        + " has a title that is not a string");
            }
            result.add(new Parameter(parameter.has("title") ? parameter.get("title").textValue() : null,
                    parameter.get("schema")));
        }
        return result;
    }

    private static byte[] hex(String text, String what) {
        if (!HEX.matcher(text).matches()) {
            throw ApplyParamsException.invalidRequest(what + " must be non-empty, even-length hex");
        }
        return HEX_FORMAT.parseHex(text);
    }

    // ---- checks ----

    private static void checkHash(Source source, CanonicalScript script, PlutusLanguage language) {
        String computed = HEX_FORMAT.formatHex(ScriptDecoder.scriptHash(language, script.singleCbor()));
        if (computed.equalsIgnoreCase(source.hash())) return;
        for (PlutusLanguage other : PlutusLanguage.values()) {
            if (HEX_FORMAT.formatHex(ScriptDecoder.scriptHash(other, script.singleCbor())).equalsIgnoreCase(source.hash())) {
                throw ApplyParamsException.invalidRequest("The blueprint hash of validator '" + source.validator()
                        + "' matches this script as " + languageName(other) + ", not " + languageName(language));
            }
        }
        throw ApplyParamsException.invalidRequest("The blueprint hash " + source.hash() + " of validator '"
                + source.validator() + "' does not match the script under any language (as "
                + languageName(language) + " it is " + computed + ")");
    }

    /** The builtin type ({@code #...}) a resolved schema names at its top, or {@code null}. */
    private static String builtinType(JsonNode resolved) {
        if (resolved != null && resolved.isObject() && resolved.path("dataType").isTextual()
                && resolved.get("dataType").textValue().startsWith("#")) {
            return resolved.get("dataType").textValue();
        }
        return null;
    }

    private static void checkCount(Source source, int given, boolean partial) {
        List<Parameter> declared = source.parameters();
        if (declared == null) return;
        String validator = "Validator '" + source.validator() + "'";
        if (given > declared.size()) {
            if (declared.isEmpty()) {
                throw ApplyParamsException.invalidRequest(validator + " declares no parameters, but " + given
                        + (given == 1 ? " value was" : " values were") + " given");
            }
            throw ApplyParamsException.invalidRequest(validator + " declares " + declared.size() + " parameters "
                    + titles(declared, 0) + ", but " + given + " values were given");
        }
        if (given < declared.size() && !partial) {
            throw ApplyParamsException.invalidRequest(validator + " declares " + declared.size() + " parameters and "
                    + given + (given == 1 ? " value was" : " values were") + " given; missing "
                    + titles(declared, given) + ". Give them, or set partial: true for a partial application.");
        }
    }

    private static String titles(List<Parameter> parameters, int from) {
        var titles = new ArrayList<String>();
        for (int i = from; i < parameters.size(); i++) {
            String title = parameters.get(i).title();
            titles.add((i + 1) + (title == null ? "" : " (" + title + ")"));
        }
        return titles.toString();
    }

    /** Names a parameter in errors by its 1-based index and, from a blueprint, its title. */
    private static String label(Source source, int index) {
        String title = source.parameters() == null || index >= source.parameters().size() ? null
                : source.parameters().get(index).title();
        return "Parameter " + (index + 1) + (title == null ? "" : " (" + title + ")");
    }

    /** The error body: the message, and the code the JavaScript client reports. */
    static Map<String, Object> errorBody(ApplyParamsException e) {
        var body = new LinkedHashMap<String, Object>();
        body.put("error", e.getMessage());
        body.put("code", e.kind().code());
        return body;
    }
}
