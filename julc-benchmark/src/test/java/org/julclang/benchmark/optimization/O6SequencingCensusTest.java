package org.julclang.benchmark.optimization;

import org.julclang.clientlib.JulcScriptAdapter;
import org.julclang.core.DefaultFun;
import org.julclang.core.Term;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-041 O6 census: counts every {@code [(λx. body) e]} whose binder is unused in {@code body}
 * in the external julc-examples blueprint and classifies {@code e}. Statement sequencing always
 * produces that shape, but so do compiler bindings of discarded values, so the count is an
 * over-approximation of sequencing sites. Prints one {@code O6CENSUS} line per validator and a
 * total; skips when the sibling blueprint is not built. Evidence: adr/evidence/041-integer-case-dispatch.md.
 */
class O6SequencingCensusTest {
    private static final Path BLUEPRINT = Path.of("../../julc-examples/build/classes/java/main/META-INF/plutus/plutus.json");
    private static final Pattern CODE = Pattern.compile("\"compiledCode\"\\s*:\\s*\"([0-9a-fA-F]+)\"");

    @Test
    void census() throws Exception {
        Assumptions.assumeTrue(Files.exists(BLUEPRINT), "external julc-examples blueprint not built: " + BLUEPRINT.toAbsolutePath().normalize());
        var codes = CODE.matcher(Files.readString(BLUEPRINT));
        int validators = 0, sites = 0, errors = 0, traces = 0, others = 0, applies = 0;
        while (codes.find()) {
            var term = JulcScriptAdapter.toProgram(codes.group(1)).term();
            var counts = new LinkedHashMap<String, Integer>();
            walk(term, counts);
            validators++;
            int e = counts.getOrDefault("error", 0), t = counts.getOrDefault("trace", 0), o = counts.getOrDefault("other", 0);
            sites += e + t + o; errors += e; traces += t; others += o; applies += counts.getOrDefault("applies", 0);
            System.out.println("O6CENSUS validator=" + validators + " sites=" + (e + t + o) + " " + counts);
        }
        System.out.println("O6CENSUS TOTAL validators=" + validators + " sequencingSites=" + sites
                + " error=" + errors + " trace=" + traces + " other=" + others + " applyNodes=" + applies);
        assertTrue(validators > 0);
    }

    private static void walk(Term term, Map<String, Integer> counts) {
        switch (term) {
            case Term.Apply a -> {
                counts.merge("applies", 1, Integer::sum);
                if (a.function() instanceof Term.Lam lam && !usesIndex(lam.body(), 1)) {
                    counts.merge(classify(a.argument()), 1, Integer::sum);
                }
                walk(a.function(), counts);
                walk(a.argument(), counts);
            }
            case Term.Lam l -> walk(l.body(), counts);
            case Term.Force f -> walk(f.term(), counts);
            case Term.Delay d -> walk(d.term(), counts);
            case Term.Case c -> { walk(c.scrutinee(), counts); c.branches().forEach(b -> walk(b, counts)); }
            case Term.Constr c -> c.fields().forEach(f -> walk(f, counts));
            default -> { }
        }
    }

    private static String classify(Term e) {
        if (e instanceof Term.Error) return "error";
        Term head = e;
        while (head instanceof Term.Apply a) head = a.function();
        while (head instanceof Term.Force f) head = f.term();
        return head instanceof Term.Builtin b && b.fun() == DefaultFun.Trace ? "trace" : "other";
    }

    private static boolean usesIndex(Term term, int idx) {
        return switch (term) {
            case Term.Var v -> v.name().index() == idx;
            case Term.Lam l -> usesIndex(l.body(), idx + 1);
            case Term.Apply a -> usesIndex(a.function(), idx) || usesIndex(a.argument(), idx);
            case Term.Force f -> usesIndex(f.term(), idx);
            case Term.Delay d -> usesIndex(d.term(), idx);
            case Term.Case c -> usesIndex(c.scrutinee(), idx) || c.branches().stream().anyMatch(b -> usesIndex(b, idx));
            case Term.Constr c -> c.fields().stream().anyMatch(f -> usesIndex(f, idx));
            default -> false;
        };
    }
}
