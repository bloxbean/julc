package org.julclang.compiler.backend;

import org.julclang.compiler.*;
import org.julclang.compiler.debug.PirDebugProvenance;
import org.julclang.compiler.pir.*;
import org.julclang.compiler.uplc.*;
import org.julclang.core.Program;
import org.julclang.core.Term;
import org.julclang.core.source.SourceLocation;
import org.julclang.core.source.SourceMap;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/** Shared post-wrapper pipeline. The caller owns frontend diagnostics and metadata. */
public final class PirBackend {
    private PirBackend() {}

    /** {@code debug} is present only when Java debug provenance was supplied. */
    public record Result(Program program, PirTerm pir, SourceMap sourceMap, DebugLowering debug) {}

    /** Exact UPLC binder and position bookkeeping for Java source-debug metadata (ADR-058). */
    public record DebugLowering(
            IdentityHashMap<Term.Lam, PirDebugProvenance.Association> emittedBinders,
            IdentityHashMap<Term, SourceLocation> exactUplcPositions) {}

    public static Result lower(
            PirTerm term,
            CompilationContext context,
            Map<PirTerm, SourceLocation> positions,
            boolean optimize) {
        return lower(term, context, positions, optimize, null);
    }

    /**
     * Lower with optional Java debug provenance. Every rebuilding pass transfers the supplied
     * binder associations, and the source-map generator records the emitted UPLC binders.
     */
    public static Result lower(
            PirTerm term,
            CompilationContext context,
            Map<PirTerm, SourceLocation> positions,
            boolean optimize,
            PirDebugProvenance provenance) {
        return lower(term, context, positions, optimize, provenance, Bindings.NONE, true);
    }

    /** {@link #lower} of a program whose methods and fields are {@code bindings}. */
    public static Result lower(
            PirTerm term,
            CompilationContext context,
            Map<PirTerm, SourceLocation> positions,
            boolean optimize,
            PirDebugProvenance provenance,
            Bindings bindings) {
        return lower(term, context, positions, optimize, provenance, bindings, true);
    }

    /**
     * The binders of a program's methods and static fields, which {@link DelayedMethodPass} reads. A term that comes
     * with none, as a linked program does, has no method to delay.
     */
    public record Bindings(Set<String> methods, Set<String> fields) {
        static final Bindings NONE = new Bindings(Set.of(), Set.of());
    }

    /** {@link #lower} without the stage log lines: {@code compileMethod} has never written them. */
    public static Result lowerQuietly(
            PirTerm term,
            CompilationContext context,
            Map<PirTerm, SourceLocation> positions,
            Bindings bindings) {
        return lower(term, context, positions, true, null, bindings, false);
    }

    private static Result lower(
            PirTerm term,
            CompilationContext context,
            Map<PirTerm, SourceLocation> positions,
            boolean optimize,
            PirDebugProvenance provenance,
            Bindings bindings,
            boolean logStages) {
        // First, at every level: it decides how a cast lowers, and needs only every method's body in the term.
        var narrowing = new NarrowingElisionPass(positions, provenance).lower(term);
        var values = new ValueLiteralFoldPass(context, narrowing.positions(), provenance).lower(narrowing.term());
        var folding =
                new ArrayLiteralFoldPass(context, values.positions(), provenance)
                        .lower(values.term());
        // After the folds, whose constants need no delay, and before the passes that move a
        // conversion, which must see each delayed read as the call it is.
        var delayed =
                new DelayedMethodPass(
                                bindings.methods(), bindings.fields(), folding.positions(), provenance)
                        .lower(folding.term());
        var sharing =
                new ValueConversionSharingPass(context, delayed.positions(), provenance)
                        .lower(delayed.term());
        var promotion =
                new ListIndexPromotionPass(context, sharing.positions(), provenance)
                        .lower(sharing.term());
        var pairs =
                new PairDestructuringPass(context, promotion.positions(), provenance)
                        .lower(promotion.term());
        var generator =
                context.isSourceMapEnabled()
                        ? new UplcGenerator(context, pairs.positions(), provenance)
                        : new UplcGenerator(context, null);
        var uplc = generator.generate(pairs.term());
        SourceMap sourceMap = null;
        String stage = "UPLC lowering";
        if (context.isSourceMapEnabled()) {
            sourceMap = SourceMap.of(generator.getUplcPositions());
            if (logStages) {
                context.logf("Source map generated: %d entries (optimization skipped)", sourceMap.size());
            }
        } else if (optimize) {
            var optimized = new UplcOptimizer(context).optimizeWithReport(uplc);
            uplc = optimized.term();
            context.recordOptimizationRules(optimized.appliedPasses());
            stage =
                    optimized.appliedPasses().isEmpty()
                            ? "UPLC optimizer (no rewrites)"
                            : "UPLC optimizer passes " + optimized.appliedPasses();
            if (logStages) context.log("UPLC optimization complete");
        }
        var version = context.target().uplcVersion();
        var program = new Program(version.major(), version.minor(), version.patch(), uplc);
        UplcTargetValidator.validate(program, context, stage);
        var debug =
                provenance == null
                        ? null
                        : new DebugLowering(
                                generator.getEmittedBinders(), generator.getExactUplcPositions());
        return new Result(program, pairs.term(), sourceMap, debug);
    }
}
