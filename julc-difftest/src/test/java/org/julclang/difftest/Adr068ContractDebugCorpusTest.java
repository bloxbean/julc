package org.julclang.difftest;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerFrontend;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.compiler.schema.ContractSchema;
import org.julclang.core.debug.DebugMetadata;
import org.julclang.core.flat.UplcFlatEncoder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-068, entry points' slice: what I1 does not compare. For every corpus case compiled with {@code compileContract} or
 * {@code compileForDebug}, the schema, and the debug metadata at every level, of both frontends. A difference is one the
 * evidence names, or a finding: a string source's {@code uri} is compared by its file name (I4), a binding's type where it
 * is shown, and a library javac does not read (D8) is no difference, since the case stops there on {@code frontend=JAVAC}.
 * Writes {@code contract-debug.tsv} to {@code adr068.out}.
 */
@Tag("adr068-contract-debug")
class Adr068ContractDebugCorpusTest {

    private static final Path REPO = Path.of(System.getProperty("adr068.repo", "..")).toAbsolutePath().normalize();
    private static final Path OUT = Path.of(System.getProperty("adr068.out", REPO.resolve("build/adr068-contract-debug").toString()));
    private static final Set<String> CONTRACT = Set.of("compileContract", "compileContractWithDetails", "compileContractPath");

    private sealed interface Outcome {
    }

    private record Compiled(byte[] flat, String view) implements Outcome {
    }

    private record Rejected(String diagnostic) implements Outcome {
    }

    @Test
    void bothFrontendsDescribeAProgramAlike() throws Exception {
        var corpus = Adr068TypesTest.corpus(REPO);
        var rows = new TreeMap<String, String>();
        var differences = new ArrayList<String>();
        int compared = 0;
        int contracts = 0;
        int debugs = 0;
        for (var c : corpus.sorted()) {
            boolean contract = CONTRACT.contains(c.entry);
            boolean debug = c.entry.equals("compileForDebug");
            if (!contract && !debug) continue;
            if (!c.lookup.equals("none") && !c.lookup.equals(Adr068Corpus.STDLIB_LOOKUP)) {
                rows.put(c.key, "unreplayable\t" + c.lookup);
                continue;
            }
            var levels = debug ? Arrays.asList(OptimizationLevel.values()) : List.of(OptimizationLevel.DEFAULT);
            var kinds = new ArrayList<String>();
            for (var level : levels) {
                var reference = compile(c, level, CompilerFrontend.JAVAPARSER, debug);
                var javac = compile(c, level, CompilerFrontend.JAVAC, debug);
                String kind;
                if (reference instanceof Compiled r && javac instanceof Compiled j) {
                    compared++;
                    if (!Arrays.equals(r.flat(), j.flat())) {
                        kind = "bytes-differ";
                    } else if (r.view().equals(j.view())) {
                        kind = "equal";
                    } else {
                        kind = "DIFFERENT";
                        differences.add(c.key + " " + c.entry + " " + level + "\n" + diff(r.view(), j.view()));
                    }
                } else if (reference instanceof Rejected && javac instanceof Rejected) {
                    kind = "both-reject";
                } else if (javac instanceof Rejected j) {
                    kind = "javac-rejects " + j.diagnostic();
                } else {
                    kind = "reference-rejects " + ((Rejected) reference).diagnostic();
                }
                kinds.add(level + "=" + kind);
            }
            if (contract) contracts++;
            else debugs++;
            rows.put(c.key, c.entry + "\t" + String.join(" ", kinds));
        }
        Files.createDirectories(OUT);
        Files.write(OUT.resolve("contract-debug.tsv"), rows.entrySet().stream().map(e -> e.getKey() + "\t" + e.getValue()).toList());
        Files.write(OUT.resolve("contract-debug-differences.txt"), differences);
        System.out.println("[ADR-068 contract and debug] " + contracts + " contract cases, " + debugs + " debug cases, "
                + compared + " compared, " + differences.size() + " differences");
        rows.values().stream().map(row -> row.replaceAll("[A-Z0-9_]+=", "")).collect(Collectors.groupingBy(row -> row, TreeMap::new,
                Collectors.counting())).forEach((row, n) -> System.out.println(n + "\t" + row));
        assertTrue(contracts > 0 && debugs > 0, "no case reached the comparison");
        // Where the bytes differ, I1 classifies the row; the description is compared where they are equal.
        assertEquals(List.of(), differences.stream().limit(10).toList(), () -> differences.size() + " differences");
    }

    private static Outcome compile(Adr068Corpus.Case c, OptimizationLevel level, CompilerFrontend frontend, boolean debug) {
        CompilerOptions options = Adr068Ledger.options(c, level, debug || c.sourceMap).setFrontend(frontend);
        var compiler = new JulcCompiler(Adr068Ledger.lookup(c.lookup), options);
        var libraries = Adr068Ledger.texts(c.libraries);
        try {
            if (debug) {
                var result = compiler.compileForDebug(c.main.text(), libraries);
                return new Compiled(UplcFlatEncoder.encodeProgram(result.compileResult().program()), view(result.debugMetadata()));
            }
            var result = compiler.compileContract(c.main.text(), libraries);
            return new Compiled(UplcFlatEncoder.encodeProgram(result.compileResult().program()), view(result.contractSchema()));
        } catch (CompilerException e) {
            var first = e.diagnostics().isEmpty() ? null : e.diagnostics().getFirst();
            return new Rejected(first == null ? e.getMessage() : first.code() + "@" + first.line() + ":" + first.column());
        } catch (RuntimeException e) {
            return new Rejected(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static String diff(String reference, String javac) {
        var left = reference.lines().toList();
        var right = javac.lines().toList();
        var text = new StringBuilder();
        for (int i = 0; i < Math.max(left.size(), right.size()); i++) {
            String l = i < left.size() ? left.get(i) : "";
            String r = i < right.size() ? right.get(i) : "";
            if (!l.equals(r)) text.append("  - ").append(l).append("\n  + ").append(r).append('\n');
        }
        return text.toString();
    }

    static String view(ContractSchema schema) {
        var text = new StringBuilder("indexed=" + schema.purposeIndexed() + "\n");
        for (var validatorInterface : schema.interfaces()) {
            text.append(validatorInterface.purpose()).append(' ').append(validatorInterface.entrypointName()).append(" @")
                    .append(validatorInterface.sourceLocation().line()).append("\n  datum ").append(view(validatorInterface.datum()))
                    .append("\n  redeemer ").append(view(validatorInterface.redeemer())).append('\n');
        }
        // A @Param's location is javac's declaration, which starts at its annotation, a line above the name where the
        // annotation stands alone: a Diagnostic change (D13), printed only in a schema generator's message.
        for (var parameter : schema.parameters()) {
            text.append("param ").append(parameter.name()).append(": ").append(parameter.type()).append('\n');
        }
        schema.namedDefinitions().forEach((id, type) -> text.append("definition ").append(id).append(' ').append(type).append('\n'));
        return text.toString();
    }

    private static String view(ContractSchema.Argument argument) {
        return argument == null ? "-" : argument.name() + ": " + argument.type() + " @" + argument.sourceLocation().line();
    }

    static String view(DebugMetadata metadata) {
        var text = new StringBuilder();
        metadata.sources().forEach(source -> text.append("source ").append(source.id()).append(' ')
                .append(Path.of(source.uri()).getFileName()).append(' ').append(source.contentSha256()).append('\n'));
        metadata.scopes().forEach(scope -> text.append("scope ").append(scope).append('\n'));
        var shown = metadata.loweredBindings().stream().map(DebugMetadata.LoweredBinding::sourceBindingId)
                .filter(id -> id != null).collect(Collectors.toSet());
        metadata.bindings().forEach(binding -> text.append("binding ").append(binding.id()).append(' ').append(binding.name())
                .append(' ').append(binding.declarationRange()).append(' ').append(binding.scopeId()).append(' ')
                .append(binding.kind()).append(' ').append(binding.declaredSpelling())
                .append(shown.contains(binding.id()) ? " " + binding.typeId() : "").append('\n'));
        metadata.loweredBindings().forEach(binding -> text.append("lowered ").append(binding).append('\n'));
        metadata.suspensionPoints().forEach(point -> text.append("point ").append(point).append('\n'));
        return text.toString();
    }
}
