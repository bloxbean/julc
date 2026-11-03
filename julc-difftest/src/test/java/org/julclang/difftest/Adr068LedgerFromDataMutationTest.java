package org.julclang.difftest;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerFrontend;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.LedgerTypeProvider;
import org.julclang.compiler.frontend.javac.NotPorted;
import org.julclang.core.PlutusData;
import org.julclang.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A ledger type's {@code fromPlutusData} on {@code frontend=JAVAC}, exhaustively near well-formed Data: for every ledger type
 * with a JVM decoder, three well-formed values and every single-node change of each (a node of another kind; a tag one or
 * two above or one below; a field more of two kinds, one fewer, none; and the same, recursively, in every field, list item,
 * map key and map value). Each input is held to {@link Adr068LedgerFromDataPropertyTest#verify}'s rule at all four levels on
 * both frontends. A type whose shape holds a map does not compile on {@code JAVAC}.
 */
class Adr068LedgerFromDataMutationTest {

    private static final LedgerTypeProvider LEDGER = new LedgerTypeProvider();
    private static final PlutusData ONE = new PlutusData.IntData(BigInteger.ONE);
    private static final int SEEDS = 3;
    private static final int PER_TYPE = 4000;

    /** Every single-node change of {@code data}. */
    static List<PlutusData> mutations(PlutusData data) {
        var out = new ArrayList<PlutusData>();
        var others = List.<PlutusData>of(new PlutusData.IntData(BigInteger.TWO), new PlutusData.BytesData(new byte[]{1}),
                new PlutusData.ConstrData(0, List.of()), new PlutusData.ListData(List.of()), new PlutusData.MapData(List.of()));
        for (var other : others) if (other.getClass() != data.getClass()) out.add(other);
        switch (data) {
            case PlutusData.ConstrData constr -> {
                var fields = constr.fields();
                out.add(new PlutusData.ConstrData(constr.tag() + 1, fields));
                out.add(new PlutusData.ConstrData(constr.tag() + 2, fields));
                if (constr.tag() > 0) out.add(new PlutusData.ConstrData(constr.tag() - 1, fields));
                var more = new ArrayList<>(fields);
                more.add(ONE);
                out.add(new PlutusData.ConstrData(constr.tag(), more));
                var moreConstr = new ArrayList<>(fields);
                moreConstr.add(new PlutusData.ConstrData(5, List.of()));
                out.add(new PlutusData.ConstrData(constr.tag(), moreConstr));
                if (!fields.isEmpty()) {
                    out.add(new PlutusData.ConstrData(constr.tag(), fields.subList(0, fields.size() - 1)));
                    out.add(new PlutusData.ConstrData(constr.tag(), List.of()));
                }
                for (int i = 0; i < fields.size(); i++) {
                    for (var changed : mutations(fields.get(i))) {
                        var copy = new ArrayList<>(fields);
                        copy.set(i, changed);
                        out.add(new PlutusData.ConstrData(constr.tag(), copy));
                    }
                }
            }
            case PlutusData.ListData list -> {
                var items = list.items();
                var more = new ArrayList<>(items);
                more.add(new PlutusData.ConstrData(7, List.of()));
                out.add(new PlutusData.ListData(more));
                for (int i = 0; i < items.size(); i++) {
                    for (var changed : mutations(items.get(i))) {
                        var copy = new ArrayList<>(items);
                        copy.set(i, changed);
                        out.add(new PlutusData.ListData(copy));
                    }
                }
            }
            case PlutusData.MapData map -> {
                var entries = map.entries();
                var more = new ArrayList<>(entries);
                more.add(new PlutusData.Pair(new PlutusData.ConstrData(7, List.of()), ONE));
                out.add(new PlutusData.MapData(more));
                for (int i = 0; i < entries.size(); i++) {
                    var entry = entries.get(i);
                    for (var changed : mutations(entry.key())) {
                        var copy = new ArrayList<>(entries);
                        copy.set(i, new PlutusData.Pair(changed, entry.value()));
                        out.add(new PlutusData.MapData(copy));
                    }
                    for (var changed : mutations(entry.value())) {
                        var copy = new ArrayList<>(entries);
                        copy.set(i, new PlutusData.Pair(entry.key(), changed));
                        out.add(new PlutusData.MapData(copy));
                    }
                }
            }
            default -> { }
        }
        return out;
    }

    @Test
    void everyChangeNearWellFormedDataTakesTheRule() throws Exception {
        var counts = new TreeMap<String, Integer>();
        int inputs = 0;
        for (String name : Adr068LedgerFromDataPropertyTest.NAMES) {
            if (!Adr068LedgerFromDataPropertyTest.decodable(name)) continue;
            var type = LEDGER.types().get("org.julclang.ledger." + name).representation();
            var data = new LinkedHashSet<PlutusData>();
            for (int seed = 0; seed < SEEDS; seed++) {
                var good = new Adr068LedgerFromDataPropertyTest.DataGenerator(LEDGER.namedDefinitions(), new Random(1000 + seed))
                        .wellFormed(type, 3);
                data.add(good);
                data.addAll(mutations(good));
            }
            var list = new ArrayList<>(data);
            if (list.size() > PER_TYPE) {
                Collections.shuffle(list, new Random(7));
                list = new ArrayList<>(list.subList(0, PER_TYPE));
            }
            inputs += list.size();
            if (!Adr068LedgerFromDataPropertyTest.TYPES.contains(name)) {
                var stop = assertThrows(CompilerException.class, () -> new JulcCompiler(StdlibRegistry.defaultRegistry(),
                        new CompilerOptions().setFrontend(CompilerFrontend.JAVAC))
                        .compileMethod(Adr068LedgerFromDataPropertyTest.accepts(name), "m"));
                assertEquals("fromPlutusData on a ledger type that holds a map", assertInstanceOf(NotPorted.class, stop).construct());
                counts.merge("stops", list.size(), Integer::sum);
                continue;
            }
            for (var input : list) Adr068LedgerFromDataPropertyTest.verify(name, input);
            counts.merge("verified", list.size(), Integer::sum);
        }
        System.out.println("ledger fromPlutusData mutations: " + inputs + " inputs " + counts);
        assertTrue(inputs > 5000, () -> "too few inputs: " + counts);
    }
}
