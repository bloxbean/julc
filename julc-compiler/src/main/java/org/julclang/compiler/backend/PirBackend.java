package org.julclang.compiler.backend;

import org.julclang.compiler.*;
import org.julclang.compiler.pir.*;
import org.julclang.compiler.uplc.*;
import org.julclang.core.Program;
import org.julclang.core.source.SourceLocation;
import org.julclang.core.source.SourceMap;

import java.util.Map;

/** Shared post-wrapper pipeline. The caller owns frontend diagnostics and metadata. */
public final class PirBackend {
    private PirBackend() {}

    public record Result(Program program, PirTerm pir, SourceMap sourceMap) {}

    public static Result lower(
            PirTerm term,
            CompilationContext context,
            Map<PirTerm, SourceLocation> positions,
            boolean optimize) {
        var values = new ValueLiteralFoldPass(context, positions).lower(term);
        var folding = new ArrayLiteralFoldPass(context, values.positions()).lower(values.term());
        var sharing =
                new ValueConversionSharingPass(context, folding.positions()).lower(folding.term());
        var promotion =
                new ListIndexPromotionPass(context, sharing.positions()).lower(sharing.term());
        var pairs =
                new PairDestructuringPass(context, promotion.positions()).lower(promotion.term());
        var generator =
                new UplcGenerator(context, context.isSourceMapEnabled() ? pairs.positions() : null);
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
        return new Result(program, pairs.term(), sourceMap);
    }
}
