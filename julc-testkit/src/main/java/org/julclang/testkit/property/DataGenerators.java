package org.julclang.testkit.property;

import org.julclang.compiler.pir.PirType;
import org.julclang.core.PlutusData;

import java.math.BigInteger;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Factories for {@link DataGenerator}s: primitives, containers and constructors, and
 * {@link #forType} which derives a generator from a PIR type so that every generated value
 * is a valid Data encoding of that type (it passes the strict boundary check).
 * <p>
 * The encodings follow the backend's: integers are {@code I}, byte strings {@code B},
 * strings UTF-8 {@code B}, {@code Bool} is {@code Constr 0 []} / {@code Constr 1 []},
 * unit {@code Constr 0 []}, optionals {@code Constr 0 [x]} / {@code Constr 1 []}, records
 * {@code Constr 0 fields} and sums {@code Constr tag fields}. Maps get distinct keys.
 */
public final class DataGenerators {

    private static final PlutusData FALSE = new PlutusData.ConstrData(0, List.of());
    private static final PlutusData TRUE = new PlutusData.ConstrData(1, List.of());
    private static final PlutusData NONE = new PlutusData.ConstrData(1, List.of());
    private static final List<Integer> HASH_LENGTHS = List.of(28, 32);

    private DataGenerators() {}

    /** Integers of any size: small ones around 0, edge cases and some up to 64 bits. */
    public static DataGenerator integers() {
        return new Integers(null, null);
    }

    /** Integers in {@code [min, max]}; either bound may be null for none. Shrinks toward 0. */
    public static DataGenerator integers(BigInteger min, BigInteger max) {
        if (min != null && max != null && min.compareTo(max) > 0)
            throw new IllegalArgumentException("empty integer range [" + min + ", " + max + "]");
        return new Integers(min, max);
    }

    public static DataGenerator integers(long min, long max) {
        return integers(BigInteger.valueOf(min), BigInteger.valueOf(max));
    }

    /** Byte strings of 0 to 64 bytes, sometimes of hash length (28 or 32). */
    public static DataGenerator bytes() {
        return new Bytes(0, 64);
    }

    /** Byte strings of exactly {@code length} bytes, such as a 28-byte key hash. */
    public static DataGenerator bytes(int length) {
        return bytes(length, length);
    }

    public static DataGenerator bytes(int minLength, int maxLength) {
        if (minLength < 0 || minLength > maxLength)
            throw new IllegalArgumentException("bad length range [" + minLength + ", " + maxLength + "]");
        return new Bytes(minLength, maxLength);
    }

    /** UTF-8 strings, mostly ASCII letters and digits. */
    public static DataGenerator strings() {
        return new Strings();
    }

    public static DataGenerator bools() {
        return new Bools();
    }

    public static DataGenerator constant(PlutusData value) {
        Objects.requireNonNull(value, "value");
        return (random, size) -> value;
    }

    /** Any Data value, of depth bounded by the size. */
    public static DataGenerator data() {
        return new AnyData();
    }

    public static DataGenerator listOf(DataGenerator element) {
        return listOf(element, 0, Integer.MAX_VALUE);
    }

    /** Lists of {@code minLength} to {@code maxLength} elements (at most the size above the minimum). */
    public static DataGenerator listOf(DataGenerator element, int minLength, int maxLength) {
        Objects.requireNonNull(element, "element");
        if (minLength < 0 || minLength > maxLength)
            throw new IllegalArgumentException("bad length range [" + minLength + ", " + maxLength + "]");
        return new Lists(element, minLength, maxLength);
    }

    /** Maps with distinct keys, in generation order. */
    public static DataGenerator mapOf(DataGenerator key, DataGenerator value) {
        return new Maps(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
    }

    /** {@code Constr 0 [x]} or {@code Constr 1 []}. */
    public static DataGenerator optional(DataGenerator element) {
        return new Optionals(Objects.requireNonNull(element, "element"));
    }

    /** {@code Constr tag fields}: a record ({@code tag} 0) or one constructor of a sum. */
    public static DataGenerator constr(int tag, List<? extends DataGenerator> fields) {
        if (tag < 0) throw new IllegalArgumentException("negative constructor tag " + tag);
        return new Constr(tag, List.copyOf(fields));
    }

    /**
     * One of several {@link #constr} alternatives with distinct tags. Shrinking moves toward
     * the earlier alternatives, so list the simplest first.
     */
    public static DataGenerator oneOf(List<? extends DataGenerator> alternatives) {
        var constructors = new ArrayList<Constr>();
        for (var alternative : alternatives) {
            if (!(alternative instanceof Constr constructor))
                throw new IllegalArgumentException("oneOf takes constr(..) alternatives");
            constructors.add(constructor);
        }
        if (constructors.isEmpty()) throw new IllegalArgumentException("oneOf needs an alternative");
        int fewest = constructors.stream().mapToInt(c -> c.fields.size()).min().orElseThrow();
        var base = IntStream.range(0, constructors.size())
                .filter(i -> constructors.get(i).fields.size() == fewest).boxed().toList();
        return new Sum(constructors, base);
    }

    /** A generator for the Data encoding of {@code type}, which may not refer to named types. */
    public static DataGenerator forType(PirType type) {
        return forType(type, Map.of(), Map.of());
    }

    /**
     * A generator for the Data encoding of {@code type}.
     *
     * @param namedTypes definitions of the {@link PirType.NamedTypeRef}s it refers to, by stable id
     *                   (for example a validator ABI's named types)
     */
    public static DataGenerator forType(PirType type, Map<String, PirType> namedTypes) {
        return forType(type, namedTypes, Map.of());
    }

    /**
     * A generator for the Data encoding of {@code type}, with generators chosen by the caller
     * for some named types: {@code overrides} is keyed by a named type's stable id or by a
     * record or sum name. Recursive types are supported: values get shallower with the size
     * and every size-0 value is as shallow as the type allows.
     *
     * @throws IllegalArgumentException if a reachable type has no Data encoding (pairs, arrays,
     *                                  functions, native values) or a recursive type has no finite value
     */
    public static DataGenerator forType(PirType type, Map<String, PirType> namedTypes,
                                        Map<String, DataGenerator> overrides) {
        return new TypeGenerators(namedTypes, overrides).of(Objects.requireNonNull(type, "type"));
    }

    // ------------------------------------------------------------------ types

    private static final class TypeGenerators {
        private final DataShapes shapes;
        private final Map<String, DataGenerator> overrides;
        private final Map<String, Ref> refs = new HashMap<>();

        TypeGenerators(Map<String, PirType> named, Map<String, DataGenerator> overrides) {
            this.shapes = new DataShapes(named);
            this.overrides = Map.copyOf(overrides);
        }

        DataGenerator of(PirType type) {
            return switch (type) {
                case PirType.IntegerType _ -> integers();
                case PirType.ByteStringType _ -> bytes();
                case PirType.StringType _ -> strings();
                case PirType.BoolType _ -> bools();
                case PirType.UnitType _ -> constant(PlutusData.UNIT);
                case PirType.DataType _ -> data();
                case PirType.ListType list -> {
                    if (list.elemType() instanceof PirType.PairType)
                        throw DataShapes.unsupported(type, "pair lists have no Data encoding; use a map type");
                    yield listOf(of(list.elemType()));
                }
                case PirType.MapType map -> mapOf(of(map.keyType()), of(map.valueType()));
                case PirType.OptionalType optional -> optional(of(optional.elemType()));
                case PirType.RecordType record -> {
                    var override = overrides.get(record.name());
                    yield override != null ? override : record(record);
                }
                case PirType.SumType sum -> {
                    var override = overrides.get(sum.name());
                    yield override != null ? override : sum(sum, null);
                }
                case PirType.NamedTypeRef ref -> {
                    var override = overrides.getOrDefault(ref.stableId(), overrides.get(ref.name()));
                    yield override != null ? override : ref(ref);
                }
                default -> throw DataShapes.unsupported(type, "it has no Data encoding");
            };
        }

        private DataGenerator ref(PirType.NamedTypeRef ref) {
            var existing = refs.get(ref.stableId());
            if (existing != null) return existing;
            var definition = shapes.definition(ref);
            shapes.requireFinite(ref);
            var generator = new Ref();
            refs.put(ref.stableId(), generator); // before the definition, to close cycles
            generator.target = switch (definition) {
                case PirType.RecordType record -> record(record);
                case PirType.SumType sum -> sum(sum, ref.stableId());
                default -> of(definition);
            };
            return generator;
        }

        private DataGenerator record(PirType.RecordType record) {
            return constr(0, record.fields().stream().map(field -> of(field.type())).toList());
        }

        private DataGenerator sum(PirType.SumType sum, String id) {
            if (sum.constructors().isEmpty()) throw DataShapes.unsupported(sum, "it has no constructors");
            var constructors = new ArrayList<Constr>();
            for (var constructor : sum.constructors())
                constructors.add(new Constr(constructor.tag(),
                        constructor.fields().stream().map(field -> of(field.type())).toList()));
            var shallowest = shapes.shallowest(sum);
            var base = IntStream.range(0, constructors.size())
                    .filter(i -> shallowest.contains(sum.constructors().get(i))).boxed().toList();
            var sumGenerator = new Sum(constructors, base);
            if (id != null) {
                // Fields of the same type are candidates when shrinking (Cons x rest -> rest).
                for (int c = 0; c < constructors.size(); c++) {
                    var fields = sum.constructors().get(c).fields();
                    for (int f = 0; f < fields.size(); f++) {
                        var fieldType = fields.get(f).type();
                        if (isRef(fieldType, id)) sumGenerator.selfFields.add(List.of(c, f, 0));
                        else if (fieldType instanceof PirType.ListType list && isRef(list.elemType(), id))
                            sumGenerator.selfFields.add(List.of(c, f, 1));
                    }
                }
            }
            return sumGenerator;
        }

        private static boolean isRef(PirType type, String id) {
            return type instanceof PirType.NamedTypeRef ref && ref.stableId().equals(id);
        }
    }

    /** A named type, halving the size on each reference so recursive values stay finite. */
    private static final class Ref implements DataGenerator {
        private DataGenerator target;

        @Override
        public PlutusData generate(SplittableRandom random, int size) {
            return target.generate(random, size / 2);
        }

        @Override
        public Stream<PlutusData> shrink(PlutusData value) {
            return target.shrink(value);
        }
    }

    // ------------------------------------------------------------- primitives

    private static final class Integers implements DataGenerator {
        private final BigInteger min;
        private final BigInteger max;
        private final BigInteger target;

        Integers(BigInteger min, BigInteger max) {
            this.min = min;
            this.max = max;
            this.target = clamp(BigInteger.ZERO);
        }

        @Override
        public PlutusData generate(SplittableRandom random, int size) {
            int pick = random.nextInt(20);
            BigInteger value;
            if (pick < 3) {
                value = clamp(pick == 0 ? BigInteger.ZERO : pick == 1 ? BigInteger.ONE : BigInteger.ONE.negate());
            } else if (pick < 13) {
                value = clamp(target.add(BigInteger.valueOf(random.nextLong(-size, size + 1L))));
            } else if (min != null && max != null) {
                value = min.add(below(random, max.subtract(min).add(BigInteger.ONE)));
            } else {
                var magnitude = bits(random, 1 + random.nextInt(Math.min(64, 8 + 2 * size)));
                value = min != null ? min.add(magnitude)
                        : max != null ? max.subtract(magnitude)
                        : random.nextBoolean() ? magnitude : magnitude.negate();
            }
            return new PlutusData.IntData(value);
        }

        @Override
        public Stream<PlutusData> shrink(PlutusData value) {
            if (!(value instanceof PlutusData.IntData(BigInteger x))) return Stream.empty();
            var candidates = new ArrayList<BigInteger>();
            if (x.signum() < 0 && target.signum() == 0 && inRange(x.negate())) candidates.add(x.negate());
            // x - d, x - d/2, x - d/4, ... : from the target back toward x.
            for (var d = x.subtract(target); d.signum() != 0; d = d.divide(BigInteger.TWO))
                candidates.add(x.subtract(d));
            return candidates.stream().distinct().filter(c -> !c.equals(x)).map(PlutusData.IntData::new);
        }

        private BigInteger clamp(BigInteger value) {
            if (min != null && value.compareTo(min) < 0) return min;
            if (max != null && value.compareTo(max) > 0) return max;
            return value;
        }

        private boolean inRange(BigInteger value) {
            return clamp(value).equals(value);
        }
    }

    /** A uniform non-negative integer below {@code bound}. */
    private static BigInteger below(SplittableRandom random, BigInteger bound) {
        if (bound.bitLength() < 63) return BigInteger.valueOf(random.nextLong(bound.longValue()));
        BigInteger value;
        do value = bits(random, bound.bitLength()); while (value.compareTo(bound) >= 0);
        return value;
    }

    /** A uniform non-negative integer of at most {@code count} bits. */
    private static BigInteger bits(SplittableRandom random, int count) {
        var bytes = new byte[(count + 7) / 8 + 1];
        random.nextBytes(bytes);
        bytes[0] = 0;
        return new BigInteger(bytes).mod(BigInteger.ONE.shiftLeft(count));
    }

    private static final class Bytes implements DataGenerator {
        private final int min;
        private final int max;

        Bytes(int min, int max) {
            this.min = min;
            this.max = max;
        }

        @Override
        public PlutusData generate(SplittableRandom random, int size) {
            int length;
            var hashLengths = HASH_LENGTHS.stream().filter(n -> n >= min && n <= max).toList();
            if (!hashLengths.isEmpty() && random.nextInt(8) == 0) {
                length = hashLengths.get(random.nextInt(hashLengths.size()));
            } else {
                int upper = (int) Math.min(max, (long) min + size);
                length = min + random.nextInt(upper - min + 1);
            }
            var bytes = new byte[length];
            random.nextBytes(bytes);
            return new PlutusData.BytesData(bytes);
        }

        @Override
        public Stream<PlutusData> shrink(PlutusData value) {
            if (!(value instanceof PlutusData.BytesData data)) return Stream.empty();
            var bytes = data.value();
            var candidates = new ArrayList<byte[]>();
            for (int length : new int[]{min, Math.max(min, bytes.length / 2), bytes.length - 1})
                if (length >= min && length < bytes.length) candidates.add(Arrays.copyOf(bytes, length));
            int first = 0;
            while (first < bytes.length && bytes[first] == 0) first++;
            if (first < bytes.length) {
                candidates.add(new byte[bytes.length]);
                var zeroed = bytes.clone();
                zeroed[first] = 0;
                candidates.add(zeroed);
            }
            return candidates.stream().map(PlutusData.BytesData::new).distinct()
                    .filter(c -> !c.equals(value)).map(PlutusData.class::cast);
        }
    }

    private static final class Strings implements DataGenerator {
        private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 ";

        @Override
        public PlutusData generate(SplittableRandom random, int size) {
            int length = random.nextInt(Math.min(size, 32) + 1);
            var text = new StringBuilder();
            for (int i = 0; i < length; i++) {
                if (random.nextInt(10) == 0) {
                    int codePoint;
                    do codePoint = 0x80 + random.nextInt(0x10000 - 0x80);
                    while (Character.isSurrogate((char) codePoint));
                    text.appendCodePoint(codePoint);
                } else {
                    text.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
                }
            }
            return string(text.toString());
        }

        @Override
        public Stream<PlutusData> shrink(PlutusData value) {
            if (!(value instanceof PlutusData.BytesData data)) return Stream.empty();
            String text;
            try {
                text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(data.value())).toString();
            } catch (CharacterCodingException e) {
                return Stream.empty();
            }
            int[] points = text.codePoints().toArray();
            var candidates = new ArrayList<String>();
            if (points.length > 0) {
                candidates.add("");
                candidates.add(new String(points, 0, points.length / 2));
                for (int i = 0; i < points.length; i++) candidates.add(without(points, i));
                for (int i = 0; i < points.length; i++)
                    if (points[i] != 'a') {
                        var simpler = points.clone();
                        simpler[i] = 'a';
                        candidates.add(new String(simpler, 0, simpler.length));
                        break;
                    }
            }
            return candidates.stream().distinct().filter(c -> !c.equals(text)).map(DataGenerators::string);
        }

        private static String without(int[] points, int index) {
            var text = new StringBuilder();
            for (int i = 0; i < points.length; i++) if (i != index) text.appendCodePoint(points[i]);
            return text.toString();
        }
    }

    private static PlutusData string(String text) {
        return new PlutusData.BytesData(text.getBytes(StandardCharsets.UTF_8));
    }

    private static final class Bools implements DataGenerator {
        @Override
        public PlutusData generate(SplittableRandom random, int size) {
            return random.nextBoolean() ? TRUE : FALSE;
        }

        @Override
        public Stream<PlutusData> shrink(PlutusData value) {
            return value.equals(TRUE) ? Stream.of(FALSE) : Stream.empty();
        }
    }

    private static final class AnyData implements DataGenerator {
        private static final DataGenerator INTEGERS = integers();
        private static final DataGenerator BYTES = bytes(0, 8);

        @Override
        public PlutusData generate(SplittableRandom random, int size) {
            int kind = random.nextInt(size <= 1 ? 2 : 5);
            int width = Math.min(size / 2, 3);
            return switch (kind) {
                case 0 -> INTEGERS.generate(random, size);
                case 1 -> BYTES.generate(random, size);
                case 2 -> new PlutusData.ListData(items(random, size, width));
                case 3 -> {
                    var entries = new ArrayList<PlutusData.Pair>();
                    for (var key : items(random, size, width))
                        entries.add(new PlutusData.Pair(key, generate(random, size / 2)));
                    yield new PlutusData.MapData(entries);
                }
                default -> new PlutusData.ConstrData(random.nextInt(4), items(random, size, width));
            };
        }

        private List<PlutusData> items(SplittableRandom random, int size, int width) {
            int count = random.nextInt(width + 1);
            var items = new ArrayList<PlutusData>();
            for (int i = 0; i < count; i++) items.add(generate(random, size / 2));
            return items;
        }

        @Override
        public Stream<PlutusData> shrink(PlutusData value) {
            var zero = new PlutusData.IntData(BigInteger.ZERO);
            Stream<PlutusData> simpler = value.equals(zero) ? Stream.empty() : Stream.of(zero);
            return Stream.concat(simpler, switch (value) {
                case PlutusData.IntData _ -> INTEGERS.shrink(value).filter(v -> !v.equals(zero));
                case PlutusData.BytesData _ -> BYTES.shrink(value);
                case PlutusData.ListData list -> Stream.concat(list.items().stream(),
                        shrinkItems(list.items(), 0, this).map(PlutusData.ListData::new));
                case PlutusData.ConstrData constr -> Stream.concat(constr.fields().stream(),
                        shrinkItems(constr.fields(), 0, this)
                                .map(fields -> new PlutusData.ConstrData(constr.constructorTag(), fields)));
                case PlutusData.MapData map -> Stream.concat(
                        map.entries().stream().flatMap(e -> Stream.of(e.key(), e.value())),
                        IntStream.range(0, map.entries().size()).mapToObj(i -> {
                            var entries = new ArrayList<>(map.entries());
                            entries.remove(i);
                            return new PlutusData.MapData(entries);
                        }));
            });
        }
    }

    // ------------------------------------------------------------- containers

    /**
     * Shorter lists first (the minimum, halves, one element removed), then each element
     * shrunk in place.
     */
    private static Stream<List<PlutusData>> shrinkItems(List<PlutusData> items, int minLength, DataGenerator element) {
        int n = items.size();
        var shorter = new ArrayList<List<PlutusData>>();
        if (n > minLength) {
            shorter.add(items.subList(0, minLength));
            if (n / 2 >= minLength) {
                shorter.add(items.subList(0, n / 2));
                shorter.add(items.subList(n - n / 2, n));
            }
            for (int i = 0; i < n; i++) {
                var removed = new ArrayList<>(items);
                removed.remove(i);
                shorter.add(removed);
            }
        }
        var inPlace = IntStream.range(0, n).boxed().flatMap(i -> element.shrink(items.get(i)).map(simpler -> {
            var replaced = new ArrayList<>(items);
            replaced.set(i, simpler);
            return (List<PlutusData>) replaced;
        }));
        return Stream.concat(shorter.stream().distinct().filter(list -> list.size() < n), inPlace);
    }

    private static final class Lists implements DataGenerator {
        private final DataGenerator element;
        private final int min;
        private final int max;

        Lists(DataGenerator element, int min, int max) {
            this.element = element;
            this.min = min;
            this.max = max;
        }

        @Override
        public PlutusData generate(SplittableRandom random, int size) {
            int upper = (int) Math.min(max, (long) min + size);
            int length = min + random.nextInt(upper - min + 1);
            var items = new ArrayList<PlutusData>(length);
            for (int i = 0; i < length; i++) items.add(element.generate(random, size / 2));
            return new PlutusData.ListData(items);
        }

        @Override
        public Stream<PlutusData> shrink(PlutusData value) {
            if (!(value instanceof PlutusData.ListData list)) return Stream.empty();
            return shrinkItems(list.items(), min, element).map(PlutusData.ListData::new);
        }
    }

    private static final class Maps implements DataGenerator {
        private final DataGenerator key;
        private final DataGenerator value;

        Maps(DataGenerator key, DataGenerator value) {
            this.key = key;
            this.value = value;
        }

        @Override
        public PlutusData generate(SplittableRandom random, int size) {
            int length = random.nextInt(size + 1);
            var keys = new HashSet<PlutusData>();
            var entries = new ArrayList<PlutusData.Pair>();
            // Few distinct keys may exist (Bool, a small range); stop after repeated collisions.
            for (int attempt = 0; entries.size() < length && attempt < 3 * length; attempt++) {
                var k = key.generate(random, size / 2);
                if (keys.add(k)) entries.add(new PlutusData.Pair(k, value.generate(random, size / 2)));
            }
            return new PlutusData.MapData(entries);
        }

        @Override
        public Stream<PlutusData> shrink(PlutusData data) {
            if (!(data instanceof PlutusData.MapData map)) return Stream.empty();
            var entries = map.entries();
            var keys = entries.stream().map(PlutusData.Pair::key).toList();
            var values = entries.stream().map(PlutusData.Pair::value).toList();
            // Removing entries is shrinking the key list; values are kept alongside their keys.
            var shorter = shrinkItems(keys, 0, constant(PlutusData.UNIT)).filter(list -> list.size() < keys.size()).map(kept -> {
                var remaining = new ArrayList<PlutusData.Pair>();
                for (var entry : entries) if (kept.contains(entry.key())) remaining.add(entry);
                return new PlutusData.MapData(remaining);
            });
            var simplerValues = IntStream.range(0, entries.size()).boxed().flatMap(i ->
                    value.shrink(values.get(i)).map(v -> replace(entries, i, new PlutusData.Pair(keys.get(i), v))));
            var simplerKeys = IntStream.range(0, entries.size()).boxed().flatMap(i ->
                    key.shrink(keys.get(i)).filter(k -> !keys.contains(k))
                            .map(k -> replace(entries, i, new PlutusData.Pair(k, values.get(i)))));
            return Stream.concat(shorter, Stream.concat(simplerValues, simplerKeys));
        }

        private static PlutusData replace(List<PlutusData.Pair> entries, int index, PlutusData.Pair entry) {
            var replaced = new ArrayList<>(entries);
            replaced.set(index, entry);
            return new PlutusData.MapData(replaced);
        }
    }

    private static final class Optionals implements DataGenerator {
        private final DataGenerator element;

        Optionals(DataGenerator element) {
            this.element = element;
        }

        @Override
        public PlutusData generate(SplittableRandom random, int size) {
            if (size == 0 || random.nextInt(4) == 0) return NONE;
            return new PlutusData.ConstrData(0, List.of(element.generate(random, size)));
        }

        @Override
        public Stream<PlutusData> shrink(PlutusData value) {
            if (!(value instanceof PlutusData.ConstrData constr) || constr.tag() != 0 || constr.fields().size() != 1)
                return Stream.empty();
            return Stream.concat(Stream.of(NONE), element.shrink(constr.fields().getFirst())
                    .map(simpler -> new PlutusData.ConstrData(0, List.of(simpler))));
        }
    }

    private static final class Constr implements DataGenerator {
        private final int tag;
        private final List<DataGenerator> fields;

        Constr(int tag, List<DataGenerator> fields) {
            this.tag = tag;
            this.fields = fields;
        }

        @Override
        public PlutusData generate(SplittableRandom random, int size) {
            var values = new ArrayList<PlutusData>(fields.size());
            for (var field : fields) values.add(field.generate(random, size));
            return new PlutusData.ConstrData(tag, values);
        }

        @Override
        public Stream<PlutusData> shrink(PlutusData value) {
            if (!(value instanceof PlutusData.ConstrData constr) || !constr.constructorTag().equals(BigInteger.valueOf(tag))
                    || constr.fields().size() != fields.size())
                return Stream.empty();
            var values = constr.fields();
            return IntStream.range(0, values.size()).boxed().flatMap(i -> fields.get(i).shrink(values.get(i)).map(simpler -> {
                var replaced = new ArrayList<>(values);
                replaced.set(i, simpler);
                return new PlutusData.ConstrData(tag, replaced);
            }));
        }
    }

    private static final class Sum implements DataGenerator {
        private static final long MINIMAL_SEED = 0x5eedL;
        private final List<Constr> constructors;
        private final List<Integer> base;
        /** For a named recursive sum: {constructor, field, 0 = the field, 1 = its list's elements}. */
        private final List<List<Integer>> selfFields = new ArrayList<>();

        Sum(List<Constr> constructors, List<Integer> base) {
            this.constructors = constructors;
            this.base = base;
        }

        @Override
        public PlutusData generate(SplittableRandom random, int size) {
            int index = size == 0 ? base.get(random.nextInt(base.size())) : random.nextInt(constructors.size());
            return constructors.get(index).generate(random, size);
        }

        @Override
        public Stream<PlutusData> shrink(PlutusData value) {
            if (!(value instanceof PlutusData.ConstrData constr)) return Stream.empty();
            int index = -1;
            for (int i = 0; i < constructors.size(); i++)
                if (BigInteger.valueOf(constructors.get(i).tag).equals(constr.constructorTag())) index = i;
            if (index < 0) return Stream.empty();
            var subterms = new ArrayList<PlutusData>();
            for (var self : selfFields) {
                if (self.get(0) != index || self.get(1) >= constr.fields().size()) continue;
                var field = constr.fields().get(self.get(1));
                if (self.get(2) == 0) subterms.add(field);
                else if (field instanceof PlutusData.ListData list) subterms.addAll(list.items());
            }
            // Earlier constructors, as simple as they come (size 0 reaches only base constructors).
            var earlier = IntStream.range(0, index)
                    .mapToObj(i -> constructors.get(i).generate(new SplittableRandom(MINIMAL_SEED), 0));
            return Stream.concat(Stream.concat(subterms.stream(), earlier), constructors.get(index).shrink(value));
        }
    }
}
