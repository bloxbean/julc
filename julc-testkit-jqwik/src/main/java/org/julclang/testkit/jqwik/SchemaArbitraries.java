package org.julclang.testkit.jqwik;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Tuple;
import org.julclang.compiler.backend.ValidatorAbi;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.schema.ContractSchema;
import org.julclang.core.PlutusData;
import org.julclang.testkit.property.DataShapes;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * jqwik {@link Arbitrary} generators derived from PIR types: every value is a valid Data
 * encoding of its type (it passes the backend's strict boundary check), and shrinking is
 * jqwik's own. Types come from a producer, typically a compiled validator's
 * {@link ValidatorAbi}:
 * <pre>{@code
 * @Provide
 * Arbitrary<PlutusData> redeemers() {
 *     return SchemaArbitraries.redeemer(result.abi(), ContractSchema.Purpose.SPEND);
 * }
 *
 * @Property
 * void neverAcceptsWithoutSignature(@ForAll("redeemers") PlutusData redeemer) { ... }
 * }</pre>
 * Recursive named types are unrolled to {@link #DEFAULT_DEPTH} references; below that only
 * their shallowest constructors are chosen, so every value is finite.
 */
public final class SchemaArbitraries {
    /** Named-type references unrolled before recursive types bottom out. */
    public static final int DEFAULT_DEPTH = 4;
    /** Default maximum length of generated lists and maps. */
    public static final int DEFAULT_MAX_LENGTH = 10;

    private static final PlutusData FALSE = new PlutusData.ConstrData(0, List.of());
    private static final PlutusData TRUE = new PlutusData.ConstrData(1, List.of());
    private static final PlutusData NONE = new PlutusData.ConstrData(1, List.of());
    private static final BigInteger BOUND = BigInteger.ONE.shiftLeft(64);

    private SchemaArbitraries() {}

    public static Arbitrary<PlutusData> forType(PirType type) {
        return forType(type, Map.of());
    }

    /** @param namedTypes definitions of the named types {@code type} refers to, by stable id */
    public static Arbitrary<PlutusData> forType(PirType type, Map<String, PirType> namedTypes) {
        return forType(type, namedTypes, Map.of());
    }

    /**
     * @param overrides arbitraries for some named types, keyed by stable id or by record or sum
     *                  name, for example exact-length hashes or bounded amounts
     * @throws IllegalArgumentException if a reachable type has no Data encoding or a recursive
     *                                  type has no finite value
     */
    public static Arbitrary<PlutusData> forType(PirType type, Map<String, PirType> namedTypes,
                                                Map<String, Arbitrary<PlutusData>> overrides) {
        return new Builder(namedTypes, overrides).of(Objects.requireNonNull(type, "type"), DEFAULT_DEPTH);
    }

    /** Redeemers of the handler for {@code purpose}. */
    public static Arbitrary<PlutusData> redeemer(ValidatorAbi abi, ContractSchema.Purpose purpose) {
        return forType(handler(abi, purpose).redeemerType(), abi.namedTypes());
    }

    /** Datums of the spend handler. */
    public static Arbitrary<PlutusData> datum(ValidatorAbi abi) {
        var handler = handler(abi, ContractSchema.Purpose.SPEND);
        if (handler.datumType() == null)
            throw new IllegalArgumentException("the spend handler of " + abi.identity() + " takes no datum");
        return forType(handler.datumType(), abi.namedTypes());
    }

    /** Deployment parameters, in the order {@code Program.applyParams} takes them. */
    public static Arbitrary<List<PlutusData>> parameters(ValidatorAbi abi) {
        var arbitraries = abi.parameters().stream()
                .map(parameter -> forType(parameter.type(), abi.namedTypes())).toList();
        if (arbitraries.isEmpty()) return Arbitraries.just(List.of());
        return Combinators.combine(arbitraries).as(List::copyOf);
    }

    private static ValidatorAbi.HandlerAbi handler(ValidatorAbi abi, ContractSchema.Purpose purpose) {
        return abi.handlers().stream().filter(h -> h.purpose() == purpose).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(abi.identity() + " has no " + purpose + " handler"));
    }

    private static final class Builder {
        private final DataShapes shapes;
        private final Map<String, Arbitrary<PlutusData>> overrides;
        private final Map<String, Arbitrary<PlutusData>> named = new HashMap<>();

        Builder(Map<String, PirType> namedTypes, Map<String, Arbitrary<PlutusData>> overrides) {
            this.shapes = new DataShapes(namedTypes);
            this.overrides = Map.copyOf(overrides);
        }

        /** {@code depth} counts the references left before only the shallowest constructors are chosen. */
        Arbitrary<PlutusData> of(PirType type, int depth) {
            return switch (type) {
                case PirType.IntegerType _ -> Arbitraries.bigIntegers().between(BOUND.negate(), BOUND)
                        .map(PlutusData.IntData::new);
                case PirType.ByteStringType _ -> Arbitraries.frequencyOf(
                        Tuple.of(6, bytes(0, 64)), Tuple.of(1, bytes(28, 28)), Tuple.of(1, bytes(32, 32)));
                case PirType.StringType _ -> Arbitraries.frequencyOf(
                        Tuple.of(4, Arbitraries.strings().alpha().numeric().withChars(' ').ofMaxLength(32)),
                        Tuple.of(1, Arbitraries.strings().withCharRange(' ', '퟿').ofMaxLength(8)))
                        .map(text -> new PlutusData.BytesData(text.getBytes(StandardCharsets.UTF_8)));
                case PirType.BoolType _ -> Arbitraries.of(FALSE, TRUE);
                case PirType.UnitType _ -> Arbitraries.just(PlutusData.UNIT);
                case PirType.DataType _ -> CardanoArbitraries.plutusData();
                case PirType.ListType list -> {
                    if (list.elemType() instanceof PirType.PairType)
                        throw DataShapes.unsupported(type, "pair lists have no Data encoding; use a map type");
                    yield depth <= 0 ? Arbitraries.just(new PlutusData.ListData(List.of()))
                            : of(list.elemType(), depth).list().ofMaxSize(DEFAULT_MAX_LENGTH)
                            .map(PlutusData.ListData::new);
                }
                case PirType.MapType map -> {
                    var key = of(map.keyType(), depth);
                    var value = of(map.valueType(), depth);
                    // Duplicate keys are dropped after generation, so small key domains still work.
                    yield depth <= 0 ? Arbitraries.just(new PlutusData.MapData(List.of()))
                            : Combinators.combine(key, value).as(PlutusData.Pair::new).list()
                            .ofMaxSize(DEFAULT_MAX_LENGTH).map(Builder::distinctKeys);
                }
                case PirType.OptionalType optional -> depth <= 0 ? Arbitraries.just(NONE)
                        : Arbitraries.frequencyOf(Tuple.of(1, Arbitraries.just(NONE)), Tuple.of(3,
                        of(optional.elemType(), depth).map(v -> new PlutusData.ConstrData(0, List.of(v)))));
                case PirType.RecordType record -> {
                    var override = overrides.get(record.name());
                    yield override != null ? override : constr(0, record.fields(), depth);
                }
                case PirType.SumType sum -> {
                    var override = overrides.get(sum.name());
                    yield override != null ? override : sum(sum, depth);
                }
                case PirType.NamedTypeRef ref -> {
                    var override = overrides.getOrDefault(ref.stableId(), overrides.get(ref.name()));
                    yield override != null ? override : ref(ref, depth);
                }
                default -> throw DataShapes.unsupported(type, "it has no Data encoding");
            };
        }

        private Arbitrary<PlutusData> ref(PirType.NamedTypeRef ref, int depth) {
            shapes.requireFinite(ref);
            // Below depth 0 only shallowest constructors are chosen and containers are empty,
            // which lowers the height at every reference, so construction terminates.
            int level = depth - 1;
            String key = ref.stableId() + "@" + level;
            var existing = named.get(key);
            if (existing != null) return existing;
            var arbitrary = of(shapes.definition(ref), level);
            named.put(key, arbitrary);
            return arbitrary;
        }

        private Arbitrary<PlutusData> sum(PirType.SumType sum, int depth) {
            var constructors = depth <= 0 ? shapes.shallowest(sum) : sum.constructors();
            if (constructors.isEmpty()) throw DataShapes.unsupported(sum, "it has no constructors");
            var alternatives = new ArrayList<Arbitrary<PlutusData>>();
            for (var constructor : constructors)
                alternatives.add(constr(constructor.tag(), constructor.fields(), depth));
            return alternatives.size() == 1 ? alternatives.getFirst() : Arbitraries.oneOf(alternatives);
        }

        private Arbitrary<PlutusData> constr(int tag, List<PirType.Field> fields, int depth) {
            if (fields.isEmpty()) return Arbitraries.just(new PlutusData.ConstrData(tag, List.of()));
            var arbitraries = fields.stream().map(field -> of(field.type(), depth)).toList();
            return Combinators.combine(arbitraries).as(values -> new PlutusData.ConstrData(tag, values));
        }

        private static Arbitrary<PlutusData> bytes(int min, int max) {
            return Arbitraries.bytes().array(byte[].class).ofMinSize(min).ofMaxSize(max).map(PlutusData.BytesData::new);
        }

        private static PlutusData distinctKeys(List<PlutusData.Pair> pairs) {
            var keys = new HashSet<PlutusData>();
            return new PlutusData.MapData(pairs.stream().filter(pair -> keys.add(pair.key())).toList());
        }
    }
}
