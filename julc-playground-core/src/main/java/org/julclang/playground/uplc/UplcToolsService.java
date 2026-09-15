package org.julclang.playground.uplc;

import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.julclang.core.Term;
import org.julclang.core.cbor.PlutusDataCborEncoder;
import org.julclang.core.text.UplcParseException;
import org.julclang.core.text.UplcPrettyPrinter;
import org.julclang.core.text.UplcPrinter;
import org.julclang.decompiler.DecompileOptions;
import org.julclang.decompiler.JulcDecompiler;
import org.julclang.playground.model.MockTransaction;
import org.julclang.playground.model.UplcModels.Breakpoints;
import org.julclang.playground.model.UplcModels.DebugRequest;
import org.julclang.playground.model.UplcModels.DebugResponse;
import org.julclang.playground.model.UplcModels.DecodeRequest;
import org.julclang.playground.model.UplcModels.DecodeResponse;
import org.julclang.playground.model.UplcModels.DecompileRequest;
import org.julclang.playground.model.UplcModels.DecompileResponse;
import org.julclang.playground.model.UplcModels.EnvEntry;
import org.julclang.playground.model.UplcModels.EvaluateRequest;
import org.julclang.playground.model.UplcModels.EvaluateResponse;
import org.julclang.playground.model.UplcModels.Frame;
import org.julclang.playground.model.UplcModels.ScriptInfo;
import org.julclang.playground.model.UplcModels.ScriptInput;
import org.julclang.playground.model.UplcModels.Snapshot;
import org.julclang.playground.model.UplcModels.Span;
import org.julclang.playground.model.UplcModels.Timeline;
import org.julclang.playground.service.ServiceResult;
import org.julclang.vm.EvalOptions;
import org.julclang.vm.EvalResult;
import org.julclang.vm.ExBudget;
import org.julclang.vm.LedgerEvaluationTarget;
import org.julclang.vm.PlutusLanguage;
import org.julclang.vm.ProtocolVersion;
import org.julclang.vm.java.CekFrame;
import org.julclang.vm.java.CekMachine;
import org.julclang.vm.java.CekValue;
import org.julclang.vm.java.JavaVmProvider;
import org.julclang.vm.java.SteppingEvaluation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    private final JavaVmProvider provider = new JavaVmProvider();
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
            EvalResult result = provider.evaluateWithArgs(prepared.decoded().program(), prepared.target(),
                    prepared.built().args(), prepared.budget(), EvalOptions.DEFAULT);
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
            var context = prepared.built().scriptContext();
            return ServiceResult.ok(new EvaluateResponse(true, null, status, accepted, message,
                    result.budgetConsumed().cpuSteps(), result.budgetConsumed().memoryUnits(), result.traces(),
                    failedSpan, resultText, builtins, context.prettyPrint(),
                    ScriptDecoder.hex(PlutusDataCborEncoder.encode(context)),
                    ScriptDecoder.hex(prepared.decoded().scriptHash()), languageName(prepared.decoded().language())));
        } catch (IllegalArgumentException | UplcParseException e) {
            return ServiceResult.ok(new EvaluateResponse(false, e.getMessage(), null, false, null, 0, 0, List.of(),
                    null, null, List.of(), null, null, null, null));
        } catch (Exception e) {
            return ServiceResult.failed(500, new EvaluateResponse(false, "Evaluation failed", null, false, null, 0, 0,
                    List.of(), null, null, List.of(), null, null, null, null), e);
        }
    }

    // ---------------------------------------------------------------- debug

    public synchronized ServiceResult<DebugResponse> debug(DebugRequest req) {
        try {
            var key = new SessionKey(req.script(), req.transaction(), req.protocolVersion(), req.maxCpu(), req.maxMem());
            if (session == null || !session.key().equals(key)) {
                session = new Session(key, prepare(req.script(), req.transaction(), req.protocolVersion(),
                        req.maxCpu(), req.maxMem()));
            }
            String action = req.action() == null ? "timeline" : req.action();
            long from = req.step() == null ? 0 : Math.max(0, req.step());
            var breakpoints = new BreakpointSet(session, req.breakpoints());
            Snapshot snapshot = switch (action) {
                case "timeline" -> {
                    session.timeline();
                    yield session.moveTo(0, "step");
                }
                case "goto" -> session.moveTo(from, "step");
                case "continue" -> session.run(from, breakpoints, Mode.CONTINUE);
                case "over" -> session.run(from, breakpoints, Mode.OVER);
                case "out" -> session.run(from, breakpoints, Mode.OUT);
                default -> throw new IllegalArgumentException("Unknown debug action: " + action);
            };
            return ServiceResult.ok(new DebugResponse(true, null, session.timeline, snapshot));
        } catch (IllegalArgumentException | UplcParseException e) {
            return ServiceResult.ok(new DebugResponse(false, e.getMessage(), null, null));
        } catch (Exception e) {
            session = null;
            return ServiceResult.failed(500, new DebugResponse(false, "Debugging failed", null, null), e);
        }
    }

    private record SessionKey(ScriptInput script, MockTransaction transaction, Integer protocolVersion, Long maxCpu,
                              Long maxMem) {}

    private enum Mode { CONTINUE, OVER, OUT }

    private record Prepared(ScriptDecoder.DecodedScript decoded, MockContextBuilder.Built built,
                            LedgerEvaluationTarget target, ExBudget budget) {}

    private Prepared prepare(ScriptInput script, MockTransaction transaction, Integer protocolVersion, Long maxCpu,
                             Long maxMem) {
        var decoded = ScriptDecoder.decode(script);
        var built = MockContextBuilder.build(transaction, decoded.scriptHash(), decoded.language());
        var pv = protocolVersion == null || protocolVersion == 11 ? ProtocolVersion.PV11
                : protocolVersion == 10 ? ProtocolVersion.PV10 : null;
        if (pv == null) throw new IllegalArgumentException("Protocol version must be 10 or 11");
        var budget = new ExBudget(maxCpu == null ? DEFAULT_MAX_CPU : maxCpu, maxMem == null ? DEFAULT_MAX_MEM : maxMem);
        return new Prepared(decoded, built, new LedgerEvaluationTarget(decoded.language(), pv), budget);
    }

    /** A debugging session for one script and transaction. */
    private final class Session {
        private final SessionKey key;
        private final Prepared prepared;
        private final UplcPrettyPrinter.PrettyUplc pretty;
        private final Map<Term, Term> parents = new IdentityHashMap<>();
        private final Set<Integer> lineHeads = new HashSet<>();
        private Timeline timeline;
        private SteppingEvaluation evaluation;
        private long previousCpu;
        private long previousMem;

        Session(SessionKey key, Prepared prepared) {
            this.key = key;
            this.prepared = prepared;
            this.pretty = UplcPrettyPrinter.print(prepared.decoded().program(), PRINT_OPTIONS);
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
                if (hit != null) return snapshot(hit);
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

        private SteppingEvaluation start() {
            return provider.startStepping(prepared.decoded().program(), prepared.target(), prepared.built().args(),
                    prepared.budget(), EvalOptions.DEFAULT);
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
                return new Snapshot(0, "failed", null, null, null, List.of(), List.of(), 0, cpu, mem, 0, 0,
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
            return new Snapshot(evaluation.steps(), phase, span(pretty, focus),
                    focus == null ? null : focus.getClass().getSimpleName().toLowerCase(), value, environment, frames,
                    machine.stackDepth(), cpu, mem, cpuDelta, memDelta, machine.getTraces(), result != null,
                    result == null ? null : statusOf(result), errorOf(result), stopReason);
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
    }

    private static final class BreakpointSet {
        private final Session session;
        private final Set<Integer> lines;
        private final boolean onTrace;
        private final Set<String> builtins;

        BreakpointSet(Session session, Breakpoints breakpoints) {
            this.session = session;
            this.lines = breakpoints == null || breakpoints.lines() == null ? Set.of() : Set.copyOf(breakpoints.lines());
            this.onTrace = breakpoints != null && Boolean.TRUE.equals(breakpoints.onTrace());
            this.builtins = breakpoints == null || breakpoints.builtins() == null ? Set.of() : Set.copyOf(breakpoints.builtins());
        }

        String hit(CekMachine machine, int tracesBefore) {
            if (onTrace && machine.traceCount() > tracesBefore) return "trace";
            if (!machine.isComputing()) return null;
            Term term = machine.currentTerm();
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
