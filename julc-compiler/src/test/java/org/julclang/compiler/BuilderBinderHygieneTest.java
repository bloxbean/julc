package org.julclang.compiler;

import org.julclang.core.PlutusData;
import org.julclang.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Builders that place caller terms inside their own binders must not capture a user variable
 * of the same name. Every case names a user variable exactly like a builder-internal binder and
 * checks both the accepting and the rejecting outcome on the VM.
 */
class BuilderBinderHygieneTest {
    private static final StdlibRegistry STDLIB = StdlibRegistry.defaultRegistry();

    private static final String LISTS = """
            import java.math.BigInteger;
            import org.julclang.core.types.JulcList;
            import org.julclang.core.types.JulcMap;
            import org.julclang.ledger.*;
            import org.julclang.stdlib.Builtins;
            import org.julclang.stdlib.lib.ListsLib;
            import org.julclang.stdlib.lib.MapLib;
            @SpendingValidator
            class Hygiene {
                @Entrypoint
                static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                    JulcList<PlutusData> pair = Builtins.unListData(redeemer);
                    JulcList<PlutusData> outer = Builtins.unListData(pair.head());
                    JulcList<PlutusData> inner = Builtins.unListData(pair.tail().head());
                    %s
                }
            }
            """;

    private static PlutusData context(PlutusData redeemer) {
        return PlutusData.constr(0, PlutusData.integer(0), redeemer, PlutusData.integer(0));
    }

    private static PlutusData integers(long... values) {
        var items = new PlutusData[values.length];
        for (int i = 0; i < values.length; i++) items[i] = PlutusData.integer(values[i]);
        return PlutusData.list(items);
    }

    private static PlutusData lists(long[] outer, long[] inner) {
        return PlutusData.list(integers(outer), integers(inner));
    }

    private static boolean accepts(String body, PlutusData redeemer, OptimizationLevel level) {
        var result = new JulcCompiler(STDLIB, new CompilerOptions().setOptimizationLevel(level))
                .compile(LISTS.formatted(body));
        assertFalse(result.hasErrors(), () -> "Compilation failed: " + result);
        return CompilerTestVm.pv11()
                .evaluateWithArgs(result.program(), List.of(context(redeemer)))
                .isSuccess();
    }

    private static void decides(String body, PlutusData accepted, PlutusData rejected) {
        for (var level : List.of(OptimizationLevel.BASELINE, OptimizationLevel.PV11_SAFE)) {
            assertTrue(accepts(body, accepted, level), "must accept at " + level + ": " + body);
            assertFalse(accepts(body, rejected, level), "must reject at " + level + ": " + body);
        }
    }

    private static final PlutusData SHARED = lists(new long[] {10}, new long[] {10, 20});
    private static final PlutusData DISJOINT = lists(new long[] {1, 2, 3}, new long[] {10, 20});

    @Test
    void nestedAnyAllDoNotCaptureOuterLambdaParameters() {
        for (var name : List.of("x", "acc", "lst", "go")) {
            decides("return outer.any(" + name + " -> inner.any(y -> Builtins.equalsData(y, "
                    + name + ")));", SHARED, DISJOINT);
            decides("return outer.all(" + name + " -> inner.any(y -> Builtins.equalsData(y, "
                    + name + ")));", SHARED, DISJOINT);
            decides("return outer.any(" + name + " -> !inner.all(y -> !Builtins.equalsData(y, "
                    + name + ")));", SHARED, DISJOINT);
        }
    }

    @Test
    void staticListsLibHofsDoNotCaptureOuterLambdaParameters() {
        decides("return ListsLib.any(outer, x -> ListsLib.any(inner, "
                + "y -> Builtins.equalsData(y, x)));", SHARED, DISJOINT);
        decides("return ListsLib.all(outer, acc -> ListsLib.any(inner, "
                + "y -> Builtins.equalsData(y, acc)));", SHARED, DISJOINT);
    }

    @Test
    void findDoesNotCaptureOuterLambdaParameters() {
        for (var name : List.of("h", "lst", "go"))
            decides("return outer.any(" + name + " -> inner.find(y -> Builtins.equalsData(y, "
                    + name + ")).isPresent());", SHARED, DISJOINT);
    }

    @Test
    void filterAndMapDoNotCaptureOuterLambdaParameters() {
        for (var name : List.of("x_flt", "acc_flt"))
            decides("return outer.any(" + name + " -> inner.filter(y -> Builtins.equalsData(y, "
                    + name + ")).size() > 0);", SHARED, DISJOINT);
        // Each mapped element must be the outer element, so all comparisons hold.
        for (var name : List.of("x_map", "acc_map"))
            decides("return outer.all(" + name + " -> inner.map(y -> " + name
                    + ").all(z -> Builtins.equalsData(z, " + name + "))) && outer.size() == 1;",
                    SHARED, DISJOINT);
    }

    @Test
    void zipAndSizeDoNotCaptureListVariables() {
        decides("JulcList<PlutusData> go_zip = outer; "
                        + "JulcList<PlutusData> zipped = ListsLib.zip(go_zip, inner); "
                        + "return zipped.size() == 1;",
                SHARED, DISJOINT);
        decides("JulcList<PlutusData> go__f = outer; return go__f.size() == 1;",
                SHARED, DISJOINT);
    }

    @Test
    void listMethodsDoNotCaptureReceiverOrArguments() {
        decides("JulcList<PlutusData> go_get = inner; "
                        + "return Builtins.equalsData(go_get.get(1), Builtins.iData(20)) && outer.size() == 1;",
                SHARED, DISJOINT);
        decides("JulcList<PlutusData> target_c = inner; "
                        + "return target_c.contains(outer.head());",
                SHARED, DISJOINT);
        decides("JulcList<PlutusData> go_rev = outer; "
                        + "return Builtins.equalsData(go_rev.reverse().head(), Builtins.iData(10));",
                SHARED, DISJOINT);
        decides("JulcList<PlutusData> go_crev = inner; return outer.concat(go_crev).size() == 3;",
                SHARED, DISJOINT);
        decides("long go_take = 1; return outer.take(go_take).size() == 1 && outer.size() == 1;",
                SHARED, DISJOINT);
        decides("long go_drop = 1; return inner.drop(go_drop).size() == 1 && outer.size() == 1;",
                SHARED, DISJOINT);
        decides("long list__pv11_drop = 1; "
                        + "return inner.drop(list__pv11_drop).size() == 1 && outer.size() == 1;",
                SHARED, DISJOINT);
        decides("PlutusData xs_con = outer.head(); return ListsLib.contains(inner, xs_con);",
                SHARED, DISJOINT);
    }

    @Test
    void mapMethodsDoNotCaptureKeys() {
        // The third redeemer element is the map {10: 1, 20: 2}.
        var map = PlutusData.map(
                new PlutusData.Pair(PlutusData.integer(10), PlutusData.integer(1)),
                new PlutusData.Pair(PlutusData.integer(20), PlutusData.integer(2)));
        var present = PlutusData.list(integers(10), integers(), map);
        var absent = PlutusData.list(integers(1), integers(), map);
        String read = "JulcMap<PlutusData, PlutusData> m = "
                + "Builtins.unMapData(pair.tail().tail().head()); ";
        decides(read + "PlutusData ps_ck = outer.head(); return m.containsKey(ps_ck);",
                present, absent);
        decides(read + "PlutusData ps_lk = outer.head(); return m.lookup(ps_lk).isPresent();",
                present, absent);
        decides(read + "PlutusData ps_mb = outer.head(); return MapLib.member(m, ps_mb);",
                present, absent);
        decides(read + "PlutusData ps_del = outer.head(); return m.delete(ps_del).size() == 1;",
                present, absent);
        decides(read + "PlutusData ps_get = outer.head(); "
                        + "return Builtins.equalsData(m.get(ps_get), Builtins.iData(1));",
                present, absent);
    }

    @Test
    void valueAssetOfDoesNotCaptureQueryArguments() {
        var policy = PlutusData.bytes(new byte[] {1, 2, 3});
        var token = PlutusData.bytes(new byte[] {4, 5});
        var value = PlutusData.map(new PlutusData.Pair(policy,
                PlutusData.map(new PlutusData.Pair(token, PlutusData.integer(42)))));
        String body = """
                JulcList<PlutusData> query = Builtins.unListData(pair.tail().head());
                Value v = (Value)(Object) outer.head();
                PlutusData v__ao = query.head();
                PlutusData pol__ao = query.tail().head();
                return v.assetOf(v__ao, pol__ao) == 42;
                """;
        var found = PlutusData.list(PlutusData.list(value), PlutusData.list(policy, token));
        var missing = PlutusData.list(PlutusData.list(value), PlutusData.list(token, policy));
        for (var level : List.of(OptimizationLevel.BASELINE, OptimizationLevel.PV11_SAFE)) {
            assertTrue(accepts(body, found, level), "must accept at " + level);
            assertFalse(accepts(body, missing, level), "must reject at " + level);
        }
    }

    @Test
    void statementSequencingDoesNotCaptureUserVariables() {
        decides("long _if = 7; if (outer.isEmpty()) { Builtins.error(); } "
                + "return _if == 7 && outer.size() == 1;", SHARED, DISJOINT);
        decides("long _forEach = 7; for (PlutusData item : outer) { } "
                + "return _forEach == 7 && outer.size() == 1;", SHARED, DISJOINT);
        decides("long _while = 7; while (outer.isEmpty()) { } "
                + "return _while == 7 && outer.size() == 1;", SHARED, DISJOINT);
        decides("boolean _u = outer.isEmpty(); while (_u) { } return outer.size() == 1;",
                SHARED, DISJOINT);
        decides("""
                long __t = 7;
                long count = 0;
                long total = 0;
                for (PlutusData item : inner) {
                    count = count + 1;
                    total = total + Builtins.unIData(item);
                }
                return __t == 7 && count == 2 && total == 30 && outer.size() == 1;
                """, SHARED, DISJOINT);
    }
}
