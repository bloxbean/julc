package org.julclang.tools.params;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.julclang.core.flat.UplcFlatDecoder;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.tools.model.ApplyParamsModels.Request;
import org.julclang.tools.model.ApplyParamsModels.Response;
import org.julclang.tools.model.MockTransaction.DataInput;
import org.julclang.tools.model.UplcModels.ScriptInput;
import org.julclang.tools.params.ApplyParamsException.Kind;
import org.julclang.tools.uplc.ScriptDecoder;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-077 D6 and D6a: application, the option matrix and every refusal. Expected bytes and hashes are independent
 * constants: the fixed vector of the ADR (Aiken v1.1.23 and Python's hashlib.blake2b over the tag and cborHex).
 */
class ApplyParamsTest {

    static final ObjectMapper JSON = new ObjectMapper();

    /** {@code (program 1.1.0 (lam p (con unit ())))}. */
    static final String FLAT = "0101002499";
    static final String SINGLE = "450101002499";
    static final String DOUBLE = "46450101002499";
    /** The integer 42 applied, wrapped once and twice. */
    static final String APPLIED_42 = "4c0101003249930102182a0001";
    static final String APPLIED_42_DOUBLE = "4d4c0101003249930102182a0001";
    /** Python: hashlib.blake2b(bytes([tag]) + bytes.fromhex(APPLIED_42), digest_size=28). */
    static final String HASH_42_V3 = "36f2b62169a767e3bcb49a0ff8dd3f473db91e15434ac69ace324ac6";
    static final String HASH_42_V2 = "c32369659a87707014447455f64c1dcf338d7a44748fb6b45a220439";
    static final String HASH_42_V1 = "52bd9ce0c0c6b39d3ba434631d0fedce6c0a9f12e86d808b84364c0c";
    /** Python: the same over SINGLE, the script before application. */
    static final String HASH_UNAPPLIED_V3 = "186e32faa80a26810392fda6d559c7ed4721a65ce1c9d4ef3e1c87b4";
    static final String HASH_UNAPPLIED_V2 = "41d3f4939f4bd204a247bbc95575a492fe1f3705c361de7f5e6c3727";

    static DataInput json(String value) {
        return new DataInput("json", value);
    }

    static DataInput int42() {
        return json("{\"int\":42}");
    }

    static Request request(String language, String script, List<DataInput> params) {
        return new Request(language, new ScriptInput(script, null, null, null), params, null, null);
    }

    static Request request(String language, ScriptInput script, List<DataInput> params, Boolean partial,
                           Boolean checkSchema) {
        return new Request(language, script, params, partial, checkSchema);
    }

    static ApplyParamsException refused(Kind kind, Request request) {
        var e = assertThrows(ApplyParamsException.class, () -> ApplyParams.apply(request));
        assertEquals(kind, e.kind(), e.getMessage());
        return e;
    }

    static void refused(Kind kind, Request request, String messagePart) {
        var e = refused(kind, request);
        assertTrue(e.getMessage().contains(messagePart), () -> "'" + e.getMessage() + "' lacks '" + messagePart + "'");
    }

    /** A blueprint with one validator per title, all with the fixed script, each declaring the given parameters. */
    static ObjectNode blueprint(String plutusVersion, String code, String hash, String... titles) {
        ObjectNode root = JSON.createObjectNode();
        ObjectNode preamble = root.putObject("preamble");
        preamble.put("title", "test");
        preamble.put("version", "0.0.0");
        if (plutusVersion != null) preamble.put("plutusVersion", plutusVersion);
        ArrayNode validators = root.putArray("validators");
        for (String title : titles) {
            ObjectNode entry = validators.addObject();
            entry.put("title", title);
            entry.put("compiledCode", code);
            if (hash != null) entry.put("hash", hash);
        }
        return root;
    }

    static ObjectNode withIntegerParameters(ObjectNode blueprint, int validator, String... titles) {
        ArrayNode parameters = ((ObjectNode) blueprint.withArray("validators").get(validator)).putArray("parameters");
        for (String title : titles) {
            ObjectNode parameter = parameters.addObject();
            parameter.put("title", title);
            parameter.putObject("schema").put("$ref", "#/definitions/Int");
        }
        blueprint.withObject("definitions").putObject("Int").put("dataType", "integer");
        return blueprint;
    }

    static ScriptInput script(Object json, String validator) {
        return new ScriptInput(json.toString(), null, null, validator);
    }

    // ---- the fixed vector ----

    @Test
    void fixedVectorV3() {
        Response response = ApplyParams.apply(request("V3", DOUBLE, List.of(int42())));
        assertEquals(APPLIED_42, response.cborHex());
        assertEquals(APPLIED_42_DOUBLE, response.compiledCode());
        assertEquals(HASH_42_V3, response.scriptHash());
        assertEquals("V3", response.language());
        assertEquals(1, response.paramsApplied());
        assertNull(response.remainingParams());
        assertEquals(false, response.schemaChecked());
    }

    @Test
    void crossLanguageVectorsGiveIdenticalBytesAndThreeHashes() {
        var v1 = ApplyParams.apply(request("V1", FLAT, List.of(int42())));
        var v2 = ApplyParams.apply(request("V2", FLAT, List.of(int42())));
        var v3 = ApplyParams.apply(request("V3", FLAT, List.of(int42())));
        assertEquals(APPLIED_42, v1.cborHex());
        assertEquals(APPLIED_42, v2.cborHex());
        assertEquals(APPLIED_42, v3.cborHex());
        assertEquals(HASH_42_V1, v1.scriptHash());
        assertEquals(HASH_42_V2, v2.scriptHash());
        assertEquals(HASH_42_V3, v3.scriptHash());
    }

    @ParameterizedTest
    @ValueSource(strings = {FLAT, SINGLE, DOUBLE})
    void everyWrappingGivesTheSameResult(String script) {
        var response = ApplyParams.apply(request("V3", script, List.of(int42())));
        assertEquals(APPLIED_42, response.cborHex());
        assertEquals(HASH_42_V3, response.scriptHash());
    }

    @Test
    void sameValueInEveryFormatGivesTheSameScript() {
        for (var value : List.of(int42(), new DataInput("cbor", "182a"), new DataInput("uplc", "I 42"))) {
            assertEquals(APPLIED_42, ApplyParams.apply(request("V3", SINGLE, List.of(value))).cborHex());
        }
    }

    @Test
    void zeroParametersReturnTheInputBytes() {
        var response = ApplyParams.apply(request("V3", DOUBLE, List.of()));
        assertEquals(SINGLE, response.cborHex());
        assertEquals(DOUBLE, response.compiledCode());
        assertEquals(HASH_UNAPPLIED_V3, response.scriptHash());
        assertEquals(0, response.paramsApplied());
        var nullParams = ApplyParams.apply(request("V3", FLAT, null));
        assertEquals(SINGLE, nullParams.cborHex());
    }

    @Test
    void appliedScriptDecodesAndHasTheValue() {
        var response = ApplyParams.apply(request("V3", SINGLE, List.of(int42(), json("{\"bytes\":\"cafe\"}"))));
        byte[] flat = CanonicalScript.unwrap(HexFormat.of().parseHex(response.cborHex()), "test");
        var program = UplcFlatDecoder.decodeProgram(flat);
        assertTrue(program.toString().contains("42"), program.toString());
        assertEquals(2, response.paramsApplied());
    }

    // ---- language ----

    @ParameterizedTest
    @ValueSource(strings = {"v3", "notV3", "auto", "PlutusV3", "V4", "", " V3"})
    void languageMustBeExactlyV1V2OrV3(String language) {
        refused(Kind.INVALID_REQUEST, request(language, FLAT, List.of(int42())), "language");
    }

    @Test
    void languageIsRequiredEvenWhenTheInputStatesIt() {
        refused(Kind.INVALID_REQUEST, request(null, FLAT, List.of(int42())), "must state language");
        // UPLC 1.1.0 is allowed under all three languages at protocol 11: nothing is inferred from the version.
        refused(Kind.INVALID_REQUEST, request(null, SINGLE, List.of()), "must state language");
        refused(Kind.INVALID_REQUEST, request(null, script(blueprint("v3", SINGLE, null, "a"), null),
                List.of(), null, null), "must state language");
        refused(Kind.INVALID_REQUEST, request(null, script(JSON.createObjectNode().put("type", "PlutusScriptV3")
                .put("cborHex", DOUBLE), null), List.of(), null, null), "must state language");
    }

    @Test
    void scriptLanguageAndScriptParamsMustBeEmpty() {
        refused(Kind.INVALID_REQUEST, request("V3", new ScriptInput(FLAT, null, "V3", null), List.of(), null, null),
                "script.language must be empty");
        refused(Kind.INVALID_REQUEST, request("V3", new ScriptInput(FLAT, null, "auto", null), List.of(), null, null),
                "script.language must be empty");
        refused(Kind.INVALID_REQUEST, request("V3", new ScriptInput(FLAT, List.of(int42()), null, null), List.of(),
                null, null), "script.params must be empty");
        // Empty forms are accepted.
        assertEquals(SINGLE, ApplyParams.apply(request("V3", new ScriptInput(FLAT, List.of(), "", null), List.of(),
                null, null)).cborHex());
    }

    // ---- cross-checks ----

    @Test
    void envelopeTypeIsCrossChecked() {
        var envelope = JSON.createObjectNode().put("type", "PlutusScriptV3").put("description", "")
                .put("cborHex", DOUBLE);
        assertEquals(HASH_42_V3, ApplyParams.apply(request("V3", script(envelope, null), List.of(int42()), null, null))
                .scriptHash());
        refused(Kind.INVALID_REQUEST, request("V2", script(envelope, null), List.of(int42()), null, null),
                "states language V2, but the text envelope's type is PlutusScriptV3");
        for (String type : List.of("PlutusScriptV4", "SimpleScript", "plutusscriptv3", "PlutusV3")) {
            for (String language : List.of("V1", "V2", "V3")) {
                refused(Kind.INVALID_REQUEST, request(language, script(envelope.deepCopy().put("type", type), null),
                        List.of(), null, null), "is not recognised");
            }
        }
        refused(Kind.INVALID_REQUEST, request("V3", script(envelope.deepCopy().putNull("type"), null), List.of(),
                null, null), "is not recognised");
        refused(Kind.INVALID_REQUEST, request("V3", script(envelope.deepCopy().put("type", 3), null), List.of(),
                null, null), "is not recognised");
        // Absent language information is no information.
        var untyped = JSON.createObjectNode().put("cborHex", DOUBLE);
        assertEquals(HASH_42_V2, ApplyParams.apply(request("V2", script(untyped, null), List.of(int42()), null, null))
                .scriptHash());
    }

    @Test
    void preamblePlutusVersionIsCrossChecked() {
        refused(Kind.INVALID_REQUEST, request("V2", script(blueprint("v3", SINGLE, null, "a"), null), List.of(),
                null, null), "states language V2, but the blueprint's preamble.plutusVersion is v3");
        for (String version : List.of("v4", "V3", "3", "plutusV3")) {
            for (String language : List.of("V1", "V2", "V3")) {
                refused(Kind.INVALID_REQUEST, request(language, script(blueprint(version, SINGLE, null, "a"), null),
                        List.of(), null, null), "is not recognised");
            }
        }
        var nullVersion = blueprint(null, SINGLE, null, "a");
        nullVersion.withObject("preamble").putNull("plutusVersion");
        refused(Kind.INVALID_REQUEST, request("V3", script(nullVersion, null), List.of(), null, null),
                "is not recognised");
        // No plutusVersion: no information.
        assertEquals(SINGLE, ApplyParams.apply(request("V1", script(blueprint(null, SINGLE, null, "a"), null),
                List.of(), null, null)).cborHex());
    }

    @Test
    void blueprintHashIsCheckedBeforeApplicationUnderTheStatedLanguage() {
        var matching = withIntegerParameters(blueprint("v3", SINGLE, HASH_UNAPPLIED_V3, "a"), 0, "n");
        var applied = ApplyParams.apply(request("V3", script(matching, null), List.of(int42()), null, null));
        assertEquals(HASH_42_V3, applied.scriptHash());
        // Upper-case hex is the same hash.
        var upper = withIntegerParameters(blueprint("v3", SINGLE, HASH_UNAPPLIED_V3.toUpperCase(), "a"), 0, "n");
        assertEquals(HASH_42_V3, ApplyParams.apply(request("V3", script(upper, null), List.of(int42()), null, null))
                .scriptHash());
        // The hash matches the script as V2: the error says so.
        var v2Hash = withIntegerParameters(blueprint(null, SINGLE, HASH_UNAPPLIED_V2, "a"), 0, "n");
        refused(Kind.INVALID_REQUEST, request("V3", script(v2Hash, null), List.of(int42()), null, null),
                "matches this script as V2, not V3");
        // A hash that matches no language.
        var wrong = withIntegerParameters(blueprint("v3", SINGLE, "00".repeat(28), "a"), 0, "n");
        refused(Kind.INVALID_REQUEST, request("V3", script(wrong, null), List.of(int42()), null, null),
                "does not match the script under any language");
        refused(Kind.INVALID_REQUEST, request("V3", script(wrong, null), List.of(int42()), null, false),
                "does not match");
        var malformed = blueprint("v3", SINGLE, "abc", "a");
        refused(Kind.INVALID_REQUEST, request("V3", script(malformed, null), List.of(), null, null),
                "not 28 bytes of hex");
    }

    // ---- validator selection ----

    @Test
    void validatorSelection() {
        var single = blueprint("v3", SINGLE, null, "only");
        assertEquals(SINGLE, ApplyParams.apply(request("V3", script(single, null), List.of(), null, null)).cborHex());
        assertEquals(SINGLE, ApplyParams.apply(request("V3", script(single, "only"), List.of(), null, null))
                .cborHex());
        refused(Kind.INVALID_REQUEST, request("V3", script(single, "0"), List.of(), null, null),
                "never an index");
        refused(Kind.INVALID_REQUEST, request("V3", script(single, "Only"), List.of(), null, null),
                "no validator titled 'Only'");
        var several = blueprint("v3", SINGLE, null, "a.spend", "a.mint");
        refused(Kind.INVALID_REQUEST, request("V3", script(several, null), List.of(), null, null),
                "select one with script.validator");
        refused(Kind.INVALID_REQUEST, request("V3", script(several, "1"), List.of(), null, null), "never an index");
        // A selected entry of a multi-purpose blueprint gives the same script as the others with the same code.
        var spend = ApplyParams.apply(request("V3", script(several, "a.spend"), List.of(), null, null));
        var mint = ApplyParams.apply(request("V3", script(several, "a.mint"), List.of(), null, null));
        assertEquals(spend, mint);
        var duplicate = blueprint("v3", SINGLE, null, "a", "a");
        refused(Kind.INVALID_REQUEST, request("V3", script(duplicate, "a"), List.of(), null, null),
                "two validators titled 'a'");
        var untitled = blueprint("v3", SINGLE, null, "a");
        ((ObjectNode) untitled.withArray("validators").get(0)).remove("title");
        refused(Kind.INVALID_REQUEST, request("V3", script(untitled, null), List.of(), null, null), "must have a title");
        refused(Kind.INVALID_REQUEST, request("V3", new ScriptInput(FLAT, null, null, "a"), List.of(), null, null),
                "not a blueprint");
    }

    @Test
    void identicalCodeWithDifferentDeclarationsNeedsASelectionInEveryOrder() {
        // Same compiledCode, one entry declares one integer parameter, the other two. Without a selection the
        // request is refused whatever the order, so the chosen contract never depends on the entries' order.
        for (boolean swapped : List.of(false, true)) {
            var bp = blueprint("v3", SINGLE, null, swapped ? "two" : "one", swapped ? "one" : "two");
            withIntegerParameters(bp, swapped ? 1 : 0, "a");
            withIntegerParameters(bp, swapped ? 0 : 1, "a", "b");
            refused(Kind.INVALID_REQUEST, request("V3", script(bp, null), List.of(int42()), null, null),
                    "select one with script.validator");
            assertEquals(0, ApplyParams.apply(request("V3", script(bp, "one"), List.of(int42()), null, null))
                    .remainingParams());
            assertEquals(1, ApplyParams.apply(request("V3", script(bp, "two"), List.of(int42()), true, null))
                    .remainingParams());
            refused(Kind.INVALID_REQUEST, request("V3", script(bp, "two"), List.of(int42()), null, null),
                    "missing [2 (b)]");
        }
    }

    // ---- canonical bytes ----

    @Test
    void nonCanonicalScriptBytesAreRefused() {
        refused(Kind.INVALID_REQUEST, request("V3", "58050101002499", List.of(int42())), "non-minimal length");
        refused(Kind.INVALID_REQUEST, request("V3", "5f450101002499ff", List.of(int42())), "indefinite-length");
        refused(Kind.INVALID_REQUEST, request("V3", "5f450101002499ff", List.of()), "indefinite-length");
        refused(Kind.INVALID_REQUEST, request("V3", "46450101002499" + "00", List.of(int42())),
                "The outer CBOR wrapper is followed by 1 trailing bytes");
        refused(Kind.INVALID_REQUEST, request("V3", "450101002499" + "00", List.of(int42())),
                "The outer CBOR wrapper is followed by 1 trailing bytes");
        refused(Kind.INVALID_REQUEST, request("V3", "4701010024990000", List.of()),
                "The script has trailing bytes after the program");
        refused(Kind.INVALID_REQUEST, request("V3", "0101002499" + "00", List.of(int42())), "trailing bytes");
        refused(Kind.INVALID_REQUEST, request("V3", "4601010024", List.of()), "truncated");
        // The inner wrapper is non-minimal.
        refused(Kind.INVALID_REQUEST, request("V3", "4758050101002499", List.of()), "non-minimal length");
        // Zero parameters are no exception: the input is checked all the same.
        refused(Kind.INVALID_REQUEST, request("V3", "58050101002499", List.of()), "non-minimal length");
    }

    @Test
    void nonCanonicalFlatIsRefused() {
        // (program 1.1.0 (con data (I 42))) with the data constant written as a two-byte CBOR integer 0x1900 0x2a:
        // the FLAT decoder reads it as I 42, but encoding again gives 182a, so the input is not canonical.
        String nonCanonical = flatWithDataConstant("19002a");
        String canonicalFlat = flatWithDataConstant("182a");
        assertEquals(canonicalFlat, HexFormat.of().formatHex(UplcFlatEncoder.encodeProgram(
                UplcFlatDecoder.decodeProgram(HexFormat.of().parseHex(canonicalFlat)))));
        ApplyParams.apply(request("V3", canonicalFlat, List.of()));
        refused(Kind.INVALID_REQUEST, request("V3", nonCanonical, List.of()), "not canonical");
        // A data constant with bytes after its CBOR item: Plutus's decoder ignores them, so the bytes would change.
        refused(Kind.INVALID_REQUEST, request("V3", flatWithDataConstant("182a00"), List.of()), "not canonical");
    }

    /** {@code (program 1.1.0 (con data <cbor>))} encoded by hand. */
    static String flatWithDataConstant(String cborHex) {
        byte[] cbor = HexFormat.of().parseHex(cborHex);
        var bits = new StringBuilder();
        bits.append(bits8(1)).append(bits8(1)).append(bits8(0)); // version 1.1.0
        bits.append("0100");                                      // term tag 4: constant
        bits.append("1").append("1000").append("0");             // type list [8 = data]
        // byte string: pad to a byte boundary, then chunks of at most 255 bytes, then a 0 chunk
        while (bits.length() % 8 != 0) bits.append(bits.length() % 8 == 7 ? "1" : "0");
        bits.append(bits8(cbor.length));
        for (byte b : cbor) bits.append(bits8(b & 0xFF));
        bits.append(bits8(0));
        // program filler
        do bits.append(bits.length() % 8 == 7 ? "1" : "0"); while (bits.length() % 8 != 0);
        byte[] out = new byte[bits.length() / 8];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(bits.substring(8 * i, 8 * i + 8), 2);
        return HexFormat.of().formatHex(out);
    }

    private static String bits8(int value) {
        String s = Integer.toBinaryString(value);
        return "0".repeat(8 - s.length()) + s;
    }

    @Test
    void uplcTextAndBadHexAreRefused() {
        refused(Kind.INVALID_REQUEST, request("V3", "(program 1.1.0 (lam p (con unit ())))", List.of(int42())),
                "UPLC text is not accepted");
        refused(Kind.INVALID_REQUEST, request("V3", "0x0101002499", List.of()), "even-length hex");
        refused(Kind.INVALID_REQUEST, request("V3", "010100249", List.of()), "even-length hex");
        refused(Kind.INVALID_REQUEST, request("V3", "   ", List.of()), "no script");
        refused(Kind.INVALID_REQUEST, new Request("V3", null, List.of(), null, null), "no script");
        refused(Kind.INVALID_REQUEST, request("V3", "[1]", List.of()), "even-length hex");
        refused(Kind.INVALID_REQUEST, request("V3", "{\"foo\":1}", List.of()), "blueprint");
        refused(Kind.INVALID_REQUEST, request("V3", "{\"validators\":[],\"cborHex\":\"00\"}", List.of()),
                "both validators and cborHex");
    }

    @Test
    void scriptDecoderWarningsAreErrors() {
        // ScriptDecoder warns about trailing bytes after the program; applyParams refuses them (before it would
        // even ask ScriptDecoder).
        var decoded = ScriptDecoder.decode(new ScriptInput("0101002499ff", List.of(), "V3", null));
        assertTrue(decoded.warnings().stream().anyMatch(w -> w.contains("trailing")), decoded.warnings().toString());
        refused(Kind.INVALID_REQUEST, request("V3", "0101002499ff", List.of()), "trailing bytes");
    }

    // ---- strict JSON ----

    @Test
    void duplicateMembersAreRefusedInEitherOrder() {
        for (String types : List.of("\"type\":\"PlutusScriptV3\",\"type\":\"PlutusScriptV2\"",
                "\"type\":\"PlutusScriptV2\",\"type\":\"PlutusScriptV3\"")) {
            for (String language : List.of("V2", "V3")) {
                refused(Kind.INVALID_REQUEST, request(language, "{" + types + ",\"cborHex\":\"" + DOUBLE + "\"}",
                        List.of()), "Duplicate field 'type'");
            }
        }
        String bp = blueprint("v3", SINGLE, null, "a").toString();
        refused(Kind.INVALID_REQUEST, request("V3", bp.replaceFirst("\\{", "{\"validators\":[],"), List.of()),
                "Duplicate field 'validators'");
        refused(Kind.INVALID_REQUEST, request("V3", bp.replace("\"compiledCode\"", "\"title\":\"b\",\"compiledCode\""),
                List.of()), "Duplicate field 'title'");
        refused(Kind.INVALID_REQUEST, request("V3", bp.replace("\"plutusVersion\":\"v3\"",
                "\"plutusVersion\":\"v2\",\"plutusVersion\":\"v3\""), List.of()), "Duplicate field 'plutusVersion'");
        refused(Kind.INVALID_REQUEST, request("V3", bp + " {}", List.of()), "not strict JSON");
        refused(Kind.INVALID_REQUEST, request("V3", FLAT, List.of(json("{\"int\":1,\"int\":2}"))),
                "Duplicate field 'int'");
        refused(Kind.INVALID_REQUEST, request("V3", FLAT, List.of(json("{\"int\":1} {\"int\":2}"))),
                "not strict JSON");
    }

    @Test
    void duplicateMembersInsideSchemasAreRefused() {
        var bp = withIntegerParameters(blueprint("v3", SINGLE, null, "a"), 0, "n").toString();
        String dup = bp.replace("\"dataType\":\"integer\"", "\"dataType\":\"bytes\",\"dataType\":\"integer\"");
        refused(Kind.INVALID_REQUEST, request("V3", dup, List.of(int42())), "Duplicate field 'dataType'");
        refused(Kind.INVALID_REQUEST, request("V3", script(dup, null), List.of(int42()), null, false),
                "Duplicate field 'dataType'");
    }

    // ---- values and counts ----

    @Test
    void valuesNeedAnExplicitFormat() {
        refused(Kind.INVALID_REQUEST, request("V3", FLAT, List.of(new DataInput(null, "42"))),
                "Parameter 1: the value has no format");
        refused(Kind.INVALID_REQUEST, request("V3", FLAT, List.of(new DataInput("auto", "{\"int\":42}"))),
                "Parameter 1: format auto is not accepted");
        refused(Kind.INVALID_REQUEST, request("V3", FLAT, List.of(int42(), new DataInput("JSON", "{\"int\":42}"))),
                "Parameter 2: unknown value format 'JSON'");
        refused(Kind.INVALID_REQUEST, request("V3", FLAT, Arrays.asList(int42(), null)), "Parameter 2");
    }

    @Test
    void optionMatrix() {
        var bp = withIntegerParameters(blueprint("v3", SINGLE, null, "v"), 0, "a", "b");
        var two = List.of(int42(), int42());
        var one = List.of(int42());
        var three = List.of(int42(), int42(), int42());
        // yes | equal | any | true -> applied, 0, true
        for (Boolean partial : new Boolean[]{null, false, true}) {
            var r = ApplyParams.apply(request("V3", script(bp, null), two, partial, true));
            assertEquals(0, r.remainingParams());
            assertTrue(r.schemaChecked());
            assertEquals(2, r.paramsApplied());
            var defaultCheck = ApplyParams.apply(request("V3", script(bp, null), two, partial, null));
            assertTrue(defaultCheck.schemaChecked());
            // yes | equal | any | false -> applied, 0, false
            var unchecked = ApplyParams.apply(request("V3", script(bp, null), two, partial, false));
            assertEquals(0, unchecked.remainingParams());
            assertEquals(false, unchecked.schemaChecked());
            assertEquals(r.cborHex(), unchecked.cborHex());
        }
        // yes | fewer | false | any -> error naming the missing titles
        for (Boolean check : new Boolean[]{null, true, false}) {
            for (Boolean partial : new Boolean[]{null, false}) {
                refused(Kind.INVALID_REQUEST, request("V3", script(bp, null), one, partial, check),
                        "missing [2 (b)]");
            }
        }
        // yes | fewer | true | true/false -> applied, declared - given, as checkSchema
        var partialChecked = ApplyParams.apply(request("V3", script(bp, null), one, true, true));
        assertEquals(1, partialChecked.remainingParams());
        assertTrue(partialChecked.schemaChecked());
        assertEquals(APPLIED_42, partialChecked.cborHex());
        var partialUnchecked = ApplyParams.apply(request("V3", script(bp, null), one, true, false));
        assertEquals(1, partialUnchecked.remainingParams());
        assertEquals(false, partialUnchecked.schemaChecked());
        var none = ApplyParams.apply(request("V3", script(bp, null), List.of(), true, true));
        assertEquals(2, none.remainingParams());
        assertTrue(none.schemaChecked());
        // yes | more | any | any -> error naming the declared titles
        for (Boolean check : new Boolean[]{null, true, false}) {
            for (Boolean partial : new Boolean[]{null, false, true}) {
                refused(Kind.INVALID_REQUEST, request("V3", script(bp, null), three, partial, check),
                        "declares 2 parameters [1 (a), 2 (b)], but 3 values were given");
            }
        }
        // blueprint without parameters (declares 0): any value -> error; none -> the script as it is
        var bare = blueprint("v3", SINGLE, null, "v");
        for (Boolean check : new Boolean[]{null, true, false}) {
            for (Boolean partial : new Boolean[]{null, false, true}) {
                refused(Kind.INVALID_REQUEST, request("V3", script(bare, null), one, partial, check),
                        "declares no parameters");
                var asIs = ApplyParams.apply(request("V3", script(bare, null), List.of(), partial, check));
                assertEquals(SINGLE, asIs.cborHex());
                assertEquals(0, asIs.remainingParams());
                assertEquals(!Boolean.FALSE.equals(check), asIs.schemaChecked());
            }
        }
        // no declaration (CBOR, flat, envelope) -> applied, null, false
        var envelope = JSON.createObjectNode().put("type", "PlutusScriptV3").put("cborHex", DOUBLE);
        for (Boolean check : new Boolean[]{null, true, false}) {
            for (Boolean partial : new Boolean[]{null, false, true}) {
                for (var input : List.of(new ScriptInput(FLAT, null, null, null),
                        new ScriptInput(DOUBLE, null, null, null), script(envelope, null))) {
                    var r = ApplyParams.apply(request("V3", input, three, partial, check));
                    assertNull(r.remainingParams());
                    assertEquals(false, r.schemaChecked());
                    assertEquals(3, r.paramsApplied());
                }
            }
        }
    }

    @Test
    void builtinTopLevelSchemaIsRefusedWhateverCheckSchemaSays() {
        var bp = blueprint("v3", SINGLE, null, "v");
        var parameter = ((ObjectNode) bp.withArray("validators").get(0)).putArray("parameters").addObject();
        parameter.put("title", "n");
        parameter.putObject("schema").put("$ref", "#/definitions/BuiltinInt");
        bp.withObject("definitions").putObject("BuiltinInt").put("dataType", "#integer");
        for (Boolean check : new Boolean[]{null, true, false}) {
            refused(Kind.INVALID_REQUEST, request("V3", script(bp, null), List.of(int42()), null, check),
                    "Parameter 1 (n): its schema is the builtin type #integer");
        }
        // Inline, without $ref.
        var inline = blueprint("v3", SINGLE, null, "v");
        var p2 = ((ObjectNode) inline.withArray("validators").get(0)).putArray("parameters").addObject();
        p2.putObject("schema").put("dataType", "#bytes");
        refused(Kind.INVALID_REQUEST, request("V3", script(inline, null), List.of(json("{\"bytes\":\"00\"}")), null,
                false), "builtin type #bytes");
        // Through two references.
        var chain = blueprint("v3", SINGLE, null, "v");
        ((ObjectNode) chain.withArray("validators").get(0)).putArray("parameters").addObject().putObject("schema")
                .put("$ref", "#/definitions/A");
        chain.withObject("definitions").putObject("A").put("$ref", "#/definitions/B");
        chain.withObject("definitions").putObject("B").put("dataType", "#integer");
        refused(Kind.INVALID_REQUEST, request("V3", script(chain, null), List.of(int42()), null, false),
                "builtin type #integer");
    }

    @Test
    void errorBodyCarriesTheCode() {
        var result = ApplyParams.run(request(null, FLAT, List.of()));
        assertEquals(400, result.status());
        assertEquals("invalid-request", ((Map<?, ?>) result.body()).get("code"));
        var ok = ApplyParams.run(request("V3", FLAT, List.of(int42())));
        assertEquals(200, ok.status());
    }

    @Nested
    class ReferenceAdapter {
        /** I4: equal to the adapted vm.decode request (script.language := language, script.params := values). */
        @Test
        void equalsScriptDecoderWhereTheLegacyReaderReadsTheSameValues() {
            var values = List.of(int42(), json("{\"bytes\":\"cafe\"}"),
                    json("{\"constructor\":3,\"fields\":[{\"list\":[{\"int\":-1},{\"int\":18446744073709551617}]}]}"),
                    json("{\"map\":[{\"k\":{\"int\":1},\"v\":{\"bytes\":\"\"}}]}"),
                    new DataInput("cbor", "d8799f01ff"), new DataInput("uplc", "Constr 0 [I 1, B #00]"));
            for (String language : List.of("V1", "V2", "V3")) {
                for (int n = 0; n <= values.size(); n++) {
                    var params = values.subList(0, n);
                    for (String input : List.of(FLAT, SINGLE, DOUBLE)) {
                        var applied = ApplyParams.apply(request(language, input, params));
                        var legacy = ScriptDecoder.decode(new ScriptInput(input, params, language, null));
                        assertEquals(HexFormat.of().formatHex(legacy.compiledCode()), applied.compiledCode());
                        assertEquals(HexFormat.of().formatHex(legacy.scriptHash()), applied.scriptHash());
                    }
                }
            }
        }

        @Test
        void legacyReaderDiffersOnlyOnDuplicateMapKeys() {
            var duplicate = json("{\"map\":[{\"k\":{\"int\":1},\"v\":{\"int\":2}},{\"k\":{\"int\":1},\"v\":{\"int\":3}}]}");
            var applied = ApplyParams.apply(request("V3", FLAT, List.of(duplicate)));
            var legacy = ScriptDecoder.decode(new ScriptInput(FLAT, List.of(duplicate), "V3", null));
            // The legacy reader drops the first entry's value; the strict one keeps both entries, in order.
            assertTrue(!HexFormat.of().formatHex(legacy.compiledCode()).equals(applied.compiledCode()));
            var both = ApplyParams.apply(request("V3", FLAT, List.of(new DataInput("cbor", "a201020103"))));
            assertEquals(both.cborHex(), applied.cborHex());
        }
    }
}
