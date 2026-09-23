package org.julclang.tools.uplc;

import com.bloxbean.cardano.client.plutus.spec.PlutusV2Script;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import org.julclang.blueprint.BlueprintGenerator;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.LibrarySourceResolver;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.core.text.UplcParser;
import org.julclang.tools.model.MockTransaction;
import org.julclang.tools.model.MockTransaction.DataInput;
import org.julclang.tools.model.UplcModels.Breakpoints;
import org.julclang.tools.model.UplcModels.DebugRequest;
import org.julclang.tools.model.UplcModels.DebugResponse;
import org.julclang.tools.model.UplcModels.DecodeRequest;
import org.julclang.tools.model.UplcModels.DecompileRequest;
import org.julclang.tools.model.UplcModels.EvaluateRequest;
import org.julclang.tools.model.UplcModels.EvaluateResponse;
import org.julclang.tools.model.UplcModels.ScriptInput;
import org.julclang.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class UplcToolsServiceTest {

    static final String OWNER = "aa".repeat(28);
    static final String OTHER = "bb".repeat(28);

    static final String SIGNED_SPEND = """
            @SpendingValidator
            class SignedSpend {
                @Param static byte[] owner;

                @Entrypoint
                static boolean validate(PlutusData datum, PlutusData redeemer, ScriptContext ctx) {
                    ContextsLib.trace("checking owner");
                    PlutusData txInfo = ContextsLib.getTxInfo(ctx);
                    return ContextsLib.signedBy(txInfo, owner);
                }
            }
            """;

    /** Plutus V2 style: succeeds when the datum equals the redeemer. */
    static final String V2_DATUM_EQUALS_REDEEMER = "(program 1.0.0 (lam d (lam r (lam ctx "
            + "(force [(force (builtin ifThenElse)) [(builtin equalsData) d r] (delay (con unit ())) (delay (error))])))))";

    final UplcToolsService service = new UplcToolsService();

    // ---------------------------------------------------------------- decode

    @Test
    void allWrappingsOfTheSameScriptDecodeToTheSameHash() {
        Program program = compile(SIGNED_SPEND);
        byte[] flat = UplcFlatEncoder.encodeProgram(program);
        byte[] single = ScriptDecoder.cborWrap(flat);
        byte[] dbl = ScriptDecoder.cborWrap(single);
        String expectedHash = BlueprintGenerator.scriptHash(program);

        for (var input : List.of(hex(dbl), hex(single), hex(flat), "0x" + hex(dbl).toUpperCase())) {
            var info = decode(new ScriptInput(input, null, null, null)).info();
            assertEquals(expectedHash, info.scriptHash(), input.substring(0, 12));
            assertEquals("V3", info.language());
            assertEquals("version", info.languageSource());
            assertEquals(hex(dbl), info.compiledCode());
            assertEquals(flat.length, info.flatBytes());
        }
        assertEquals("double-cbor", decode(new ScriptInput(hex(dbl), null, null, null)).info().wrapping());
        assertEquals("single-cbor", decode(new ScriptInput(hex(single), null, null, null)).info().wrapping());
        assertEquals("flat", decode(new ScriptInput(hex(flat), null, null, null)).info().wrapping());
    }

    @Test
    void onChainScriptDecodesWithTheLedgerHashAndPrintsParseableUplc() throws Exception {
        String scriptHex = resource("/uplc/onchain-v3.hex");
        var response = decode(new ScriptInput(scriptHex, null, null, null));

        String cclHash = hex(PlutusV3Script.builder().cborHex(scriptHex).build().getScriptHash());
        assertEquals(cclHash, response.info().scriptHash());
        assertTrue(response.info().builtins().contains("unConstrData"));
        assertTrue(response.info().termCount() > 1000);

        var reparsed = UplcParser.parseProgram(response.uplcText());
        var decoded = ScriptDecoder.decode(new ScriptInput(scriptHex, null, null, null));
        assertArrayEquals(UplcFlatEncoder.encodeProgram(decoded.program()), UplcFlatEncoder.encodeProgram(reparsed));
    }

    @Test
    void textEnvelopeGivesTheLanguageAndV2HashMatchesTheLedger() throws Exception {
        Program program = UplcParser.parseProgram(V2_DATUM_EQUALS_REDEEMER);
        String dbl = hex(ScriptDecoder.cborWrap(ScriptDecoder.cborWrap(UplcFlatEncoder.encodeProgram(program))));
        String envelope = "{\"type\": \"PlutusScriptV2\", \"description\": \"\", \"cborHex\": \"" + dbl + "\"}";

        var info = decode(new ScriptInput(envelope, null, null, null)).info();

        assertEquals("envelope", info.inputFormat());
        assertEquals("V2", info.language());
        assertEquals(hex(PlutusV2Script.builder().cborHex(dbl).build().getScriptHash()), info.scriptHash());
    }

    @Test
    void blueprintValidatorIsSelectedAndItsHashFixesTheLanguage() {
        Program program = UplcParser.parseProgram(V2_DATUM_EQUALS_REDEEMER);
        String single = hex(ScriptDecoder.cborWrap(UplcFlatEncoder.encodeProgram(program)));
        String v2Hash = hex(ScriptDecoder.scriptHash(org.julclang.vm.PlutusLanguage.PLUTUS_V2, ScriptDecoder.cborWrap(
                UplcFlatEncoder.encodeProgram(program))));
        String blueprint = """
                {"preamble": {"title": "demo"},
                 "validators": [
                   {"title": "first", "compiledCode": "%s", "hash": "%s"},
                   {"title": "second", "compiledCode": "%s"}
                 ]}""".formatted(single, v2Hash, single);

        var first = decode(new ScriptInput(blueprint, null, null, null)).info();
        var second = decode(new ScriptInput(blueprint, null, null, "second")).info();

        assertEquals("blueprint", first.inputFormat());
        assertEquals(List.of("first", "second"), first.validators());
        assertEquals("first", first.validator());
        assertEquals("V2", first.language());
        assertEquals("hash", first.languageSource());
        assertEquals("second", second.validator());
        assertEquals("default", second.languageSource());
        assertFalse(second.warnings().isEmpty());
    }

    @Test
    void parametersAreAppliedBeforeHashing() {
        Program program = compile(SIGNED_SPEND);
        String dbl = hex(ScriptDecoder.cborWrap(ScriptDecoder.cborWrap(UplcFlatEncoder.encodeProgram(program))));
        byte[] owner = HexFormat.of().parseHex(OWNER);

        var info = decode(new ScriptInput(dbl, List.of(new DataInput("uplc", "B #" + OWNER)), null, null)).info();

        assertEquals(1, info.paramsApplied());
        assertEquals(BlueprintGenerator.scriptHash(program.applyParams(PlutusData.bytes(owner))), info.scriptHash());
    }

    @Test
    void invalidInputIsReportedNotThrown() {
        assertFalse(decode(new ScriptInput("zz", null, null, null)).ok());
        assertFalse(decode(new ScriptInput("{\"validators\": []}", null, null, null)).ok());
        assertTrue(decode(new ScriptInput("5f", null, null, null)).error().startsWith("Invalid CBOR"));
        var missing = service.decode(new DecodeRequest(new ScriptInput(" ", null, null, null)));
        assertEquals(200, missing.status());
        assertFalse(missing.body().ok());
    }

    @Test
    void dataInputNotationsAgree() {
        PlutusData expected = PlutusData.constr(0, PlutusData.integer(42), PlutusData.bytes(new byte[]{(byte) 0xca, (byte) 0xfe}));
        String cbor = hex(org.julclang.core.cbor.PlutusDataCborEncoder.encode(expected));

        assertEquals(expected, DataInputs.parse(new DataInput("json", "{\"constructor\":0,\"fields\":[{\"int\":42},{\"bytes\":\"cafe\"}]}")));
        assertEquals(expected, DataInputs.parse(new DataInput("auto", "{\"constructor\":0,\"fields\":[{\"int\":42},{\"bytes\":\"cafe\"}]}")));
        assertEquals(expected, DataInputs.parse(new DataInput("uplc", "Constr 0 [I 42, B #cafe]")));
        assertEquals(expected, DataInputs.parse(new DataInput(null, "Constr 0 [I 42, B #cafe]")));
        assertEquals(expected, DataInputs.parse(new DataInput("cbor", cbor)));
        assertEquals(expected, DataInputs.parse(new DataInput(null, cbor)));
        assertEquals(PlutusData.integer(-7), DataInputs.parse(new DataInput(null, "-7")));
        assertNull(DataInputs.parse(new DataInput(null, "  ")));
        assertThrows(IllegalArgumentException.class, () -> DataInputs.parse(new DataInput("uplc", "Constr [")));
    }

    // ---------------------------------------------------------------- evaluate

    @Test
    void spendingValidatorAcceptsOnlyTheOwnersSignature() {
        var script = signedSpendScript();

        var signed = evaluate(script, spendTx(List.of(OWNER)));
        var unsigned = evaluate(script, spendTx(List.of(OTHER)));

        assertTrue(signed.ok(), signed.error());
        assertEquals("success", signed.status());
        assertTrue(signed.accepted());
        assertEquals(List.of("checking owner"), signed.traces());
        assertTrue(signed.cpu() > 0 && signed.mem() > 0);
        assertTrue(signed.scriptContext().contains("B #" + OWNER));

        assertEquals("failure", unsigned.status());
        assertFalse(unsigned.accepted());
        assertNotNull(unsigned.failedSpan());
        assertFalse(unsigned.lastBuiltins().isEmpty());
    }

    @Test
    void v3ScriptsMustReturnUnit() {
        var response = evaluate(new ScriptInput("(program 1.1.0 (lam ctx (con integer 1)))", null, null, null),
                spendTx(List.of()));

        assertEquals("success", response.status());
        assertFalse(response.accepted());
        assertTrue(response.message().contains("must return unit"));
    }

    @Test
    void v2SpendingScriptsReceiveDatumRedeemerAndContext() {
        var script = new ScriptInput(V2_DATUM_EQUALS_REDEEMER, null, "V2", null);
        var matching = withRedeemer(spendTx(List.of()), "I 7");
        var different = withRedeemer(spendTx(List.of()), "I 8");

        assertTrue(evaluate(script, matching).accepted());
        assertEquals("failure", evaluate(script, different).status());

        var noDatum = evaluate(script, new MockTransaction(new MockTransaction.Purpose("spend", 0, null, null), null,
                List.of(scriptInput(null)), null, null, null, null, null, null, null, null, null, null, null, null, null, null));
        assertFalse(noDatum.ok());
        assertTrue(noDatum.error().contains("need a datum"));
    }

    @Test
    void mintingPurposeUsesTheScriptHashAsPolicy() {
        var script = new ScriptInput("(program 1.1.0 (lam ctx (con unit ())))", null, null, null);
        var tx = new MockTransaction(new MockTransaction.Purpose("mint", null, null, null), null, null, null, null,
                "0", List.of(new MockTransaction.Asset("$self", "746f6b656e", "1")), null, null, null, null, null,
                null, null, null, null, null);

        var response = evaluate(script, tx);

        assertTrue(response.accepted(), response.error());
        assertTrue(response.scriptContext().contains("B #" + response.scriptHash()));
    }

    // ---------------------------------------------------------------- debug

    @Test
    void timelineStepsAndBudgetMatchEvaluation() {
        var script = signedSpendScript();
        var tx = spendTx(List.of(OWNER));
        var evaluated = evaluate(script, tx);

        var timeline = debug(script, tx, "timeline", 0L, null);

        assertTrue(timeline.ok(), timeline.error());
        assertEquals("success", timeline.timeline().status());
        assertEquals(evaluated.cpu(), timeline.timeline().cpu());
        assertEquals(1, timeline.timeline().traceSteps().size());
        assertEquals(0, timeline.snapshot().step());
        assertEquals("compute", timeline.snapshot().phase());

        long total = timeline.timeline().totalSteps();
        var end = debug(script, tx, "goto", total, null).snapshot();
        assertTrue(end.finished());
        assertEquals("done", end.phase());
        assertEquals(evaluated.cpu(), end.cpu());

        var middle = debug(script, tx, "goto", total / 2, null).snapshot();
        var before = debug(script, tx, "goto", total / 2 - 1, null).snapshot();
        var middleAgain = debug(script, tx, "goto", total / 2, null).snapshot();
        assertEquals(total / 2, middle.step());
        assertEquals(total / 2 - 1, before.step());
        assertEquals(middle, middleAgain);
        assertEquals(middle.cpu() - before.cpu(), middle.cpuDelta());
    }

    @Test
    void continueStopsAtTracesLineBreakpointsAndErrors() {
        var script = signedSpendScript();
        var tx = spendTx(List.of(OTHER));
        var text = decode(script).uplcText();
        int traceLine = lineContaining(text, "(con string \"checking owner\")");

        var atTrace = debug(script, tx, "continue", 0L, new Breakpoints(null, true, null, null)).snapshot();
        assertEquals("trace", atTrace.stopReason());
        assertEquals(List.of("checking owner"), atTrace.traces());

        var atLine = debug(script, tx, "continue", 0L, new Breakpoints(List.of(traceLine), null, null, null)).snapshot();
        assertEquals("breakpoint", atLine.stopReason());
        assertEquals(traceLine, atLine.span().startLine());

        var atError = debug(script, tx, "continue", atLine.step(), null).snapshot();
        assertEquals("error", atError.stopReason());
        assertEquals("failed", atError.phase());
        assertEquals("failure", atError.status());
        assertNotNull(atError.error());

        var timeline = debug(script, tx, "timeline", 0L, null).timeline();
        assertEquals(timeline.totalSteps(), timeline.errorStep());
        assertEquals(atError.step(), timeline.totalSteps());
    }

    @Test
    void stepOverFinishesTheCurrentTermAndEnvironmentUsesPrintedNames() {
        var script = signedSpendScript();
        var tx = spendTx(List.of(OWNER));
        var text = decode(script).uplcText();

        var snapshot = debug(script, tx, "goto", 0L, null).snapshot();
        long step = 0;
        while (!("apply".equals(snapshot.termKind()) && snapshot.environment().size() >= 2 && snapshot.stackDepth() >= 2)) {
            snapshot = debug(script, tx, "goto", ++step, null).snapshot();
            assertFalse(snapshot.finished(), "no application with two bindings found");
        }
        for (var entry : snapshot.environment()) {
            assertTrue(entry.name().startsWith("#") || text.contains("(lam " + entry.name()), entry.name());
        }

        int depth = snapshot.stackDepth();
        var over = debug(script, tx, "over", step, null).snapshot();
        assertEquals("return", over.phase());
        assertTrue(over.stackDepth() <= depth);
        assertTrue(over.step() > step + 1);
        assertNotNull(over.value());

        var out = debug(script, tx, "out", step, null).snapshot();
        assertTrue(out.stackDepth() < Math.max(depth, 1) || out.finished());
    }

    @Test
    void decompilesToJava() {
        var response = service.decompile(new DecompileRequest(signedSpendScript())).body();

        assertTrue(response.ok(), response.error());
        assertTrue(response.javaSource().contains("class"));
        assertNotNull(response.summary());
    }

    // ---------------------------------------------------------------- helpers

    private static Program compile(String source) {
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry());
        var libraries = LibrarySourceResolver.scanClasspathSources(JulcCompiler.class.getClassLoader());
        return compiler.compile(source, LibrarySourceResolver.resolve(source, libraries)).program();
    }

    private static ScriptInput signedSpendScript() {
        Program program = compile(SIGNED_SPEND);
        String dbl = hex(ScriptDecoder.cborWrap(ScriptDecoder.cborWrap(UplcFlatEncoder.encodeProgram(program))));
        return new ScriptInput(dbl, List.of(new DataInput("uplc", "B #" + OWNER)), null, null);
    }

    private static MockTransaction.TxIn scriptInput(MockTransaction.Datum datum) {
        return new MockTransaction.TxIn("11".repeat(32), 0L, new MockTransaction.Address("$self", null),
                new MockTransaction.Value("10000000", null), datum, null);
    }

    private static MockTransaction spendTx(List<String> signers) {
        var datum = new MockTransaction.Datum("inline", new DataInput("uplc", "I 7"), null);
        var wallet = new MockTransaction.TxIn("22".repeat(32), 1L, new MockTransaction.Address("key:" + OWNER, null),
                new MockTransaction.Value("100000000", null), null, null);
        var change = new MockTransaction.TxOut(new MockTransaction.Address("key:" + OWNER, null),
                new MockTransaction.Value("109800000", null), null, null);
        return new MockTransaction(new MockTransaction.Purpose("spend", 0, null, null), null,
                List.of(scriptInput(datum), wallet), null, List.of(change), "200000", null, null, null, null,
                signers, null, "33".repeat(32), null, null, null, null);
    }

    private static MockTransaction withRedeemer(MockTransaction tx, String redeemer) {
        return new MockTransaction(tx.purpose(), new DataInput("uplc", redeemer), tx.inputs(), tx.referenceInputs(),
                tx.outputs(), tx.fee(), tx.mint(), tx.certificates(), tx.withdrawals(), tx.validRange(),
                tx.signatories(), tx.datums(), tx.txId(), tx.votes(), tx.proposals(), tx.currentTreasuryAmount(),
                tx.treasuryDonation());
    }

    private org.julclang.tools.model.UplcModels.DecodeResponse decode(ScriptInput script) {
        var result = service.decode(new DecodeRequest(script));
        assertEquals(200, result.status());
        return result.body();
    }

    private EvaluateResponse evaluate(ScriptInput script, MockTransaction tx) {
        var result = service.evaluate(new EvaluateRequest(script, tx, null, null, null));
        assertEquals(200, result.status(), () -> String.valueOf(result.failure()));
        return result.body();
    }

    private DebugResponse debug(ScriptInput script, MockTransaction tx, String action, Long step, Breakpoints breakpoints) {
        var result = service.debug(new DebugRequest(script, tx, null, null, null, action, step, breakpoints));
        assertEquals(200, result.status(), () -> String.valueOf(result.failure()));
        assertTrue(result.body().ok(), result.body().error());
        return result.body();
    }

    private static int lineContaining(String text, String needle) {
        var lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(needle)) return i + 1;
        }
        fail("no line contains " + needle);
        return -1;
    }

    private static String resource(String name) throws IOException {
        try (var in = UplcToolsServiceTest.class.getResourceAsStream(name)) {
            return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8).strip();
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
