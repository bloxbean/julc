package com.bloxbean.cardano.julc.decompiler.input;

import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.Term;

import java.util.*;

/**
 * Structural analysis of a UPLC program.
 * Walks the AST to collect statistics about the script.
 */
public final class ScriptAnalyzer {

    private ScriptAnalyzer() {}

    /**
     * Analyze a program and return structural statistics.
     */
    public static ScriptStats analyze(Program program) {
        var collector = new StatsCollector();
        collector.walk(program.term(), 0);

        PlutusVersion version = detectVersion(program, collector);
        int arity = estimateArity(program.term());

        return new ScriptStats(
                program.versionString(),
                version,
                collector.totalNodes,
                collector.maxDepth,
                arity,
                collector.nodeCounts,
                Collections.unmodifiableSet(collector.builtinsUsed),
                collector.lamCount,
                collector.applyCount,
                collector.constCount,
                collector.forceCount,
                collector.delayCount,
                collector.errorCount,
                collector.hasConstrCase
        );
    }

    /**
     * Detect the Plutus version based on program version and term features.
     */
    private static PlutusVersion detectVersion(Program program, StatsCollector collector) {
        // V3+ uses Constr/Case terms (SOP) and has version 1.1.0
        if (collector.hasConstrCase || program.minor() >= 1) {
            return PlutusVersion.V3;
        }
        // V1 and V2 share the same program version (1.0.0)
        // V2 uses builtins 51-53 (SerialiseData, SECP signatures)
        for (DefaultFun fun : collector.builtinsUsed) {
            if (fun.flatCode() >= 51) {
                return PlutusVersion.V2;
            }
        }
        return PlutusVersion.V1;
    }

    /**
     * Estimate the entrypoint arity by counting outermost consecutive lambdas.
     */
    private static int estimateArity(Term term) {
        int arity = 0;
        Term current = term;
        while (current instanceof Term.Lam lam) {
            arity++;
            current = lam.body();
        }
        return arity;
    }

    private static class StatsCollector {
        int totalNodes;
        int maxDepth;
        int lamCount;
        int applyCount;
        int constCount;
        int forceCount;
        int delayCount;
        int errorCount;
        boolean hasConstrCase;
        final Map<String, Integer> nodeCounts = new LinkedHashMap<>();
        final Set<DefaultFun> builtinsUsed = new TreeSet<>(Comparator.comparingInt(DefaultFun::flatCode));

        void walk(Term term, int depth) {
            totalNodes++;
            maxDepth = Math.max(maxDepth, depth);

            switch (term) {
                case Term.Var _ -> count("Var");
                case Term.Lam lam -> {
                    count("Lam");
                    lamCount++;
                    walk(lam.body(), depth + 1);
                }
                case Term.Apply app -> {
                    count("Apply");
                    applyCount++;
                    walk(app.function(), depth + 1);
                    walk(app.argument(), depth + 1);
                }
                case Term.Force f -> {
                    count("Force");
                    forceCount++;
                    walk(f.term(), depth + 1);
                }
                case Term.Delay d -> {
                    count("Delay");
                    delayCount++;
                    walk(d.term(), depth + 1);
                }
                case Term.Const _ -> {
                    count("Const");
                    constCount++;
                }
                case Term.Builtin b -> {
                    count("Builtin");
                    builtinsUsed.add(b.fun());
                }
                case Term.Error _ -> {
                    count("Error");
                    errorCount++;
                }
                case Term.Constr c -> {
                    count("Constr");
                    hasConstrCase = true;
                    for (var field : c.fields()) {
                        walk(field, depth + 1);
                    }
                }
                case Term.Case cs -> {
                    count("Case");
                    hasConstrCase = true;
                    walk(cs.scrutinee(), depth + 1);
                    for (var branch : cs.branches()) {
                        walk(branch, depth + 1);
                    }
                }
            }
        }

        void count(String nodeType) {
            nodeCounts.merge(nodeType, 1, Integer::sum);
        }
    }

    public enum PlutusVersion {
        V1, V2, V3
    }

    /**
     * Structural statistics of a UPLC script.
     */
    public record ScriptStats(
            String programVersion,
            PlutusVersion plutusVersion,
            int totalNodes,
            int maxDepth,
            int estimatedArity,
            Map<String, Integer> nodeCounts,
            Set<DefaultFun> builtinsUsed,
            int lamCount,
            int applyCount,
            int constCount,
            int forceCount,
            int delayCount,
            int errorCount,
            boolean usesSop
    ) {
        /**
         * Format a human-readable summary.
         */
        public String summary() {
            var sb = new StringBuilder();
            sb.append("=== Script Analysis ===\n");
            sb.append("Program version: ").append(programVersion).append('\n');
            sb.append("Plutus version:  ").append(plutusVersion).append('\n');
            sb.append("Total AST nodes: ").append(totalNodes).append('\n');
            sb.append("Max AST depth:   ").append(maxDepth).append('\n');
            sb.append("Estimated arity: ").append(estimatedArity).append('\n');
            sb.append("Uses SOP:        ").append(usesSop ? "yes" : "no").append('\n');
            sb.append("\nNode breakdown:\n");
            nodeCounts.forEach((type, count) ->
                    sb.append("  ").append(type).append(": ").append(count).append('\n'));
            sb.append("\nBuiltins used (").append(builtinsUsed.size()).append("):\n");
            for (var fun : builtinsUsed) {
                sb.append("  ").append(fun.name()).append('\n');
            }
            return sb.toString();
        }
    }
}
