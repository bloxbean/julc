package org.julclang.compiler;

import org.julclang.core.PlutusData;

import java.util.List;

/**
 * ADR-043 (O9) fixtures: {@code compileMethod} sources whose {@code JulcList.get} sites the
 * costed list-to-array promotion must rewrite, must leave alone, or must rewrite only in part.
 * Each fixture is its own class so rule provenance is attributable to one method. Every
 * fixture is evaluated on Java, Truffle and Scalus by {@link O9ListIndexPromotionTest};
 * {@code promotes} is the expected rule provenance at {@code PV11_COSTED}, {@code arrays} the
 * number of {@code ListToArray} bindings expected, and {@code sitesLeft} the number of
 * recursive {@code get} sites that must survive (unpromoted scopes).
 */
final class O9ListIndexFixtures {

    /** What the costed program's budget must do on this input relative to the golden program. */
    enum Path {
        /** Two or more promoted sites (or loop iterations) run: CPU must be strictly lower. */
        SAVES,
        /** An array is built but at most one promoted site runs: CPU may rise by at most the
         *  array conversions plus one binding each (the bound is measured from the profile). */
        PAYS,
        /** The array binding is not on this path: the budget must be identical. */
        UNTOUCHED,
        /** The input fails; budgets are not compared. */
        FAILS,
        /**
         * Documented divergence (ADR-043 "Typing trust"): the list variable holds a non-list, so
         * the costed program fails at {@code ListToArray} where the golden program succeeded or
         * failed later at a site.
         */
        DIVERGES_AT_CONVERSION
    }

    /**
     * One evaluation. {@code index}/{@code size} are non-null exactly when the input fails at a
     * promoted site: the array failure text must then name that index and array size.
     * {@code arrayLengths} are the lengths of the lists converted on a {@link Path#PAYS} path.
     */
    record Input(String name, List<PlutusData> args, boolean success, Long index, Integer size,
                 Path path, List<Integer> arrayLengths) {
        static Input saves(String name, PlutusData... args) {
            return new Input(name, List.of(args), true, null, null, Path.SAVES, List.of());
        }
        static Input pays(String name, List<Integer> arrayLengths, PlutusData... args) {
            return new Input(name, List.of(args), true, null, null, Path.PAYS, arrayLengths);
        }
        static Input untouched(String name, PlutusData... args) {
            return new Input(name, List.of(args), true, null, null, Path.UNTOUCHED, List.of());
        }
        static Input outOfRange(String name, long index, int size, PlutusData... args) {
            return new Input(name, List.of(args), false, index, size, Path.FAILS, List.of());
        }
        /** Fails, but at an unpromoted site: the failure text must be identical before and after. */
        static Input failsElsewhere(String name, PlutusData... args) {
            return new Input(name, List.of(args), false, null, null, Path.FAILS, List.of());
        }
        /** The costed program fails at the conversion; {@code success} is the golden program's outcome. */
        static Input divergesAtConversion(String name, boolean goldenSucceeds, PlutusData... args) {
            return new Input(name, List.of(args), goldenSucceeds, null, null, Path.DIVERGES_AT_CONVERSION, List.of());
        }
        @Override
        public String toString() { return name; }
    }

    record Fixture(String name, String source, String method, boolean promotes, int arrays, int sitesLeft,
                   List<Input> inputs) {}

    static final String IMPORTS = """
            import org.julclang.core.PlutusData;
            import org.julclang.core.types.JulcArray;
            import org.julclang.core.types.JulcList;
            import org.julclang.stdlib.Builtins;
            import org.julclang.stdlib.lib.ContextsLib;
            import java.math.BigInteger;
            """;

    /** 0: the motivating shape; two sites on one parameter. */
    static final String TWO = IMPORTS + """
            class Two {
                static BigInteger two(JulcList<BigInteger> xs, BigInteger i, BigInteger j) {
                    return xs.get(i).add(xs.get(j));
                }
            }
            """;

    /** 1: the documented manual form; the costed bytes of 0 must equal these. */
    static final String MANUAL = IMPORTS + """
            class Manual {
                static BigInteger manual(JulcList<BigInteger> xs, BigInteger i, BigInteger j) {
                    JulcArray<BigInteger> a = xs.toArray();
                    return a.get(i).add(a.get(j));
                }
            }
            """;

    /** 2: the WingRiders pool shape: one site per list inside a while loop driven by a redeemer index list. */
    static final String LOOP = IMPORTS + """
            class Loop {
                static BigInteger loop(JulcList<BigInteger> xs, JulcList<BigInteger> idxs) {
                    BigInteger total = BigInteger.ZERO;
                    int i = 0;
                    int n = idxs.size();
                    while (i < n) {
                        total = total.add(xs.get(idxs.get(i)));
                        i = i + 1;
                    }
                    return total;
                }
            }
            """;

    /** 3: one site inside a for-each over another list. */
    static final String EACH = IMPORTS + """
            class Each {
                static BigInteger each(JulcList<BigInteger> xs, JulcList<BigInteger> ys) {
                    BigInteger total = BigInteger.ZERO;
                    for (BigInteger y : ys) {
                        total = total.add(xs.get(0)).add(y);
                    }
                    return total;
                }
            }
            """;

    /** 4: a record field bound to a local (two sites, promoted) and read through the accessor (left alone). */
    static final String FIELD = IMPORTS + """
            class Field {
                record Holder(JulcList<BigInteger> items, BigInteger n) {}
                static BigInteger field(PlutusData d) {
                    Holder h = (Holder) d;
                    JulcList<BigInteger> items = h.items();
                    return items.get(0).add(items.get(1)).add(h.items().get(2));
                }
            }
            """;

    /** 5: both sites in one branch; the other branch traces and never indexes, so it must not pay. */
    static final String BRANCH = IMPORTS + """
            class Branch {
                static BigInteger branch(JulcList<BigInteger> xs, boolean flag) {
                    if (flag) {
                        return xs.get(0).add(xs.get(1));
                    }
                    ContextsLib.trace("no index");
                    return BigInteger.valueOf(xs.size());
                }
            }
            """;

    /** 6: a trace between the two sites; the array is built before the first site, the trace order is kept. */
    static final String TRACE_BETWEEN = IMPORTS + """
            class TraceBetween {
                static BigInteger traceBetween(JulcList<BigInteger> xs) {
                    BigInteger first = xs.get(0);
                    ContextsLib.trace("mid");
                    return first.add(xs.get(1));
                }
            }
            """;

    /** 7: sites behind a length guard; the short-list path is byte-for-byte the old path. */
    static final String GUARDED = IMPORTS + """
            class Guarded {
                static boolean guarded(JulcList<BigInteger> xs, BigInteger want) {
                    if (xs.size() < 2) {
                        return false;
                    }
                    return xs.get(0).add(xs.get(1)).equals(want);
                }
            }
            """;

    /** 8: the list escapes into a helper that iterates it; only the indexing changes. */
    static final String ESCAPE = IMPORTS + """
            class Escape {
                static BigInteger sum(JulcList<BigInteger> xs) {
                    BigInteger t = BigInteger.ZERO;
                    for (BigInteger x : xs) {
                        t = t.add(x);
                    }
                    return t;
                }
                static BigInteger escape(JulcList<BigInteger> xs) {
                    BigInteger s = sum(xs);
                    return s.add(xs.get(0)).add(xs.get(1));
                }
            }
            """;

    /**
     * 9: a helper whose own parameter is also named {@code xs} (a different, once-indexed
     * binding outside the promoted scope) keeps its recursive site; the caller's pair promotes.
     * Rebinding inside a promoted scope is pinned by the direct-PIR shapes.
     */
    static final String SHADOW = IMPORTS + """
            class Shadow {
                static BigInteger helper(JulcList<BigInteger> xs) {
                    return xs.get(0);
                }
                static BigInteger shadow(JulcList<BigInteger> xs, JulcList<BigInteger> other) {
                    return xs.get(0).add(helper(other)).add(xs.get(1));
                }
            }
            """;

    /** 10: the index of one site is another site of the same list. */
    static final String NESTED = IMPORTS + """
            class Nested {
                static BigInteger nested(JulcList<BigInteger> xs) {
                    return xs.get(xs.get(0));
                }
            }
            """;

    /** 11: one site per exclusive branch: counted statically, so promoted above the conditional. */
    static final String EXCLUSIVE = IMPORTS + """
            class Exclusive {
                static BigInteger exclusive(JulcList<BigInteger> xs, boolean flag) {
                    if (flag) {
                        return xs.get(0);
                    }
                    return xs.get(1);
                }
            }
            """;

    /** 12: one site inside a list-operation callback; the stdlib inlines the callback into its recursion, so it is a repeat. */
    static final String CALLBACK = IMPORTS + """
            class Callback {
                static BigInteger callback(JulcList<BigInteger> xs, JulcList<BigInteger> ys) {
                    return BigInteger.valueOf(ys.filter(y -> y.equals(xs.get(0))).size());
                }
            }
            """;

    /**
     * 14: a cast from Data to JulcList lowers to the Data-typed inner term, so the binding is
     * not a list by construction and is left alone; the non-indexing path keeps succeeding.
     */
    static final String CAST_LET = IMPORTS + """
            class CastLet {
                static BigInteger castLet(PlutusData d, BigInteger k) {
                    JulcList<BigInteger> xs = (JulcList<BigInteger>) (Object) d;
                    if (k.equals(BigInteger.ONE)) {
                        return xs.get(0);
                    }
                    if (k.equals(BigInteger.TWO)) {
                        return xs.get(1);
                    }
                    return BigInteger.ZERO;
                }
            }
            """;

    /**
     * 15: the residual exposure. The cast lie flows into a helper's list parameter, which is
     * trusted; the helper promotes and converts above its first conditional, so the path that
     * never indexes now fails at the conversion (ADR-043 "Typing trust", pinned as documented).
     */
    static final String CAST_HELPER = IMPORTS + """
            class CastHelper {
                static BigInteger chain(JulcList<BigInteger> xs, BigInteger k) {
                    if (k.equals(BigInteger.ONE)) {
                        return xs.get(0);
                    }
                    if (k.equals(BigInteger.TWO)) {
                        return xs.get(1);
                    }
                    return BigInteger.ZERO;
                }
                static BigInteger castHelper(PlutusData d, BigInteger k) {
                    return chain((JulcList<BigInteger>) (Object) d, k);
                }
            }
            """;

    /**
     * 16: a list-typed alias of the cast local. The alias's declared type says list, but its
     * value is the unproven variable, so it is unproven too and both sites stay recursive.
     */
    static final String CAST_ALIAS = IMPORTS + """
            class CastAlias {
                static BigInteger castAlias(PlutusData d, BigInteger k) {
                    JulcList<BigInteger> xs = (JulcList<BigInteger>) (Object) d;
                    JulcList<BigInteger> ys = xs;
                    if (k.equals(BigInteger.ONE)) {
                        return ys.get(0);
                    }
                    if (k.equals(BigInteger.TWO)) {
                        return ys.get(1);
                    }
                    return BigInteger.ZERO;
                }
            }
            """;

    /**
     * 17: the alias the compiler itself emits. After a loop the lowering rebinds every pre-loop
     * variable used afterwards as {@code let xs = xs} with the declared list type; that
     * self-alias of the cast local must stay unproven, so the never-indexing path keeps
     * succeeding at every level.
     */
    static final String CAST_LOOP_ALIAS = IMPORTS + """
            class CastLoopAlias {
                static BigInteger castLoopAlias(PlutusData d, JulcList<BigInteger> ys, BigInteger k) {
                    JulcList<BigInteger> xs = (JulcList<BigInteger>) (Object) d;
                    BigInteger total = BigInteger.ZERO;
                    for (BigInteger y : ys) {
                        total = total.add(y);
                    }
                    if (k.equals(BigInteger.ONE)) {
                        return total.add(xs.get(0));
                    }
                    if (k.equals(BigInteger.TWO)) {
                        return total.add(xs.get(1));
                    }
                    return total;
                }
            }
            """;

    /**
     * 19: the residual exposure through a loop. The cast local is the loop's single accumulator,
     * so after the loop it is bound to the loop call, whose list-typed return is trusted; with
     * no iterations the call returns the cast Data unchanged, and the post-loop pair promotes
     * above the conditionals (ADR-043 "Typing trust", pinned as documented). With iterations
     * the loop body's {@code prepend} fails on the Data at every level, before the conversion.
     */
    static final String CAST_LOOP_STATE = IMPORTS + """
            class CastLoopState {
                static BigInteger castLoopState(PlutusData d, JulcList<BigInteger> ys, BigInteger k) {
                    JulcList<BigInteger> xs = (JulcList<BigInteger>) (Object) d;
                    for (BigInteger y : ys) {
                        xs = xs.prepend(y);
                    }
                    if (k.equals(BigInteger.ONE)) {
                        return xs.get(0);
                    }
                    if (k.equals(BigInteger.TWO)) {
                        return xs.get(1);
                    }
                    return BigInteger.ZERO;
                }
            }
            """;

    /** 18: an alias of a proven list is proven: the pair on the alias promotes like the pair on a parameter. */
    static final String ALIAS = IMPORTS + """
            class Alias {
                static BigInteger alias(JulcList<BigInteger> xs, BigInteger i, BigInteger j) {
                    JulcList<BigInteger> ys = xs;
                    return ys.get(i).add(ys.get(j));
                }
            }
            """;

    /** 13: control; a single site is never touched. */
    static final String SINGLE = IMPORTS + """
            class Single {
                static BigInteger single(JulcList<BigInteger> xs, BigInteger i) {
                    return xs.get(i);
                }
            }
            """;

    static PlutusData ints(long... values) {
        var items = new PlutusData[values.length];
        for (int i = 0; i < values.length; i++) items[i] = PlutusData.integer(values[i]);
        return PlutusData.list(items);
    }

    /** {@code [0, 10, 20, ..., 10*(n-1)]}. */
    static PlutusData tens(int n) {
        var values = new long[n];
        for (int i = 0; i < n; i++) values[i] = 10L * i;
        return ints(values);
    }

    static PlutusData i(long value) { return PlutusData.integer(value); }

    static final PlutusData TRUE = PlutusData.constr(1);
    static final PlutusData FALSE = PlutusData.constr(0);
    static final PlutusData EIGHT = tens(8);
    static final PlutusData EMPTY = ints();

    private static final List<Input> TWO_INPUTS = List.of(
            Input.saves("0-1", EIGHT, i(0), i(1)),
            Input.saves("7-7", EIGHT, i(7), i(7)),
            Input.saves("64-0-63", tens(64), i(0), i(63)),
            Input.outOfRange("7-8", 8, 8, EIGHT, i(7), i(8)),
            Input.outOfRange("neg-first", -1, 8, EIGHT, i(-1), i(0)),
            Input.outOfRange("empty", 0, 0, EMPTY, i(0), i(0)),
            Input.outOfRange("huge", 1L << 40, 8, EIGHT, i(0), i(1L << 40)));

    /** The manual control is already an array program: every budget and text is unchanged. */
    private static final List<Input> MANUAL_INPUTS = List.of(
            Input.untouched("0-1", EIGHT, i(0), i(1)),
            Input.untouched("7-7", EIGHT, i(7), i(7)),
            Input.untouched("64-0-63", tens(64), i(0), i(63)),
            Input.failsElsewhere("7-8", EIGHT, i(7), i(8)),
            Input.failsElsewhere("neg-first", EIGHT, i(-1), i(0)),
            Input.failsElsewhere("empty", EMPTY, i(0), i(0)));

    static final List<Fixture> FIXTURES = List.of(
            new Fixture("TWO", TWO, "two", true, 1, 0, TWO_INPUTS),
            new Fixture("MANUAL", MANUAL, "manual", false, 0, 0, MANUAL_INPUTS),
            new Fixture("LOOP", LOOP, "loop", true, 2, 0, List.of(
                    Input.saves("three-requests", EIGHT, ints(1, 3, 7)),
                    Input.pays("no-requests", List.of(8, 0), EIGHT, EMPTY),
                    Input.pays("one-request-64", List.of(64, 1), tens(64), ints(1)),
                    Input.pays("one-request-0", List.of(8, 1), EIGHT, ints(0)),
                    Input.outOfRange("index-9", 9, 8, EIGHT, ints(9)),
                    Input.outOfRange("index-neg", -1, 8, EIGHT, ints(1, -1)))),
            new Fixture("EACH", EACH, "each", true, 1, 0, List.of(
                    Input.saves("three", EIGHT, ints(1, 2, 3)),
                    Input.pays("none", List.of(8), EIGHT, EMPTY),
                    Input.outOfRange("empty-list", 0, 0, EMPTY, ints(1)))),
            new Fixture("FIELD", FIELD, "field", true, 1, 1, List.of(
                    Input.saves("four", PlutusData.constr(0, tens(4), i(1))),
                    Input.failsElsewhere("two-accessor-fails", PlutusData.constr(0, tens(2), i(1))),
                    Input.outOfRange("one", 1, 1, PlutusData.constr(0, tens(1), i(1))),
                    Input.outOfRange("empty", 0, 0, PlutusData.constr(0, EMPTY, i(1))))),
            new Fixture("BRANCH", BRANCH, "branch", true, 1, 0, List.of(
                    Input.saves("then", EIGHT, TRUE),
                    Input.untouched("else", EIGHT, FALSE),
                    Input.outOfRange("then-one", 1, 1, tens(1), TRUE),
                    Input.untouched("else-empty", EMPTY, FALSE))),
            new Fixture("TRACE_BETWEEN", TRACE_BETWEEN, "traceBetween", true, 1, 0, List.of(
                    Input.saves("eight", EIGHT),
                    Input.outOfRange("one", 1, 1, tens(1)),
                    Input.outOfRange("empty", 0, 0, EMPTY))),
            new Fixture("GUARDED", GUARDED, "guarded", true, 1, 0, List.of(
                    Input.saves("eight-match", EIGHT, i(10)),
                    Input.saves("eight-mismatch", EIGHT, i(11)),
                    Input.untouched("short", tens(1), i(10)),
                    Input.untouched("empty", EMPTY, i(0)))),
            new Fixture("ESCAPE", ESCAPE, "escape", true, 1, 0, List.of(
                    Input.saves("three", ints(1, 2, 3)),
                    Input.outOfRange("one", 1, 1, ints(5)),
                    Input.outOfRange("empty", 0, 0, EMPTY))),
            new Fixture("SHADOW", SHADOW, "shadow", true, 1, 1, List.of(
                    Input.saves("both", ints(1, 2), ints(7)),
                    Input.failsElsewhere("helper-empty", ints(1, 2), EMPTY),
                    Input.outOfRange("outer-one", 1, 1, ints(1), ints(7)))),
            new Fixture("NESTED", NESTED, "nested", true, 1, 0, List.of(
                    Input.saves("three", ints(1, 5, 7)),
                    Input.saves("self", ints(0)),
                    Input.outOfRange("one", 3, 1, ints(3)),
                    Input.outOfRange("empty", 0, 0, EMPTY))),
            new Fixture("EXCLUSIVE", EXCLUSIVE, "exclusive", true, 1, 0, List.of(
                    Input.pays("then", List.of(8), EIGHT, TRUE),
                    Input.saves("else", EIGHT, FALSE),
                    Input.pays("then-64", List.of(64), tens(64), TRUE),
                    Input.outOfRange("else-one", 1, 1, tens(1), FALSE))),
            new Fixture("CALLBACK", CALLBACK, "callback", true, 1, 0, List.of(
                    Input.saves("match", ints(5, 6), ints(5, 6, 5)),
                    Input.pays("no-ys", List.of(1), ints(5), EMPTY),
                    Input.outOfRange("empty-xs", 0, 0, EMPTY, ints(5)))),
            new Fixture("SINGLE", SINGLE, "single", false, 0, 1, List.of(
                    Input.untouched("three", EIGHT, i(3)),
                    Input.failsElsewhere("eight", EIGHT, i(8)),
                    Input.failsElsewhere("neg", EIGHT, i(-1)))),
            new Fixture("CAST_LET", CAST_LET, "castLet", false, 0, 2, List.of(
                    Input.untouched("int-data-no-index", PlutusData.integer(5), i(3)),
                    Input.untouched("list-data-no-index", ints(1, 2), i(3)),
                    Input.failsElsewhere("list-data-index", ints(1, 2), i(1)))),
            new Fixture("CAST_HELPER", CAST_HELPER, "castHelper", true, 1, 0, List.of(
                    Input.divergesAtConversion("int-data-no-index", true, PlutusData.integer(5), i(3)),
                    Input.divergesAtConversion("list-data-no-index", true, ints(1, 2), i(3)),
                    Input.divergesAtConversion("list-data-index", false, ints(1, 2), i(1)))),
            new Fixture("CAST_ALIAS", CAST_ALIAS, "castAlias", false, 0, 2, List.of(
                    Input.untouched("int-data-no-index", PlutusData.integer(5), i(3)),
                    Input.untouched("list-data-no-index", ints(1, 2), i(3)),
                    Input.failsElsewhere("list-data-index", ints(1, 2), i(1)))),
            new Fixture("CAST_LOOP_ALIAS", CAST_LOOP_ALIAS, "castLoopAlias", false, 0, 2, List.of(
                    Input.untouched("int-data-no-index", PlutusData.integer(5), ints(1, 2), i(3)),
                    Input.untouched("list-data-no-index", ints(1, 2), ints(1, 2), i(3)),
                    Input.untouched("int-data-empty-loop", PlutusData.integer(5), EMPTY, i(3)),
                    Input.failsElsewhere("list-data-index", ints(1, 2), ints(1, 2), i(1)))),
            new Fixture("ALIAS", ALIAS, "alias", true, 1, 0, TWO_INPUTS),
            new Fixture("CAST_LOOP_STATE", CAST_LOOP_STATE, "castLoopState", true, 1, 0, List.of(
                    Input.divergesAtConversion("int-data-empty-loop-no-index", true, PlutusData.integer(5), EMPTY, i(3)),
                    Input.divergesAtConversion("list-data-empty-loop-index", false, ints(1, 2), EMPTY, i(1)),
                    Input.failsElsewhere("int-data-loop", PlutusData.integer(5), ints(1), i(3)))));

    private O9ListIndexFixtures() {}
}
