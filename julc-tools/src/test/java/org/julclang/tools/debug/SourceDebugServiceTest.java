package org.julclang.tools.debug;

import org.julclang.blueprint.BlueprintGenerator;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.LibrarySourceResolver;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.tools.model.MockTransaction.DataInput;
import org.julclang.tools.model.MockTransaction;
import org.julclang.tools.model.SourceDebugModels.ActionRequest;
import org.julclang.tools.model.SourceDebugModels.CloseRequest;
import org.julclang.tools.model.SourceDebugModels.ChildrenRequest;
import org.julclang.tools.model.SourceDebugModels.LocalsRequest;
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

    private static final String LOCALS = """
            import java.math.BigInteger;

            @SpendingValidator
            class LocalsDebugExample {
                @Entrypoint
                static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                    BigInteger exact = BigInteger.valueOf(9007199254740993L);
                    return exact.compareTo(BigInteger.ZERO) > 0;
                }
            }
            """;

    private static final String RAW_DATA_LOCAL = """
            @SpendingValidator
            class RawDataLocalsExample {
                @Entrypoint
                static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                    PlutusData copy = redeemer;
                    return Builtins.equalsData(copy, redeemer);
                }
            }
            """;

    private static final String BRANCH_LOCALS = """
            import java.math.BigInteger;

            @SpendingValidator
            class BranchLocalsExample {
                @Entrypoint
                static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                    BigInteger chosen = Builtins.unIData(redeemer).signum() > 0
                            ? BigInteger.valueOf(11) : BigInteger.valueOf(22);
                    return chosen.signum() > 0;
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
        assertEquals(404, service.locals(new LocalsRequest(
                opened.sessionId(), opened.snapshot().stopGeneration())).status());
    }

    @Test
    void exactIntegerLocalUsesVerifiedComputeSlotAndGeneration() {
        var opened = service.open(localsRequest(LOCALS)).body();
        assertTrue(opened.ok(), () -> opened.error() + ": " + opened.diagnostics());
        assertNotNull(opened.localsCapability());
        assertTrue(opened.localsCapability().available());

        Long generationWithLocal = null;
        String summary = null;
        for (long step = 0; step <= opened.timeline().totalSteps(); step++) {
            var snapshot = step == 0 ? opened.snapshot()
                    : service.act(new ActionRequest(opened.sessionId(), "goto", step, null)).body().snapshot();
            if (!"available".equals(snapshot.localsAvailability())) continue;
            var locals = service.locals(new LocalsRequest(opened.sessionId(), snapshot.stopGeneration())).body();
            var exact = locals.scopes().stream().flatMap(scope -> scope.variables().stream())
                    .filter(variable -> variable.name().equals("exact") && variable.value() != null)
                    .findFirst();
            if (exact.isPresent()) {
                generationWithLocal = snapshot.stopGeneration();
                summary = exact.orElseThrow().value().summary();
                assertEquals(locals, service.locals(new LocalsRequest(
                        opened.sessionId(), snapshot.stopGeneration())).body(),
                        "reading Java locals must not move or otherwise mutate the debug machine");
                break;
            }
        }
        assertNotNull(generationWithLocal, "the local must be observable at an exact compute point");
        assertEquals("9007199254740993", summary);

        var moved = service.act(new ActionRequest(opened.sessionId(), "goto", 0L, null)).body().snapshot();
        assertNotEquals(generationWithLocal, moved.stopGeneration());
        var stale = service.locals(new LocalsRequest(opened.sessionId(), generationWithLocal)).body();
        assertFalse(stale.ok());
        assertEquals("stale", stale.availability());
    }

    @Test
    void localsAreUnavailableOutsideComputeAndObservationDoesNotMoveTheMachine() {
        var opened = service.open(localsRequest(LOCALS)).body();
        long initialGeneration = opened.snapshot().stopGeneration();
        var first = service.locals(new LocalsRequest(opened.sessionId(), initialGeneration)).body();
        var second = service.locals(new LocalsRequest(opened.sessionId(), initialGeneration)).body();
        assertEquals(first, second);

        var finished = service.act(new ActionRequest(opened.sessionId(), "goto",
                opened.timeline().totalSteps(), null)).body().snapshot();
        var unavailable = service.locals(new LocalsRequest(opened.sessionId(), finished.stopGeneration())).body();
        assertTrue(unavailable.ok());
        assertEquals("unavailable", unavailable.availability());
        assertTrue(unavailable.scopes().isEmpty());
    }

    @Test
    void observingLocalsAtEveryTransitionPreservesResultBudgetAndTraces() {
        var request = localsRequest(LOCALS);
        var opened = service.open(request).body();
        assertTrue(opened.ok(), opened.error());
        var evaluated = new UplcToolsService().evaluate(new EvaluateRequest(
                new ScriptInput(opened.compiledCode(), List.of(), "V3", null), request.transaction(),
                request.protocolVersion(), request.maxCpu(), request.maxMem())).body();

        for (long step = 0; step <= opened.timeline().totalSteps(); step++) {
            var snapshot = step == 0 ? opened.snapshot()
                    : service.act(new ActionRequest(opened.sessionId(), "goto", step, null)).body().snapshot();
            var observed = service.locals(new LocalsRequest(opened.sessionId(), snapshot.stopGeneration())).body();
            assertTrue(observed.ok(), observed.error());
        }

        var completed = service.act(new ActionRequest(opened.sessionId(), "goto",
                opened.timeline().totalSteps(), null)).body().snapshot();
        assertEquals(evaluated.status(), completed.status());
        assertEquals(evaluated.cpu(), completed.cpu());
        assertEquals(evaluated.mem(), completed.mem());
        assertEquals(evaluated.traces(), completed.traces());
        assertEquals(evaluated.result(), completed.value());
    }

    @Test
    void rawDataChildrenAreBoundedAndHandlesBecomeStaleAfterMovement() {
        var opened = service.open(localsRequest(RAW_DATA_LOCAL)).body();
        String handle = null;
        long generation = -1;
        for (long step = 0; step <= opened.timeline().totalSteps() && handle == null; step++) {
            var snapshot = step == 0 ? opened.snapshot()
                    : service.act(new ActionRequest(opened.sessionId(), "goto", step, null)).body().snapshot();
            if (!"available".equals(snapshot.localsAvailability())) continue;
            var locals = service.locals(new LocalsRequest(opened.sessionId(), snapshot.stopGeneration())).body();
            var copy = locals.scopes().stream().flatMap(scope -> scope.variables().stream())
                    .filter(variable -> variable.name().equals("copy") && variable.value() != null)
                    .findFirst();
            if (copy.isPresent()) {
                handle = copy.orElseThrow().value().childrenHandle();
                generation = snapshot.stopGeneration();
            }
        }
        assertNotNull(handle);
        var children = service.children(new ChildrenRequest(opened.sessionId(), generation, handle, 0, 1)).body();
        assertTrue(children.ok(), children.error());
        assertEquals(1, children.children().size());
        assertNotNull(children.nextStart(), "the raw constructor has more children than the requested page");

        service.act(new ActionRequest(opened.sessionId(), "goto", 0L, null));
        var stale = service.children(new ChildrenRequest(opened.sessionId(), generation, handle, 0, 1)).body();
        assertFalse(stale.ok());
        assertTrue(stale.error().toLowerCase().contains("stale"));
    }

    @Test
    void childHandlesAreScopedToOneExactLocalsServiceSession() {
        var firstService = new SourceDebugService(
                LibrarySourceResolver.scanClasspathSources(JulcCompiler.class.getClassLoader()));
        var secondService = new SourceDebugService(
                LibrarySourceResolver.scanClasspathSources(JulcCompiler.class.getClassLoader()));
        var first = rawDataHandle(firstService);
        var second = rawDataHandle(secondService);

        assertEquals(first.generation(), second.generation(),
                "the collision test requires the same numeric stop generation");
        assertNotEquals(first.handle(), second.handle(),
                "opaque value references must carry a per-service nonce");
        var firstRejectsSecond = firstService.children(new ChildrenRequest(first.sessionId(),
                first.generation(), second.handle(), 0, 1)).body();
        var secondRejectsFirst = secondService.children(new ChildrenRequest(second.sessionId(),
                second.generation(), first.handle(), 0, 1)).body();
        assertFalse(firstRejectsSecond.ok());
        assertFalse(secondRejectsFirst.ok());
        assertTrue(firstRejectsSecond.error().contains("stale or unknown"));
        assertTrue(secondRejectsFirst.error().contains("stale or unknown"));
    }

    @Test
    void parameterApplicationIsReboundAndValidatedForLocalsSessions() {
        var opened = service.open(new OpenRequest(PARAMETERIZED, null,
                List.of(new DataInput("uplc", "I 5")), spendTransaction(), 11,
                null, null, null, null, true)).body();

        assertTrue(opened.ok(), opened.error());
        assertNotNull(opened.localsCapability());
        assertTrue(opened.localsCapability().available());
        assertEquals(1, opened.params().size());
        assertNotNull(opened.snapshot().stopGeneration());
        assertDoesNotThrow(() -> service.locals(new LocalsRequest(
                opened.sessionId(), opened.snapshot().stopGeneration())));
        assertEquals("5", observedLocal(PARAMETERIZED, spendTransaction(), "minimum",
                List.of(new DataInput("uplc", "I 5"))));
    }

    @Test
    void sourceMapOnlySessionsRejectTheAdditiveLocalsOperation() {
        var opened = service.open(request(LOCALS, List.of())).body();
        assertTrue(opened.ok(), opened.error());
        assertFalse(opened.localsCapability().available());

        var response = service.locals(new LocalsRequest(opened.sessionId(), opened.snapshot().stopGeneration()));
        assertEquals(409, response.status());
        assertEquals("unsupported", response.body().availability());
    }

    @Test
    void conditionalInitializerReportsTheValueFromTheExecutedBranch() {
        assertEquals("11", observedLocal(BRANCH_LOCALS, spendTransaction("I 1"), "chosen"));
        assertEquals("22", observedLocal(BRANCH_LOCALS, spendTransaction("I 0"), "chosen"));
    }

    private String observedLocal(String source, MockTransaction transaction, String name) {
        return observedLocal(source, transaction, name, List.of());
    }

    private String observedLocal(String source, MockTransaction transaction, String name,
                                 List<DataInput> params) {
        var opened = service.open(new OpenRequest(source, null, params, transaction, 11,
                null, null, null, null, true)).body();
        assertTrue(opened.ok(), () -> opened.error() + ": " + opened.diagnostics());
        for (long step = 0; step <= opened.timeline().totalSteps(); step++) {
            var snapshot = step == 0 ? opened.snapshot()
                    : service.act(new ActionRequest(opened.sessionId(), "goto", step, null)).body().snapshot();
            if (!"available".equals(snapshot.localsAvailability())) continue;
            var locals = service.locals(new LocalsRequest(opened.sessionId(), snapshot.stopGeneration())).body();
            var value = locals.scopes().stream().flatMap(scope -> scope.variables().stream())
                    .filter(variable -> variable.name().equals(name) && variable.value() != null)
                    .map(variable -> variable.value().summary()).findFirst();
            if (value.isPresent()) return value.orElseThrow();
        }
        fail("No observable value for " + name);
        return null;
    }

    private static RawDataHandle rawDataHandle(SourceDebugService target) {
        var opened = target.open(new OpenRequest(RAW_DATA_LOCAL, null, List.of(), spendTransaction(), 11,
                null, null, null, null, true)).body();
        assertTrue(opened.ok(), opened.error());
        for (long step = 0; step <= opened.timeline().totalSteps(); step++) {
            var snapshot = step == 0 ? opened.snapshot()
                    : target.act(new ActionRequest(opened.sessionId(), "goto", step, null)).body().snapshot();
            if (!"available".equals(snapshot.localsAvailability())) continue;
            var locals = target.locals(new LocalsRequest(opened.sessionId(), snapshot.stopGeneration())).body();
            var handle = locals.scopes().stream().flatMap(scope -> scope.variables().stream())
                    .filter(variable -> variable.name().equals("copy") && variable.value() != null)
                    .map(variable -> variable.value().childrenHandle()).filter(java.util.Objects::nonNull)
                    .findFirst();
            if (handle.isPresent()) {
                return new RawDataHandle(opened.sessionId(), snapshot.stopGeneration(), handle.orElseThrow());
            }
        }
        fail("No raw Data child handle was observed");
        return null;
    }

    private record RawDataHandle(String sessionId, long generation, String handle) {}

    private static OpenRequest request(String source, List<DataInput> params) {
        return new OpenRequest(source, null, params, spendTransaction(), 11, null, null, null, null);
    }

    private static OpenRequest localsRequest(String source) {
        return new OpenRequest(source, null, List.of(), spendTransaction(), 11,
                null, null, null, null, true);
    }

    private static MockTransaction spendTransaction() {
        return spendTransaction("Constr 0 [I 1, B #abcd]");
    }

    private static MockTransaction spendTransaction(String redeemer) {
        var input = new MockTransaction.TxIn("11".repeat(32), 0L,
                new MockTransaction.Address("$self", null), new MockTransaction.Value("10000000", null),
                new MockTransaction.Datum("inline", new DataInput("uplc", "I 7"), null), null);
        return new MockTransaction(new MockTransaction.Purpose("spend", 0, null, null),
                new DataInput("uplc", redeemer), List.of(input), null, null, "0", null, null, null,
                null, null, null, "22".repeat(32), null, null, null, null);
    }
}
