package org.julclang.tools.params;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.julclang.tools.model.ApplyParamsModels.Request;
import org.julclang.tools.model.ApplyParamsModels.Response;
import org.julclang.tools.model.MockTransaction.DataInput;
import org.julclang.tools.model.UplcModels.ScriptInput;
import org.julclang.tools.params.ApplyParamsException.Kind;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ADR-077 I4: {@code aiken blueprint apply} (Aiken v1.1.23), run independently, as the oracle. The fixtures under
 * {@code adr077/aiken} and the commands that made them are described in its README. The values are given here as
 * JSON; Aiken was given their canonical CBOR, written by hand.
 */
class AikenOracleTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String ORACLE = "oracle.oracle.else";
    static final String ANYDATA = "oracle.anydata.else";

    static final String OWNER = "{\"bytes\":\"" + "aa".repeat(28) + "\"}";
    static final String DEADLINE = "{\"int\":1700000000000}";
    static final String CONFIG = "{\"constructor\":0,\"fields\":[{\"bytes\":\"" + "bb".repeat(28) + "\"},{\"int\":5},"
            + "{\"list\":[{\"int\":1},{\"int\":2},{\"int\":3}]},{\"constructor\":1,\"fields\":[{\"int\":10}]}]}";

    static String resource(String name) {
        try (InputStream in = AikenOracleTest.class.getResourceAsStream("/adr077/aiken/" + name)) {
            assertNotNull(in, name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static JsonNode entry(String blueprint, String title) {
        try {
            for (JsonNode validator : JSON.readTree(resource(blueprint)).get("validators")) {
                if (validator.get("title").asText().equals(title)) return validator;
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        throw new IllegalArgumentException(title + " not in " + blueprint);
    }

    static Response apply(String blueprint, String title, Boolean partial, String... values) {
        var params = new ArrayList<DataInput>();
        for (String value : values) params.add(new DataInput("json", value));
        return ApplyParams.apply(new Request("V3", new ScriptInput(resource(blueprint), null, null, title), params,
                partial, null));
    }

    /** A definite CBOR byte string with a minimal header, written out by hand. */
    static String wrap(String hex) {
        int length = hex.length() / 2;
        String header = length < 24 ? String.format("%02x", 0x40 + length)
                : length < 256 ? String.format("58%02x", length) : String.format("59%04x", length);
        return header + hex;
    }

    /** I4 for an Aiken blueprint: cborHex is Aiken's compiledCode, compiledCode wraps it once more, equal hashes. */
    static void assertMatchesAiken(Response response, String aikenBlueprint, String title) {
        JsonNode aiken = entry(aikenBlueprint, title);
        assertEquals(aiken.get("compiledCode").asText(), response.cborHex(), aikenBlueprint);
        assertEquals(wrap(aiken.get("compiledCode").asText()), response.compiledCode(), aikenBlueprint);
        assertEquals(aiken.get("hash").asText(), response.scriptHash(), aikenBlueprint);
    }

    @Test
    void oneParameterAtATimeMatchesAiken() {
        var first = apply("plutus.json", ORACLE, true, OWNER);
        assertMatchesAiken(first, "out/oracle-1.json", ORACLE);
        assertEquals(2, first.remainingParams());
        var second = apply("out/oracle-1.json", ORACLE, true, DEADLINE);
        assertMatchesAiken(second, "out/oracle-2.json", ORACLE);
        assertEquals(1, second.remainingParams());
        var third = apply("out/oracle-2.json", ORACLE, null, CONFIG);
        assertMatchesAiken(third, "out/oracle-3.json", ORACLE);
        assertEquals(0, third.remainingParams());
        assertEquals(true, third.schemaChecked());
    }

    @Test
    void allParametersAtOnceMatchAikensThreeApplications() {
        var all = apply("plutus.json", ORACLE, null, OWNER, DEADLINE, CONFIG);
        assertMatchesAiken(all, "out/oracle-3.json", ORACLE);
        // And as CBOR values, Aiken's own input.
        var cbor = ApplyParams.apply(new Request("V3", new ScriptInput(resource("plutus.json"), null, null, ORACLE),
                List.of(new DataInput("cbor", "581c" + "aa".repeat(28)), new DataInput("cbor", "1b0000018bcfe56800"),
                        new DataInput("cbor", "d8799f581c" + "bb".repeat(28) + "059f010203ffd87a9f0affff")),
                null, null));
        assertEquals(all, cbor);
    }

    @Test
    void opaqueDataValuesMatchAiken() {
        // A map with a duplicate key keeps both entries, as Aiken does.
        String dupMap = "{\"map\":[{\"k\":{\"int\":1},\"v\":{\"int\":2}},{\"k\":{\"int\":1},\"v\":{\"int\":3}}]}";
        assertMatchesAiken(apply("plutus.json", ANYDATA, true, dupMap), "out/anydata-dupmap.json", ANYDATA);
        assertMatchesAiken(apply("out/anydata-dupmap.json", ANYDATA, null, "{\"constructor\":0,\"fields\":[{\"int\":7}]}"),
                "out/anydata-dupmap-some7.json", ANYDATA);
        assertMatchesAiken(apply("plutus.json", ANYDATA, true, "{\"bytes\":\"" + "00".repeat(65) + "\"}"),
                "out/anydata-bytes65.json", ANYDATA);
        assertMatchesAiken(apply("plutus.json", ANYDATA, true, "{\"int\":1208925819614629174706176}"),
                "out/anydata-int2p80.json", ANYDATA);
        assertMatchesAiken(apply("plutus.json", ANYDATA, true, "{\"constructor\":1,\"fields\":[{\"list\":[{\"int\":1},"
                + "{\"map\":[{\"k\":{\"bytes\":\"\"},\"v\":{\"int\":5}}]},{\"list\":[]}]},{\"constructor\":0,\"fields\":[]}]}"),
                "out/anydata-nested.json", ANYDATA);
    }

    /** Aiken's encoder and decoder handle the whole unsigned 64-bit constructor range, beyond Scalus' domain. */
    @Test
    void constructorIndicesAbove2To63MatchAiken() {
        assertMatchesAiken(apply("plutus.json", ANYDATA, true, "{\"constructor\":18446744073709551615,\"fields\":[]}"),
                "out/anydata-maxtag.json", ANYDATA);
        assertMatchesAiken(apply("plutus.json", ANYDATA, true, "{\"constructor\":9223372036854775808,\"fields\":[]}"),
                "out/anydata-tag2p63.json", ANYDATA);
    }

    /** Values Aiken's schema check refuses are refused here, and one it accepts is accepted. */
    @Test
    void schemaVerdictsAgreeWithAiken() {
        // out/refusals.txt: each of these made `aiken blueprint apply` fail.
        record Case(String blueprint, String title, String value) {}
        var refused = List.of(
                new Case("plutus.json", ORACLE, "{\"int\":1}"),                                       // owner-int
                new Case("out/oracle-1.json", ORACLE, "{\"bytes\":\"00\"}"),                         // deadline-bytes
                new Case("out/oracle-2.json", ORACLE, CONFIG.replace("{\"constructor\":1,\"fields\":[{\"int\":10}]}",
                        "{\"constructor\":2,\"fields\":[{\"int\":10}]}")),                            // config-mode2
                new Case("out/oracle-2.json", ORACLE, CONFIG.replace(",{\"constructor\":1,\"fields\":[{\"int\":10}]}",
                        "")),                                                                        // config-3fields
                new Case("out/oracle-2.json", ORACLE, CONFIG.replace("{\"list\":[{\"int\":1},{\"int\":2},{\"int\":3}]}",
                        "{\"list\":[{\"bytes\":\"01\"}]}")),                                          // config-tags-bytes
                new Case("out/anydata-dupmap.json", ANYDATA, "{\"constructor\":0,\"fields\":[{\"bytes\":\"01\"}]}"),
                new Case("out/anydata-dupmap.json", ANYDATA, "{\"constructor\":2,\"fields\":[]}"));   // limit-index2
        assertEquals(refused.size(), resource("out/refusals.txt").strip().split("\n").length);
        for (var c : refused) {
            var e = assertThrows(ApplyParamsException.class, () -> apply(c.blueprint(), c.title(), true, c.value()),
                    c::toString);
            assertEquals(Kind.PARAMETER_MISMATCH, e.kind(), e.getMessage());
        }
        // Aiken's ByteArray schema has no length bound: a 1-byte owner is accepted by both.
        assertMatchesAiken(apply("plutus.json", ORACLE, true, "{\"bytes\":\"01\"}"), "out/owner-short.json", ORACLE);
    }

    /** Aiken keeps a value's own CBOR encoding; vm.applyParams refuses a non-canonical one instead. */
    @Test
    void nonCanonicalValuesAikenKeepsAreRefused() {
        JsonNode definite = entry("out/anydata-definite-list.json", ANYDATA);
        var canonical = ApplyParams.apply(new Request("V3", new ScriptInput(resource("plutus.json"), null, null, ANYDATA),
                List.of(new DataInput("cbor", "9f01ff")), true, null));
        // Aiken applied 8101 as given; the canonical 9f01ff gives a different script, so a different hash.
        assertEquals(false, definite.get("compiledCode").asText().equals(canonical.cborHex()));
        var e = assertThrows(ApplyParamsException.class, () -> ApplyParams.apply(new Request("V3",
                new ScriptInput(resource("plutus.json"), null, null, ANYDATA), List.of(new DataInput("cbor", "8101")),
                true, null)));
        assertEquals(Kind.INVALID_REQUEST, e.kind());
    }

    /** Every Aiken output is itself accepted by the canonical-bytes rule, and returned unchanged. */
    @Test
    void aikenOutputsAreCanonical() {
        for (String blueprint : List.of("plutus.json", "out/oracle-1.json", "out/oracle-2.json", "out/oracle-3.json",
                "out/anydata-maxtag.json", "out/anydata-bytes65.json", "out/anydata-int2p80.json",
                "out/anydata-nested.json", "out/anydata-dupmap-some7.json")) {
            for (String title : List.of(ORACLE, ANYDATA)) {
                JsonNode aiken = entry(blueprint, title);
                var response = ApplyParams.apply(new Request("V3", new ScriptInput(resource(blueprint), null, null,
                        title), List.of(), true, null));
                assertEquals(aiken.get("compiledCode").asText(), response.cborHex(), blueprint + " " + title);
                assertEquals(aiken.get("hash").asText(), response.scriptHash(), blueprint + " " + title);
            }
        }
        // Scripts Aiken applied with values in a non-canonical encoding keep that encoding inside a data constant,
        // so they do not survive a FLAT round trip and are refused.
        for (String blueprint : List.of("out/anydata-noncanonical-input.json", "out/anydata-definite-list.json")) {
            var e = assertThrows(ApplyParamsException.class, () -> ApplyParams.apply(new Request("V3",
                    new ScriptInput(resource(blueprint), null, null, ANYDATA), List.of(), true, null)));
            assertEquals(true, e.getMessage().contains("data constant number 1 is not in the canonical Data encoding"),
                    e.getMessage());
        }
    }
}
