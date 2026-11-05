package org.julclang.difftest;

import org.julclang.compiler.CompileResult;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JavaLibraryProvider;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.compiler.frontend.javac.JavacEvidence;
import org.julclang.compiler.frontend.javac.JavacFrontend;
import org.julclang.compiler.frontend.javac.JavacProgram;
import org.julclang.compiler.schema.ContractSchema;
import org.julclang.core.corpus.CorpusFiles;
import org.julclang.core.debug.DebugMetadata;
import org.julclang.core.flat.UplcFlatEncoder;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * ADR-068 I1 after M6: what the javac frontend gives for one corpus case, as a line of {@code lowering-javac.tsv}. The line
 * holds whatever a comparison with the reference read of the javac side, so with the reference frozen as data an equal line
 * is an equal row of {@code lowering-i1.tsv}: every level with and without a map, the diagnostics and {@code @Param} infos,
 * the located terms, each method's binder, type and term, the type registry, and a contract's schema or a debug program's
 * metadata. {@code lowering-reference.tsv} and {@code lowering-i1.tsv} stay as the evidence of what the table was frozen
 * against, at M6's start, in a run with 0 moved rows ({@code adr/evidence/068-m6.md}).
 */
final class Adr068Lowering {

    /** One compilation: what the ledger records of it, and what it threw, if it did. */
    record Compilation(Adr068Ledger.Outcome outcome, CompileResult result, Throwable thrown) {
        boolean accepted() {
            return outcome.accepted();
        }
    }

    /** What the tool takes from the frontend, so that a test can inject the difference the gate must report. */
    interface Javac {
        Compilation compile(Adr068Corpus.Case c, CompilerOptions options);

        List<JavacEvidence.Attempt> attempts(Adr068Corpus.Case c, CompilerOptions options);
    }

    private static final Set<String> CONTRACT = Set.of("compileContract", "compileContractWithDetails", "compileContractPath");

    /** The frontend itself. */
    static Javac frontend() {
        return FRONTEND;
    }

    private static final Javac FRONTEND = new Javac() {
        @Override
        public Compilation compile(Adr068Corpus.Case c, CompilerOptions options) {
            return Adr068Lowering.compile(c, options);
        }

        @Override
        public List<JavacEvidence.Attempt> attempts(Adr068Corpus.Case c, CompilerOptions options) {
            return JavacEvidence.attempts(c.main.text(), Adr068Ledger.texts(c.libraries),
                    c.entry.equals("compileMethod") ? c.method : null, Adr068Ledger.lookup(c.lookup), options);
        }
    };

    private final Javac javac;

    Adr068Lowering() {
        this(FRONTEND);
    }

    Adr068Lowering(Javac javac) {
        this.javac = javac;
    }

    private static Compilation compile(Adr068Corpus.Case c, CompilerOptions options) {
        try {
            if (c.entry.equals("JavaLibraryProvider")) {
                var provider = new JavaLibraryProvider(Adr068Ledger.texts(c.libraries), Adr068Ledger.lookup(c.lookup), options);
                var failures = new ArrayList<Adr068Ledger.Diagnostic>();
                String description = Adr068Ledger.describe(provider, c.libraries, failures);
                return new Compilation(Adr068Ledger.Outcome.accepted(CorpusFiles.sha256(description).substring(0, 32), -1, failures), null, null);
            }
            var result = Adr068Ledger.invoke(c, options);
            byte[] flat = UplcFlatEncoder.encodeProgram(result.program());
            return new Compilation(Adr068Ledger.Outcome.accepted(CorpusFiles.sha256(flat).substring(0, 32), flat.length,
                    Adr068Ledger.diagnostics(result.diagnostics())), result, null);
        } catch (AssertionError e) {
            throw e;
        } catch (Throwable t) {
            return new Compilation(Adr068Ledger.rejection(t), null, t);
        }
    }

    /**
     * Only a {@link CompilerException} is a rejection: anything else, a failed I13 check or a crash, is the frontend breaking,
     * and that is no row. A {@link JavaLibraryProvider} is the one other API: it refuses a source it cannot export with an
     * {@link IllegalArgumentException}. And {@code compileForDebug} refuses a source whose ranges its metadata cannot hold
     * (lone carriage returns) with a {@link DebugMetadata.ValidationException}.
     */
    private Compilation onJavac(Adr068Corpus.Case c, CompilerOptions options) {
        var compilation = javac.compile(c, options);
        var thrown = compilation.thrown();
        boolean rejection = thrown == null || thrown instanceof CompilerException
                || c.entry.equals("JavaLibraryProvider") && thrown instanceof IllegalArgumentException
                || c.entry.equals("compileForDebug") && thrown instanceof DebugMetadata.ValidationException;
        if (!rejection) throw new AssertionError(c.key + ": " + thrown, thrown);
        return compilation;
    }

    /** The source-map settings a line reads: both, for a program. A provider's row has no map to set. */
    private static List<Boolean> sourceMapSettings(Adr068Corpus.Case c) {
        return Adr068Ledger.isProgramEntry(c.entry) ? List.of(false, true) : List.of(c.sourceMap);
    }

    /** The case's line of {@code lowering-javac.tsv}. */
    String observation(Adr068Corpus.Case c) {
        if (c.entry.equals("ledger-source")) return c.key + "\tledger-source";
        String unreplayable = Adr068Ledger.unreplayableReason(c);
        if (unreplayable != null) return c.key + "\tunreplayable\t" + unreplayable.replace(' ', '-');
        var cells = new ArrayList<String>();
        for (boolean sourceMap : sourceMapSettings(c)) {
            for (var level : Adr068Ledger.LEVELS) cells.add(onJavac(c, Adr068Ledger.options(c, level, sourceMap)).outcome().cell());
        }
        var atDefault = onJavac(c, Adr068Ledger.options(c, OptimizationLevel.DEFAULT, c.sourceMap));
        var located = new ArrayList<String>();
        if (Adr068Ledger.isProgramEntry(c.entry)) {
            for (var level : Adr068Ledger.LEVELS) {
                var mapped = onJavac(c, Adr068Ledger.options(c, level, true));
                located.add(mapped.accepted() ? locations(mapped.result()) : "-");
            }
        }
        var methods = new ArrayList<String>();
        if (Adr068Ledger.isProgramEntry(c.entry) && c.main != null) {
            try {
                for (var attempt : javac.attempts(c, Adr068Ledger.options(c, OptimizationLevel.DEFAULT, c.sourceMap))) {
                    methods.add(attempt.term() == null ? "stop:" + attempt.construct()
                            : digest(attempt.binder() + "\n" + attempt.type() + "\n" + PirCanonical.of(attempt.term())));
                }
            } catch (CompilerException e) {
                methods.add("rejected");   // Rejected before its methods: the cells say how.
            }
        }
        return c.key + "\tcompiled\t" + String.join("\t", cells) + "\t" + atDefault.outcome().diagnosticsCell() + "\t"
                + (atDefault.result() == null ? "-" : digest(atDefault.result().params().toString())) + "\t"
                + (located.isEmpty() ? "-" : String.join(",", located)) + "\t" + (methods.isEmpty() ? "-" : String.join(",", methods))
                + "\t" + registry(c) + "\t" + description(c);
    }

    /** The located terms, by index, with their file names, lines, columns and fragments. */
    private static String locations(CompileResult result) {
        var text = new StringBuilder();
        new TreeMap<>(result.sourceMap().toIndexed(result.program().term())).forEach((index, location) -> text.append(index)
                .append('\t').append(location.fileName() == null ? null : Path.of(location.fileName()).getFileName()).append('\t')
                .append(location.line()).append('\t').append(location.column()).append('\t').append(location.fragment()).append('\n'));
        return digest(text.toString());
    }

    /** I11: what javac registers of the case's units, or the rejection that stops it. */
    private static String registry(Adr068Corpus.Case c) {
        if (c.main == null) return "-";
        try {
            var units = c.libraries.stream().map(source -> JavacFrontend.Unit.of(source.text())).toList();
            var registry = JavacProgram.analyze(List.of(JavacFrontend.Unit.of(c.main.text())), units).registry().snapshot();
            return digest(new TreeMap<>(registry.records()) + "|" + new TreeMap<>(registry.sums()) + "|" + new TreeMap<>(registry.variants())
                    + "|" + new TreeMap<>(registry.newTypes()) + "|" + registry.parameterized());
        } catch (RuntimeException e) {
            return "rejected:" + digest(String.valueOf(e.getMessage()));
        }
    }

    /** A contract's schema, or a debug program's metadata at every level; - for any other entry. */
    private static String description(Adr068Corpus.Case c) {
        boolean contract = CONTRACT.contains(c.entry);
        boolean debug = c.entry.equals("compileForDebug");
        if (!contract && !debug) return "-";
        if (!c.lookup.equals("none") && !c.lookup.equals(Adr068Corpus.STDLIB_LOOKUP)) return "unreplayable";
        var levels = debug ? Arrays.asList(OptimizationLevel.values()) : List.of(OptimizationLevel.DEFAULT);
        var views = new ArrayList<String>();
        for (var level : levels) {
            var compiler = new JulcCompiler(Adr068Ledger.lookup(c.lookup), Adr068Ledger.options(c, level, debug || c.sourceMap));
            var libraries = Adr068Ledger.texts(c.libraries);
            try {
                views.add(digest(debug ? view(compiler.compileForDebug(c.main.text(), libraries).debugMetadata())
                        : view(compiler.compileContract(c.main.text(), libraries).contractSchema())));
            } catch (RuntimeException e) {
                views.add("rejected:" + digest(String.valueOf(e.getMessage())));
            }
        }
        return String.join(",", views);
    }

    private static String view(ContractSchema schema) {
        var text = new StringBuilder("indexed=" + schema.purposeIndexed() + "\n");
        for (var validatorInterface : schema.interfaces()) {
            text.append(validatorInterface.purpose()).append(' ').append(validatorInterface.entrypointName()).append(" @")
                    .append(validatorInterface.sourceLocation().line()).append("\n  datum ").append(view(validatorInterface.datum()))
                    .append("\n  redeemer ").append(view(validatorInterface.redeemer())).append('\n');
        }
        for (var parameter : schema.parameters()) {
            text.append("param ").append(parameter.name()).append(": ").append(parameter.type()).append('\n');
        }
        new TreeMap<>(schema.namedDefinitions()).forEach((id, type) -> text.append("definition ").append(id).append(' ').append(type).append('\n'));
        return text.toString();
    }

    private static String view(ContractSchema.Argument argument) {
        return argument == null ? "-" : argument.name() + ": " + argument.type() + " @" + argument.sourceLocation().line();
    }

    private static String view(DebugMetadata metadata) {
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

    private static String digest(String text) {
        return CorpusFiles.sha256(text).substring(0, 16);
    }

    /** Both directions: a committed line not reproduced, and a line the table does not have. */
    static List<String> moved(Map<String, String> frozen, Map<String, String> lines) {
        var moved = new ArrayList<String>();
        frozen.forEach((key, line) -> {
            if (!line.equals(lines.get(key))) moved.add(line + "  ->  " + lines.getOrDefault(key, "(not observed)"));
        });
        lines.forEach((key, line) -> {
            if (!frozen.containsKey(key)) moved.add("(new)  ->  " + line);
        });
        return moved;
    }
}
