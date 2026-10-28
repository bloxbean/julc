package org.julclang.difftest;

import org.julclang.core.Constant;
import org.julclang.core.PlutusData;
import org.julclang.core.Term;
import org.julclang.core.types.JulcList;
import org.julclang.core.types.JulcMap;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The single mapping between Java values, UPLC result terms and Data that the harness compares
 * under (ADR-063 D4). It is a trusted component: {@code ValueMappingTest} pins it against
 * hand-written Data, never against the compiler's own encoders.
 * <p>
 * Supported: {@code BigInteger}, {@code long}, {@code int}, {@code boolean}, {@code byte[]},
 * {@code String}, {@code PlutusData}, and {@code Optional<T>}, {@code JulcList<T>} and
 * {@code JulcMap<K, V>} of those.
 * Anything else is a {@link HarnessException}, never a guess.
 * <ul>
 *   <li>Integers are {@code IntData}; {@code boolean} is {@code Constr 0 []} for false and
 *       {@code Constr 1 []} for true; {@code byte[]} is {@code BytesData}; a {@code String} is
 *       {@code BytesData} of its UTF-8 bytes.</li>
 *   <li>{@code Optional} is {@code Constr 0 [x]} when present and {@code Constr 1 []} when empty.</li>
 *   <li>A list is {@code ListData} of its elements; a map is {@code MapData} of its entries, in
 *       order, duplicates kept.</li>
 * </ul>
 */
public final class ValueMapping {

    private ValueMapping() {
    }

    /** The Data of a Java value: a JVM result, or a hand-written argument. */
    public static PlutusData toData(Object value) {
        return switch (value) {
            case null -> throw new HarnessException("null has no Data mapping");
            case PlutusData data -> data;
            case BigInteger i -> new PlutusData.IntData(i);
            case Long l -> new PlutusData.IntData(BigInteger.valueOf(l));
            case Integer i -> new PlutusData.IntData(BigInteger.valueOf(i));
            case Boolean b -> bool(b);
            case byte[] bytes -> new PlutusData.BytesData(bytes.clone());
            case String s -> new PlutusData.BytesData(s.getBytes(StandardCharsets.UTF_8));
            case Optional<?> o -> o.isPresent()
                    ? new PlutusData.ConstrData(0, List.of(toData(o.get())))
                    : new PlutusData.ConstrData(1, List.of());
            case JulcMap<?, ?> map -> {
                // keys() and values() are in entry order; get(key) would lose duplicate keys.
                var keys = new ArrayList<PlutusData>();
                for (Object key : map.keys()) keys.add(toData(key));
                var entries = new ArrayList<PlutusData.Pair>();
                int index = 0;
                for (Object entryValue : map.values()) entries.add(new PlutusData.Pair(keys.get(index++), toData(entryValue)));
                yield new PlutusData.MapData(entries);
            }
            case JulcList<?> list -> {
                var items = new ArrayList<PlutusData>();
                for (Object item : list) items.add(toData(item));
                yield new PlutusData.ListData(items);
            }
            default -> throw new HarnessException("No Data mapping for " + value.getClass().getName());
        };
    }

    /** The Java value of Data for a parameter of the given declared type. */
    public static Object fromData(PlutusData data, Type type) {
        Class<?> raw = rawClass(type);
        if (raw == PlutusData.class) return data;
        if (raw == BigInteger.class) return integer(data);
        if (raw == long.class || raw == Long.class) {
            try {
                return integer(data).longValueExact();
            } catch (ArithmeticException e) {
                throw new HarnessException("Argument " + data + " does not fit a long");
            }
        }
        if (raw == int.class || raw == Integer.class) {
            try {
                return integer(data).intValueExact();
            } catch (ArithmeticException e) {
                throw new HarnessException("Argument " + data + " does not fit an int");
            }
        }
        if (raw == boolean.class || raw == Boolean.class) {
            if (data instanceof PlutusData.ConstrData c && c.fields().isEmpty() && (c.tag() == 0 || c.tag() == 1)) {
                return c.tag() == 1;
            }
            throw new HarnessException("Not a Bool: " + data);
        }
        if (raw == byte[].class) return bytes(data);
        if (raw == String.class) return new String(bytes(data), StandardCharsets.UTF_8);
        if (raw == Optional.class) {
            if (data instanceof PlutusData.ConstrData c && c.tag() == 0 && c.fields().size() == 1) {
                return Optional.of(fromData(c.fields().getFirst(), typeArgument(type)));
            }
            if (data instanceof PlutusData.ConstrData c && c.tag() == 1 && c.fields().isEmpty()) {
                return Optional.empty();
            }
            throw new HarnessException("Not an Optional: " + data);
        }
        if (raw == JulcList.class) {
            if (!(data instanceof PlutusData.ListData list)) throw new HarnessException("Not a list: " + data);
            var element = typeArgument(type);
            return JulcList.of(list.items().stream().map(item -> fromData(item, element)).toArray());
        }
        if (raw == JulcMap.class) {
            if (!(data instanceof PlutusData.MapData map)) throw new HarnessException("Not a map: " + data);
            var params = ((ParameterizedType) type).getActualTypeArguments();
            JulcMap<Object, Object> result = JulcMap.empty();
            // insert prepends, so insert the entries last to first to keep their order.
            for (var entry : map.entries().reversed()) {
                result = result.insert(fromData(entry.key(), params[0]), fromData(entry.value(), params[1]));
            }
            return result;
        }
        throw new HarnessException("No Data mapping for parameter type " + type.getTypeName());
    }

    /** The Data of an evaluated UPLC result term. */
    public static PlutusData fromTerm(Term term) {
        return switch (term) {
            case Term.Const c -> fromConstant(c.value());
            case Term.Constr c -> new PlutusData.ConstrData(Math.toIntExact(c.tag()),
                    c.fields().stream().map(ValueMapping::fromTerm).toList());
            default -> throw new HarnessException("No Data mapping for result term " + term.getClass().getSimpleName());
        };
    }

    private static PlutusData fromConstant(Constant constant) {
        return switch (constant) {
            case Constant.IntegerConst i -> new PlutusData.IntData(i.value());
            case Constant.BoolConst b -> bool(b.value());
            case Constant.ByteStringConst b -> new PlutusData.BytesData(b.value().clone());
            case Constant.StringConst s -> new PlutusData.BytesData(s.value().getBytes(StandardCharsets.UTF_8));
            case Constant.DataConst d -> d.value();
            case Constant.ListConst l -> new PlutusData.ListData(l.values().stream().map(ValueMapping::fromConstant).toList());
            default -> throw new HarnessException("No Data mapping for result constant " + constant.getClass().getSimpleName());
        };
    }

    private static PlutusData bool(boolean value) {
        return new PlutusData.ConstrData(value ? 1 : 0, List.of());
    }

    private static BigInteger integer(PlutusData data) {
        if (data instanceof PlutusData.IntData i) return i.value();
        throw new HarnessException("Not an integer: " + data);
    }

    private static byte[] bytes(PlutusData data) {
        if (data instanceof PlutusData.BytesData b) return b.value().clone();
        throw new HarnessException("Not a bytestring: " + data);
    }

    private static Class<?> rawClass(Type type) {
        if (type instanceof Class<?> c) return c;
        if (type instanceof ParameterizedType p) return (Class<?>) p.getRawType();
        throw new HarnessException("Unsupported parameter type " + type.getTypeName());
    }

    private static Type typeArgument(Type type) {
        if (type instanceof ParameterizedType p) return p.getActualTypeArguments()[0];
        throw new HarnessException("Raw generic parameter type " + type.getTypeName() + " has no element type");
    }
}
