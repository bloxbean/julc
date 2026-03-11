package com.bloxbean.cardano.julc.clientlib;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.core.types.JulcMap;
import com.bloxbean.cardano.julc.core.types.Tuple2;
import com.bloxbean.cardano.julc.core.types.Tuple3;
import com.bloxbean.cardano.julc.ledger.PlutusDataConvertible;
import com.bloxbean.cardano.julc.ledger.PlutusDataHelper;

import java.lang.reflect.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reflection-based converter between Java records/sealed interfaces and JuLC {@link PlutusData}.
 * <p>
 * Encoding rules match the JuLC compiler output so on-chain and off-chain representations
 * are binary-compatible.
 * <p>
 * Package-private — external callers use {@link PlutusDataAdapter#convert(Object)} and
 * {@link PlutusDataAdapter#convert(com.bloxbean.cardano.client.plutus.spec.PlutusData, Class)}.
 */
final class ReflectivePlutusDataConverter {

    private ReflectivePlutusDataConverter() {}

    // Cache for reflected type metadata
    private static final ConcurrentHashMap<Class<?>, TypeMetadata> CACHE = new ConcurrentHashMap<>();

    // -----------------------------------------------------------------------
    // Encode: Object → PlutusData
    // -----------------------------------------------------------------------

    static PlutusData toPlutusData(Object obj) {
        if (obj == null) {
            throw new IllegalArgumentException("Cannot convert null to PlutusData");
        }

        // Already PlutusData — pass through
        if (obj instanceof PlutusData pd) {
            return pd;
        }

        // PlutusDataConvertible shortcut (covers all ledger types)
        if (obj instanceof PlutusDataConvertible convertible) {
            return convertible.toPlutusData();
        }

        // Primitives
        if (obj instanceof BigInteger bi) {
            return PlutusDataHelper.encodeInteger(bi);
        }
        if (obj instanceof Integer i) {
            return PlutusDataHelper.encodeInteger(BigInteger.valueOf(i));
        }
        if (obj instanceof Long l) {
            return PlutusDataHelper.encodeInteger(BigInteger.valueOf(l));
        }
        if (obj instanceof Boolean b) {
            return PlutusDataHelper.encodeBool(b);
        }
        if (obj instanceof byte[] bytes) {
            return PlutusDataHelper.encodeBytes(bytes);
        }
        if (obj instanceof String s) {
            return PlutusDataHelper.encodeBytes(s.getBytes(StandardCharsets.UTF_8));
        }

        // Optional
        if (obj instanceof Optional<?> opt) {
            return PlutusDataHelper.encodeOptional(opt, ReflectivePlutusDataConverter::toPlutusData);
        }

        // List / JulcList
        if (obj instanceof List<?> list) {
            return encodeList(list);
        }
        if (obj instanceof JulcList<?> julcList) {
            return encodeJulcList(julcList);
        }

        // Map / JulcMap
        if (obj instanceof Map<?, ?> map) {
            return encodeMap(map);
        }
        if (obj instanceof JulcMap<?, ?> julcMap) {
            return encodeJulcMap(julcMap);
        }

        // Tuple2 / Tuple3
        if (obj instanceof Tuple2<?, ?> t) {
            return PlutusData.constr(0, toPlutusData(t.first()), toPlutusData(t.second()));
        }
        if (obj instanceof Tuple3<?, ?, ?> t) {
            return PlutusData.constr(0, toPlutusData(t.first()), toPlutusData(t.second()), toPlutusData(t.third()));
        }

        // Record (including @NewType and sealed interface variants)
        Class<?> clazz = obj.getClass();
        if (clazz.isRecord()) {
            return encodeRecord(obj, clazz);
        }

        throw new IllegalArgumentException("Unsupported type for PlutusData conversion: " + clazz.getName());
    }

    private static PlutusData encodeList(List<?> list) {
        List<PlutusData> items = new ArrayList<>(list.size());
        for (Object elem : list) {
            items.add(toPlutusData(elem));
        }
        return new PlutusData.ListData(items);
    }

    private static PlutusData encodeJulcList(JulcList<?> list) {
        List<PlutusData> items = new ArrayList<>();
        for (Object elem : list) {
            items.add(toPlutusData(elem));
        }
        return new PlutusData.ListData(items);
    }

    private static PlutusData encodeMap(Map<?, ?> map) {
        List<PlutusData.Pair> entries = new ArrayList<>(map.size());
        for (var entry : map.entrySet()) {
            entries.add(new PlutusData.Pair(toPlutusData(entry.getKey()), toPlutusData(entry.getValue())));
        }
        return new PlutusData.MapData(entries);
    }

    @SuppressWarnings("unchecked")
    private static <K, V> PlutusData encodeJulcMap(JulcMap<K, V> map) {
        List<PlutusData.Pair> entries = new ArrayList<>();
        for (K key : map.keys()) {
            entries.add(new PlutusData.Pair(toPlutusData(key), toPlutusData(map.get(key))));
        }
        return new PlutusData.MapData(entries);
    }

    private static PlutusData encodeRecord(Object obj, Class<?> clazz) {
        TypeMetadata meta = getMetadata(clazz);

        if (meta.isNewType) {
            // @NewType — encode the single field directly, no ConstrData wrap
            try {
                Object fieldValue = meta.components[0].getAccessor().invoke(obj);
                return toPlutusData(fieldValue);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException("Failed to access @NewType field on " + clazz.getSimpleName(), e);
            }
        }

        // Regular record — ConstrData(tag, [fields...])
        PlutusData[] fields = new PlutusData[meta.components.length];
        for (int i = 0; i < meta.components.length; i++) {
            try {
                Object fieldValue = meta.components[i].getAccessor().invoke(obj);
                fields[i] = toPlutusData(fieldValue);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException("Failed to access field '" + meta.components[i].getName()
                        + "' on " + clazz.getSimpleName(), e);
            }
        }
        return PlutusData.constr(meta.tag, fields);
    }

    // -----------------------------------------------------------------------
    // Decode: PlutusData → Object
    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static <T> T fromPlutusData(PlutusData data, Class<T> type) {
        if (data == null) {
            throw new IllegalArgumentException("Cannot convert null PlutusData");
        }

        // Target is PlutusData itself — pass through
        if (PlutusData.class.isAssignableFrom(type)) {
            return type.cast(data);
        }

        // Target has static fromPlutusData(PlutusData) — ledger types
        TypeMetadata meta = getMetadata(type);
        if (meta.fromPlutusDataMethod != null) {
            try {
                return type.cast(meta.fromPlutusDataMethod.invoke(null, data));
            } catch (InvocationTargetException e) {
                throw new RuntimeException("fromPlutusData() failed for " + type.getSimpleName(), e.getCause());
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException("Failed to invoke fromPlutusData() on " + type.getSimpleName(), e);
            }
        }

        // Primitives
        if (type == BigInteger.class) {
            return type.cast(PlutusDataHelper.decodeInteger(data));
        }
        if (type == Integer.class || type == int.class) {
            return (T) Integer.valueOf(PlutusDataHelper.decodeInteger(data).intValueExact());
        }
        if (type == Long.class || type == long.class) {
            return (T) Long.valueOf(PlutusDataHelper.decodeInteger(data).longValueExact());
        }
        if (type == Boolean.class || type == boolean.class) {
            return (T) Boolean.valueOf(PlutusDataHelper.decodeBool(data));
        }
        if (type == byte[].class) {
            return type.cast(PlutusDataHelper.decodeBytes(data));
        }
        if (type == String.class) {
            return type.cast(new String(PlutusDataHelper.decodeBytes(data), StandardCharsets.UTF_8));
        }

        // Sealed interface — dispatch by ConstrData tag
        if (type.isSealed()) {
            return decodeSealedInterface(data, type);
        }

        // @NewType record
        if (type.isRecord() && meta.isNewType) {
            return decodeNewType(data, type, meta);
        }

        // Regular record
        if (type.isRecord()) {
            return decodeRecord(data, type, meta);
        }

        throw new IllegalArgumentException("Unsupported target type for PlutusData conversion: " + type.getName());
    }

    /**
     * Decode with full generic type information (for collections with type parameters).
     */
    @SuppressWarnings("unchecked")
    static Object fromPlutusData(PlutusData data, Type genericType) {
        if (genericType instanceof Class<?> clazz) {
            return fromPlutusData(data, clazz);
        }

        if (genericType instanceof ParameterizedType pt) {
            Class<?> rawType = (Class<?>) pt.getRawType();
            Type[] typeArgs = pt.getActualTypeArguments();

            // Optional<T>
            if (rawType == Optional.class) {
                return PlutusDataHelper.decodeOptional(data,
                        d -> fromPlutusData(d, typeArgs[0]));
            }

            // List<T>
            if (rawType == List.class || rawType == ArrayList.class) {
                return PlutusDataHelper.decodeList(data,
                        d -> fromPlutusData(d, typeArgs[0]));
            }

            // JulcList<T>
            if (JulcList.class.isAssignableFrom(rawType)) {
                return PlutusDataHelper.decodeJulcList(data,
                        d -> fromPlutusData(d, typeArgs[0]));
            }

            // Map<K,V>
            if (rawType == Map.class || rawType == LinkedHashMap.class || rawType == HashMap.class) {
                return PlutusDataHelper.decodeMap(data,
                        d -> fromPlutusData(d, typeArgs[0]),
                        d -> fromPlutusData(d, typeArgs[1]));
            }

            // JulcMap<K,V>
            if (JulcMap.class.isAssignableFrom(rawType)) {
                return PlutusDataHelper.decodeJulcMap(data,
                        d -> fromPlutusData(d, typeArgs[0]),
                        d -> fromPlutusData(d, typeArgs[1]));
            }

            // Tuple2<A,B>
            if (rawType == Tuple2.class) {
                var fields = PlutusDataHelper.expectConstr(data, 0);
                return new Tuple2<>(
                        fromPlutusData(fields.get(0), typeArgs[0]),
                        fromPlutusData(fields.get(1), typeArgs[1]));
            }

            // Tuple3<A,B,C>
            if (rawType == Tuple3.class) {
                var fields = PlutusDataHelper.expectConstr(data, 0);
                return new Tuple3<>(
                        fromPlutusData(fields.get(0), typeArgs[0]),
                        fromPlutusData(fields.get(1), typeArgs[1]),
                        fromPlutusData(fields.get(2), typeArgs[2]));
            }

            // Fall through to raw type
            return fromPlutusData(data, rawType);
        }

        throw new IllegalArgumentException("Unsupported generic type: " + genericType);
    }

    @SuppressWarnings("unchecked")
    private static <T> T decodeSealedInterface(PlutusData data, Class<T> sealedType) {
        if (!(data instanceof PlutusData.ConstrData constr)) {
            throw new IllegalArgumentException("Expected ConstrData for sealed interface "
                    + sealedType.getSimpleName() + ", got: " + data.getClass().getSimpleName());
        }

        Class<?>[] permitted = sealedType.getPermittedSubclasses();
        if (permitted == null || permitted.length == 0) {
            throw new IllegalArgumentException("Sealed interface " + sealedType.getSimpleName()
                    + " has no permitted subclasses");
        }

        int tag = constr.tag();
        if (tag < 0 || tag >= permitted.length) {
            throw new IllegalArgumentException("ConstrData tag " + tag + " out of range for "
                    + sealedType.getSimpleName() + " (expected 0.." + (permitted.length - 1) + ")");
        }

        return (T) fromPlutusData(data, permitted[tag]);
    }

    @SuppressWarnings("unchecked")
    private static <T> T decodeNewType(PlutusData data, Class<T> type, TypeMetadata meta) {
        // Decode the underlying value from PlutusData
        RecordComponent comp = meta.components[0];
        Object fieldValue = fromPlutusData(data, comp.getGenericType());

        try {
            return (T) meta.canonicalConstructor.newInstance(fieldValue);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to construct @NewType " + type.getSimpleName(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T decodeRecord(PlutusData data, Class<T> type, TypeMetadata meta) {
        var fields = PlutusDataHelper.expectConstr(data, meta.tag);

        if (fields.size() != meta.components.length) {
            throw new IllegalArgumentException("Field count mismatch for " + type.getSimpleName()
                    + ": expected " + meta.components.length + ", got " + fields.size());
        }

        Object[] args = new Object[meta.components.length];
        for (int i = 0; i < meta.components.length; i++) {
            args[i] = fromPlutusData(fields.get(i), meta.components[i].getGenericType());
        }

        try {
            return (T) meta.canonicalConstructor.newInstance(args);
        } catch (InvocationTargetException e) {
            throw new RuntimeException("Failed to construct " + type.getSimpleName(), e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to construct " + type.getSimpleName(), e);
        }
    }

    // -----------------------------------------------------------------------
    // Metadata cache
    // -----------------------------------------------------------------------

    private static TypeMetadata getMetadata(Class<?> clazz) {
        return CACHE.computeIfAbsent(clazz, ReflectivePlutusDataConverter::buildMetadata);
    }

    private static TypeMetadata buildMetadata(Class<?> clazz) {
        Method fromPD = findFromPlutusData(clazz);
        boolean isNewType = isNewType(clazz);
        RecordComponent[] components = clazz.isRecord() ? clazz.getRecordComponents() : new RecordComponent[0];
        Constructor<?> canonicalCtor = clazz.isRecord() ? findCanonicalConstructor(clazz, components) : null;
        int tag = clazz.isRecord() ? resolveTag(clazz) : 0;

        return new TypeMetadata(components, canonicalCtor, fromPD, isNewType, tag);
    }

    private static Method findFromPlutusData(Class<?> clazz) {
        try {
            Method m = clazz.getMethod("fromPlutusData", PlutusData.class);
            if (Modifier.isStatic(m.getModifiers()) && clazz.isAssignableFrom(m.getReturnType())) {
                return m;
            }
        } catch (NoSuchMethodException ignored) {
        }
        return null;
    }

    private static boolean isNewType(Class<?> clazz) {
        if (!clazz.isRecord()) return false;
        for (var ann : clazz.getAnnotations()) {
            if (ann.annotationType().getSimpleName().equals("NewType")) {
                return true;
            }
        }
        return false;
    }

    private static int resolveTag(Class<?> recordClass) {
        // Find sealed parent and determine tag from permits order
        for (Class<?> iface : recordClass.getInterfaces()) {
            if (iface.isSealed()) {
                Class<?>[] permitted = iface.getPermittedSubclasses();
                for (int i = 0; i < permitted.length; i++) {
                    if (permitted[i] == recordClass) {
                        return i;
                    }
                }
            }
        }
        // Also check superclass for sealed classes (not just interfaces)
        Class<?> superclass = recordClass.getSuperclass();
        if (superclass != null && superclass.isSealed()) {
            Class<?>[] permitted = superclass.getPermittedSubclasses();
            for (int i = 0; i < permitted.length; i++) {
                if (permitted[i] == recordClass) {
                    return i;
                }
            }
        }
        // Standalone record — tag 0
        return 0;
    }

    private static Constructor<?> findCanonicalConstructor(Class<?> clazz, RecordComponent[] components) {
        Class<?>[] paramTypes = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            paramTypes[i] = components[i].getType();
        }
        try {
            Constructor<?> ctor = clazz.getDeclaredConstructor(paramTypes);
            ctor.setAccessible(true);
            return ctor;
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("No canonical constructor found for record " + clazz.getSimpleName(), e);
        }
    }

    private record TypeMetadata(
            RecordComponent[] components,
            Constructor<?> canonicalConstructor,
            Method fromPlutusDataMethod,
            boolean isNewType,
            int tag
    ) {}
}
