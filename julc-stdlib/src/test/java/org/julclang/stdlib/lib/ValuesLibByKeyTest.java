package org.julclang.stdlib.lib;

import org.julclang.compiler.CompileResult;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.core.Constant;
import org.julclang.core.PlutusData;
import org.julclang.core.Term;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalOptions;
import org.julclang.vm.JulcVm;
import org.julclang.vm.TermExtractor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code lovelaceOf}, {@code add}, {@code subtract} and {@code negate} go by key: a computed Value is read on-chain with
 * {@code lovelaceOf} and {@code assetOf}, and compared whole, at every level. On-chain is the point: a Value carried back
 * to the JVM is read through a map, by key, which shows neither the order of its entries nor a repeated key.
 */
class ValuesLibByKeyTest {

    private static final String SOURCE = """
            import java.math.BigInteger;
            import org.julclang.ledger.PolicyId;
            import org.julclang.ledger.TokenName;
            import org.julclang.ledger.Value;
            import org.julclang.stdlib.lib.ValuesLib;

            class Probe {
                static BigInteger lovelaceMethod(Value v) { return v.lovelaceOf(); }
                static BigInteger lovelaceLibrary(Value v) { return ValuesLib.lovelaceOf(v); }
                static boolean geqByLovelace(Value a, Value b) { return ValuesLib.geq(a, b); }
                static BigInteger lovelaceOfSum(Value a, Value b) { return ValuesLib.add(a, b).lovelaceOf(); }
                static BigInteger lovelaceOfDifference(Value a, Value b) { return ValuesLib.lovelaceOf(ValuesLib.subtract(a, b)); }
                static BigInteger lovelaceOfNegated(Value a) { return ValuesLib.negate(a).lovelaceOf(); }
                static BigInteger assetOfSum(Value a, Value b, byte[] p, byte[] t) { return ValuesLib.assetOf(ValuesLib.add(a, b), p, t); }
                static BigInteger assetOfDifference(Value a, Value b, byte[] p, byte[] t) { return ValuesLib.subtract(a, b).assetOf(new PolicyId(p), new TokenName(t)); }
                static BigInteger assetOfNegated(Value a, byte[] p, byte[] t) { return ValuesLib.assetOf(ValuesLib.negate(a), p, t); }
                static Value sum(Value a, Value b) { return ValuesLib.add(a, b); }
                static Value difference(Value a, Value b) { return ValuesLib.subtract(a, b); }
                static Value negated(Value a) { return ValuesLib.negate(a); }
            }
            """;

    private static final PlutusData ADA = bytes("");
    private static final PlutusData P = bytes("aa");
    private static final PlutusData Q = bytes("bb");
    private static final PlutusData R = bytes("cc");
    private static final PlutusData T1 = bytes("01");
    private static final PlutusData T2 = bytes("02");
    private static final PlutusData T3 = bytes("03");

    private static final Map<String, CompileResult> COMPILED = new ConcurrentHashMap<>();

    private static PlutusData bytes(String hex) {
        return new PlutusData.BytesData(HexFormat.of().parseHex(hex));
    }

    private static PlutusData.Pair token(PlutusData name, long amount) {
        return new PlutusData.Pair(name, PlutusData.integer(amount));
    }

    private static PlutusData.Pair policy(PlutusData id, PlutusData.Pair... tokens) {
        return new PlutusData.Pair(id, new PlutusData.MapData(List.of(tokens)));
    }

    private static PlutusData.Pair lovelace(long amount) {
        return policy(ADA, token(ADA, amount));
    }

    private static PlutusData value(PlutusData.Pair... policies) {
        return new PlutusData.MapData(List.of(policies));
    }

    private static PlutusData run(OptimizationLevel level, String method, PlutusData... args) {
        var compiled = COMPILED.computeIfAbsent(level + "." + method, key -> new JulcCompiler(StdlibRegistry.defaultRegistry(),
                new CompilerOptions().setOptimizationLevel(level)).compileMethod(SOURCE, method));
        var result = JulcVm.create().evaluateWithArgs(compiled.program(), compiled.target().ledgerTarget(), List.of(args), null,
                EvalOptions.DEFAULT);
        var term = TermExtractor.extractResultTerm(result);
        return term instanceof Term.Const c && c.value() instanceof Constant.IntegerConst i
                ? PlutusData.integer(i.value()) : TermExtractor.extractData(term);
    }

    private static void assertAmount(long expected, OptimizationLevel level, String method, PlutusData... args) {
        assertEquals(PlutusData.integer(expected), run(level, method, args), () -> level + " " + method);
    }

    private static boolean bool(OptimizationLevel level, String method, PlutusData... args) {
        var compiled = COMPILED.computeIfAbsent(level + "." + method, key -> new JulcCompiler(StdlibRegistry.defaultRegistry(),
                new CompilerOptions().setOptimizationLevel(level)).compileMethod(SOURCE, method));
        return TermExtractor.extractBoolean(TermExtractor.extractResultTerm(JulcVm.create().evaluateWithArgs(compiled.program(),
                compiled.target().ledgerTarget(), List.of(args), null, EvalOptions.DEFAULT)));
    }

    // --- lovelaceOf ---

    private static void assertLovelace(long expected, OptimizationLevel level, PlutusData value) {
        assertAmount(expected, level, "lovelaceMethod", value);
        assertAmount(expected, level, "lovelaceLibrary", value);
    }

    @ParameterizedTest
    @EnumSource(OptimizationLevel.class)
    void lovelaceOfLooksUpTheEmptyKeys(OptimizationLevel level) {
        assertLovelace(5, level, value(lovelace(5), policy(P, token(T1, 7))));
        assertLovelace(5, level, value(policy(P, token(T1, 7)), lovelace(5)));
        assertLovelace(5, level, value(policy(P, token(T1, 7)), policy(Q, token(T1, 9)), lovelace(5)));
    }

    @ParameterizedTest
    @EnumSource(OptimizationLevel.class)
    void lovelaceOfAValueWithoutTheKeyIsZero(OptimizationLevel level) {
        assertLovelace(0, level, value());
        assertLovelace(0, level, value(policy(P, token(T1, 7))));
        assertLovelace(0, level, value(policy(ADA)));
        assertLovelace(0, level, value(policy(ADA, token(T1, 7))));
    }

    @ParameterizedTest
    @EnumSource(OptimizationLevel.class)
    void geqGoesThroughLovelaceOf(OptimizationLevel level) {
        var withToken = value(policy(P, token(T1, 5000)));
        var adaLater = value(policy(P, token(T1, 1)), lovelace(100));
        assertTrue(bool(level, "geqByLovelace", adaLater, value(lovelace(100))));
        assertTrue(bool(level, "geqByLovelace", value(lovelace(100)), withToken));
        assertEquals(false, bool(level, "geqByLovelace", withToken, value(lovelace(1))));
    }

    // --- Reading a computed Value ---

    @ParameterizedTest
    @EnumSource(OptimizationLevel.class)
    void aSumHoldsEveryAmountByKey(OptimizationLevel level) {
        var a = value(lovelace(10), policy(P, token(T1, 5)));
        var b = value(lovelace(1), policy(P, token(T2, 7)));
        assertAmount(11, level, "lovelaceOfSum", a, b);
        assertAmount(5, level, "assetOfSum", a, b, P, T1);
        assertAmount(7, level, "assetOfSum", a, b, P, T2);
        assertAmount(0, level, "assetOfSum", a, b, Q, T1);
    }

    @ParameterizedTest
    @EnumSource(OptimizationLevel.class)
    void aDifferenceHoldsEveryAmountByKey(OptimizationLevel level) {
        var a = value(lovelace(200), policy(P, token(T1, 5)));
        var b = value(lovelace(50), policy(P, token(T1, 2)), policy(Q, token(T2, 9)));
        assertAmount(150, level, "lovelaceOfDifference", a, b);
        assertAmount(3, level, "assetOfDifference", a, b, P, T1);
        assertAmount(-9, level, "assetOfDifference", a, b, Q, T2);
    }

    @ParameterizedTest
    @EnumSource(OptimizationLevel.class)
    void aValueWithoutTheAdaKeyStaysWithoutIt(OptimizationLevel level) {
        var tokens = value(policy(P, token(T1, 5000)));
        assertAmount(0, level, "lovelaceOfSum", tokens, tokens);
        assertAmount(0, level, "lovelaceOfDifference", value(), tokens);
        assertAmount(0, level, "lovelaceOfNegated", tokens);
        assertAmount(-5000, level, "assetOfNegated", tokens, P, T1);
    }

    @ParameterizedTest
    @EnumSource(OptimizationLevel.class)
    void aKeyAfterAdaIsFoundInASum(OptimizationLevel level) {
        var a = value(policy(P, token(T1, 1)), lovelace(100));
        var b = value(policy(Q, token(T2, 1)));
        assertAmount(100, level, "lovelaceOfSum", a, b);
        assertAmount(100, level, "lovelaceOfSum", b, a);
        assertAmount(-100, level, "lovelaceOfNegated", a);
    }

    @ParameterizedTest
    @EnumSource(OptimizationLevel.class)
    void aTokenHeldAtZeroIsAKeyLikeAnother(OptimizationLevel level) {
        var a = value(policy(P, token(T1, 0)));
        var b = value(policy(P, token(T1, 7)));
        assertAmount(7, level, "assetOfSum", a, b, P, T1);
        assertAmount(7, level, "assetOfSum", b, a, P, T1);
        assertEquals(value(policy(P, token(T1, 7))), run(level, "sum", a, b));
        assertEquals(value(policy(P, token(T1, 7))), run(level, "sum", b, a));
    }

    // --- The whole result ---

    @ParameterizedTest
    @EnumSource(OptimizationLevel.class)
    void onePolicyInBothIsOneEntryWithItsTokensMerged(OptimizationLevel level) {
        var a = value(lovelace(10), policy(P, token(T1, 5), token(T3, 1)));
        var b = value(lovelace(1), policy(P, token(T2, 7)));
        assertEquals(value(lovelace(11), policy(P, token(T1, 5), token(T2, 7), token(T3, 1))), run(level, "sum", a, b));
        assertEquals(value(lovelace(11), policy(P, token(T1, 5), token(T2, 7), token(T3, 1))), run(level, "sum", b, a));
    }

    @ParameterizedTest
    @EnumSource(OptimizationLevel.class)
    void sortedInputsGiveASortedResult(OptimizationLevel level) {
        var a = value(lovelace(1), policy(Q, token(T2, 1)));
        var b = value(policy(P, token(T1, 1)), policy(R, token(T3, 1)));
        assertEquals(value(lovelace(1), policy(P, token(T1, 1)), policy(Q, token(T2, 1)), policy(R, token(T3, 1))),
                run(level, "sum", a, b));
        assertEquals(value(lovelace(1), policy(P, token(T1, 1)), policy(Q, token(T2, 1)), policy(R, token(T3, 1))),
                run(level, "sum", b, a));
        assertEquals(value(lovelace(-1), policy(P, token(T1, -2), token(T2, -3))),
                run(level, "negated", value(lovelace(1), policy(P, token(T1, 2), token(T2, 3)))));
    }

    @ParameterizedTest
    @EnumSource(OptimizationLevel.class)
    void aZeroAmountStaysAnEntry(OptimizationLevel level) {
        var v = value(lovelace(5), policy(P, token(T1, 3)));
        assertEquals(value(lovelace(0), policy(P, token(T1, 0))), run(level, "difference", v, v));
        assertEquals(value(policy(P, token(T1, 0))), run(level, "sum", value(policy(P, token(T1, 0))), value()));
    }

    @ParameterizedTest
    @EnumSource(OptimizationLevel.class)
    void emptyValues(OptimizationLevel level) {
        var v = value(lovelace(5), policy(P, token(T1, 3)));
        assertEquals(value(), run(level, "sum", value(), value()));
        assertEquals(v, run(level, "sum", v, value()));
        assertEquals(v, run(level, "sum", value(), v));
        assertEquals(value(), run(level, "negated", value()));
        assertEquals(v, run(level, "difference", v, value()));
    }

    // --- Against a map model ---

    /** A Value as a map: what a result must hold, whatever the order. */
    private static Map<String, BigInteger> model(PlutusData value) {
        var flat = new TreeMap<String, BigInteger>();
        for (var outer : ((PlutusData.MapData) value).entries()) {
            for (var inner : ((PlutusData.MapData) outer.value()).entries()) {
                String key = hex(outer.key()) + "/" + hex(inner.key());
                var amount = ((PlutusData.IntData) inner.value()).value();
                var previous = flat.put(key, amount);
                assertEquals(null, previous, () -> "a key held twice: " + key + " in " + value);
            }
        }
        return flat;
    }

    private static String hex(PlutusData key) {
        return HexFormat.of().formatHex(((PlutusData.BytesData) key).value());
    }

    private static Map<String, BigInteger> plus(Map<String, BigInteger> a, Map<String, BigInteger> b) {
        var sum = new TreeMap<>(a);
        b.forEach((key, amount) -> sum.merge(key, amount, BigInteger::add));
        return sum;
    }

    private static Map<String, BigInteger> negated(Map<String, BigInteger> a) {
        var result = new TreeMap<String, BigInteger>();
        a.forEach((key, amount) -> result.put(key, amount.negate()));
        return result;
    }

    private static boolean isSorted(PlutusData value) {
        var outer = ((PlutusData.MapData) value).entries();
        for (int i = 0; i < outer.size(); i++) {
            if (i > 0 && hex(outer.get(i - 1).key()).compareTo(hex(outer.get(i).key())) >= 0) return false;
            var inner = ((PlutusData.MapData) outer.get(i).value()).entries();
            for (int j = 1; j < inner.size(); j++) {
                if (hex(inner.get(j - 1).key()).compareTo(hex(inner.get(j).key())) >= 0) return false;
            }
        }
        return true;
    }

    private static final List<PlutusData> POLICIES = List.of(ADA, P, Q, R);
    private static final List<PlutusData> TOKENS = List.of(T1, T2, T3);

    /** A Value of the small key space, so that keys meet, with zero amounts among them; sorted, or shuffled at both levels. */
    private static PlutusData randomValue(Random random, boolean sorted) {
        var outer = new ArrayList<PlutusData.Pair>();
        for (var id : POLICIES) {
            if (random.nextInt(3) == 0) continue;
            var inner = new ArrayList<PlutusData.Pair>();
            if (id == ADA) {
                inner.add(token(ADA, random.nextInt(11) - 5));
            } else {
                for (var name : TOKENS) {
                    if (random.nextInt(2) == 0) inner.add(token(name, random.nextInt(11) - 5));
                }
                if (inner.isEmpty()) inner.add(token(TOKENS.get(random.nextInt(TOKENS.size())), 0));
            }
            if (!sorted) Collections.shuffle(inner, random);
            outer.add(new PlutusData.Pair(id, new PlutusData.MapData(inner)));
        }
        if (!sorted) Collections.shuffle(outer, random);
        return new PlutusData.MapData(outer);
    }

    private void compareWithTheModel(long seed, boolean sorted) {
        var random = new Random(seed);
        for (int i = 0; i < 300; i++) {
            var a = randomValue(random, sorted);
            var b = randomValue(random, sorted);
            var level = OptimizationLevel.values()[i % OptimizationLevel.values().length];
            String input = level + " " + a + " " + b;
            var sum = run(level, "sum", a, b);
            var difference = run(level, "difference", a, b);
            var negative = run(level, "negated", a);
            assertEquals(plus(model(a), model(b)), model(sum), () -> "add " + input);
            assertEquals(plus(model(a), negated(model(b))), model(difference), () -> "subtract " + input);
            assertEquals(negated(model(a)), model(negative), () -> "negate " + input);
            if (sorted) {
                assertTrue(isSorted(sum), () -> "add " + input + " -> " + sum);
                assertTrue(isSorted(difference), () -> "subtract " + input + " -> " + difference);
                assertTrue(isSorted(negative), () -> "negate " + input + " -> " + negative);
            }
        }
    }

    @Test
    void sortedValuesAgreeWithAMapModel() {
        compareWithTheModel(20261101L, true);
    }

    @Test
    void unsortedValuesAgreeWithAMapModel() {
        compareWithTheModel(20261102L, false);
    }
}
