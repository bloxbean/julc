package com.bloxbean.cardano.julc.vm.truffle.debug;

import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.core.source.SourceMap;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.ExBudget;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;
import com.bloxbean.cardano.julc.vm.java.cost.*;
import com.bloxbean.cardano.julc.vm.truffle.UplcContext;
import com.bloxbean.cardano.julc.vm.truffle.UplcTruffleLanguage;
import com.bloxbean.cardano.julc.vm.truffle.convert.NodeToTermConverter;
import com.oracle.truffle.api.debug.Breakpoint;
import com.oracle.truffle.api.debug.Debugger;
import com.oracle.truffle.api.debug.DebuggerSession;
import com.oracle.truffle.api.debug.SourceElement;
import com.oracle.truffle.api.debug.SuspensionFilter;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;

import java.util.ArrayList;
import java.util.List;

/**
 * Programmatic step-through debugger for UPLC programs at Java source level.
 * <p>
 * Uses Truffle's Debugger API via a polyglot {@link Context} to enable
 * stepping, breakpoints, and budget inspection during UPLC evaluation.
 * This is the same debugger infrastructure used by VS Code (via DAP) and
 * IntelliJ, enabling future IDE integration.
 * <p>
 * Source sections on UPLC nodes (from the {@link SourceMap}) map to Java
 * source file:line positions. Stepping naturally advances between Java source
 * lines because only statement-tagged nodes (Apply, Force, Case, Error) have
 * source sections — intermediate UPLC nodes are skipped automatically.
 * <p>
 * Example usage:
 * <pre>{@code
 * try (var debugger = JulcDebugger.create()) {
 *     debugger.sourceMap(sourceMap);
 *     EvalResult result = debugger.stepThrough(term, event -> {
 *         System.out.println(event.fileName() + ":" + event.line() +
 *                            " CPU=" + event.cpuConsumed());
 *         event.stepOver();
 *     });
 * }
 * }</pre>
 */
public final class JulcDebugger implements AutoCloseable {

    private SourceMap sourceMap;
    private PlutusLanguage language = PlutusLanguage.PLUTUS_V3;
    private ExBudget budget;
    private final List<BreakpointSpec> breakpointSpecs = new ArrayList<>();

    private JulcDebugger() {}

    /**
     * Create a new debugger instance.
     */
    public static JulcDebugger create() {
        return new JulcDebugger();
    }

    /**
     * Set the source map for mapping UPLC nodes to Java source locations.
     * Required for stepping to work at Java source level.
     */
    public JulcDebugger sourceMap(SourceMap sm) {
        this.sourceMap = sm;
        return this;
    }

    /**
     * Set the Plutus language version (default: PLUTUS_V3).
     */
    public JulcDebugger language(PlutusLanguage lang) {
        this.language = lang;
        return this;
    }

    /**
     * Set the execution budget (null for unlimited).
     */
    public JulcDebugger budget(ExBudget budget) {
        this.budget = budget;
        return this;
    }

    /**
     * Add a breakpoint at the specified file and line.
     */
    public JulcDebugger breakAt(String fileName, int line) {
        breakpointSpecs.add(new BreakpointSpec(fileName, line));
        return this;
    }

    /**
     * Step through the entire program, suspending at every statement-tagged node
     * that has a source section. The handler is called at each suspension.
     */
    public EvalResult stepThrough(Term term, StepHandler handler) {
        return executeWithDebugger(term, handler, true);
    }

    /**
     * Run the program, suspending only at breakpoints.
     */
    public EvalResult run(Term term, StepHandler handler) {
        return executeWithDebugger(term, handler, false);
    }

    private EvalResult executeWithDebugger(Term term, StepHandler handler, boolean stepMode) {
        // Set up cost tracking (shared with julc-vm-java)
        MachineCosts mc = DefaultCostModel.defaultMachineCosts(language);
        BuiltinCostModel bcm = DefaultCostModel.defaultBuiltinCostModel(language);
        var costTracker = new CostTracker(mc, bcm, budget);
        var uplcContext = new UplcContext(costTracker, language);

        try {
            // Charge startup cost
            costTracker.chargeMachineStep(MachineCosts.StepKind.STARTUP);

            // Pass Term + source map via thread-local; AST is built inside parse()
            // so nodes belong to the polyglot engine's sharing layer
            UplcTruffleLanguage.setPending(term, sourceMap, uplcContext);
            try {
                try (Context polyglotContext = Context.newBuilder(UplcTruffleLanguage.ID).build()) {

                    Debugger debugger = Debugger.find(polyglotContext.getEngine());

                    try (DebuggerSession session = debugger.startSession(event -> {
                        handler.onStep(new StepEvent(event, uplcContext));
                    }, SourceElement.STATEMENT)) {

                        // Include internal sources (our CONTENT_NONE sources)
                        session.setSteppingFilter(
                                SuspensionFilter.newBuilder()
                                        .includeInternal(true)
                                        .build());

                        // Install breakpoints
                        for (var spec : breakpointSpecs) {
                            var src = com.oracle.truffle.api.source.Source
                                    .newBuilder(UplcTruffleLanguage.ID, "", spec.fileName)
                                    .content(com.oracle.truffle.api.source.Source.CONTENT_NONE)
                                    .build();
                            session.install(Breakpoint.newBuilder(src).lineIs(spec.line).build());
                        }

                        if (stepMode) {
                            session.suspendNextExecution();
                        }

                        // Execute — triggers parse() → builds AST → instruments → runs
                        polyglotContext.eval(UplcTruffleLanguage.ID, "");
                    }
                }
            } finally {
                UplcTruffleLanguage.clearPending();
            }

            // Retrieve result captured by UplcRootNode
            Object result = uplcContext.getCapturedResult();
            if (result == null) {
                return new EvalResult.Failure("No result captured",
                        costTracker.consumed(), uplcContext.getTraces());
            }
            Term resultTerm = NodeToTermConverter.toTerm(result);
            return new EvalResult.Success(resultTerm, costTracker.consumed(), uplcContext.getTraces());

        } catch (PolyglotException e) {
            if (e.isCancelled() || e.isInterrupted()
                    || (e.getMessage() != null && e.getMessage().contains("KillException"))) {
                return new EvalResult.Failure("Killed by debugger",
                        costTracker.consumed(), uplcContext.getTraces());
            }
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new EvalResult.Failure(msg, costTracker.consumed(), uplcContext.getTraces());
        } catch (BudgetExhaustedException e) {
            return new EvalResult.BudgetExhausted(
                    costTracker.consumed(), uplcContext.getTraces(), e.failedTerm());
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new EvalResult.Failure(msg, costTracker.consumed(), uplcContext.getTraces());
        }
    }

    @Override
    public void close() {
        breakpointSpecs.clear();
    }

    private record BreakpointSpec(String fileName, int line) {}
}
