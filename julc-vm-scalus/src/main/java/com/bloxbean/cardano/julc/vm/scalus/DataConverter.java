package com.bloxbean.cardano.julc.vm.scalus;

import com.bloxbean.cardano.julc.core.PlutusData;
import scalus.uplc.builtin.ByteString;
import scalus.uplc.builtin.Data;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;

/**
 * Converts between plutus-java {@link PlutusData} and Scalus {@link Data} types.
 * <p>
 * This direct conversion bypasses CBOR encoding, avoiding the 64-byte bytestring
 * limit enforced by Scalus's CBOR decoder (borer library). This allows Data
 * constants with arbitrarily-sized bytestrings to be passed to the Scalus VM.
 * <p>
 * Uses reflection for Scalus List construction because the
 * {@code scalus.cardano.onchain.plutus.prelude.List} class has undeclared type
 * variables that Java's compiler cannot handle (same issue documented in TermConverter).
 */
final class DataConverter {

    // Cached reflection handles for Scalus List construction
    private static final Object LIST_COMPANION;
    private static final Method LIST_FROM;
    private static final Constructor<?> DATA_CONSTR_CTOR;
    private static final Constructor<?> DATA_LIST_CTOR;
    private static final Constructor<?> DATA_MAP_CTOR;

    static {
        try {
            // List$.MODULE$.from(Iterable)
            Class<?> listCompanionClass = Class.forName("scalus.cardano.onchain.plutus.prelude.List$");
            LIST_COMPANION = listCompanionClass.getField("MODULE$").get(null);
            LIST_FROM = listCompanionClass.getMethod("from", Iterable.class);

            // Data.Constr(BigInt, List), Data.List(List), Data.Map(List)
            Class<?> scalusListClass = Class.forName("scalus.cardano.onchain.plutus.prelude.List");
            DATA_CONSTR_CTOR = Data.Constr.class.getConstructor(scala.math.BigInt.class, scalusListClass);
            DATA_LIST_CTOR = Data.List.class.getConstructor(scalusListClass);
            DATA_MAP_CTOR = Data.Map.class.getConstructor(scalusListClass);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private DataConverter() {}

    /**
     * Convert a plutus-java PlutusData to a Scalus Data object.
     */
    static Data toScalus(PlutusData data) {
        try {
            return switch (data) {
                case PlutusData.ConstrData c -> {
                    var scalusArgs = new ArrayList<Data>();
                    for (var field : c.fields()) {
                        scalusArgs.add(toScalus(field));
                    }
                    Object scalusList = LIST_FROM.invoke(LIST_COMPANION, (Iterable<Data>) scalusArgs);
                    yield (Data.Constr) DATA_CONSTR_CTOR.newInstance(
                            scala.math.BigInt.apply(c.tag()), scalusList);
                }
                case PlutusData.IntData i -> new Data.I(new scala.math.BigInt(i.value()));
                case PlutusData.BytesData b -> new Data.B(new ByteString(b.value()));
                case PlutusData.ListData l -> {
                    var scalusItems = new ArrayList<Data>();
                    for (var item : l.items()) {
                        scalusItems.add(toScalus(item));
                    }
                    Object scalusList = LIST_FROM.invoke(LIST_COMPANION, (Iterable<Data>) scalusItems);
                    yield (Data.List) DATA_LIST_CTOR.newInstance(scalusList);
                }
                case PlutusData.MapData m -> {
                    var scalusPairs = new ArrayList<scala.Tuple2<Data, Data>>();
                    for (var entry : m.entries()) {
                        scalusPairs.add(new scala.Tuple2<>(toScalus(entry.key()), toScalus(entry.value())));
                    }
                    Object scalusList = LIST_FROM.invoke(LIST_COMPANION, (Iterable<?>) scalusPairs);
                    yield (Data.Map) DATA_MAP_CTOR.newInstance(scalusList);
                }
            };
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert PlutusData to Scalus Data", e);
        }
    }
}
