package org.julclang.tools.debug;

import org.julclang.blueprint.BlueprintGenerator;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.LibrarySourceResolver;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.tools.model.MockTransaction.DataInput;
import org.julclang.tools.model.MockTransaction;
import org.julclang.tools.model.SourceDebugModels.ActionRequest;
import org.julclang.tools.model.SourceDebugModels.CloseRequest;
import org.julclang.tools.model.SourceDebugModels.OpenRequest;
import org.julclang.tools.model.UplcModels.Breakpoints;
import org.julclang.tools.model.UplcModels.EvaluateRequest;
import org.julclang.tools.model.UplcModels.ScriptInput;
import org.julclang.tools.model.VmModels.Target;
import org.julclang.tools.uplc.UplcToolsService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SourceDebugServiceTest {
    private static final String SOURCE = """
            @SpendingValidator
            class SourceDebugExample {
                @Entrypoint
                static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                    ContextsLib.trace("source debug");
                    return true;
                }
            }
            """;

    private static final String PARAMETERIZED = """
            import java.math.BigInteger;

            @SpendingValidator
            class ParameterizedDebugExample {
                @Param static BigInteger minimum;

                @Entrypoint
                static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                    return minimum.compareTo(BigInteger.ZERO) >= 0;
                }
            }
            """;

    private final SourceDebugService service = new SourceDebugService(
            LibrarySourceResolver.scanClasspathSources(JulcCompiler.class.getClassLoader()));

    @Test
    void sourceAndUplcBreakpointsShareTheExactSteppingSession() {
        var opened = service.open(request(SOURCE, List.of()));

        assertEquals(200, opened.status());
        assertTrue(opened.body().ok(), opened.body().error());
        assertNotNull(opened.body().sessionId());
        assertFalse(opened.body().executableJavaLines().isEmpty());
        assertTrue(opened.body().warning().contains("optimizer is disabled"));
        assertEquals(0, opened.body().snapshot().step());

        int line = -1;
        for (long step = 0; step < opened.body().timeline().totalSteps(); step++) {
            var snapshot = service.act(new ActionRequest(opened.body().sessionId(), "goto", step, null))
                    .body().snapshot();
            if (!snapshot.finished() && snapshot.javaLocation() != null) {
                line = snapshot.javaLocation().line();
                break;
            }
        }
        assertTrue(line > 0, "at least one executed term must retain a Java location");
        var continued = service.act(new ActionRequest(opened.body().sessionId(), "continue", 0L,
                new Breakpoints(List.of(), false, true, List.of(), List.of(line))));

        assertEquals(200, continued.status());
        assertTrue(continued.body().ok(), continued.body().error());
        assertEquals("javaBreakpoint", continued.body().snapshot().stopReason());
        assertNotNull(continued.body().snapshot().javaLocation());
        assertEquals(line, continued.body().snapshot().javaLocation().line());
        assertNotNull(continued.body().snapshot().span(), "same term must retain its UPLC span");

        long firstStop = continued.body().snapshot().step();
        var resumed = service.act(new ActionRequest(opened.body().sessionId(), "continue", firstStop,
                new Breakpoints(List.of(), false, true, List.of(), List.of(line))));
        assertTrue(resumed.body().ok(), resumed.body().error());
        assertTrue(resumed.body().snapshot().step() > firstStop,
                "continue must advance beyond the exact state that triggered the breakpoint");

        service.act(new ActionRequest(opened.body().sessionId(), "goto", firstStop, null));
        var hitAfterExplicitReposition = service.act(new ActionRequest(opened.body().sessionId(), "continue",
                firstStop, new Breakpoints(List.of(), false, true, List.of(), List.of(line))));
        assertEquals(firstStop, hitAfterExplicitReposition.body().snapshot().step(),
                "an explicit reposition should make the breakpoint eligible again");
        assertEquals("javaBreakpoint", hitAfterExplicitReposition.body().snapshot().stopReason());
    }

    @Test
    void unsupportedJavaLinesStayUnboundAndDoNotStopEvaluation() {
        var opened = service.open(request(SOURCE, List.of())).body();
        assertFalse(opened.executableJavaLines().contains(10_000));

        var continued = service.act(new ActionRequest(opened.sessionId(), "continue", 0L,
                new Breakpoints(List.of(), false, true, List.of(), List.of(10_000))));

        assertTrue(continued.body().ok(), continued.body().error());
        assertTrue(continued.body().snapshot().finished());
        assertNotEquals("javaBreakpoint", continued.body().snapshot().stopReason());
    }

    @Test
    void steppingAndPlainEvaluationOfTheSameDebugArtifactHaveParity() {
        var request = request(SOURCE, List.of());
        var opened = service.open(request).body();
        var evaluated = new UplcToolsService().evaluate(new EvaluateRequest(
                new ScriptInput(opened.compiledCode(), List.of(), "V3", null), request.transaction(),
                11, null, null)).body();

        assertEquals(evaluated.status(), opened.timeline().status());
        assertEquals(evaluated.cpu(), opened.timeline().cpu());
        assertEquals(evaluated.mem(), opened.timeline().mem());
        assertEquals(evaluated.traces(), List.of("source debug"));
    }

    @Test
    void sourceDebugCompilationDoesNotChangeLaterNormalCompilation() {
        var normalCompiler = new JulcCompiler(StdlibRegistry.defaultRegistry());
        String before = BlueprintGenerator.compiledCode(normalCompiler.compile(SOURCE).program());

        assertTrue(service.open(request(SOURCE, List.of())).body().ok());

        String after = BlueprintGenerator.compiledCode(normalCompiler.compile(SOURCE).program());
        assertEquals(before, after);
    }

    @Test
    void parameterizedArtifactsKeepSourceLocationsAfterApplication() {
        var opened = service.open(request(PARAMETERIZED, List.of(new DataInput("uplc", "I 5"))));

        assertTrue(opened.body().ok(), opened.body().error());
        assertEquals(1, opened.body().params().size());
        assertFalse(opened.body().executableJavaLines().isEmpty());
    }

    @Test
    void missingParameterValuesReturnMetadataWithoutOpeningASession() {
        var opened = service.open(request(PARAMETERIZED, List.of()));

        assertEquals(200, opened.status());
        assertFalse(opened.body().ok());
        assertNull(opened.body().sessionId());
        assertEquals(1, opened.body().params().size());
        assertEquals("minimum", opened.body().params().getFirst().name());
    }

    @Test
    void sessionsAreIndependentAndClosingOneDoesNotAffectAnother() {
        var first = service.open(request(SOURCE, List.of())).body();
        var second = service.open(request(SOURCE, List.of())).body();
        assertNotEquals(first.sessionId(), second.sessionId());

        assertEquals(200, service.act(new ActionRequest(first.sessionId(), "goto", 1L, null)).status());
        assertEquals(200, service.close(new CloseRequest(first.sessionId())).status());

        var secondAction = service.act(new ActionRequest(second.sessionId(), "goto", 1L, null));
        assertEquals(200, secondAction.status());
        assertTrue(secondAction.body().ok(), secondAction.body().error());
        assertEquals(1, secondAction.body().snapshot().step());
    }

    @Test
    void rejectsWrongTargetAndClosedSessions() {
        var wrongTarget = new OpenRequest(SOURCE, null, List.of(), null, null, null, null,
                new Target("PlutusV2", 11), null);
        assertEquals(400, service.open(wrongTarget).status());

        var opened = service.open(request(SOURCE, List.of())).body();
        assertEquals(200, service.close(new CloseRequest(opened.sessionId())).status());
        assertEquals(404, service.act(new ActionRequest(opened.sessionId(), "goto", 0L, null)).status());
    }

    private static OpenRequest request(String source, List<DataInput> params) {
        return new OpenRequest(source, null, params, spendTransaction(), 11, null, null, null, null);
    }

    private static MockTransaction spendTransaction() {
        var input = new MockTransaction.TxIn("11".repeat(32), 0L,
                new MockTransaction.Address("$self", null), new MockTransaction.Value("10000000", null),
                new MockTransaction.Datum("inline", new DataInput("uplc", "I 7"), null), null);
        return new MockTransaction(new MockTransaction.Purpose("spend", 0, null, null),
                new DataInput("uplc", "Constr 0 []"), List.of(input), null, null, "0", null, null, null,
                null, null, null, "22".repeat(32), null, null, null, null);
    }
}
