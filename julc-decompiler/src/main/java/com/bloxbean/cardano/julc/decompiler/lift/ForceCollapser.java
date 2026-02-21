package com.bloxbean.cardano.julc.decompiler.lift;

import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.core.Term;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Collapses Force chains on polymorphic builtins.
 * <p>
 * Many builtins require 1 or 2 Force applications to instantiate their type variables.
 * For example, {@code Force(Force(Builtin(IfThenElse)))} is the fully-applied IfThenElse.
 * <p>
 * This pass also collects the arguments applied to a fully-forced builtin into a flat list,
 * turning nested Apply chains into a single {@link ForcedBuiltin} record.
 */
public final class ForceCollapser {

    private ForceCollapser() {}

    /**
     * A builtin that has been fully force-applied with its arguments collected.
     */
    public record ForcedBuiltin(DefaultFun fun, List<Term> args) {}

    /**
     * Known force counts per builtin. Builtins not in this map require 0 forces.
     */
    private static final Map<DefaultFun, Integer> FORCE_COUNTS = Map.ofEntries(
            // 2 forces (polymorphic in 2 type vars)
            Map.entry(DefaultFun.IfThenElse, 2),
            Map.entry(DefaultFun.ChooseUnit, 2),
            Map.entry(DefaultFun.Trace, 2),
            Map.entry(DefaultFun.FstPair, 2),
            Map.entry(DefaultFun.SndPair, 2),
            Map.entry(DefaultFun.ChooseList, 2),
            Map.entry(DefaultFun.MkCons, 2),
            Map.entry(DefaultFun.ChooseData, 2),
            // 1 force (polymorphic in 1 type var)
            Map.entry(DefaultFun.HeadList, 1),
            Map.entry(DefaultFun.TailList, 1),
            Map.entry(DefaultFun.NullList, 1)
    );

    /**
     * Get the number of Force applications required for a builtin.
     */
    public static int forceCount(DefaultFun fun) {
        return FORCE_COUNTS.getOrDefault(fun, 0);
    }

    /**
     * Try to match a term as a fully-forced builtin with arguments.
     * Returns null if the term doesn't match this pattern.
     *
     * @param term the term to analyze
     * @return a ForcedBuiltin if matched, or null
     */
    public static ForcedBuiltin matchForcedBuiltin(Term term) {
        // Peel off Apply wrappers to collect args
        var args = new ArrayList<Term>();
        Term current = term;
        while (current instanceof Term.Apply app) {
            args.addFirst(app.argument());
            current = app.function();
        }

        // Peel off Force wrappers
        int forces = 0;
        while (current instanceof Term.Force f) {
            forces++;
            current = f.term();
        }

        // Must be a Builtin at the core
        if (!(current instanceof Term.Builtin b)) {
            return null;
        }

        // Verify force count matches
        int expected = forceCount(b.fun());
        if (forces != expected) {
            return null;
        }

        return new ForcedBuiltin(b.fun(), List.copyOf(args));
    }

    /**
     * Try to match a term as a forced builtin (with or without arguments).
     * This matches even partial application (fewer args than the builtin expects).
     */
    public static ForcedBuiltin matchForcedBuiltinPartial(Term term) {
        var args = new ArrayList<Term>();
        Term current = term;
        while (current instanceof Term.Apply app) {
            args.addFirst(app.argument());
            current = app.function();
        }

        int forces = 0;
        while (current instanceof Term.Force f) {
            forces++;
            current = f.term();
        }

        if (!(current instanceof Term.Builtin b)) {
            return null;
        }

        int expected = forceCount(b.fun());
        if (forces != expected) {
            return null;
        }

        return new ForcedBuiltin(b.fun(), List.copyOf(args));
    }
}
