package org.julclang.difftest;

import org.julclang.blueprint.SchemaGenerator;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.frontend.javac.JavacEvidence;
import org.julclang.compiler.frontend.javac.JavacFrontend;
import org.julclang.compiler.frontend.javac.JavacGateMode;
import org.julclang.compiler.frontend.javac.JavacProgram;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.pir.StdlibLookup;
import org.julclang.compiler.resolve.TypeResolver;
import org.julclang.compiler.schema.ContractSchema;
import org.julclang.core.corpus.CorpusFiles;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalResult;
import org.julclang.vm.ExBudget;
import org.julclang.vm.JulcVm;
import org.julclang.vm.OptimizationCostProfiles;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.TypeElement;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-068 M2's independent encoding tests (I11), over the corpus: the Data of every corpus type and every blueprint
 * schema, from the javac frontend's registry, against the frozen reference. Run by {@code adr068Types}.
 * <p>
 * With {@code -Padr068.freeze=true} it writes the reference from the JavaParser frontend (the I1 reference): each
 * type's Data comes from a program that frontend compiled and the VM evaluated, so it is not computed by the code
 * under test. Otherwise it reads the frozen files in {@code adr/evidence/068/} and compares.
 */
@Tag("adr068-types")
class Adr068EncodingsTest {

    private static final Path REPO = Path.of(System.getProperty("adr068.repo", "..")).toAbsolutePath().normalize();
    private static final Path OUT = Path.of(System.getProperty("adr068.out", REPO.resolve("build/adr068-types").toString()));
    private static final Path FROZEN = REPO.resolve("adr/evidence/068");
    private static final boolean FREEZE = Boolean.parseBoolean(System.getProperty("adr068.freeze", "false"));
    private static final ExBudget BUDGET = new ExBudget(10_000_000_000L, 14_000_000L);
    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+[\\w.]+\\s*;");
    private static final String IMPORTS = "import java.math.BigInteger; import java.util.Optional;"
            + " import org.julclang.core.PlutusData; import org.julclang.core.types.JulcList;"
            + " import org.julclang.core.types.JulcMap; import org.julclang.stdlib.Builtins; ";

    private static final ThreadLocal<JulcVm> VM = ThreadLocal.withInitial(() -> {
        var vm = JulcVm.create("java");
        var profile = OptimizationCostProfiles.PLUTUS_V3_PV11_COSTS_V1;
        vm.setCostModelParams(profile.costModelParameters(), profile.target());
        return vm;
    });

    /** One sample of one type in one case. */
    private record Probe(String key, String type, String java) {
    }

    @Test
    void dataAndBlueprintsAgainstTheFrozenReference() throws Exception {
        var corpus = Adr068TypesTest.corpus(REPO);
        var encodings = new ConcurrentHashMap<String, String>();     // layout key -> digest of the javac registry's Data
        var references = new ConcurrentHashMap<String, String>();    // layout key -> digest of the reference's Data
        var blueprints = new ConcurrentHashMap<String, String>();    // case/interface -> digest of javac's schemas
        var referenceBlueprints = new ConcurrentHashMap<String, String>();
        var skips = new ConcurrentHashMap<String, Integer>();
        var conflicts = new ConcurrentHashMap<String, String>();
        var rejectedOnly = ConcurrentHashMap.<String>newKeySet();
        int threads = Integer.parseInt(System.getProperty("adr068.threads",
                String.valueOf(Math.max(1, Runtime.getRuntime().availableProcessors() - 2))));
        var pool = new ForkJoinPool(threads);
        try {
            pool.submit(() -> corpus.sorted().parallelStream().forEach(c -> {
                try {
                    probeCase(c, encodings, references, blueprints, referenceBlueprints, skips, conflicts, rejectedOnly);
                } catch (RuntimeException | StackOverflowError e) {
                    // The encoder met a sample its registry cannot hold: that is a wrong registry, not a skip.
                    conflicts.put(c.key, "the encoder failed: " + Adr068Types.message(e));
                }
            })).get();
        } finally {
            pool.shutdown();
        }
        Files.createDirectories(OUT);
        if (FREEZE) {
            write(OUT.resolve("encodings-b1.tsv"), references);
            write(OUT.resolve("blueprints-b1.tsv"), referenceBlueprints);
        }
        var frozenEncodings = read(FREEZE ? OUT.resolve("encodings-b1.tsv") : FROZEN.resolve("encodings-b1.tsv"));
        var frozenBlueprints = read(FREEZE ? OUT.resolve("blueprints-b1.tsv") : FROZEN.resolve("blueprints-b1.tsv"));
        var totals = new TreeMap<String, Integer>();
        var differences = new ArrayList<String>();
        compare("encoding", encodings, frozenEncodings, totals, differences);
        compare("blueprint", blueprints, frozenBlueprints, totals, differences);
        skips.forEach((reason, n) -> totals.put("skip: " + reason, n));
        encodings.keySet().forEach(key -> rejectedOnly.remove(key.substring(0, key.indexOf('\t', key.indexOf('\t') + 1))));
        totals.put("layouts only in sources javac rejects", rejectedOnly.size());
        conflicts.forEach((key, detail) -> differences.add("conflict\t" + key + "\t" + detail));
        var accepted = new TreeMap<>(Adr068Types.acceptedDifferences(REPO, "encoding"));
        accepted.putAll(Adr068Types.acceptedDifferences(REPO, "blueprint"));
        var summary = new ArrayList<String>();
        totals.forEach((kind, n) -> summary.add(kind + "\t" + n));
        Files.write(OUT.resolve("encodings-m2-totals.tsv"), summary);
        Files.write(OUT.resolve("encodings-m2-differences.tsv"), differences);
        summary.forEach(System.out::println);
        differences.stream().limit(40).forEach(System.out::println);
        assertTrue(totals.getOrDefault("encoding.equal", 0) > 0, "no encoding was compared");
        assertTrue(totals.getOrDefault("blueprint.equal", 0) > 0, "no blueprint was compared");
        var found = new TreeSet<String>();
        differences.forEach(line -> found.add(line.split("\t", 2)[1].replaceFirst("\t[0-9a-f]{32}\t[0-9a-f]{32}$", "")
                .replace('\t', '|')));
        assertEquals(accepted.keySet(), found, "an unclassified difference (D13), or a stale entry");
    }

    private static void compare(String kind, Map<String, String> actual, Map<String, String> frozen,
                                Map<String, Integer> totals, List<String> differences) {
        actual.forEach((key, digest) -> {
            String expected = frozen.get(key);
            if (expected == null) {
                // A layout the reference never had: a wrong layout under test lands here, so it is a difference.
                totals.merge(kind + ".DIFFERENT (no such layout in the reference)", 1, Integer::sum);
                differences.add(kind + "\t" + key + "\tno such layout in the reference\t" + digest);
            } else if (expected.startsWith("-")) {
                totals.merge(kind + ".no-reference: " + expected, 1, Integer::sum);
            } else if (expected.equals(digest)) {
                totals.merge(kind + ".equal", 1, Integer::sum);
            } else {
                totals.merge(kind + ".DIFFERENT", 1, Integer::sum);
                differences.add(kind + "\t" + key + "\t" + expected + "\t" + digest);
            }
        });
    }

    private static void write(Path file, Map<String, String> rows) throws Exception {
        var lines = new ArrayList<String>();
        new TreeMap<>(rows).forEach((key, value) -> lines.add(key + "\t" + value));
        Files.write(file, lines);
    }

    private static Map<String, String> read(Path file) throws Exception {
        var rows = new TreeMap<String, String>();
        if (!Files.exists(file)) return rows;
        for (String line : Files.readAllLines(file)) {
            int tab = line.lastIndexOf('\t');
            rows.put(line.substring(0, tab), line.substring(tab + 1));
        }
        return rows;
    }

    private static void probeCase(Adr068Corpus.Case c, Map<String, String> encodings, Map<String, String> references,
                                  Map<String, String> blueprints, Map<String, String> referenceBlueprints,
                                  Map<String, Integer> skips, Map<String, String> conflicts, Set<String> rejectedOnly) {
        if (c.main == null || c.entry.equals("ledger-source")) return;
        JavacProgram program;
        var libraries = c.libraries.stream().map(l -> l.text()).toList();
        try {
            program = JavacProgram.analyze(List.of(JavacFrontend.Unit.of(c.main.text())),
                    libraries.stream().map(JavacFrontend.Unit::of).toList());
        } catch (RuntimeException e) {
            skips.merge("javac rejects the case", 1, Integer::sum);
            // Such a source never reaches the javac frontend, so its types have no Data to compare; count those no
            // accepted source has. Their registries are compared by Adr068TypesTest.
            var read = Adr068Types.tolerant(Adr068Types.units(c));
            if (read.accepted()) {
                read.registry().records().forEach((identity, definition) -> {
                    if (!Adr068Encodings.ledger(identity)) rejectedOnly.add(Adr068Encodings.layout(identity, definition));
                });
                read.registry().sums().forEach((identity, definition) -> {
                    if (!Adr068Encodings.ledger(identity)) rejectedOnly.add(Adr068Encodings.layout(identity, definition));
                });
            }
            return;
        }
        var registry = program.types().registry();
        for (var probe : probes(program, registry, encodings, conflicts, skips)) {
            if (FREEZE && !references.containsKey(probe.key())) {
                references.put(probe.key(), reference(c.main.text(), program, probe, libraries));
            }
        }
        blueprint(c, program, libraries, c.lookup.equals(Adr068Corpus.STDLIB_LOOKUP) ? StdlibRegistry.defaultRegistry() : null,
                blueprints, referenceBlueprints);
    }

    /** Every user type's samples: one per record and {@code @NewType}, one per variant of a sealed interface. */
    private static List<Probe> probes(JavacProgram program, TypeResolver.Registry registry, Map<String, String> encodings,
                                      Map<String, String> conflicts, Map<String, Integer> skips) {
        var probes = new ArrayList<Probe>();
        registry.records().forEach((identity, record) -> {
            if (Adr068Encodings.ledger(identity) || registry.variants().containsKey(identity)) return;
            add(program, registry, identity, identity, record, record, probes, encodings, conflicts, skips);
        });
        registry.newTypes().forEach((identity, underlying) -> {
            if (Adr068Encodings.ledger(identity)) return;
            add(program, registry, identity, identity, underlying, underlying, probes, encodings, conflicts, skips);
        });
        registry.sums().forEach((identity, sum) -> {
            if (Adr068Encodings.ledger(identity)) return;
            for (var constructor : sum.constructors()) {
                String variant = variantIdentity(registry, identity, sum, constructor.name());
                if (variant == null) continue;
                add(program, registry, identity, variant, sum, sum, probes, encodings, conflicts, skips);
            }
        });
        return probes;
    }

    private static String variantIdentity(TypeResolver.Registry registry, String sum, PirType.SumType type, String name) {
        for (var entry : registry.variants().entrySet()) {
            if (entry.getValue().contains(type) && entry.getKey().endsWith("." + name) || entry.getValue().contains(type) && entry.getKey().equals(name)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static void add(JavacProgram program, TypeResolver.Registry registry, String type, String sampled,
                            PirType definition, PirType at, List<Probe> probes, Map<String, String> encodings,
                            Map<String, String> conflicts, Map<String, Integer> skips) {
        TypeElement element = JavacEvidence.type(program, sampled);
        if (element == null) {
            skips.merge("a local type has no element by name", 1, Integer::sum);
            return;
        }
        var sampler = new Adr068Encodings.Sampler(program.facts());
        Adr068Encodings.Sample sample;
        try {
            sample = registry.newTypes().containsKey(sampled) ? sampler.newType(element) : sampler.record(element, 0);
        } catch (Adr068Encodings.NoSample e) {
            skips.merge("no sample of " + type + ": " + e.getMessage(), 1, Integer::sum);
            return;
        }
        // The sample's text is in the key: one layout may be written in Java with different leaves (a @NewType field).
        // A variant of two sealed parents (#279) takes its tag from the last one, so the other is part of its layout.
        var family = registry.variants().getOrDefault(sampled, List.of());
        String key = Adr068Encodings.layout(type, family.size() > 1 ? new PirType.FunType(definition, family.getLast()) : definition)
                + "\t" + sampled + "\t" + CorpusFiles.sha256(sample.java()).substring(0, 16);
        String digest = Adr068Encodings.digest(Adr068Encodings.encode(sample, at, registry));
        var previous = encodings.putIfAbsent(key, digest);
        if (previous != null && !previous.equals(digest)) conflicts.put(key, previous + " / " + digest);
        probes.add(new Probe(key, type, sample.java()));
    }

    /**
     * The reference: a program the JavaParser frontend compiles, evaluated on the VM. The probe goes into the case's
     * first class; if that source does not compile on its own (a rejection fixture), into its declarations alone.
     */
    private static String reference(String source, JavacProgram program, Probe probe, List<String> libraries) {
        String method = "adr068Probe";
        String body = "\n    static " + probe.type() + " " + method + "() { return " + probe.java() + "; }\n";
        int end = JavacEvidence.firstClassEnd(program);
        String tail = "\nclass Adr068Probe {" + body + "}\n";
        String text = end < 0 ? source + tail : source.substring(0, end) + body + source.substring(end);
        String first = compileAndRun(text, method, libraries);
        if (!first.startsWith("-rejected")) return first;
        try {
            var unit = program.roots().getFirst();
            String declarations = JavacEvidence.declarationsOnly(program, unit, end, body);
            if (end < 0) declarations += tail;
            var read = new ArrayList<String>();
            for (var library : program.libraries()) read.add(JavacEvidence.declarationsOnly(program, library, -1, ""));
            return compileAndRun(declarations, method, read);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String compileAndRun(String source, String method, List<String> libraries) {
        var matcher = PACKAGE.matcher(source);
        String text = matcher.find() ? source.substring(0, matcher.end()) + IMPORTS + source.substring(matcher.end()) : IMPORTS + source;
        try {
            var options = new CompilerOptions().setJavacGate(JavacGateMode.OFF).setLogger(m -> { });
            var compiled = new JulcCompiler(StdlibRegistry.defaultRegistry(), options).compileMethod(text, method, libraries);
            return switch (VM.get().evaluateWithArgs(compiled.program(), List.of(), BUDGET)) {
                case EvalResult.Success s -> Adr068Encodings.digest(ValueMapping.fromTerm(s.resultTerm()));
                case EvalResult.Failure f -> "-failure";
                case EvalResult.BudgetExhausted b -> "-budget";
            };
        } catch (RuntimeException e) {
            return "-rejected: " + Adr068Types.message(e).replaceAll("\\s+", " ");
        }
    }

    private static void blueprint(Adr068Corpus.Case c, JavacProgram program, List<String> libraries,
                                  StdlibLookup lookup, Map<String, String> blueprints,
                                  Map<String, String> referenceBlueprints) {
        ContractSchema schema;
        try {
            var options = new CompilerOptions().setJavacGate(JavacGateMode.OFF).setLogger(m -> { });
            schema = new JulcCompiler(lookup, options).compileContract(c.main.text(), libraries).contractSchema();
        } catch (RuntimeException e) {
            return;     // no validator, or no schema: nothing to compare
        }
        if (schema == null) return;
        var facts = program.facts();
        var interfaces = new ArrayList<ContractSchema.ValidatorInterface>();
        for (var i : schema.interfaces()) {
            var datum = i.datum() == null ? null : argument(program, i.entrypointName(), i.datum(), true);
            interfaces.add(new ContractSchema.ValidatorInterface(i.entrypointName(), i.purpose(), datum,
                    argument(program, i.entrypointName(), i.redeemer(), false), i.sourceLocation()));
        }
        var parameters = schema.parameters().stream().map(p -> new ContractSchema.Argument(p.name(),
                facts.pirType(JavacEvidence.fieldType(program, p.name()), null), p.sourceLocation())).toList();
        var javac = new ContractSchema(interfaces, parameters, program.types().namedDefinitions(), schema.purposeIndexed());
        for (int i = 0; i < schema.interfaces().size(); i++) {
            String key = c.key + "\t" + schema.interfaces().get(i).purpose();
            referenceBlueprints.put(key, digest(SchemaGenerator.from(schema, schema.interfaces().get(i), null, true)));
            blueprints.put(key, digest(SchemaGenerator.from(javac, interfaces.get(i), null, true)));
        }
    }

    private static ContractSchema.Argument argument(JavacProgram program, String method, ContractSchema.Argument argument,
                                                    boolean datum) {
        var type = program.facts().pirType(JavacEvidence.parameterType(program, method, argument.name()), null);
        if (datum && type instanceof PirType.OptionalType optional) type = optional.elemType();
        return new ContractSchema.Argument(argument.name(), type, argument.sourceLocation());
    }

    private static String digest(SchemaGenerator.ValidatorSchema schema) {
        return CorpusFiles.sha256(schema.toString()).substring(0, 32);
    }
}
