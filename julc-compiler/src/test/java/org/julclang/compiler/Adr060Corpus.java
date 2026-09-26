package org.julclang.compiler;

import org.julclang.core.PlutusData;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.OptimizationCostProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * ADR-060 byte-stability corpus: every compileMethod fixture of the O-series suites, the
 * switch-dispatch validators, the golden validators and the builder-hygiene validators. Renaming a
 * compiler-generated binder must not change the FLAT bytes of any of these programs unless a
 * user name coincided with an internal one (recorded per row in {@link Adr060ByteSnapshotTest}).
 */
final class Adr060Corpus {
    private Adr060Corpus() {}

    /** A validator source ({@code method == null}) or a {@code compileMethod} fixture. */
    record Entry(String id, String source, String method, List<List<PlutusData>> inputs) {
        boolean validator() { return method == null; }
    }

    static final List<OptimizationLevel> LEVELS = List.of(OptimizationLevel.NONE, OptimizationLevel.BASELINE,
            OptimizationLevel.PV11_SAFE, OptimizationLevel.PV11_COSTED);

    static List<Entry> entries() {
        var entries = new ArrayList<Entry>();
        addFixtures(entries, "o8", O8ValueSharingFixtures.FIXTURES, f -> new Entry(null, f.source(), f.method(),
                f.inputs().stream().map(O8ValueSharingFixtures.Input::args).toList()));
        addFixtures(entries, "o9", O9ListIndexFixtures.FIXTURES, f -> new Entry(null, f.source(), f.method(),
                f.inputs().stream().map(O9ListIndexFixtures.Input::args).toList()));
        addFixtures(entries, "o10", O10ArrayLiteralFixtures.FIXTURES, f -> new Entry(null, f.source(), f.method(),
                f.inputs().stream().map(O10ArrayLiteralFixtures.Input::args).toList()));
        addFixtures(entries, "o11", O11BlsTypesFixtures.FIXTURES, f -> new Entry(null, f.source(), f.method(),
                f.inputs().stream().map(O11BlsTypesFixtures.Input::args).toList()));
        addFixtures(entries, "o14", O14ValueLiteralFixtures.FIXTURES, f -> new Entry(null, f.source(), f.method(),
                f.inputs().stream().map(O14ValueLiteralFixtures.Input::args).toList()));
        addFixtures(entries, "o15", O15ProjectionSharingFixtures.FIXTURES, f -> new Entry(null, f.source(), f.method(),
                f.inputs().stream().map(O15ProjectionSharingFixtures.Input::args).toList()));
        addValidators(entries, "o5", O5IntegerCaseFixtures.SOURCES);
        addValidators(entries, "switch", SwitchPairCaseLoweringTest.SOURCES);
        addValidators(entries, "golden", List.of(GoldenUplcTest.SIMPLE_VALIDATOR, GoldenUplcTest.FOREACH_SINGLE_ACC,
                GoldenUplcTest.WHILE_BREAK, GoldenUplcTest.NESTED_WHILE, GoldenUplcTest.HOF_MAP,
                GoldenUplcTest.HOF_FILTER, GoldenUplcTest.MULTI_ACC_WHILE, GoldenUplcTest.NESTED_WHILE_NO_ACC));
        addValidators(entries, "hygiene", hygieneSources());
        return entries;
    }

    private static <F> void addFixtures(List<Entry> entries, String prefix, List<F> fixtures, Function<F, Entry> map) {
        for (int i = 0; i < fixtures.size(); i++) {
            var e = map.apply(fixtures.get(i));
            entries.add(new Entry(prefix + "-" + i, e.source(), e.method(), e.inputs()));
        }
    }

    private static void addValidators(List<Entry> entries, String prefix, List<String> sources) {
        for (int i = 0; i < sources.size(); i++) entries.add(new Entry(prefix + "-" + i, sources.get(i), null, List.of()));
    }

    /** The PR #186 hygiene shapes: user names equal to builder binder names. */
    private static List<String> hygieneSources() {
        var bodies = new ArrayList<String>();
        for (var name : List.of("x", "acc", "lst", "go")) {
            bodies.add("return outer.any(" + name + " -> inner.any(y -> Builtins.equalsData(y, " + name + ")));");
            bodies.add("return outer.all(" + name + " -> inner.any(y -> Builtins.equalsData(y, " + name + ")));");
        }
        for (var name : List.of("h", "lst", "go"))
            bodies.add("return outer.any(" + name + " -> inner.find(y -> Builtins.equalsData(y, " + name
                    + ")).isPresent());");
        for (var name : List.of("x_flt", "acc_flt"))
            bodies.add("return outer.any(" + name + " -> inner.filter(y -> Builtins.equalsData(y, " + name
                    + ")).size() > 0);");
        bodies.add("return ListsLib.any(outer, x -> ListsLib.any(inner, y -> Builtins.equalsData(y, x)));");
        bodies.add("JulcList<PlutusData> go_zip = outer; "
                + "JulcList<PlutusData> zipped = ListsLib.zip(go_zip, inner); return zipped.size() == 1;");
        bodies.add("JulcList<PlutusData> go_get = inner; "
                + "return Builtins.equalsData(go_get.get(1), Builtins.iData(20)) && outer.size() == 1;");
        bodies.add("JulcList<PlutusData> target_c = inner; return target_c.contains(outer.head());");
        bodies.add("long go_drop = 1; return inner.drop(go_drop).size() == 1 && outer.size() == 1;");
        bodies.add("long _if = 7; if (outer.isEmpty()) { Builtins.error(); } return _if == 7 && outer.size() == 1;");
        bodies.add("long _forEach = 7; for (PlutusData item : outer) { } return _forEach == 7 && outer.size() == 1;");
        bodies.add("long count = 0; long total = 0; for (PlutusData item : inner) { count = count + 1; "
                + "total = total + Builtins.unIData(item); } return count == 2 && total == 30;");
        bodies.add("return outer.any(a -> inner.any(b -> Builtins.equalsData(b, a)));");
        return bodies.stream().map(BuilderBinderHygieneTest.LISTS::formatted).toList();
    }

    static CompileResult compile(Entry entry, OptimizationLevel level, boolean maps) {
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions()
                .setOptimizationLevel(level).setSourceMapEnabled(maps)
                .setOptimizationCostProfile(OptimizationCostProfiles.PLUTUS_V3_PV11_COSTS_V1));
        return entry.validator() ? compiler.compile(entry.source()) : compiler.compileMethod(entry.source(), entry.method());
    }
}
