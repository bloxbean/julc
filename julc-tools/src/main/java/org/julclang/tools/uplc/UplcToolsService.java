package org.julclang.tools.uplc;

import org.julclang.core.Constant;
import org.julclang.tools.uplc.EvaluationPreparation.Prepared;
import org.julclang.tools.model.VmModels.Target;
import org.julclang.tools.model.VmModels.CostModel;
import org.julclang.core.DefaultFun;
import org.julclang.core.Term;
import org.julclang.core.cbor.PlutusDataCborEncoder;
import org.julclang.core.source.SourceLocation;
import org.julclang.core.source.SourceMap;
import org.julclang.core.text.UplcParseException;
import org.julclang.core.text.UplcPrettyPrinter;
import org.julclang.core.text.UplcPrinter;
import org.julclang.decompiler.DecompileOptions;
import org.julclang.decompiler.JulcDecompiler;
import org.julclang.tools.model.MockTransaction;
import org.julclang.tools.model.UplcModels.Breakpoints;
import org.julclang.tools.model.UplcModels.DebugRequest;
import org.julclang.tools.model.UplcModels.DebugResponse;
import org.julclang.tools.model.UplcModels.DecodeRequest;
import org.julclang.tools.model.UplcModels.DecodeResponse;
import org.julclang.tools.model.UplcModels.DecompileRequest;
import org.julclang.tools.model.UplcModels.DecompileResponse;
import org.julclang.tools.model.UplcModels.EnvEntry;
import org.julclang.tools.model.UplcModels.EvaluateRequest;
import org.julclang.tools.model.UplcModels.EvaluateResponse;
import org.julclang.tools.model.UplcModels.Frame;
import org.julclang.tools.model.UplcModels.JavaLocation;
import org.julclang.tools.model.UplcModels.ScriptInfo;
import org.julclang.tools.model.UplcModels.ScriptInput;
import org.julclang.tools.model.UplcModels.Snapshot;
import org.julclang.tools.model.UplcModels.Span;
import org.julclang.tools.model.UplcModels.Timeline;
import org.julclang.tools.service.ServiceResult;
import org.julclang.vm.EvalOptions;
import org.julclang.vm.EvalResult;
import org.julclang.vm.PlutusLanguage;
import org.julclang.vm.java.CekFrame;
import org.julclang.vm.java.CekMachine;
import org.julclang.vm.java.CekValue;
import org.julclang.vm.java.SteppingEvaluation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Tools for compiled scripts: decode and format, decompile, evaluate against a mock transaction, and step through
 * the evaluation.
 * <p>
 * Script input errors (bad hex, unknown validator, invalid data) are reported in the response with
 * {@code ok = false} and status 200, like compile errors. The debugger replays evaluation deterministically: a
 * session caches the decoded script and the stepping machine, so moving forward continues where the previous
 * request stopped and moving backward replays from the start.
 */
public final class UplcToolsService {

    /** Mainnet per-transaction execution unit limits, used when a request sets none. */
    public static final long DEFAULT_MAX_CPU = 10_000_000_000L;
    public static final long DEFAULT_MAX_MEM = 14_000_000L;

    static final UplcPrettyPrinter.Options PRINT_OPTIONS = new UplcPrettyPrinter.Options(100, 2, 0);
    private static final long MAX_DEBUG_STEPS = 10_000_000L;
    private static final int ENV_LIMIT = 60;
    private static final int FRAME_LIMIT = 40;
    private static final int VALUE_CHARS = 240;

    private Session session;

    // ---------------------------------------------------------------- decode

    public ServiceResult<DecodeResponse> decode(DecodeRequest req) {
        try {
            var decoded = ScriptDecoder.decode(req.script());
            String text = UplcPrettyPrinter.print(decoded.program(), PRINT_OPTIONS).text();
            return ServiceResult.ok(new DecodeResponse(true, null, info(decoded), text));
        } catch (IllegalArgumentException | UplcParseException e) {
            return ServiceResult.ok(new DecodeResponse(false, e.getMessage(), null, null));
        } catch (Exception e) {
            return ServiceResult.failed(500, new DecodeResponse(false, "Decoding failed", null, null), e);
        }
    }

    // ---------------------------------------------------------------- decompile

    public ServiceResult<DecompileResponse> decompile(DecompileRequest req) {
        try {
            var decoded = ScriptDecoder.decode(req.script());
            var result = JulcDecompiler.decompile(decoded.program(), DecompileOptions.defaults());
            return ServiceResult.ok(new DecompileResponse(true, null, result.javaSource(), result.stats().summary()));
        } catch (IllegalArgumentException | UplcParseException e) {
            return ServiceResult.ok(new DecompileResponse(false, e.getMessage(), null, null));
        } catch (StackOverflowError e) {
            return ServiceResult.ok(new DecompileResponse(false,
                    "The script is nested too deeply to decompile here", null, null));
        } catch (Exception e) {
            return ServiceResult.failed(500, new DecompileResponse(false, "Decompilation failed", null, null), e);
        }
    }

    // ---------------------------------------------------------------- evaluate

    public ServiceResult<EvaluateResponse> evaluate(EvaluateRequest req) {
        try {
            var prepared = prepare(req.script(), req.transaction(), req.protocolVersion(), req.maxCpu(), req.maxMem());
            return evaluatePrepared(prepared);
        } catch (IllegalArgumentException | UplcParseException e) {
            return ServiceResult.ok(new EvaluateResponse(false, e.getMessage(), null, false, null, 0, 0, List.of(),
                    null, null, List.of(), null, null, null, null));
        } catch (Exception e) {
            return ServiceResult.failed(500, new EvaluateResponse(false, "Evaluation failed", null, false, null, 0, 0,
                    List.of(), null, null, List.of(), null, null, null, null), e);
        }
    }

    /** Evaluate the same prepared inputs used by a debug session. */
    public ServiceResult<EvaluateResponse> evaluatePrepared(Prepared prepared) {
        EvalResult result = prepared.provider().evaluateWithArgs(prepared.decoded().program(), prepared.target(),
                prepared.args(), prepared.budget(), EvalOptions.DEFAULT);
        var pretty = UplcPrettyPrinter.print(prepared.decoded().program(), PRINT_OPTIONS);

        String status;
        String resultText = null;
        Span failedSpan = null;
        String message = null;
        boolean accepted = false;
        switch (result) {
            case EvalResult.Success s -> {
                status = "success";
                resultText = truncate(UplcPrinter.print(s.resultTerm()), 4000);
                accepted = prepared.decoded().language() != PlutusLanguage.PLUTUS_V3
                        || s.resultTerm() instanceof Term.Const(Constant.UnitConst ignored);
                if (!accepted) message = "Plutus V3 scripts must return unit, but this script returned " + resultText;
            }
            case EvalResult.Failure f -> {
                status = "failure";
                message = f.error();
                failedSpan = span(pretty, f.failedTerm());
            }
            case EvalResult.BudgetExhausted b -> {
                status = "budgetExhausted";
                message = "Execution budget exhausted";
                failedSpan = span(pretty, b.failedTerm());
            }
        }
        var builtins = result.builtinTrace().stream()
                .map(b -> builtinName(b.fun()) + "(" + b.argSummary() + ") → " + b.resultSummary())
                .toList();
        var context = prepared.scriptContext();
        return ServiceResult.ok(new EvaluateResponse(true, null, status, accepted, message,
                result.budgetConsumed().cpuSteps(), result.budgetConsumed().memoryUnits(), result.traces(),
                failedSpan, resultText, builtins, context == null ? null : context.prettyPrint(),
                context == null ? null : ScriptDecoder.hex(PlutusDataCborEncoder.encode(context)),
                ScriptDecoder.hex(prepared.decoded().scriptHash()), languageName(prepared.decoded().language())));
    }

    // ---------------------------------------------------------------- debug

    public synchronized ServiceResult<DebugResponse> debug(DebugRequest req) {
        try {
            var key = new SessionKey(req.script(), req.transaction(), req.protocolVersion(), req.maxCpu(), req.maxMem());
            if (session == null || !key.equals(session.key())) {
                session = new Session(key, prepare(req.script(), req.transaction(), req.protocolVersion(),
                        req.maxCpu(), req.maxMem()));
            }
            return act(req.action(), req.step(), req.breakpoints());
        } catch (IllegalArgumentException | UplcParseException e) {
            return ServiceResult.ok(new DebugResponse(false, e.getMessage(), null, null));
        } catch (Exception e) {
            session = null;
            return ServiceResult.failed(500, new DebugResponse(false, "Debugging failed", null, null), e);
        }
    }

    /** A raw session owns prepared inputs, including a request-local provider. */
    public synchronized ServiceResult<DebugResponse> open(Prepared prepared) {
        return open(prepared, true);
    }

    public synchronized ServiceResult<DebugResponse> open(Prepared prepared, boolean timeline) {
        return open(prepared, timeline, null);
    }

    /** Open a session whose exact decoded term tree is associated with Java source locations. */
    public synchronized ServiceResult<DebugResponse> open(Prepared prepared, boolean timeline, SourceMap sourceMap) {
        session = new Session(null, prepared, sourceMap);
        return act(timeline ? "timeline" : "goto", 0L, null);
    }

    /** Java lines with at least one executable term in the currently open session. */
    public synchronized List<Integer> executableJavaLines() {
        if (session == null || session.sourceMap == null) return List.of();
        var lines = new TreeSet<Integer>();
        for (Term term : session.pretty.terms()) {
            SourceLocation location = session.sourceMap.lookup(term);
            if (location != null && location.line() > 0) lines.add(location.line());
        }
        return List.copyOf(lines);
    }

    /** Pretty-printed UPLC whose spans are used by this session. */
    public synchronized String uplcText() {
        if (session == null) throw new IllegalStateException("Debug session is closed");
        return session.pretty.text();
    }

    public synchronized ServiceResult<DebugResponse> act(String action, Long step, Breakpoints points) {
        try {
            return actInternal(action, step, points);
        } catch (IllegalArgumentException | UplcParseException e) {
            return ServiceResult.ok(new DebugResponse(false, e.getMessage(), null, null));
        } catch (Exception e) {
            session = null;
            return ServiceResult.failed(500, new DebugResponse(false, "Debugging failed", null, null), e);
        }
    }

    private ServiceResult<DebugResponse> actInternal(String action, Long step, Breakpoints points) {
        if (session == null) throw new IllegalArgumentException("Debug session is closed");
        action = action == null ? "timeline" : action;
        long from = step == null ? 0 : Math.max(0, step);
        var breakpoints = new BreakpointSet(session, points);
        Snapshot snapshot = switch (action) {
            case "timeline" -> {
                session.clearBreakpoint();
                session.timeline();
                yield session.moveTo(0, "step");
            }
            case "goto" -> {
                session.clearBreakpoint();
                yield session.moveTo(from, "step");
            }
            case "continue" -> session.run(from, breakpoints, Mode.CONTINUE);
            case "over" -> session.run(from, breakpoints, Mode.OVER);
            case "out" -> session.run(from, breakpoints, Mode.OUT);
            default -> throw new IllegalArgumentException("Unknown debug action: " + action);
        };
        return ServiceResult.ok(new DebugResponse(true, null, session.timeline, snapshot));
    }

    public synchronized void close() {
        session = null;
    }

    private record SessionKey(ScriptInput script, MockTransaction transaction, Integer protocolVersion, Long maxCpu,
                              Long maxMem) {}

    private enum Mode { CONTINUE, OVER, OUT }

    private Prepared prepare(ScriptInput script, MockTransaction transaction, Integer protocolVersion, Long maxCpu,
                             Long maxMem) {
        return prepareTransaction(script, transaction, new Target(null, protocolVersion), null, maxCpu, maxMem);
    }

    public Prepared prepareTransaction(ScriptInput script, MockTransaction transaction, Target target,
                                       CostModel model, Long maxCpu, Long maxMem) {
        var prepared = EvaluationPreparation.script(script, target, model, maxCpu, maxMem);
        var built = MockContextBuilder.build(transaction, prepared.decoded().scriptHash(), prepared.decoded().language());
        return prepared.withArguments(built.args(), built.scriptContext());
    }

    /** A debugging session for one script and transaction. */
    private final class Session {
        private final SessionKey key;
        private final Prepared prepared;
        private final UplcPrettyPrinter.PrettyUplc pretty;
        private final SourceMap sourceMap;
        private final Map<Term, Term> parents = new IdentityHashMap<>();
        private final Set<Integer> lineHeads = new HashSet<>();
        private Timeline timeline;
        private SteppingEvaluation evaluation;
        private long previousCpu;
        private long previousMem;
        private long lastBreakpointStep = -1;
        private Term lastBreakpointTerm;

        Session(SessionKey key, Prepared prepared) {
            this(key, prepared, null);
        }

        Session(SessionKey key, Prepared prepared, SourceMap sourceMap) {
            this.key = key;
            this.prepared = prepared;
            this.pretty = UplcPrettyPrinter.print(prepared.decoded().program(), PRINT_OPTIONS);
            this.sourceMap = sourceMap;
            for (Term term : pretty.terms()) {
                for (Term child : children(term)) parents.putIfAbsent(child, term);
            }
            for (int id = 0; id < pretty.terms().size(); id++) {
                Term parent = parents.get(pretty.terms().get(id));
                int parentId = parent == null ? -1 : pretty.idOf(parent);
                if (parentId < 0 || pretty.spans().get(parentId).startLine() != pretty.spans().get(id).startLine()) {
                    lineHeads.add(id);
                }
            }
        }

        SessionKey key() {
            return key;
        }

        /** Runs the whole evaluation once and records where traces and the failure happen. */
        Timeline timeline() {
            if (timeline != null) return timeline;
            var run = start();
            var traceSteps = new ArrayList<Long>();
            int traces = 0;
            boolean truncated = false;
            while (!run.isFinished()) {
                if (run.steps() >= MAX_DEBUG_STEPS) {
                    truncated = true;
                    break;
                }
                run.step();
                int now = run.machine().traceCount();
                if (now > traces) {
                    traceSteps.add(run.steps());
                    traces = now;
                }
            }
            String status = run.result() == null ? "running" : statusOf(run.result());
            Long errorStep = run.result() != null && !run.result().isSuccess() ? run.steps() : null;
            long cpu = run.costTracker() == null ? run.result().budgetConsumed().cpuSteps() : run.costTracker().cpuConsumed();
            long mem = run.costTracker() == null ? run.result().budgetConsumed().memoryUnits() : run.costTracker().memConsumed();
            timeline = new Timeline(run.steps(), traceSteps, errorStep, status, truncated, cpu, mem);
            return timeline;
        }

        Snapshot moveTo(long target, String reason) {
            if (evaluation == null || target < evaluation.steps()) {
                evaluation = start();
                previousCpu = cpu();
                previousMem = mem();
            }
            while (evaluation.steps() < target && !evaluation.isFinished()) {
                stepOnce();
            }
            return snapshot(evaluation.steps() == target || evaluation.isFinished() ? reason : "end");
        }

        Snapshot run(long from, BreakpointSet breakpoints, Mode mode) {
            moveTo(from, "step");
            if (evaluation.isFinished()) return snapshot("end");
            CekMachine machine = evaluation.machine();
            String initialHit = breakpoints.hit(machine, machine.traceCount());
            if (initialHit != null && (evaluation.steps() != lastBreakpointStep
                    || machine.currentTerm() != lastBreakpointTerm)) {
                rememberBreakpoint(machine);
                return snapshot(initialHit);
            }
            lastBreakpointStep = -1;
            lastBreakpointTerm = null;
            int depth = machine.stackDepth();
            boolean startedComputing = machine.isComputing();
            long limit = evaluation.steps() + MAX_DEBUG_STEPS;
            while (true) {
                int traces = machine.traceCount();
                stepOnce();
                if (evaluation.isFinished()) {
                    return snapshot(evaluation.result().isSuccess() ? "end" : "error");
                }
                String hit = breakpoints.hit(machine, traces);
                if (hit != null) {
                    rememberBreakpoint(machine);
                    return snapshot(hit);
                }
                switch (mode) {
                    case OVER -> {
                        if (!startedComputing || (!machine.isComputing() && machine.stackDepth() <= depth)) {
                            return snapshot("step");
                        }
                    }
                    case OUT -> {
                        if (machine.stackDepth() < depth) return snapshot("step");
                    }
                    case CONTINUE -> {
                    }
                }
                if (evaluation.steps() >= limit) return snapshot("limit");
            }
        }

        private void rememberBreakpoint(CekMachine machine) {
            lastBreakpointStep = evaluation.steps();
            lastBreakpointTerm = machine.currentTerm();
        }

        private void clearBreakpoint() {
            lastBreakpointStep = -1;
            lastBreakpointTerm = null;
        }

        private SteppingEvaluation start() {
            return prepared.provider().startStepping(prepared.decoded().program(), prepared.target(), prepared.args(),
                    prepared.budget(), sourceMap == null ? EvalOptions.DEFAULT : EvalOptions.DEFAULT.withSourceMap(sourceMap));
        }

        private void stepOnce() {
            previousCpu = cpu();
            previousMem = mem();
            evaluation.step();
        }

        private long cpu() {
            return evaluation.costTracker() == null ? 0 : evaluation.costTracker().cpuConsumed();
        }

        private long mem() {
            return evaluation.costTracker() == null ? 0 : evaluation.costTracker().memConsumed();
        }

        private Snapshot snapshot(String stopReason) {
            EvalResult result = evaluation.result();
            CekMachine machine = evaluation.machine();
            long cpu = result != null ? result.budgetConsumed().cpuSteps() : cpu();
            long mem = result != null ? result.budgetConsumed().memoryUnits() : mem();
            long cpuDelta = evaluation.steps() == 0 ? 0 : cpu - previousCpu;
            long memDelta = evaluation.steps() == 0 ? 0 : mem - previousMem;
            if (machine == null) {
                return new Snapshot(0, "failed", null, null, null, null, List.of(), List.of(), 0, cpu, mem, 0, 0,
                        List.of(), true, statusOf(result), errorOf(result), stopReason);
            }

            String phase;
            Term focus = machine.currentTerm();
            String value = null;
            if (result instanceof EvalResult.Success s) {
                phase = "done";
                value = truncate(UplcPrinter.print(s.resultTerm()), VALUE_CHARS * 4);
            } else if (result != null) {
                phase = "failed";
                Term failed = result instanceof EvalResult.Failure f ? f.failedTerm()
                        : ((EvalResult.BudgetExhausted) result).failedTerm();
                if (failed != null) focus = failed;
            } else if (machine.isComputing()) {
                phase = "compute";
            } else {
                phase = "return";
                value = DebugValues.value(machine.currentValue(), VALUE_CHARS);
            }

            var environment = new ArrayList<EnvEntry>();
            if (!phase.equals("done") && !phase.equals("return") && focus != null) {
                var names = binderNames(focus);
                var env = machine.currentEnvironment();
                int size = env.size();
                for (int i = 1; i <= Math.min(size, ENV_LIMIT); i++) {
                    String name = i <= names.size() ? names.get(i - 1) : "#" + i;
                    environment.add(new EnvEntry(name, DebugValues.value(env.lookup(i), VALUE_CHARS)));
                }
            }
            var frames = new ArrayList<Frame>();
            for (CekFrame frame : machine.frames(FRAME_LIMIT)) {
                frames.add(frame(frame));
            }
            return new Snapshot(evaluation.steps(), phase, span(pretty, focus), javaLocation(focus),
                    focus == null ? null : focus.getClass().getSimpleName().toLowerCase(), value, environment, frames,
                    machine.stackDepth(), cpu, mem, cpuDelta, memDelta, machine.getTraces(), result != null,
                    result == null ? null : statusOf(result), errorOf(result), stopReason);
        }

        private JavaLocation javaLocation(Term term) {
            if (sourceMap == null || term == null) return null;
            SourceLocation location = sourceMap.lookup(term);
            return location == null ? null : new JavaLocation(location.fileName(), location.line(),
                    location.column(), location.fragment());
        }

        private Frame frame(CekFrame frame) {
            return switch (frame) {
                case CekFrame.ForceFrame ignored -> new Frame("force", "the returned value", null);
                case CekFrame.ApplyArgFrame a -> new Frame("apply",
                        DebugValues.value(a.function(), 80) + " to the returned value", null);
                case CekFrame.ComputeArgFrame c -> new Frame("argument", "evaluated next, then applied", span(pretty, c.term()));
                case CekFrame.ValueArgFrame v -> new Frame("apply-to",
                        "the returned function to " + DebugValues.value(v.value(), 80), null);
                case CekFrame.ConstrFrame c -> new Frame("constr", "constr " + Long.toUnsignedString(c.tag()) + ": field "
                        + (c.evaluatedFields().size() + 1) + " of " + (c.evaluatedFields().size() + c.remainingTerms().size() + 1),
                        null);
                case CekFrame.CaseFrame c -> new Frame("case", "case with " + c.branches().size() + " branches", null);
            };
        }

        /** Names of the lambdas enclosing {@code term}, innermost first (De Bruijn order). */
        private List<String> binderNames(Term term) {
            var names = new ArrayList<String>();
            Term child = term;
            Term parent = parents.get(child);
            while (parent != null) {
                if (parent instanceof Term.Lam lam) names.add(lam.paramName());
                child = parent;
                parent = parents.get(child);
            }
            return names;
        }

        boolean isLineHead(Term term, Set<Integer> lines) {
            int id = pretty.idOf(term);
            return id >= 0 && lineHeads.contains(id) && lines.contains(pretty.spans().get(id).startLine());
        }

        boolean isJavaLine(Term term, Set<Integer> lines) {
            if (sourceMap == null || term == null) return false;
            SourceLocation location = sourceMap.lookup(term);
            return location != null && lines.contains(location.line());
        }
    }

    private static final class BreakpointSet {
        private final Session session;
        private final Set<Integer> lines;
        private final Set<Integer> javaLines;
        private final boolean onTrace;
        private final Set<String> builtins;

        BreakpointSet(Session session, Breakpoints breakpoints) {
            this.session = session;
            this.lines = breakpoints == null || breakpoints.lines() == null ? Set.of() : Set.copyOf(breakpoints.lines());
            this.javaLines = breakpoints == null || breakpoints.javaLines() == null
                    ? Set.of() : Set.copyOf(breakpoints.javaLines());
            this.onTrace = breakpoints != null && Boolean.TRUE.equals(breakpoints.onTrace());
            this.builtins = breakpoints == null || breakpoints.builtins() == null ? Set.of() : Set.copyOf(breakpoints.builtins());
        }

        String hit(CekMachine machine, int tracesBefore) {
            if (onTrace && machine.traceCount() > tracesBefore) return "trace";
            if (!machine.isComputing()) return null;
            Term term = machine.currentTerm();
            if (!javaLines.isEmpty() && session.isJavaLine(term, javaLines)) return "javaBreakpoint";
            if (!lines.isEmpty() && session.isLineHead(term, lines)) return "breakpoint";
            if (term instanceof Term.Builtin b && builtins.contains(builtinName(b.fun()))) return "builtin";
            return null;
        }
    }

    // ---------------------------------------------------------------- helpers

    private static ScriptInfo info(ScriptDecoder.DecodedScript d) {
        return new ScriptInfo(d.inputFormat(), d.wrapping(), d.program().versionString(), languageName(d.language()),
                d.languageSource(), ScriptDecoder.hex(d.scriptHash()), ScriptDecoder.hex(d.compiledCode()),
                d.flatBytes(), d.termCount(), d.builtins(), d.paramsApplied(), d.validator(), d.validators(),
                d.warnings());
    }

    private static Span span(UplcPrettyPrinter.PrettyUplc pretty, Term term) {
        if (term == null) return null;
        int id = pretty.idOf(term);
        if (id < 0) return null;
        var s = pretty.spans().get(id);
        return new Span(s.startLine(), s.startColumn(), s.endLine(), s.endColumn());
    }

    private static List<Term> children(Term term) {
        return switch (term) {
            case Term.Lam l -> List.of(l.body());
            case Term.Apply a -> List.of(a.function(), a.argument());
            case Term.Force f -> List.of(f.term());
            case Term.Delay d -> List.of(d.term());
            case Term.Constr c -> c.fields();
            case Term.Case cs -> {
                var all = new ArrayList<Term>(cs.branches().size() + 1);
                all.add(cs.scrutinee());
                all.addAll(cs.branches());
                yield all;
            }
            default -> List.of();
        };
    }

    private static String statusOf(EvalResult result) {
        return switch (result) {
            case EvalResult.Success ignored -> "success";
            case EvalResult.Failure ignored -> "failure";
            case EvalResult.BudgetExhausted ignored -> "budgetExhausted";
        };
    }

    private static String errorOf(EvalResult result) {
        return switch (result) {
            case null -> null;
            case EvalResult.Success ignored -> null;
            case EvalResult.Failure f -> f.error();
            case EvalResult.BudgetExhausted ignored -> "Execution budget exhausted";
        };
    }

    private static String languageName(PlutusLanguage language) {
        return language.name().substring("PLUTUS_".length());
    }

    static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    /** UPLC text name of a builtin, e.g. {@code addInteger}. */
    static String builtinName(DefaultFun fun) {
        String name = fun.name();
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }
}
