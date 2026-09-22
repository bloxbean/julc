package org.julclang.tools.model;

import java.util.List;

/**
 * Requests and responses of the UPLC tools API ({@code /api/uplc/*}).
 */
public final class UplcModels {

    private UplcModels() {}

    /**
     * A compiled script.
     *
     * @param script    CBOR hex (double-wrapped, single-wrapped or raw FLAT), a CIP-57 blueprint, a text envelope, or
     *                  UPLC text
     * @param params    parameters applied to the script, in order
     * @param language  {@code auto | V1 | V2 | V3}
     * @param validator blueprint validator title or index; the first validator by default
     */
    public record ScriptInput(String script, List<MockTransaction.DataInput> params, String language,
                              String validator) {}

    /** Position of a term in the formatted UPLC: 1-based, end column exclusive. */
    public record Span(int startLine, int startColumn, int endLine, int endColumn) {}

    public record DecodeRequest(ScriptInput script) {}

    /**
     * @param inputFormat    {@code hex | blueprint | envelope | uplc}
     * @param wrapping       {@code double-cbor | single-cbor | flat | text}
     * @param language       {@code V1 | V2 | V3}
     * @param languageSource where the language came from: {@code user | blueprint | envelope | hash | version |
     *                       builtins | default}
     * @param scriptHash     script hash of the script with parameters applied
     * @param compiledCode   double-CBOR hex of the script with parameters applied
     * @param flatBytes      FLAT size in bytes
     * @param termCount      number of terms
     * @param builtins       builtins used, sorted
     */
    public record ScriptInfo(String inputFormat, String wrapping, String programVersion, String language,
                             String languageSource, String scriptHash, String compiledCode, int flatBytes,
                             int termCount, List<String> builtins, int paramsApplied, String validator,
                             List<String> validators, List<String> warnings) {}

    public record DecodeResponse(boolean ok, String error, ScriptInfo info, String uplcText) {}

    public record DecompileRequest(ScriptInput script) {}

    public record DecompileResponse(boolean ok, String error, String javaSource, String summary) {}

    /**
     * @param protocolVersion 10 or 11 (default 11)
     * @param maxCpu          execution budget limit (default: mainnet per-transaction maximum)
     * @param maxMem          execution budget limit (default: mainnet per-transaction maximum)
     */
    public record EvaluateRequest(ScriptInput script, MockTransaction transaction, Integer protocolVersion,
                                  Long maxCpu, Long maxMem) {}

    /**
     * @param status          {@code success | failure | budgetExhausted}
     * @param accepted        whether a ledger would accept the script result (V3 must return unit)
     * @param message         explanation when not accepted
     * @param failedSpan      where evaluation failed, when known
     * @param lastBuiltins    the last builtin calls, oldest first
     * @param scriptContext   the script context argument as pretty-printed data
     */
    public record EvaluateResponse(boolean ok, String error, String status, boolean accepted, String message,
                                   long cpu, long mem, List<String> traces, Span failedSpan, String result,
                                   List<String> lastBuiltins, String scriptContext, String scriptContextCbor,
                                   String scriptHash, String language) {}

    /**
     * @param lines    stop before computing a term that starts on one of these lines
     * @param onTrace  stop after a trace message
     * @param onError  stop at an evaluation error (the last step)
     * @param builtins stop before computing these builtins
     */
    public record Breakpoints(List<Integer> lines, Boolean onTrace, Boolean onError, List<String> builtins) {}

    /**
     * @param action {@code timeline} (run to the end and report where traces and errors happen), {@code goto}
     *               (the state after {@code step} transitions), {@code continue} (from {@code step} to the next
     *               breakpoint), {@code over} (until the current term has been evaluated), {@code out} (until the
     *               current frame returns)
     */
    public record DebugRequest(ScriptInput script, MockTransaction transaction, Integer protocolVersion, Long maxCpu,
                               Long maxMem, String action, Long step, Breakpoints breakpoints) {}

    public record Timeline(long totalSteps, List<Long> traceSteps, Long errorStep, String status, boolean truncated,
                           long cpu, long mem) {}

    public record EnvEntry(String name, String value) {}

    public record Frame(String kind, String detail, Span span) {}

    /**
     * Machine state after {@code step} transitions.
     *
     * @param phase      {@code compute | return | done | failed}
     * @param span       the term being computed (compute phase), or the failed term
     * @param value      the value being returned (return phase) or the result
     * @param stopReason why a continue/over/out stopped: {@code breakpoint | trace | error | end | limit | step}
     */
    public record Snapshot(long step, String phase, Span span, String termKind, String value,
                           List<EnvEntry> environment, List<Frame> frames, int stackDepth, long cpu, long mem,
                           long cpuDelta, long memDelta, List<String> traces, boolean finished, String status,
                           String error, String stopReason) {}

    public record DebugResponse(boolean ok, String error, Timeline timeline, Snapshot snapshot) {}
}
