package org.julclang.tools.params;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.julclang.core.PlutusData;
import org.julclang.tools.model.MockTransaction.DataInput;
import org.julclang.tools.model.UplcModels.ScriptInput;
import org.julclang.tools.params.ApplyParamsException.Kind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-077 D6b and I5: each keyword and applicator with conforming values and values that differ in one place, every
 * kind of invalid schema, and the cost and depth bounds.
 */
class DataSchemaCheckerTest {

    static final ObjectMapper JSON = new ObjectMapper();

    /** JSON with single quotes, for readability. */
    static JsonNode q(String text) {
        try {
            return JSON.readTree(text.replace('\'', '"'));
        } catch (Exception e) {
            throw new IllegalArgumentException(text, e);
        }
    }

    static PlutusData v(String json) {
        return StrictDataReader.fromJson(q(json));
    }

    static DataSchemaChecker checker(String definitions) {
        return new DataSchemaChecker(definitions == null ? null : q(definitions), DataSchemaChecker.DEFAULT_WORK_BOUND);
    }

    static void accepts(String schema, String definitions, String... values) {
        var checker = checker(definitions);
        var compiled = checker.compile(q(schema), "P");
        for (String value : values) checker.check(compiled, v(value), "P");
    }

    static String mismatch(String schema, String definitions, String value) {
        var checker = checker(definitions);
        var compiled = checker.compile(q(schema), "P");
        var e = assertThrows(ApplyParamsException.class, () -> checker.check(compiled, v(value), "P"),
                () -> value + " should not match " + schema);
        assertEquals(Kind.PARAMETER_MISMATCH, e.kind());
        return e.getMessage();
    }

    static void mismatch(String schema, String definitions, String value, String messagePart) {
        String message = mismatch(schema, definitions, value);
        assertTrue(message.contains(messagePart), () -> "'" + message + "' lacks '" + messagePart + "'");
    }

    static String invalid(String schema, String definitions) {
        var e = assertThrows(ApplyParamsException.class, () -> checker(definitions).compile(q(schema), "P"),
                () -> schema + " should be an invalid schema");
        assertEquals(Kind.INVALID_SCHEMA, e.kind(), e.getMessage());
        return e.getMessage();
    }

    static void invalid(String schema, String definitions, String messagePart) {
        String message = invalid(schema, definitions);
        assertTrue(message.contains(messagePart), () -> "'" + message + "' lacks '" + messagePart + "'");
    }

    // ==== keywords ====

    @Test
    void integerBoundsAtAndAroundTheLimits() {
        String schema = "{'dataType':'integer','minimum':-5,'maximum':10}";
        accepts(schema, null, "{'int':-5}", "{'int':10}", "{'int':0}");
        mismatch(schema, null, "{'int':-6}", "expected an integer of at least -5, found -6");
        mismatch(schema, null, "{'int':11}", "expected an integer of at most 10, found 11");
        String exclusive = "{'dataType':'integer','exclusiveMinimum':-5,'exclusiveMaximum':10}";
        accepts(exclusive, null, "{'int':-4}", "{'int':9}");
        mismatch(exclusive, null, "{'int':-5}", "greater than -5");
        mismatch(exclusive, null, "{'int':10}", "less than 10");
        // Arbitrary precision: bounds and values beyond 64 bits.
        BigInteger big = BigInteger.ONE.shiftLeft(100);
        String huge = "{'dataType':'integer','maximum':" + big + "}";
        accepts(huge, null, "{'int':" + big + "}");
        mismatch(huge, null, "{'int':" + big.add(BigInteger.ONE) + "}", "at most " + big);
        String multiple = "{'dataType':'integer','multipleOf':" + big + "}";
        accepts(multiple, null, "{'int':0}", "{'int':" + big.negate() + "}", "{'int':" + big.shiftLeft(1) + "}");
        mismatch(multiple, null, "{'int':" + big.add(BigInteger.ONE) + "}", "expected a multiple of " + big);
        accepts("{'dataType':'integer','multipleOf':3}", null, "{'int':-9}");
        mismatch("{'dataType':'integer','multipleOf':3}", null, "{'int':-10}", "multiple of 3");
    }

    @Test
    void bytesLengthIsInBytesAndEnumIgnoresCase() {
        String schema = "{'dataType':'bytes','minLength':2,'maxLength':3}";
        accepts(schema, null, "{'bytes':'0000'}", "{'bytes':'000000'}");
        mismatch(schema, null, "{'bytes':'00'}", "expected at least 2 bytes, found 1");
        mismatch(schema, null, "{'bytes':'00000000'}", "expected at most 3 bytes, found 4");
        String enumSchema = "{'dataType':'bytes','enum':['CAFE','00']}";
        accepts(enumSchema, null, "{'bytes':'cafe'}", "{'bytes':'CaFe'}", "{'bytes':'00'}");
        mismatch(enumSchema, null, "{'bytes':'cafd'}", "expected one of [cafe, 00], found cafd");
        mismatch(enumSchema, null, "{'bytes':''}", "found empty bytes");
        accepts("{'dataType':'bytes','enum':['']}", null, "{'bytes':''}");
    }

    @Test
    void listItemsSingleAndPositional() {
        String single = "{'dataType':'list','items':{'dataType':'integer'},'minItems':1,'maxItems':2}";
        accepts(single, null, "{'list':[{'int':1}]}", "{'list':[{'int':1},{'int':2}]}");
        mismatch(single, null, "{'list':[]}", "expected at least 1 elements, found 0");
        mismatch(single, null, "{'list':[{'int':1},{'int':2},{'int':3}]}", "at most 2 elements");
        mismatch(single, null, "{'list':[{'int':1},{'bytes':'00'}]}", "at list[1]: expected int, found bytes");
        String tuple = "{'dataType':'list','items':[{'dataType':'integer'},{'dataType':'bytes'}]}";
        accepts(tuple, null, "{'list':[{'int':1},{'bytes':'00'}]}");
        mismatch(tuple, null, "{'list':[{'int':1}]}", "expected exactly 2 elements, found 1");
        mismatch(tuple, null, "{'list':[{'int':1},{'bytes':'00'},{'int':1}]}", "expected exactly 2 elements, found 3");
        mismatch(tuple, null, "{'list':[{'bytes':'00'},{'bytes':'00'}]}", "at list[0]: expected int, found bytes");
        accepts("{'dataType':'list','items':[]}", null, "{'list':[]}");
        mismatch("{'dataType':'list','items':[]}", null, "{'list':[{'int':1}]}", "exactly 0 elements");
        accepts("{'dataType':'list'}", null, "{'list':[{'int':1},{'bytes':''}]}");
    }

    @Test
    void uniqueItemsComparesAsPlutusData() {
        String schema = "{'dataType':'list','uniqueItems':true}";
        accepts(schema, null, "{'list':[{'int':1},{'int':2},{'bytes':'01'},{'list':[{'int':1}]},{'list':[]}]}");
        mismatch(schema, null, "{'list':[{'int':1},{'int':2},{'int':1}]}", "list[0] and list[2] are equal");
        mismatch(schema, null, "{'list':[{'constructor':1,'fields':[{'map':[{'k':{'int':1},'v':{'bytes':'aa'}}]}]},"
                + "{'int':0},{'constructor':1,'fields':[{'map':[{'k':{'int':1},'v':{'bytes':'AA'}}]}]}]}",
                "list[0] and list[2] are equal");
        // Same structure, different constructor index; map entry order matters for Plutus data.
        accepts(schema, null, "{'list':[{'constructor':1,'fields':[]},{'constructor':2,'fields':[]}]}",
                "{'list':[{'map':[{'k':{'int':1},'v':{'int':2}},{'k':{'int':3},'v':{'int':4}}]},"
                        + "{'map':[{'k':{'int':3},'v':{'int':4}},{'k':{'int':1},'v':{'int':2}}]}]}",
                "{'list':[{'list':[{'int':1}]},{'list':[{'list':[{'int':1}]}]}]}");
        accepts("{'dataType':'list','uniqueItems':false}", null, "{'list':[{'int':1},{'int':1}]}");
    }

    @Test
    void mapKeysValuesAndPairCounts() {
        String schema = "{'dataType':'map','keys':{'dataType':'bytes'},'values':{'dataType':'integer'},"
                + "'minItems':1,'maxItems':2}";
        accepts(schema, null, "{'map':[{'k':{'bytes':'00'},'v':{'int':1}}]}",
                "{'map':[{'k':{'bytes':'00'},'v':{'int':1}},{'k':{'bytes':'00'},'v':{'int':2}}]}");
        mismatch(schema, null, "{'map':[]}", "expected at least 1 pairs, found 0");
        mismatch(schema, null, "{'map':[{'k':{'bytes':'00'},'v':{'int':1}},{'k':{'bytes':'01'},'v':{'int':1}},"
                + "{'k':{'bytes':'02'},'v':{'int':1}}]}", "at most 2 pairs");
        mismatch(schema, null, "{'map':[{'k':{'bytes':'00'},'v':{'int':1}},{'k':{'int':0},'v':{'int':1}}]}",
                "at map[1].k: expected bytes, found int");
        mismatch(schema, null, "{'map':[{'k':{'bytes':'00'},'v':{'bytes':'01'}}]}", "at map[0].v: expected int");
        mismatch(schema, null, "{'list':[]}", "expected map, found list");
    }

    @Test
    void constructorIndexAndFields() {
        String schema = "{'dataType':'constructor','index':1,'fields':[{'dataType':'bytes'},{'dataType':'integer'}]}";
        accepts(schema, null, "{'constructor':1,'fields':[{'bytes':''},{'int':0}]}");
        mismatch(schema, null, "{'constructor':0,'fields':[{'bytes':''},{'int':0}]}",
                "expected constructor index 1, found 0");
        mismatch(schema, null, "{'constructor':1,'fields':[{'bytes':''}]}", "expected 2 fields for constructor 1, found 1");
        mismatch(schema, null, "{'constructor':1,'fields':[{'bytes':''},{'int':0},{'int':0}]}", "found 3");
        mismatch(schema, null, "{'constructor':1,'fields':[{'int':0},{'int':0}]}",
                "at fields[0]: expected bytes, found int");
        mismatch(schema, null, "{'int':1}", "expected constructor, found int");
        String big = "{'dataType':'constructor','index':18446744073709551615,'fields':[]}";
        accepts(big, null, "{'constructor':18446744073709551615,'fields':[]}");
        mismatch(big, null, "{'constructor':0,'fields':[]}", "expected constructor index 18446744073709551615");
    }

    @Test
    void nestedPathsAreReported() {
        String defs = "{'Owner':{'dataType':'constructor','index':0,'fields':[{'$ref':'#/definitions/Hash'}]},"
                + "'Hash':{'dataType':'bytes','minLength':28,'maxLength':28}}";
        mismatch("{'dataType':'list','items':{'$ref':'#/definitions/Owner'}}", defs,
                "{'list':[{'constructor':0,'fields':[{'bytes':'" + "00".repeat(28) + "'}]},"
                        + "{'constructor':0,'fields':[{'int':5}]}]}",
                "P: at list[1].fields[0]: expected bytes, found int (schema definitions/Hash)");
    }

    @Test
    void opaqueDataAcceptsEverything() {
        for (String schema : List.of("{}", "{'title':'Data','description':'Any Plutus data.'}", "{'$comment':'x'}")) {
            accepts(schema, null, "{'int':1}", "{'bytes':''}", "{'list':[]}", "{'map':[]}",
                    "{'constructor':18446744073709551615,'fields':[{'int':1}]}");
        }
        // Through a reference to a definition named Data, as JuLC and Aiken emit it.
        accepts("{'$ref':'#/definitions/Data'}", "{'Data':{'title':'Data','description':'Any Plutus data.'}}",
                "{'map':[]}");
    }

    @Test
    void aDefinitionNamedDataIsAnOrdinarySchema() {
        String defs = "{'Data':{'title':'Data','dataType':'constructor','index':0,'fields':[{'dataType':'integer'}]}}";
        accepts("{'$ref':'#/definitions/Data'}", defs, "{'constructor':0,'fields':[{'int':1}]}");
        mismatch("{'$ref':'#/definitions/Data'}", defs, "{'int':1}", "expected constructor, found int");
        mismatch("{'title':'Data','dataType':'integer'}", null, "{'bytes':''}", "expected int");
    }

    @Test
    void referencesWithEscapes() {
        String defs = "{'aiken/crypto/Hash':{'dataType':'bytes'},'a~b':{'dataType':'integer'},"
                + "'x~1y':{'dataType':'list'}}";
        accepts("{'$ref':'#/definitions/aiken~1crypto~1Hash'}", defs, "{'bytes':'00'}");
        mismatch("{'$ref':'#/definitions/aiken~1crypto~1Hash'}", defs, "{'int':1}", "expected bytes");
        accepts("{'$ref':'#/definitions/a~0b'}", defs, "{'int':1}");
        accepts("{'$ref':'#/definitions/x~01y'}", defs, "{'list':[]}");
        // $ref with annotations beside it.
        accepts("{'title':'t','description':'d','$comment':'c','$ref':'#/definitions/a~0b'}", defs, "{'int':1}");
    }

    // ==== applicators ====

    @Test
    void allOfReportsTheFirstFailingSchema() {
        String schema = "{'allOf':[{'dataType':'integer'},{'dataType':'integer','minimum':0},"
                + "{'dataType':'integer','maximum':5}]}";
        accepts(schema, null, "{'int':3}");
        mismatch(schema, null, "{'int':-1}", "at least 0, found -1 (schema schema/allOf/1)");
        mismatch(schema, null, "{'int':9}", "at most 5, found 9 (schema schema/allOf/2)");
        mismatch(schema, null, "{'bytes':''}", "expected int, found bytes (schema schema/allOf/0)");
    }

    @Test
    void anyOfListsEachBranchsFirstFailure() {
        String schema = "{'anyOf':[{'dataType':'constructor','index':0,'fields':[]},"
                + "{'dataType':'constructor','index':1,'fields':[{'dataType':'integer'}]}]}";
        accepts(schema, null, "{'constructor':0,'fields':[]}", "{'constructor':1,'fields':[{'int':1}]}");
        String message = mismatch(schema, null, "{'constructor':1,'fields':[{'bytes':''}]}");
        assertTrue(message.contains("no branch of anyOf matched (schema schema/anyOf)"), message);
        assertTrue(message.contains("branch 0: expected constructor index 0, found 1"), message);
        assertTrue(message.contains("branch 1: at fields[0]: expected int, found bytes"), message);
        // Branches may share an index or be primitives: matching is by schema, not by constructor index.
        String shared = "{'anyOf':[{'dataType':'constructor','index':0,'fields':[{'dataType':'bytes'}]},"
                + "{'dataType':'constructor','index':0,'fields':[{'dataType':'integer'}]},{'dataType':'integer'}]}";
        accepts(shared, null, "{'constructor':0,'fields':[{'int':1}]}", "{'constructor':0,'fields':[{'bytes':''}]}",
                "{'int':7}");
        mismatch(shared, null, "{'bytes':''}", "no branch of anyOf matched");
    }

    @Test
    void oneOfNeedsExactlyOneMatch() {
        String schema = "{'oneOf':[{'dataType':'integer','minimum':0},{'dataType':'integer','maximum':10}]}";
        accepts(schema, null, "{'int':-1}", "{'int':11}");
        mismatch(schema, null, "{'int':5}", "branches [0, 1] of oneOf all matched, but exactly one must");
        mismatch(schema, null, "{'bytes':''}", "no branch of oneOf matched");
    }

    @Test
    void notRefusesWhatItsSchemaMatches() {
        String schema = "{'dataType':'integer','not':{'dataType':'integer'}}";
        mismatch(schema, null, "{'int':1}", "matched the schema it must not match (schema schema/not)");
        String notZero = "{'dataType':'integer','not':{'dataType':'integer','minimum':0,'maximum':0}}";
        accepts(notZero, null, "{'int':1}", "{'int':-1}");
        mismatch(notZero, null, "{'int':0}", "must not match");
    }

    @Test
    void dataTypeAndApplicatorsCombine() {
        String schema = "{'dataType':'integer','minimum':0,'anyOf':[{'dataType':'integer','maximum':3},"
                + "{'dataType':'integer','minimum':10}]}";
        accepts(schema, null, "{'int':2}", "{'int':12}");
        mismatch(schema, null, "{'int':5}", "no branch of anyOf matched");
        mismatch(schema, null, "{'int':-1}", "at least 0");
    }

    // ==== invalid schemas ====

    @Test
    void unknownAndMisplacedKeywords() {
        invalid("{'dataType':'integer','format':'x'}", null, "unknown keyword 'format'");
        invalid("{'type':'integer'}", null, "unknown keyword 'type'");
        invalid("{'dataType':'integer','enum':['00']}", null, "keyword 'enum' does not apply to dataType integer");
        invalid("{'dataType':'bytes','minimum':0}", null, "'minimum' does not apply to dataType bytes");
        invalid("{'dataType':'list','keys':{}}", null, "'keys' does not apply to dataType list");
        invalid("{'dataType':'map','items':{}}", null, "'items' does not apply to dataType map");
        invalid("{'dataType':'map','uniqueItems':true}", null, "'uniqueItems' does not apply");
        invalid("{'dataType':'constructor','index':0,'fields':[],'items':{}}", null, "does not apply");
        invalid("{'items':{'dataType':'integer'}}", null, "'items' does not apply to a schema without dataType");
        invalid("{'dataType':'string'}", null, "unknown dataType 'string'");
        invalid("{'dataType':'#integer'}", null, "builtin type #integer");
        invalid("{'dataType':'list','items':{'dataType':'#bytes'}}", null, "builtin type #bytes");
        invalid("{'$ref':'#/definitions/P'}", "{'P':{'dataType':'#pair','left':{},'right':{}}}", "builtin type #pair");
        invalid("{'definitions':{}}", null, "unknown keyword 'definitions'");
    }

    @Test
    void malformedKeywordValues() {
        invalid("{'dataType':'bytes','minLength':-1}", null, "minLength must not be negative");
        invalid("{'dataType':'bytes','maxLength':'2'}", null, "maxLength must be an integer");
        invalid("{'dataType':'bytes','maxLength':2.5}", null, "maxLength must be an integer");
        invalid("{'dataType':'bytes','enum':'00'}", null, "enum must be an array of hex strings");
        invalid("{'dataType':'bytes','enum':['abc']}", null, "even-length hex");
        invalid("{'dataType':'integer','multipleOf':0}", null, "multipleOf must be greater than 0");
        invalid("{'dataType':'integer','minimum':1.5}", null, "minimum must be an integer");
        invalid("{'dataType':'integer','exclusiveMaximum':true}", null, "exclusiveMaximum must be an integer");
        invalid("{'dataType':'list','items':3}", null, "items must be a schema or an array");
        invalid("{'dataType':'list','items':[3]}", null, "a schema must be a JSON object");
        invalid("{'dataType':'list','uniqueItems':'yes'}", null, "uniqueItems must be true or false");
        invalid("{'dataType':'list','minItems':-2}", null, "minItems must not be negative");
        invalid("{'dataType':'map','keys':[]}", null, "keys must be a schema object");
        invalid("{'dataType':'constructor','fields':[]}", null, "needs its index");
        invalid("{'dataType':'constructor','index':0}", null, "needs its fields");
        invalid("{'dataType':'constructor','index':-1,'fields':[]}", null, "index must not be negative");
        invalid("{'dataType':'constructor','index':0,'fields':{}}", null, "fields must be an array");
        invalid("{'anyOf':[]}", null, "anyOf must be a non-empty array");
        invalid("{'oneOf':{}}", null, "oneOf must be a non-empty array");
        invalid("{'allOf':[true]}", null, "a schema must be a JSON object");
        invalid("{'not':[]}", null, "not must be a schema object");
        invalid("{'dataType':3}", null, "dataType must be a string");
        invalid("{'title':3,'dataType':'integer'}", null, "'title' must be a string");
        invalid("true", null, "a schema must be a JSON object");
    }

    @Test
    void references() {
        invalid("{'$ref':'#/definitions/Missing'}", "{'A':{}}", "names a missing definition");
        invalid("{'$ref':'#/definitions/A'}", null, "names a missing definition");
        invalid("{'$ref':'other.json#/definitions/A'}", "{'A':{}}", "is not local");
        invalid("{'$ref':'#/components/A'}", "{'A':{}}", "is not local");
        invalid("{'$ref':'#/definitions/A/B'}", "{'A':{'B':{}}}", "not a single definition name");
        invalid("{'$ref':'#/definitions/a~2b'}", "{'a~2b':{}}", "invalid ~ escape");
        invalid("{'$ref':'#/definitions/a~'}", "{'a~':{}}", "invalid ~ escape");
        invalid("{'$ref':3}", null, "$ref must be a string");
        invalid("{'$ref':'#/definitions/A','dataType':'integer'}", "{'A':{}}",
                "may carry only title, description and $comment beside it; found 'dataType'");
        invalid("{'$ref':'#/definitions/A','anyOf':[{}]}", "{'A':{}}", "found 'anyOf'");
        invalid("{'$ref':'#/definitions/A'}", "{'A':3}", "definitions/A: a schema must be a JSON object");
        invalid("{'$ref':'#/definitions/A'}", "{'A':{'dataType':'integer','bogus':1}}",
                "definitions/A: unknown keyword 'bogus'");
    }

    @Test
    void cyclesThatConsumeNoDataAreInvalid() {
        invalid("{'$ref':'#/definitions/A'}", "{'A':{'$ref':'#/definitions/A'}}",
                "definitions/A refers to itself without consuming data");
        invalid("{'$ref':'#/definitions/A'}", "{'A':{'$ref':'#/definitions/B'},'B':{'$ref':'#/definitions/A'}}",
                "reference cycle that consumes no data");
        invalid("{'$ref':'#/definitions/A'}", "{'A':{'anyOf':[{'$ref':'#/definitions/A'},{'dataType':'integer'}]}}",
                "consumes no data");
        invalid("{'$ref':'#/definitions/A'}", "{'A':{'allOf':[{'dataType':'integer'},{'$ref':'#/definitions/A'}]}}",
                "consumes no data");
        invalid("{'$ref':'#/definitions/A'}", "{'A':{'oneOf':[{'$ref':'#/definitions/B'}]},"
                + "'B':{'dataType':'list','anyOf':[{'$ref':'#/definitions/A'}]}}", "consumes no data");
    }

    @Test
    void cyclesThroughNotAreInvalid() {
        invalid("{'$ref':'#/definitions/S'}", "{'S':{'not':{'$ref':'#/definitions/S'}}}", "definitions/S");
        invalid("{'$ref':'#/definitions/S'}", "{'S':{'not':{'not':{'$ref':'#/definitions/S'}}}}", "definitions/S");
        // A cycle that consumes data but passes through not.
        invalid("{'$ref':'#/definitions/S'}", "{'S':{'dataType':'list','items':{'not':{'$ref':'#/definitions/S'}}}}",
                "a reference cycle passes through not");
        // not outside any cycle is fine.
        accepts("{'$ref':'#/definitions/S'}", "{'S':{'dataType':'list','items':{'$ref':'#/definitions/S'},"
                + "'not':{'dataType':'list','minItems':3}}}", "{'list':[{'list':[]}]}");
    }

    @Test
    void faultsUnderNotOrInsideAnyOfAreStillInvalidSchemas() {
        String bad = "{'Bad':{'dataType':'integer','bogus':true},'Loop':{'$ref':'#/definitions/Loop'},"
                + "'S':{'not':{'$ref':'#/definitions/S'}}}";
        for (String target : List.of("Bad", "Loop", "S")) {
            String ref = "{'$ref':'#/definitions/" + target + "'}";
            invalid("{'not':" + ref + "}", bad);
            invalid("{'anyOf':[{'dataType':'integer'}," + ref + "]}", bad);
            invalid("{'anyOf':[" + ref + ",{'dataType':'integer'}]}", bad);
            invalid("{'not':{'anyOf':[{}," + ref + "]}}", bad);
        }
        invalid("{'not':{'dataType':'integer','enum':['00']}}", null, "does not apply");
        invalid("{'anyOf':[{},{'dataType':'list','items':{'dataType':'#integer'}}]}", null, "builtin");
    }

    @Test
    void schemaFaultsAreFoundBeforeAnyValue() {
        // Parameter 1's value does not match; parameter 2's schema is invalid. The schema fault is reported.
        ObjectNode bp = ApplyParamsTest.blueprint("v3", ApplyParamsTest.SINGLE, null, "v");
        var parameters = ((ObjectNode) bp.withArray("validators").get(0)).putArray("parameters");
        parameters.addObject().put("title", "first").putObject("schema").put("dataType", "integer");
        parameters.addObject().put("title", "second").putObject("schema").put("$ref", "#/definitions/Missing");
        bp.putObject("definitions");
        var request = ApplyParamsTest.request("V3", new ScriptInput(bp.toString(), null, null, null),
                List.of(new DataInput("json", "{\"bytes\":\"00\"}"), new DataInput("json", "{\"int\":1}")), null, null);
        var e = assertThrows(ApplyParamsException.class, () -> ApplyParams.apply(request));
        assertEquals(Kind.INVALID_SCHEMA, e.kind());
        assertTrue(e.getMessage().startsWith("Parameter 2 (second): invalid schema: schema: $ref"), e.getMessage());
        // With checkSchema false neither is checked... except the builtin rule's reference resolution.
        var unchecked = ApplyParamsTest.request("V3", new ScriptInput(bp.toString(), null, null, null),
                List.of(new DataInput("json", "{\"bytes\":\"00\"}"), new DataInput("json", "{\"int\":1}")), null, false);
        assertEquals(Kind.INVALID_SCHEMA, assertThrows(ApplyParamsException.class,
                () -> ApplyParams.apply(unchecked)).kind());
        // Fix the reference: now the value of parameter 1 is the fault.
        bp.withObject("definitions").putObject("Missing").put("dataType", "integer");
        var fixed = ApplyParamsTest.request("V3", new ScriptInput(bp.toString(), null, null, null),
                List.of(new DataInput("json", "{\"bytes\":\"00\"}"), new DataInput("json", "{\"int\":1}")), null, null);
        var mismatch = assertThrows(ApplyParamsException.class, () -> ApplyParams.apply(fixed));
        assertEquals(Kind.PARAMETER_MISMATCH, mismatch.kind());
        assertEquals("Parameter 1 (first): expected int, found bytes (schema schema)", mismatch.getMessage());
        var unfixed = ApplyParamsTest.request("V3", new ScriptInput(bp.toString(), null, null, null),
                List.of(new DataInput("json", "{\"bytes\":\"00\"}"), new DataInput("json", "{\"int\":1}")), null, false);
        assertEquals(false, ApplyParams.apply(unfixed).schemaChecked());
    }

    // ==== productive recursion ====

    @Test
    void productiveRecursionIsAccepted() {
        String list = "{'List':{'anyOf':[{'dataType':'constructor','index':0,'fields':[]},"
                + "{'dataType':'constructor','index':1,'fields':[{'dataType':'integer'},{'$ref':'#/definitions/List'}]}]}}";
        accepts("{'$ref':'#/definitions/List'}", list, "{'constructor':0,'fields':[]}",
                "{'constructor':1,'fields':[{'int':1},{'constructor':1,'fields':[{'int':2},{'constructor':0,'fields':[]}]}]}");
        mismatch("{'$ref':'#/definitions/List'}", list,
                "{'constructor':1,'fields':[{'int':1},{'constructor':1,'fields':[{'bytes':''},{'constructor':0,'fields':[]}]}]}",
                "at fields[1]: no branch of anyOf matched");
        String tree = "{'Tree':{'anyOf':[{'title':'Leaf','dataType':'constructor','index':0,'fields':[{'dataType':'integer'}]},"
                + "{'title':'Node','dataType':'constructor','index':1,'fields':[{'$ref':'#/definitions/Tree'},"
                + "{'$ref':'#/definitions/Tree'}]}]}}";
        accepts("{'$ref':'#/definitions/Tree'}", tree, "{'constructor':1,'fields':[{'constructor':0,'fields':[{'int':1}]},"
                + "{'constructor':1,'fields':[{'constructor':0,'fields':[{'int':2}]},{'constructor':0,'fields':[{'int':3}]}]}]}");
        String alias = "{'A':{'$ref':'#/definitions/B'},'B':{'anyOf':[{'dataType':'constructor','index':0,'fields':[]},"
                + "{'dataType':'constructor','index':1,'fields':[{'dataType':'integer'},{'$ref':'#/definitions/A'}]}]}}";
        accepts("{'$ref':'#/definitions/A'}", alias, "{'constructor':1,'fields':[{'int':1},{'constructor':0,'fields':[]}]}");
        String nested = "{'T':{'dataType':'list','items':{'$ref':'#/definitions/T'}}}";
        accepts("{'$ref':'#/definitions/T'}", nested, "{'list':[{'list':[]},{'list':[{'list':[]}]}]}");
        mismatch("{'$ref':'#/definitions/T'}", nested, "{'list':[{'list':[{'int':1}]}]}",
                "at list[0].list[0]: expected list, found int");
        String viaMap = "{'M':{'dataType':'map','keys':{'dataType':'bytes'},'values':{'$ref':'#/definitions/M'}}}";
        accepts("{'$ref':'#/definitions/M'}", viaMap, "{'map':[{'k':{'bytes':''},'v':{'map':[]}}]}");
    }

    /** M2-C3: CIP-57 allows an empty enum; it matches nothing. */
    @Test
    void anEmptyEnumMatchesNoValue() {
        String schema = "{'dataType':'bytes','enum':[]}";
        checker(null).compile(q(schema), "P");
        mismatch(schema, null, "{'bytes':''}", "expected one of [], found empty bytes");
        mismatch(schema, null, "{'bytes':'00'}", "expected one of [], found 00");
        accepts("{'anyOf':[{'dataType':'bytes','enum':[]},{'dataType':'integer'}]}", null, "{'int':1}");
    }

    /** M2-C4: a schema's index need only be non-negative; one of 2^64 or more matches no (bounded) value. */
    @Test
    void aConstructorIndexBeyond64BitsIsAWellFormedSchema() {
        String schema = "{'dataType':'constructor','index':18446744073709551616,'fields':[]}";
        checker(null).compile(q(schema), "P");
        mismatch(schema, null, "{'constructor':18446744073709551615,'fields':[]}",
                "expected constructor index 18446744073709551616, found 18446744073709551615");
        invalid("{'dataType':'constructor','index':-1,'fields':[]}", null, "index must not be negative");
    }

    /** M2-R6: percent-encoding in a reference is refused, not taken literally. */
    @Test
    void percentEncodedReferencesAreRefused() {
        String defs = "{'a/b':{'dataType':'integer'},'a%2Fb':{'dataType':'integer'},'a b':{'dataType':'bytes'}}";
        invalid("{'$ref':'#/definitions/a%2Fb'}", defs, "uses percent-encoding, which is not supported");
        invalid("{'$ref':'#/definitions/a%20b'}", defs, "uses percent-encoding");
        accepts("{'$ref':'#/definitions/a~1b'}", defs, "{'int':1}");
        accepts("{'$ref':'#/definitions/a b'}", defs, "{'bytes':''}");
    }

    /** M2-R5: explaining a mismatch spends no work, so a real mismatch near the bound is reported as one. */
    @Test
    void explainingAMismatchSpendsNoWork() {
        String schema = "{'dataType':'list','uniqueItems':true}";
        PlutusData value = v("{'list':[" + "{'int':1},".repeat(99) + "{'int':1}]}");
        // Evaluation spends 1 for the pair and 100 for the uniqueness check; explaining repeats the check.
        var checker = new DataSchemaChecker(null, 101);
        var compiled = checker.compile(q(schema), "P");
        var e = assertThrows(ApplyParamsException.class, () -> checker.check(compiled, value, "P"));
        assertEquals(Kind.PARAMETER_MISMATCH, e.kind(), e.getMessage());
        var tight = new DataSchemaChecker(null, 100);
        var tightCompiled = tight.compile(q(schema), "P");
        assertEquals(Kind.WORK_LIMIT_EXCEEDED, assertThrows(ApplyParamsException.class,
                () -> tight.check(tightCompiled, value, "P")).kind());
    }

    /** M2-R7: with checkSchema, every declared parameter's schema is checked before any value is read. */
    @Test
    void everyDeclaredSchemaIsCheckedFirstInAPartialApplication() {
        ObjectNode bp = ApplyParamsTest.blueprint("v3", ApplyParamsTest.SINGLE, null, "v");
        var parameters = ((ObjectNode) bp.withArray("validators").get(0)).putArray("parameters");
        parameters.addObject().put("title", "applied").putObject("schema").put("dataType", "integer");
        parameters.addObject().put("title", "later").putObject("schema").put("$ref", "#/definitions/Missing");
        parameters.addObject().put("title", "builtinLater").putObject("schema").put("$ref", "#/definitions/B");
        bp.putObject("definitions").putObject("B").put("dataType", "#integer");
        var badValue = List.of(new DataInput("json", "{\"int\":1.5}"));
        var good = List.of(new DataInput("json", "{\"int\":1}"));
        var e = assertThrows(ApplyParamsException.class, () -> ApplyParams.apply(ApplyParamsTest.request("V3",
                new ScriptInput(bp.toString(), null, null, null), badValue, true, null)));
        assertEquals(Kind.INVALID_SCHEMA, e.kind(), e.getMessage());
        assertTrue(e.getMessage().startsWith("Parameter 2 (later): invalid schema"), e.getMessage());
        // checkSchema false: the unapplied schemas are not examined, and the value is read.
        var unchecked = ApplyParams.apply(ApplyParamsTest.request("V3", new ScriptInput(bp.toString(), null, null,
                null), good, true, false));
        assertEquals(2, unchecked.remainingParams());
        // With the reference fixed, a later builtin parameter is no schema fault; it is refused only when applied.
        bp.withObject("definitions").putObject("Missing").put("dataType", "integer");
        var partial = ApplyParams.apply(ApplyParamsTest.request("V3", new ScriptInput(bp.toString(), null, null, null),
                good, true, null));
        assertTrue(partial.schemaChecked());
        var all = List.of(good.get(0), good.get(0), good.get(0));
        var builtin = assertThrows(ApplyParamsException.class, () -> ApplyParams.apply(ApplyParamsTest.request("V3",
                new ScriptInput(bp.toString(), null, null, null), all, null, null)));
        assertEquals(Kind.INVALID_REQUEST, builtin.kind());
        assertTrue(builtin.getMessage().startsWith("Parameter 3 (builtinLater): its schema is the builtin type #integer"),
                builtin.getMessage());
        // A builtin type nested below the top of an unapplied parameter is still a schema fault.
        bp.withObject("definitions").putObject("B").put("dataType", "list").putObject("items").put("dataType", "#integer");
        assertEquals(Kind.INVALID_SCHEMA, assertThrows(ApplyParamsException.class, () -> ApplyParams.apply(
                ApplyParamsTest.request("V3", new ScriptInput(bp.toString(), null, null, null), good, true, null)))
                .kind());
    }

    // ==== cost and depth ====

    @Test
    @Timeout(10)
    void exponentialAnyOfIsMemoised() {
        String defs = "{'T':{'anyOf':[{'dataType':'list','items':{'$ref':'#/definitions/T'}},"
                + "{'dataType':'list','items':{'$ref':'#/definitions/T'}}]}}";
        PlutusData value = new PlutusData.ListData(List.of());
        for (int i = 0; i < 30; i++) value = new PlutusData.ListData(List.of(value));
        var checker = new DataSchemaChecker(q(defs), 10_000);
        var compiled = checker.compile(q("{'$ref':'#/definitions/T'}"), "P");
        checker.check(compiled, value, "P"); // 2^30 checks without memoisation; well under 10,000 work units with it
        // A failing value deep inside gives a mismatch, not an exponential explanation.
        PlutusData bad = PlutusData.integer(1);
        for (int i = 0; i < 30; i++) bad = new PlutusData.ListData(List.of(bad));
        PlutusData finalBad = bad;
        var e = assertThrows(ApplyParamsException.class, () -> checker.check(compiled, finalBad, "P"));
        assertEquals(Kind.PARAMETER_MISMATCH, e.kind());
        assertTrue(e.getMessage().length() < 2000, e.getMessage());
    }

    @Test
    void theWorkBoundIsItsOwnError() {
        String defs = "{'T':{'dataType':'list','items':{'$ref':'#/definitions/T'}}}";
        PlutusData value = new PlutusData.ListData(List.of());
        for (int i = 0; i < 100; i++) value = new PlutusData.ListData(List.of(value));
        var checker = new DataSchemaChecker(q(defs), 50);
        var compiled = checker.compile(q("{'$ref':'#/definitions/T'}"), "P");
        PlutusData finalValue = value;
        var e = assertThrows(ApplyParamsException.class, () -> checker.check(compiled, finalValue, "P"));
        assertEquals(Kind.WORK_LIMIT_EXCEEDED, e.kind());
        assertEquals(400, e.kind().status());
        assertEquals("work-limit-exceeded", e.kind().code());
        assertTrue(e.getMessage().startsWith("P: the schema check exceeded its work bound of 50 steps"), e.getMessage());
        assertTrue(e.getMessage().contains("set checkSchema: false"), e.getMessage());
        // Within the default bound it passes.
        var roomy = checker(defs);
        roomy.check(roomy.compile(q("{'$ref':'#/definitions/T'}"), "P"), value, "P");
    }

    @Test
    void deepValuesAndSchemasNeedNoJavaStack() {
        String defs = "{'T':{'dataType':'list','items':{'$ref':'#/definitions/T'}}}";
        PlutusData value = new PlutusData.ListData(List.of());
        for (int i = 0; i < 100_000; i++) value = new PlutusData.ListData(List.of(value));
        var checker = checker(defs);
        var compiled = checker.compile(q("{'$ref':'#/definitions/T'}"), "P");
        checker.check(compiled, value, "P");
        PlutusData bad = PlutusData.integer(0);
        for (int i = 0; i < 100_000; i++) bad = new PlutusData.ListData(List.of(bad));
        PlutusData finalBad = bad;
        var e = assertThrows(ApplyParamsException.class, () -> checker.check(compiled, finalBad, "P"));
        assertTrue(e.getMessage().endsWith("expected list, found int (schema definitions/T)"),
                e.getMessage().substring(Math.max(0, e.getMessage().length() - 200)));
        // A deeply nested schema: allOf inside allOf, 20,000 deep.
        String deep = "{'allOf':[".repeat(20_000) + "{'dataType':'integer'}" + "]}".repeat(20_000);
        var deepChecker = checker(null);
        JsonNode deepSchema;
        try {
            deepSchema = new ObjectMapper(JsonFactory.builder().streamReadConstraints(
                    StreamReadConstraints.builder().maxNestingDepth(100_000).build()).build())
                    .readTree(deep.replace('\'', '"'));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        var deepCompiled = deepChecker.compile(deepSchema, "P");
        deepChecker.check(deepCompiled, PlutusData.integer(1), "P");
        assertThrows(ApplyParamsException.class, () -> deepChecker.check(deepCompiled, PlutusData.bytes(new byte[0]), "P"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{'dataType':'integer'}", "{'$ref':'#/definitions/I'}", "{'anyOf':[{'$ref':'#/definitions/I'}]}"})
    void parameterMismatchReachesTheEngineErrorWithItsLabel(String schema) {
        ObjectNode bp = ApplyParamsTest.blueprint("v3", ApplyParamsTest.SINGLE, null, "v");
        var parameters = ((ObjectNode) bp.withArray("validators").get(0)).putArray("parameters");
        parameters.addObject().put("title", "owner").set("schema", q(schema));
        bp.putObject("definitions").putObject("I").put("dataType", "integer");
        var result = ApplyParams.run(ApplyParamsTest.request("V3", new ScriptInput(bp.toString(), null, null, null),
                List.of(new DataInput("json", "{\"bytes\":\"00\"}")), null, null));
        assertEquals(422, result.status());
        var body = (Map<?, ?>) result.body();
        assertEquals("parameter-mismatch", body.get("code"));
        assertTrue(body.get("error").toString().startsWith("Parameter 1 (owner): "), body.toString());
    }
}
