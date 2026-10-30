package org.julclang.difftest;

import org.julclang.core.Constant;
import org.julclang.core.PlutusData;
import org.julclang.core.Term;
import org.julclang.core.types.JulcList;
import org.julclang.core.types.JulcMap;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
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
 * {@code String}, {@code PlutusData}, user records and sealed interfaces (ADR-066 D3), and
 * {@code Optional<T>}, {@code JulcList<T>} and {@code JulcMap<K, V>} of those.
 * Anything else is a {@link HarnessException}, never a guess.
 * <ul>
 *   <li>Integers are {@code IntData}; {@code boolean} is {@code Constr 0 []} for false and
 *       {@code Constr 1 []} for true; {@code byte[]} is {@code BytesData}; a {@code String} is
 *       {@code BytesData} of its UTF-8 bytes.</li>
 *   <li>{@code Optional} is {@code Constr 0 [x]} when present and {@code Constr 1 []} when empty.</li>
 *   <li>A list is {@code ListData} of its elements; a map is {@code MapData} of its entries, in
 *       order, duplicates kept.</li>
 *   <li>A record is {@code Constr tag [components]}, its components in declaration order, each mapped by
 *       these rules. {@code tag} is the record's tag in the case's {@link TagTable} if it implements a sealed
 *       interface, and 0 otherwise, whatever the static type at the use site; no tag ever comes from
 *       reflection order or from JuLC (ADR-066 G1-I3, G1-I4). For a sealed parameter type, the variant is the
 *       one whose tag is the {@code Constr}'s, with as many fields as it has components.</li>
 * </ul>
 * Records and sealed interfaces of JuLC's own libraries ({@link #LIBRARY_PACKAGES}) have encodings of their own
 * and are not mapped. This is a denylist: any other record is a user record, JDK records included, which G1
 * programs never use. Ledger records get a mapping, and the result check of ADR-066 D4, only with ADR-063 M4.
 */
public final class ValueMapping {

    /**
     * The denylist of packages whose records and sealed interfaces are not user types: JuLC's ledger and library
     * types, mapped by their own encoders (ADR-063 D4, M4) and unmapped here. Records from anywhere else, the JDK
     * included, are user records.
     */
    static final List<String> LIBRARY_PACKAGES = List.of("org.julclang.core.", "org.julclang.ledger.", "org.julclang.stdlib.");

    private ValueMapping() {
    }

    /**
     * The representation a UPLC result must have for the entry method's declared return type (ADR-066 D4,
     * G1-I10). {@link #fromTerm(Term)} accepts a native {@code Constr} and a {@code ConstrData} constant alike,
     * and a builtin bool as the Bool constructor; for these declared types a result in another representation
     * is a defect even when it maps to the expected Data.
     */
    public enum Representation {
        /** Another declared type: no check. */
        ANY,
        /** {@code BigInteger}: an integer constant. */
        INTEGER,
        /** {@code boolean}: a bool constant. */
        BOOL,
        /** A user record or sealed interface: a Data constant holding a {@code Constr}. */
        CONSTR_DATA;

        public static Representation of(Type declared) {
            Class<?> raw = declared instanceof ParameterizedType p ? (Class<?>) p.getRawType()
                    : declared instanceof Class<?> c ? c : null;
            if (raw == BigInteger.class) return INTEGER;
            if (raw == boolean.class) return BOOL;
            if (raw != null && isUserType(raw) && (raw.isRecord() || raw.isInterface() && raw.isSealed())) return CONSTR_DATA;
            return ANY;
        }

        boolean holds(Term term) {
            Constant constant = term instanceof Term.Const c ? c.value() : null;
            return switch (this) {
                case ANY -> true;
                case INTEGER -> constant instanceof Constant.IntegerConst;
                case BOOL -> constant instanceof Constant.BoolConst;
                case CONSTR_DATA -> constant instanceof Constant.DataConst d && d.value() instanceof PlutusData.ConstrData;
            };
        }
    }

    /** The Data of a Java value: a JVM result, or a hand-written argument; records only without a sealed parent. */
    public static PlutusData toData(Object value) {
        return toData(value, TagTable.EMPTY);
    }

    /** The Data of a Java value whose records take their tags from {@code tags}. */
    public static PlutusData toData(Object value, TagTable tags) {
        return toData(value, Object.class, tags);
    }

    /**
     * The Data of a Java value at a use site of static type {@code declared}, such as the entry method's return
     * type; a record value takes the tag for that type if it is one of its sealed parents ({@link TagTable#tagFor}).
     */
    public static PlutusData toData(Object value, Type declared, TagTable tags) {
        return toData(value, declared instanceof Class<?> c ? c : Object.class, tags);
    }

    private static PlutusData toData(Object value, Class<?> useSite, TagTable tags) {
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
                    ? new PlutusData.ConstrData(0, List.of(toData(o.get(), tags)))
                    : new PlutusData.ConstrData(1, List.of());
            case JulcMap<?, ?> map -> {
                // keys() and values() are in entry order; get(key) would lose duplicate keys.
                var keys = new ArrayList<PlutusData>();
                for (Object key : map.keys()) keys.add(toData(key, tags));
                var entries = new ArrayList<PlutusData.Pair>();
                int index = 0;
                for (Object entryValue : map.values()) entries.add(new PlutusData.Pair(keys.get(index++), toData(entryValue, tags)));
                yield new PlutusData.MapData(entries);
            }
            case JulcList<?> list -> {
                var items = new ArrayList<PlutusData>();
                for (Object item : list) items.add(toData(item, tags));
                yield new PlutusData.ListData(items);
            }
            case Record record when isUserType(record.getClass()) -> recordToData(record, useSite, tags);
            default -> throw new HarnessException("No Data mapping for " + value.getClass().getName());
        };
    }

    private static PlutusData recordToData(Record value, Class<?> useSite, TagTable tags) {
        Class<?> type = value.getClass();
        int tag = tags.tagFor(type, useSite);
        var fields = new ArrayList<PlutusData>();
        for (RecordComponent component : type.getRecordComponents()) {
            Object field;
            try {
                var accessor = component.getAccessor();
                accessor.setAccessible(true);
                field = accessor.invoke(value);
            } catch (ReflectiveOperationException e) {
                throw new HarnessException("Cannot read " + type.getName() + "." + component.getName(), e);
            }
            fields.add(toData(field, component.getType(), tags));
        }
        return new PlutusData.ConstrData(tag, fields);
    }

    /** The Java value of Data for a parameter of the given declared type; records only without a sealed parent. */
    public static Object fromData(PlutusData data, Type type) {
        return fromData(data, type, TagTable.EMPTY);
    }

    /** The Java value of Data for a parameter of the given declared type, with the case's tags. */
    public static Object fromData(PlutusData data, Type type, TagTable tags) {
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
                return Optional.of(fromData(c.fields().getFirst(), typeArgument(type), tags));
            }
            if (data instanceof PlutusData.ConstrData c && c.tag() == 1 && c.fields().isEmpty()) {
                return Optional.empty();
            }
            throw new HarnessException("Not an Optional: " + data);
        }
        if (raw == JulcList.class) {
            if (!(data instanceof PlutusData.ListData list)) throw new HarnessException("Not a list: " + data);
            var element = typeArgument(type);
            return JulcList.of(list.items().stream().map(item -> fromData(item, element, tags)).toArray());
        }
        if (raw == JulcMap.class) {
            if (!(data instanceof PlutusData.MapData map)) throw new HarnessException("Not a map: " + data);
            var params = ((ParameterizedType) type).getActualTypeArguments();
            JulcMap<Object, Object> result = JulcMap.empty();
            // insert prepends, so insert the entries last to first to keep their order.
            for (var entry : map.entries().reversed()) {
                result = result.insert(fromData(entry.key(), params[0], tags), fromData(entry.value(), params[1], tags));
            }
            return result;
        }
        if (isUserType(raw) && raw.isRecord()) return recordFromData(data, raw, raw, tags);
        if (isUserType(raw) && raw.isInterface() && raw.isSealed()) {
            if (!(data instanceof PlutusData.ConstrData c)) throw new HarnessException("Not a " + raw.getName() + ": " + data);
            Class<?> variant = null;
            for (Class<?> permitted : raw.getPermittedSubclasses()) {
                var tag = tags.tag(permitted, raw);
                if (tag.isPresent() && tag.getAsInt() == c.tag()) variant = permitted;
            }
            if (variant == null) throw new HarnessException("No variant of " + raw.getName() + " has tag " + c.tag() + ": " + data);
            return recordFromData(c, variant, raw, tags);
        }
        throw new HarnessException("No Data mapping for parameter type " + type.getTypeName());
    }

    private static Object recordFromData(PlutusData data, Class<?> type, Class<?> useSite, TagTable tags) {
        int tag = tags.tagFor(type, useSite);
        var components = type.getRecordComponents();
        if (!(data instanceof PlutusData.ConstrData c) || c.tag() != tag) {
            throw new HarnessException("Not a " + type.getName() + " (Constr " + tag + "): " + data);
        }
        if (c.fields().size() != components.length) {
            throw new HarnessException(type.getName() + " has " + components.length + " components, the Constr has "
                    + c.fields().size() + " fields: " + data);
        }
        var types = new Class<?>[components.length];
        var values = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            values[i] = fromData(c.fields().get(i), components[i].getGenericType(), tags);
        }
        try {
            Constructor<?> constructor = type.getDeclaredConstructor(types);
            constructor.setAccessible(true);
            return constructor.newInstance(values);
        } catch (InvocationTargetException e) {
            throw new HarnessException("The constructor of " + type.getName() + " threw for " + data, e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new HarnessException("Cannot construct " + type.getName(), e);
        }
    }

    private static boolean isUserType(Class<?> type) {
        return !PlutusData.class.isAssignableFrom(type) && LIBRARY_PACKAGES.stream().noneMatch(type.getName()::startsWith);
    }

    /**
     * The Data of an evaluated UPLC result term, which must have the representation of the entry method's declared
     * return type (ADR-066 D4); a violation is a {@link HarnessException} whose message starts with
     * {@code representation}.
     */
    public static PlutusData fromTerm(Term term, Representation expected) {
        if (!expected.holds(term)) {
            throw new HarnessException("representation: the declared type needs " + expected + ", the result is " + describe(term));
        }
        return fromTerm(term);
    }

    private static String describe(Term term) {
        return switch (term) {
            case Term.Const c when c.value() instanceof Constant.DataConst d -> "a Data constant " + d.value();
            case Term.Const c -> "a constant of type " + c.value().getClass().getSimpleName();
            case Term.Constr c -> "a native Constr " + c.tag() + " with " + c.fields().size() + " fields";
            default -> "a " + term.getClass().getSimpleName();
        };
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
