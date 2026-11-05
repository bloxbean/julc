package org.julclang.difftest;

import org.julclang.compiler.CompilerOptions;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterContainer;
import org.julclang.compiler.LedgerTypeProvider;
import org.julclang.compiler.pir.PirType;
import org.julclang.core.PlutusData;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-068, entry points' slice: a ledger type's {@code fromPlutusData} on {@code frontend=JAVAC} against the JVM, at every
 * level, over random Data of every ledger type that declares one: well-formed, and with one node broken (a wrong kind, a
 * wrong tag, a field more or less, fields on a {@code Bool} or an empty {@code Optional}). The script accepts exactly what
 * the JVM's decoder accepts, and a value read back is the JVM's. The reference's identity accepts everything, which this
 * counts as its known divergence and does not assert away.
 */
class Adr068LedgerFromDataPropertyTest {

    static final List<String> NAMES = List.of("Address", "Committee", "Constitution", "Credential", "DRep", "DatumHash",
            "Delegatee", "GovernanceAction", "GovernanceActionId", "Interval", "IntervalBound", "IntervalBoundType", "OutputDatum",
            "PolicyId", "ProposalProcedure", "ProtocolVersion", "PubKeyHash", "Rational", "ScriptContext", "ScriptHash", "ScriptInfo",
            "ScriptPurpose", "StakingCredential", "TokenName", "TxCert", "TxId", "TxInInfo", "TxInfo", "TxOut", "TxOutRef",
            "ValidatorHash", "Vote", "Voter");

    private static final LedgerTypeProvider LEDGER = new LedgerTypeProvider(new CompilerOptions());
    /** The types decoded on-chain: a type whose shape holds a map stops there ({@code JavacLedgerFromDataTest}). */
    static final List<String> TYPES = NAMES.stream().filter(Adr068LedgerFromDataPropertyTest::decodable)
            .filter(name -> !holdsMap(type(name), new HashSet<>())).toList();
    private static final DiffRunner RUNNER = new DiffRunner(configs(), 50_000);
    private static final Map<String, Integer> SEEN = new ConcurrentHashMap<>();

    private static List<DiffConfig> configs() {
        var configs = new ArrayList<>(DiffConfig.allLevels());
        return configs;
    }

    /** A type with a public static {@code fromPlutusData(PlutusData)} on the JVM and a type in the ledger's registry. */
    static boolean decodable(String name) {
        try {
            Method method = Class.forName("org.julclang.ledger." + name).getMethod("fromPlutusData", PlutusData.class);
            return Modifier.isStatic(method.getModifiers()) && LEDGER.types().containsKey("org.julclang.ledger." + name);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private static boolean holdsMap(PirType type, Set<String> seen) {
        return switch (type) {
            case PirType.MapType ignored -> true;
            case PirType.NamedTypeRef ref -> seen.add(ref.stableId()) && holdsMap(LEDGER.namedDefinitions().get(ref.stableId()), seen);
            case PirType.RecordType record -> record.fields().stream().anyMatch(field -> holdsMap(field.type(), seen));
            case PirType.SumType sum -> sum.constructors().stream()
                    .anyMatch(constructor -> constructor.fields().stream().anyMatch(field -> holdsMap(field.type(), seen)));
            case PirType.ListType list -> holdsMap(list.elemType(), seen);
            case PirType.OptionalType optional -> holdsMap(optional.elemType(), seen);
            default -> false;
        };
    }

    private static PirType type(String name) {
        return LEDGER.types().get("org.julclang.ledger." + name).representation();
    }

    static String accepts(String name) {
        return """
                import org.julclang.core.PlutusData;
                import org.julclang.ledger.%1$s;

                class Case {
                    static boolean m(PlutusData d) {
                        %1$s x = %1$s.fromPlutusData(d);
                        return true;
                    }
                }
                """.formatted(name);
    }

    private static String readsBack(String name) {
        return """
                import org.julclang.core.PlutusData;
                import org.julclang.ledger.%1$s;

                class Case {
                    static PlutusData m(PlutusData d) {
                        return %1$s.fromPlutusData(d).toPlutusData();
                    }
                }
                """.formatted(name);
    }

    @Provide
    Arbitrary<Integer> types() {
        return Arbitraries.integers().between(0, TYPES.size() - 1);
    }

    @Provide
    Arbitrary<Long> seeds() {
        return Arbitraries.longs();
    }

    @Property(tries = 400, seed = "20261103")
    void theScriptAcceptsExactlyTheTypesShape(@ForAll("types") int index, @ForAll("seeds") long seed) throws Exception {
        String name = TYPES.get(index);
        var random = new Random(seed);
        var generator = new DataGenerator(LEDGER.namedDefinitions(), random);
        boolean broken = random.nextInt(3) > 0;
        var data = broken ? generator.broken(type(name)) : generator.wellFormed(type(name), 0);
        verify(name, data);
    }

    /**
     * The rule over one input: {@code JAVAC} returns where the JVM's decoder returns a value whose Data is the input, and
     * fails everywhere else, where the JVM throws and where it decodes by ignoring part of the input. Where it returns, the
     * value read back is the JVM's. The reference's identity returns everywhere, counted.
     */
    static void verify(String name, PlutusData data) throws Exception {
        boolean exact = decodesExactly(name, data);
        var report = RUNNER.run(new DiffCase("from-data-" + name, accepts(name), "Case", "m", List.of(data)));
        boolean jvmAccepts = report.jvm() instanceof Observation.Value;
        SEEN.merge(!jvmAccepts ? "jvm throws" : exact ? "jvm decodes exactly" : "jvm ignores a part", 1, Integer::sum);
        SEEN.merge(name, 1, Integer::sum);
        report.results().forEach((config, result) -> {
                if (exact) assertEquals(Verdict.Kind.AGREE, result.verdict().kind(), () -> name + " " + config + " on " + data + "\n" + report.describe());
                else assertInstanceOf(Observation.Failure.class, result.uplc(), () -> name + " " + config + " on " + data + "\n" + report.describe());
        });
        if (!exact) return;
        var readBack = RUNNER.run(new DiffCase("read-back-" + name, readsBack(name), "Case", "m", List.of(data)));
        readBack.results().forEach((config, result) -> {
                assertEquals(Verdict.Kind.AGREE, result.verdict().kind(), () -> name + " " + config + "\n" + readBack.describe());
        });
        SEEN.merge("read back", 1, Integer::sum);
    }

    /** Whether the JVM's decoder returns a value whose Data is {@code data}: it ignored nothing. */
    static boolean decodesExactly(String name, PlutusData data) throws Exception {
        var decoder = Class.forName("org.julclang.ledger." + name).getMethod("fromPlutusData", PlutusData.class);
        Object value;
        try {
            value = decoder.invoke(null, data);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException) return false;
            throw e;
        }
        return value.getClass().getMethod("toPlutusData").invoke(value).equals(data);
    }

    @Property(tries = 200, seed = "20261104")
    void aValueReadBackIsTheJvms(@ForAll("types") int index, @ForAll("seeds") long seed) throws Exception {
        String name = TYPES.get(index);
        verify(name, new DataGenerator(LEDGER.namedDefinitions(), new Random(seed)).wellFormed(type(name), 0));
    }

    @AfterContainer
    static void everyKindWasRun() {
        System.out.println("ledger fromPlutusData " + new TreeMap<>(SEEN));
        assertTrue(SEEN.getOrDefault("jvm throws", 0) >= 50, SEEN::toString);
        assertTrue(SEEN.getOrDefault("jvm decodes exactly", 0) >= 50, SEEN::toString);
        assertTrue(SEEN.getOrDefault("jvm ignores a part", 0) >= 10, SEEN::toString);
        assertTrue(SEEN.getOrDefault("read back", 0) >= 50, SEEN::toString);
        for (String name : TYPES) assertTrue(SEEN.getOrDefault(name, 0) >= 3, () -> name + " in too few tries: " + SEEN);
    }

    /** Data of a {@link PirType}, well-formed or with one node broken. */
    static final class DataGenerator {
        private static final int DEPTH = 7;
        private final Map<String, PirType> definitions;
        private final Random random;

        DataGenerator(Map<String, PirType> definitions, Random random) {
            this.definitions = definitions;
            this.random = random;
        }

        /** A {@code Value} record is a map of maps in Data, as the ledger encodes it. */
        private static final PirType VALUE_RECORD = LEDGER.types().get("org.julclang.ledger.Value").representation();
        private static final PirType VALUE_DATA = new PirType.MapType(new PirType.ByteStringType(),
                new PirType.MapType(new PirType.ByteStringType(), new PirType.IntegerType()));

        private PirType resolved(PirType type) {
            var resolved = type instanceof PirType.NamedTypeRef ref ? definitions.get(ref.stableId()) : type;
            var value = VALUE_RECORD instanceof PirType.NamedTypeRef ref ? definitions.get(ref.stableId()) : VALUE_RECORD;
            return resolved.equals(value) ? VALUE_DATA : resolved;
        }

        PlutusData wellFormed(PirType type, int depth) {
            return switch (resolved(type)) {
                case PirType.IntegerType ignored -> new PlutusData.IntData(BigInteger.valueOf(random.nextInt(2001) - 1000));
                case PirType.ByteStringType ignored -> bytes();
                case PirType.StringType ignored -> new PlutusData.BytesData("s".getBytes());
                case PirType.BoolType ignored -> new PlutusData.ConstrData(random.nextInt(2), List.of());
                case PirType.UnitType ignored -> new PlutusData.ConstrData(0, List.of());
                case PirType.DataType ignored -> new PlutusData.IntData(BigInteger.valueOf(random.nextInt(10)));
                case PirType.RecordType record -> new PlutusData.ConstrData(0, fields(record.fields(), depth));
                case PirType.SumType sum -> {
                    var constructor = sum.constructors().get(random.nextInt(sum.constructors().size()));
                    if (depth > DEPTH) {
                        constructor = sum.constructors().stream().min((a, b) -> Integer.compare(a.fields().size(), b.fields().size())).orElseThrow();
                    }
                    yield new PlutusData.ConstrData(constructor.tag(), fields(constructor.fields(), depth));
                }
                case PirType.OptionalType optional -> random.nextBoolean() || depth > DEPTH
                        ? new PlutusData.ConstrData(1, List.of())
                        : new PlutusData.ConstrData(0, List.of(wellFormed(optional.elemType(), depth + 1)));
                case PirType.ListType list -> {
                    var items = new ArrayList<PlutusData>();
                    int size = depth > DEPTH ? 0 : random.nextInt(3);
                    for (int i = 0; i < size; i++) items.add(wellFormed(list.elemType(), depth + 1));
                    yield new PlutusData.ListData(items);
                }
                case PirType.MapType map -> {
                    var entries = new ArrayList<PlutusData.Pair>();
                    int size = depth > DEPTH ? 0 : random.nextInt(3);
                    for (int i = 0; i < size; i++) {
                        var key = wellFormed(map.keyType(), depth + 1);
                        // Distinct keys, and no ADA policy in a Value: the JVM's map and Value encode each key once, ADA first.
                        boolean ada = map == VALUE_DATA && key instanceof PlutusData.BytesData b && b.value().length == 0;
                        if (ada || entries.stream().anyMatch(entry -> entry.key().equals(key))) continue;
                        entries.add(new PlutusData.Pair(key, wellFormed(map.valueType(), depth + 1)));
                    }
                    yield new PlutusData.MapData(entries);
                }
                default -> throw new IllegalStateException("no Data for " + type);
            };
        }

        private List<PlutusData> fields(List<PirType.Field> fields, int depth) {
            var data = new ArrayList<PlutusData>();
            for (var field : fields) data.add(wellFormed(field.type(), depth + 1));
            return data;
        }

        private PlutusData.BytesData bytes() {
            var value = new byte[random.nextInt(4)];
            random.nextBytes(value);
            return new PlutusData.BytesData(value);
        }

        /** Well-formed Data of {@code type} with one node, chosen at random along one path, broken. */
        PlutusData broken(PirType type) {
            return broken(type, 0);
        }

        private PlutusData broken(PirType type, int depth) {
            var resolved = resolved(type);
            var good = wellFormed(resolved, depth);
            boolean here = depth > DEPTH || random.nextInt(4) == 0;
            if (!here && good instanceof PlutusData.ConstrData constr && !constr.fields().isEmpty()) {
                var fieldTypes = fieldTypes(resolved, constr.tag());
                if (fieldTypes != null && fieldTypes.size() == constr.fields().size()) {
                    int at = random.nextInt(fieldTypes.size());
                    var fields = new ArrayList<>(constr.fields());
                    fields.set(at, broken(fieldTypes.get(at), depth + 1));
                    return new PlutusData.ConstrData(constr.tag(), fields);
                }
            }
            if (!here && resolved instanceof PirType.ListType list && good instanceof PlutusData.ListData items && !items.items().isEmpty()) {
                var copy = new ArrayList<>(items.items());
                copy.set(0, broken(list.elemType(), depth + 1));
                return new PlutusData.ListData(copy);
            }
            if (!here && resolved instanceof PirType.MapType map && good instanceof PlutusData.MapData entries && !entries.entries().isEmpty()) {
                var copy = new ArrayList<>(entries.entries());
                var first = copy.getFirst();
                copy.set(0, random.nextBoolean() ? new PlutusData.Pair(broken(map.keyType(), depth + 1), first.value())
                        : new PlutusData.Pair(first.key(), broken(map.valueType(), depth + 1)));
                return new PlutusData.MapData(copy);
            }
            return breakNode(good);
        }

        private List<PirType> fieldTypes(PirType type, int tag) {
            return switch (type) {
                case PirType.RecordType record -> record.fields().stream().map(PirType.Field::type).toList();
                case PirType.SumType sum -> sum.constructors().stream().filter(c -> c.tag() == tag).findFirst()
                        .map(c -> c.fields().stream().map(PirType.Field::type).toList()).orElse(null);
                case PirType.OptionalType optional -> tag == 0 ? List.of(optional.elemType()) : null;
                default -> null;
            };
        }

        private PlutusData breakNode(PlutusData good) {
            int kind = random.nextInt(6);
            if (good instanceof PlutusData.ConstrData constr) {
                var fields = new ArrayList<>(constr.fields());
                return switch (kind) {
                    case 0 -> new PlutusData.ConstrData(constr.tag() + 1 + random.nextInt(3), fields);
                    case 1 -> {
                        fields.add(new PlutusData.IntData(BigInteger.ONE));
                        yield new PlutusData.ConstrData(constr.tag(), fields);
                    }
                    case 2 -> {
                        if (!fields.isEmpty()) fields.removeLast();
                        else fields.add(new PlutusData.ConstrData(0, List.of()));
                        yield new PlutusData.ConstrData(constr.tag(), fields);
                    }
                    default -> otherKind(good);
                };
            }
            return otherKind(good);
        }

        private PlutusData otherKind(PlutusData good) {
            List<PlutusData> others = List.of(new PlutusData.IntData(BigInteger.TWO), new PlutusData.BytesData(new byte[]{1}),
                    new PlutusData.ConstrData(0, List.of()), new PlutusData.ListData(List.of()), new PlutusData.MapData(List.of()));
            var candidates = others.stream().filter(other -> other.getClass() != good.getClass()).toList();
            return candidates.get(random.nextInt(candidates.size()));
        }
    }
}
