package org.julclang.vm.java.cost;

import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.julclang.core.DefaultUni;
import org.julclang.core.PlutusData;
import org.julclang.vm.BuiltinSemanticsVariant;
import org.julclang.vm.ExBudget;
import org.julclang.vm.LedgerEvaluationTarget;
import org.julclang.vm.PlutusLanguage;
import org.julclang.vm.ProtocolFeatureProfile;
import org.julclang.vm.ProtocolFeatureRegistry;
import org.julclang.vm.ProtocolVersion;
import org.julclang.vm.java.CekValue;
import org.julclang.vm.java.builtins.BuiltinTable;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The evaluator sizes a builtin's arguments only when one of its cost
 * functions reads them ({@link CostFunction#sizedArguments()}). These tests
 * check that this never changes a charge: every cost function shape reads
 * nothing outside its declared arguments, and every builtin of every shipped
 * cost model charges exactly what eager sizing of all arguments
 * ({@link EagerArgSizesOracle}) charges, for random arguments of every kind.
 */
class LazyArgumentSizingTest {

    private static final long SEED = 0x5EED_319_320L;

    // === Every cost function shape reads only the sizes it declares ===

    @Test
    void everyCostFunctionShapeIgnoresTheSizesItDoesNotDeclare() throws Exception {
        var random = new Random(SEED);
        var shapes = CostFunction.class.getPermittedSubclasses();
        assertTrue(shapes.length >= 24, "expected every cost function shape, got " + shapes.length);
        for (var shape : shapes) {
            for (int trial = 0; trial < 2_000; trial++) {
                CostFunction function = randomInstance(shape, random);
                int declared = function.sizedArguments();
                long[] sizes = randomSizes(random);
                long[] perturbed = sizes.clone();
                for (int i = 0; i < perturbed.length; i++) {
                    if ((declared & (1 << i)) == 0) perturbed[i] = randomSize(random);
                }
                assertEquals(function.apply(sizes), function.apply(perturbed),
                        () -> function + " read an undeclared size: " + Arrays.toString(sizes)
                                + " vs " + Arrays.toString(perturbed));
            }
        }
    }

    @Test
    void constantCostReadsNoSize() {
        assertEquals(0, new CostFunction.ConstantCost(42).sizedArguments());
    }

    /** A random instance of a cost function shape with random parameters. */
    private static CostFunction randomInstance(Class<?> shape, Random random) throws Exception {
        RecordComponent[] components = shape.getRecordComponents();
        Class<?>[] types = new Class<?>[components.length];
        Object[] values = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            if (types[i] == long.class) {
                values[i] = randomParameter(random);
            } else if (types[i] == CostFunction.class) {
                // AboveAndBelowDiagonal: a nested two-argument model
                values[i] = randomTwoArgumentModel(random);
            } else {
                throw new AssertionError("unexpected parameter type " + types[i] + " in " + shape);
            }
        }
        Constructor<?> constructor = shape.getDeclaredConstructor(types);
        return (CostFunction) constructor.newInstance(values);
    }

    private static CostFunction randomTwoArgumentModel(Random random) {
        return switch (random.nextInt(5)) {
            case 0 -> new CostFunction.ConstantCost(randomParameter(random));
            case 1 -> new CostFunction.LinearInX(randomParameter(random), randomParameter(random));
            case 2 -> new CostFunction.LinearInY(randomParameter(random), randomParameter(random));
            case 3 -> new CostFunction.MultipliedSizes(randomParameter(random), randomParameter(random));
            default -> new CostFunction.QuadraticInXAndY(
                    randomParameter(random), randomParameter(random), randomParameter(random),
                    randomParameter(random), randomParameter(random), randomParameter(random),
                    randomParameter(random));
        };
    }

    private static long randomParameter(Random random) {
        return switch (random.nextInt(4)) {
            case 0 -> random.nextInt(10);
            case 1 -> random.nextInt(1_000_000);
            case 2 -> random.nextLong(Long.MAX_VALUE);
            default -> -random.nextInt(1_000);
        };
    }

    private static long[] randomSizes(Random random) {
        long[] sizes = new long[4];
        for (int i = 0; i < sizes.length; i++) sizes[i] = randomSize(random);
        return sizes;
    }

    private static long randomSize(Random random) {
        return switch (random.nextInt(5)) {
            case 0 -> random.nextInt(3);
            case 1 -> random.nextInt(100);
            case 2 -> random.nextInt(1_000_000);
            case 3 -> random.nextLong(Long.MAX_VALUE);
            default -> Long.MAX_VALUE;
        };
    }

    // === Every builtin charges what eager sizing charges ===

    @Test
    void everyBuiltinChargesExactlyWhatEagerSizingCharges() {
        var random = new Random(SEED);
        List<CekValue> pool = valuePool(random);
        int checked = 0;
        for (var model : shippedModels()) {
            var functions = EnumSet.noneOf(DefaultFun.class);
            functions.addAll(model.costs().costedBuiltins());
            functions.retainAll(model.profile().availableBuiltins());
            for (var fun : functions) {
                int arity = BuiltinTable.getSignature(fun).arity();
                for (int trial = 0; trial < 300; trial++) {
                    var args = new ArrayList<CekValue>(arity);
                    for (int i = 0; i < arity; i++) args.add(pool.get(random.nextInt(pool.size())));
                    assertSameCharge(model, fun, args);
                    checked++;
                }
            }
        }
        assertTrue(checked > 10_000, "checked " + checked + " calls");
    }

    @Test
    void sizeOfDataMatchesTheEagerWalkOnEveryKindOfData() {
        var random = new Random(SEED + 1);
        for (int trial = 0; trial < 2_000; trial++) {
            PlutusData data = randomData(random, 1 + random.nextInt(6));
            assertEquals(EagerArgSizesOracle.sizeOfData(data), BuiltinCostModel.sizeOfData(data));
        }
    }

    @Test
    void integerSizeMatchesTheAbsoluteValueFormula() {
        var random = new Random(SEED + 2);
        var values = new ArrayList<BigInteger>();
        for (int k = 0; k <= 300; k++) {
            BigInteger power = BigInteger.TWO.pow(k);
            for (var v : List.of(power, power.subtract(BigInteger.ONE), power.add(BigInteger.ONE))) {
                values.add(v);
                values.add(v.negate());
            }
        }
        for (int i = 0; i < 5_000; i++) {
            BigInteger v = new BigInteger(random.nextInt(2_000), random);
            values.add(random.nextBoolean() ? v : v.negate());
        }
        for (var v : values) {
            assertEquals(EagerArgSizesOracle.integerSize(v), BuiltinCostModel.integerSize(v), v::toString);
        }
    }

    @Test
    void constantCostDataBuiltinsSizeNothingInAnyShippedModel() {
        var dataBuiltins = EnumSet.of(
                DefaultFun.UnConstrData, DefaultFun.UnListData, DefaultFun.UnMapData,
                DefaultFun.UnIData, DefaultFun.UnBData, DefaultFun.ChooseData,
                DefaultFun.ConstrData, DefaultFun.ListData, DefaultFun.MapData,
                DefaultFun.IData, DefaultFun.BData, DefaultFun.MkPairData,
                DefaultFun.MkCons, DefaultFun.HeadList, DefaultFun.TailList,
                DefaultFun.NullList, DefaultFun.ChooseList, DefaultFun.FstPair, DefaultFun.SndPair);
        for (var model : shippedModels()) {
            for (var fun : dataBuiltins) {
                var pair = model.costs().get(fun);
                if (pair == null) continue;
                assertEquals(0, pair.cpu().sizedArguments() | pair.mem().sizedArguments(),
                        fun + " in " + model.name());
            }
        }
    }

    @Test
    void argumentsOutsideTheMaskAreNotSized() {
        var args = List.<CekValue>of(
                new CekValue.VCon(Constant.byteString(new byte[100])),
                new CekValue.VCon(Constant.byteString(new byte[9])));
        assertArrayEquals(new long[]{0, 0}, BuiltinCostModel.argSizes(
                BuiltinSemanticsVariant.C, DefaultFun.AppendByteString, args, 0));
        assertArrayEquals(new long[]{0, 2}, BuiltinCostModel.argSizes(
                BuiltinSemanticsVariant.C, DefaultFun.AppendByteString, args, 0b10));
        assertArrayEquals(new long[]{13, 2}, BuiltinCostModel.argSizes(
                BuiltinSemanticsVariant.C, DefaultFun.AppendByteString, args));
    }

    /**
     * A Data value nested far deeper than the stack allows walking: charging
     * a constant-cost builtin on it must not walk it, while eager sizing
     * overflows the stack.
     */
    @Test
    void constantCostBuiltinDoesNotWalkItsDataArgument() throws Exception {
        PlutusData deep = PlutusData.UNIT;
        for (int i = 0; i < 200_000; i++) {
            deep = new PlutusData.ConstrData(0, List.of(deep));
        }
        var args = List.<CekValue>of(new CekValue.VCon(Constant.data(deep)));
        var model = shippedModels().getLast();
        var failure = new AtomicReference<Throwable>();
        var charged = new AtomicReference<ExBudget>();
        var eagerOverflowed = new AtomicReference<Boolean>(false);
        Runnable check = () -> {
            try {
                var tracker = new CostTracker(DefaultCostModel.defaultMachineCosts(model.profile()),
                        model.costs(), model.profile(), null);
                tracker.chargeBuiltin(DefaultFun.UnConstrData, args);
                charged.set(tracker.consumed());
                try {
                    EagerArgSizesOracle.argSizes(model.profile().semanticsVariant(),
                            DefaultFun.UnConstrData, args);
                } catch (StackOverflowError expected) {
                    eagerOverflowed.set(true);
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        };
        // A small, fixed stack so that the eager walk overflows on every platform
        var thread = new Thread(null, check, "lazy-sizing-deep-data", 512 * 1024);
        thread.start();
        thread.join();
        assertNull(failure.get(), () -> "charging failed: " + failure.get());
        var pair = model.costs().get(DefaultFun.UnConstrData);
        assertEquals(new ExBudget(pair.cpu().apply(), pair.mem().apply()), charged.get());
        assertTrue(eagerOverflowed.get(), "the eager walk was expected to overflow the stack");
    }

    private record ShippedModel(String name, ProtocolFeatureProfile profile, BuiltinCostModel costs) {
    }

    /** Every builtin cost model JuLC ships, each with a profile it is used with. */
    private static List<ShippedModel> shippedModels() {
        var models = new ArrayList<ShippedModel>();
        var targets = List.of(
                new LedgerEvaluationTarget(PlutusLanguage.PLUTUS_V1, new ProtocolVersion(8, 0)),
                new LedgerEvaluationTarget(PlutusLanguage.PLUTUS_V2, new ProtocolVersion(8, 0)),
                new LedgerEvaluationTarget(PlutusLanguage.PLUTUS_V2, new ProtocolVersion(9, 0)),
                new LedgerEvaluationTarget(PlutusLanguage.PLUTUS_V3, new ProtocolVersion(9, 0)),
                LedgerEvaluationTarget.pv10(PlutusLanguage.PLUTUS_V3),
                LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V1),
                LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V2),
                LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3));
        for (var target : targets) {
            var profile = ProtocolFeatureRegistry.resolve(target);
            models.add(new ShippedModel(target + " default", profile,
                    DefaultCostModel.defaultBuiltinCostModel(profile)));
        }
        var v1 = ProtocolFeatureRegistry.resolve(
                new LedgerEvaluationTarget(PlutusLanguage.PLUTUS_V1, new ProtocolVersion(8, 0)));
        models.add(new ShippedModel("V1 legacy", v1, DefaultCostModel.defaultBuiltinCostModelV1()));
        var v2 = ProtocolFeatureRegistry.resolve(
                new LedgerEvaluationTarget(PlutusLanguage.PLUTUS_V2, new ProtocolVersion(9, 0)));
        models.add(new ShippedModel("V2 legacy", v2, DefaultCostModel.defaultBuiltinCostModelV2()));
        for (var variant : BuiltinSemanticsVariant.values()) {
            var profile = models.stream()
                    .filter(m -> m.profile().semanticsVariant() == variant)
                    .findFirst().orElseThrow().profile();
            models.add(new ShippedModel("variant " + variant, profile,
                    DefaultCostModel.defaultBuiltinCostModel(variant)));
        }
        return models;
    }

    private static void assertSameCharge(ShippedModel model, DefaultFun fun, List<CekValue> args) {
        var pair = model.costs().get(fun);
        long[] eager = EagerArgSizesOracle.argSizes(model.profile().semanticsVariant(), fun, args);
        var expected = new ExBudget(pair.cpu().apply(eager), pair.mem().apply(eager));

        var tracker = new CostTracker(DefaultCostModel.defaultMachineCosts(model.profile()),
                model.costs(), model.profile(), null);
        tracker.chargeBuiltin(fun, args);
        assertEquals(expected, tracker.consumed(),
                () -> fun + " in " + model.name() + " with sizes " + Arrays.toString(eager));
    }

    // === Random arguments of every kind ===

    private static List<CekValue> valuePool(Random random) {
        var constants = new ArrayList<Constant>();
        // Integers: around word boundaries, negative powers of two, small literals, huge
        for (int k : new int[]{0, 1, 3, 6, 62, 63, 64, 65, 127, 128, 1000, 4096}) {
            BigInteger power = BigInteger.TWO.pow(k);
            for (var v : List.of(power, power.subtract(BigInteger.ONE))) {
                constants.add(Constant.integer(v));
                constants.add(Constant.integer(v.negate()));
            }
        }
        for (int i = 0; i < 40; i++) {
            constants.add(Constant.integer(random.nextInt(200) - 20));
        }
        for (int i = 0; i < 10; i++) {
            constants.add(Constant.integer(new BigInteger(1 + random.nextInt(5_000), random)));
        }
        // Byte strings
        for (int length : new int[]{0, 1, 7, 8, 9, 28, 32, 33, 64, 1000, 65_536}) {
            constants.add(Constant.byteString(randomBytes(random, length)));
        }
        // Text
        for (var text : List.of("", "a", "hello", "é", "😀", "é😀ab", "x".repeat(1000))) {
            constants.add(Constant.string(text));
        }
        constants.add(Constant.bool(true));
        constants.add(Constant.bool(false));
        constants.add(Constant.unit());
        // Data: random, deep, wide, with large byte strings and big integers
        for (int i = 0; i < 40; i++) {
            constants.add(Constant.data(randomData(random, 1 + random.nextInt(6))));
        }
        PlutusData chain = new PlutusData.IntData(BigInteger.ONE);
        for (int i = 0; i < 2_000; i++) chain = new PlutusData.ConstrData(i % 3, List.of(chain));
        constants.add(Constant.data(chain));
        var wide = new ArrayList<PlutusData>();
        for (int i = 0; i < 5_000; i++) wide.add(new PlutusData.IntData(BigInteger.valueOf(i - 2_500)));
        constants.add(Constant.data(new PlutusData.ListData(wide)));
        constants.add(Constant.data(new PlutusData.BytesData(randomBytes(random, 100_000))));
        constants.add(Constant.data(new PlutusData.IntData(BigInteger.TWO.pow(640).negate())));
        // Lists, arrays, pairs
        for (int n : new int[]{0, 1, 5, 100}) {
            var ints = new ArrayList<Constant>();
            var datas = new ArrayList<Constant>();
            for (int i = 0; i < n; i++) {
                ints.add(Constant.integer(random.nextInt()));
                datas.add(Constant.data(randomData(random, 2)));
            }
            constants.add(new Constant.ListConst(DefaultUni.INTEGER, ints));
            constants.add(new Constant.ListConst(DefaultUni.DATA, datas));
            constants.add(new Constant.ArrayConst(DefaultUni.INTEGER, ints));
        }
        constants.add(new Constant.PairConst(Constant.integer(1), Constant.data(randomData(random, 3))));
        // Values
        for (int policies : new int[]{0, 1, 3, 17}) {
            var entries = new ArrayList<Constant.ValueConst.ValueEntry>();
            for (int p = 0; p < policies; p++) {
                var tokens = new ArrayList<Constant.ValueConst.TokenEntry>();
                int count = 1 + random.nextInt(9);
                for (int t = 0; t < count; t++) {
                    tokens.add(new Constant.ValueConst.TokenEntry(
                            randomBytes(random, random.nextInt(33)), BigInteger.valueOf(random.nextLong())));
                }
                entries.add(new Constant.ValueConst.ValueEntry(randomBytes(random, 28), tokens));
            }
            constants.add(new Constant.ValueConst(entries));
        }
        // BLS elements are sized by type only
        constants.add(new Constant.Bls12_381_G1Element(randomBytes(random, 48)));
        constants.add(new Constant.Bls12_381_G2Element(randomBytes(random, 96)));
        constants.add(new Constant.Bls12_381_MlResult(randomBytes(random, 16)));

        var pool = new ArrayList<CekValue>();
        for (var c : constants) pool.add(new CekValue.VCon(c));
        // A non-constant value (an argument of a polymorphic builtin)
        pool.add(new CekValue.VDelay(null, null));
        return pool;
    }

    private static PlutusData randomData(Random random, int depth) {
        int kind = depth <= 1 ? random.nextInt(2) : random.nextInt(5);
        return switch (kind) {
            case 0 -> {
                BigInteger v = random.nextInt(4) == 0
                        ? new BigInteger(1 + random.nextInt(700), random)
                        : BigInteger.valueOf(random.nextInt(1_000));
                if (random.nextInt(3) == 0) v = v.negate();
                if (random.nextInt(10) == 0) v = BigInteger.TWO.pow(64 * (1 + random.nextInt(4))).negate();
                yield new PlutusData.IntData(v);
            }
            case 1 -> new PlutusData.BytesData(randomBytes(random,
                    random.nextInt(10) == 0 ? random.nextInt(5_000) : random.nextInt(40)));
            case 2 -> new PlutusData.ConstrData(random.nextInt(10), children(random, depth));
            case 3 -> new PlutusData.ListData(children(random, depth));
            default -> {
                var entries = new ArrayList<PlutusData.Pair>();
                int n = random.nextInt(8);
                for (int i = 0; i < n; i++) {
                    entries.add(new PlutusData.Pair(
                            randomData(random, depth - 1), randomData(random, depth - 1)));
                }
                yield new PlutusData.MapData(entries);
            }
        };
    }

    private static List<PlutusData> children(Random random, int depth) {
        int n = random.nextInt(10) == 0 ? random.nextInt(60) : random.nextInt(6);
        var items = new ArrayList<PlutusData>(n);
        for (int i = 0; i < n; i++) items.add(randomData(random, depth - 1));
        return items;
    }

    private static byte[] randomBytes(Random random, int length) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }
}
