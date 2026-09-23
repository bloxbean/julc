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
        var values = new ValueLiteralFoldPass(context, positions, provenance).lower(term);
        var folding =
                new ArrayLiteralFoldPass(context, values.positions(), provenance)
                        .lower(values.term());
        var sharing =
                new ValueConversionSharingPass(context, folding.positions(), provenance)
                        .lower(folding.term());
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
            context.logf(
                    "Source map generated: %d entries (optimization skipped)", sourceMap.size());
        } else if (optimize) {
            var optimized = new UplcOptimizer(context).optimizeWithReport(uplc);
            uplc = optimized.term();
            context.recordOptimizationRules(optimized.appliedPasses());
            stage =
                    optimized.appliedPasses().isEmpty()
                            ? "UPLC optimizer (no rewrites)"
                            : "UPLC optimizer passes " + optimized.appliedPasses();
            context.log("UPLC optimization complete");
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
