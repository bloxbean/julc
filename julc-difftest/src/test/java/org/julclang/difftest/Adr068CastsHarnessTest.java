package org.julclang.difftest;

import org.julclang.core.PlutusData;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A cast to a map of a variable assigned again, where the variable's own type, written or {@code var}, is a map: it holds a
 * map whatever was assigned, so it is read by its type, and both frontends answer as the JVM at all four levels.
 */
class Adr068CastsHarnessTest {

    private static final DiffRunner RUNNER = new DiffRunner(configs(), 20_000);
    private static final String MAP = "JulcMap<PlutusData, PlutusData>";

    private static List<DiffConfig> configs() {
        var configs = new ArrayList<>(DiffConfig.allLevels());
        return configs;
    }

    private static PlutusData map(long... keysAndValues) {
        var pairs = new ArrayList<PlutusData.Pair>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            pairs.add(new PlutusData.Pair(new PlutusData.IntData(BigInteger.valueOf(keysAndValues[i])),
                    new PlutusData.IntData(BigInteger.valueOf(keysAndValues[i + 1]))));
        }
        return new PlutusData.MapData(pairs);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "MAP y = x; for (var e : ds) { y = z; } MAP q = PlutusData.cast(y, JulcMap.class); return q.isEmpty();",
            "var y = x; for (var e : ds) { y = z; } MAP q = PlutusData.cast(y, JulcMap.class); return q.isEmpty();",
            "MAP y = x; for (var e : ds) { y = z; } MAP q = (MAP)(Object) y; return q.isEmpty();"})
    void aVariableOfAMapAssignedAgainIsReadByItsType(String body) {
        String source = """
                import org.julclang.core.PlutusData;
                import org.julclang.core.types.JulcList;
                import org.julclang.core.types.JulcMap;

                public class Case {
                    public static boolean m(MAP x, MAP z, JulcList<PlutusData> ds) { BODY }
                }
                """.replace("BODY", body).replace("MAP", MAP);
        int runs = 0;
        for (var x : List.of(map(1, 2), map())) {
            for (var z : List.of(map(), map(3, 4))) {
                for (var ds : List.<PlutusData>of(new PlutusData.ListData(List.of()),
                        new PlutusData.ListData(List.of(new PlutusData.IntData(BigInteger.ONE))))) {
                    var report = RUNNER.run(new DiffCase("cast-of-a-map-assigned-again", source, "Case", "m", List.of(x, z, ds)));
                    assertInstanceOf(Observation.Value.class, report.jvm(), report::describe);
                    assertTrue(report.all(Verdict.Kind.AGREE), report::describe);
                    runs += report.results().size();
                }
            }
        }
        assertEquals(32, runs);
    }
}
