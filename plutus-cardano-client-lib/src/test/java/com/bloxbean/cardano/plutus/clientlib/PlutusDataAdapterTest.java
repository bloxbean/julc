package com.bloxbean.cardano.plutus.clientlib;

import com.bloxbean.cardano.plutus.core.PlutusData;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for bidirectional PlutusData conversion between
 * plutus-java and cardano-client-lib representations.
 */
class PlutusDataAdapterTest {

    // -------------------------------------------------------------------------
    // toClientLib: our PlutusData -> cardano-client-lib PlutusData
    // -------------------------------------------------------------------------

    @Nested
    class ToClientLib {

        @Test
        void convertsIntData() {
            var data = PlutusData.integer(42);
            var result = PlutusDataAdapter.toClientLib(data);

            assertInstanceOf(com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData.class, result);
            var bigInt = (com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData) result;
            assertEquals(BigInteger.valueOf(42), bigInt.getValue());
        }

        @Test
        void convertsNegativeIntData() {
            var data = PlutusData.integer(-999);
            var result = PlutusDataAdapter.toClientLib(data);

            assertInstanceOf(com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData.class, result);
            assertEquals(BigInteger.valueOf(-999),
                    ((com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData) result).getValue());
        }

        @Test
        void convertsBytesData() {
            var bytes = new byte[]{0x01, 0x02, 0x03, 0x04};
            var data = PlutusData.bytes(bytes);
            var result = PlutusDataAdapter.toClientLib(data);

            assertInstanceOf(com.bloxbean.cardano.client.plutus.spec.BytesPlutusData.class, result);
            assertArrayEquals(bytes,
                    ((com.bloxbean.cardano.client.plutus.spec.BytesPlutusData) result).getValue());
        }

        @Test
        void convertsEmptyBytesData() {
            var data = PlutusData.bytes(new byte[0]);
            var result = PlutusDataAdapter.toClientLib(data);

            assertInstanceOf(com.bloxbean.cardano.client.plutus.spec.BytesPlutusData.class, result);
            assertEquals(0,
                    ((com.bloxbean.cardano.client.plutus.spec.BytesPlutusData) result).getValue().length);
        }

        @Test
        void convertsConstrData() {
            var data = PlutusData.constr(1,
                    PlutusData.integer(10),
                    PlutusData.bytes(new byte[]{(byte) 0xAB}));
            var result = PlutusDataAdapter.toClientLib(data);

            assertInstanceOf(com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData.class, result);
            var constr = (com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData) result;
            assertEquals(1, constr.getAlternative());
            assertEquals(2, constr.getData().getPlutusDataList().size());
        }

        @Test
        void convertsConstrWithNoFields() {
            var data = PlutusData.constr(0);
            var result = PlutusDataAdapter.toClientLib(data);

            assertInstanceOf(com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData.class, result);
            var constr = (com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData) result;
            assertEquals(0, constr.getAlternative());
            assertEquals(0, constr.getData().getPlutusDataList().size());
        }

        @Test
        void convertsListData() {
            var data = PlutusData.list(
                    PlutusData.integer(1),
                    PlutusData.integer(2),
                    PlutusData.integer(3));
            var result = PlutusDataAdapter.toClientLib(data);

            assertInstanceOf(com.bloxbean.cardano.client.plutus.spec.ListPlutusData.class, result);
            var list = (com.bloxbean.cardano.client.plutus.spec.ListPlutusData) result;
            assertEquals(3, list.getPlutusDataList().size());
        }

        @Test
        void convertsMapData() {
            var data = PlutusData.map(
                    new PlutusData.Pair(PlutusData.integer(1), PlutusData.bytes(new byte[]{0x01})),
                    new PlutusData.Pair(PlutusData.integer(2), PlutusData.bytes(new byte[]{0x02})));
            var result = PlutusDataAdapter.toClientLib(data);

            assertInstanceOf(com.bloxbean.cardano.client.plutus.spec.MapPlutusData.class, result);
            var map = (com.bloxbean.cardano.client.plutus.spec.MapPlutusData) result;
            assertEquals(2, map.getMap().size());
        }

        @Test
        void convertsNestedData() {
            // Constr(0, [List[Int(1), Int(2)], Map{Int(3) -> Bytes(0xff)}])
            var nested = PlutusData.constr(0,
                    PlutusData.list(PlutusData.integer(1), PlutusData.integer(2)),
                    PlutusData.map(
                            new PlutusData.Pair(PlutusData.integer(3),
                                    PlutusData.bytes(new byte[]{(byte) 0xff}))));
            var result = PlutusDataAdapter.toClientLib(nested);

            assertInstanceOf(com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData.class, result);
            var constr = (com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData) result;
            assertEquals(2, constr.getData().getPlutusDataList().size());
            assertInstanceOf(com.bloxbean.cardano.client.plutus.spec.ListPlutusData.class,
                    constr.getData().getPlutusDataList().get(0));
            assertInstanceOf(com.bloxbean.cardano.client.plutus.spec.MapPlutusData.class,
                    constr.getData().getPlutusDataList().get(1));
        }
    }

    // -------------------------------------------------------------------------
    // fromClientLib: cardano-client-lib PlutusData -> our PlutusData
    // -------------------------------------------------------------------------

    @Nested
    class FromClientLib {

        @Test
        void convertsIntData() {
            var clientData = new com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData(BigInteger.valueOf(42));
            var result = PlutusDataAdapter.fromClientLib(clientData);

            assertInstanceOf(PlutusData.IntData.class, result);
            assertEquals(BigInteger.valueOf(42), ((PlutusData.IntData) result).value());
        }

        @Test
        void convertsBytesData() {
            var bytes = new byte[]{0x01, 0x02};
            var clientData = new com.bloxbean.cardano.client.plutus.spec.BytesPlutusData(bytes);
            var result = PlutusDataAdapter.fromClientLib(clientData);

            assertInstanceOf(PlutusData.BytesData.class, result);
            assertArrayEquals(bytes, ((PlutusData.BytesData) result).value());
        }

        @Test
        void convertsConstrData() {
            var fields = new com.bloxbean.cardano.client.plutus.spec.ListPlutusData();
            fields.add(new com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData(BigInteger.TEN));
            var clientData = com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData.builder()
                    .alternative(2)
                    .data(fields)
                    .build();
            var result = PlutusDataAdapter.fromClientLib(clientData);

            assertInstanceOf(PlutusData.Constr.class, result);
            var constr = (PlutusData.Constr) result;
            assertEquals(2, constr.tag());
            assertEquals(1, constr.fields().size());
        }

        @Test
        void convertsConstrWithNullFields() {
            var clientData = com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData.builder()
                    .alternative(0)
                    .build();
            var result = PlutusDataAdapter.fromClientLib(clientData);

            assertInstanceOf(PlutusData.Constr.class, result);
            assertEquals(0, ((PlutusData.Constr) result).fields().size());
        }

        @Test
        void convertsListData() {
            var list = new com.bloxbean.cardano.client.plutus.spec.ListPlutusData();
            list.add(new com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData(BigInteger.ONE));
            list.add(new com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData(BigInteger.TWO));
            var result = PlutusDataAdapter.fromClientLib(list);

            assertInstanceOf(PlutusData.ListData.class, result);
            assertEquals(2, ((PlutusData.ListData) result).items().size());
        }

        @Test
        void convertsMapData() {
            var map = new com.bloxbean.cardano.client.plutus.spec.MapPlutusData();
            map.put(new com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData(BigInteger.ONE),
                    new com.bloxbean.cardano.client.plutus.spec.BytesPlutusData(new byte[]{0x01}));
            var result = PlutusDataAdapter.fromClientLib(map);

            assertInstanceOf(PlutusData.Map.class, result);
            assertEquals(1, ((PlutusData.Map) result).entries().size());
        }
    }

    // -------------------------------------------------------------------------
    // Round-trip tests: our -> client-lib -> our
    // -------------------------------------------------------------------------

    @Nested
    class RoundTrip {

        @Test
        void roundTripInteger() {
            var original = PlutusData.integer(BigInteger.valueOf(Long.MAX_VALUE));
            var roundTripped = PlutusDataAdapter.fromClientLib(PlutusDataAdapter.toClientLib(original));
            assertEquals(original, roundTripped);
        }

        @Test
        void roundTripBytes() {
            var original = PlutusData.bytes(new byte[]{0x00, (byte) 0xff, 0x42});
            var roundTripped = PlutusDataAdapter.fromClientLib(PlutusDataAdapter.toClientLib(original));
            assertEquals(original, roundTripped);
        }

        @Test
        void roundTripConstr() {
            var original = PlutusData.constr(3,
                    PlutusData.integer(100),
                    PlutusData.bytes(new byte[]{0x01, 0x02}));
            var roundTripped = PlutusDataAdapter.fromClientLib(PlutusDataAdapter.toClientLib(original));
            assertEquals(original, roundTripped);
        }

        @Test
        void roundTripList() {
            var original = PlutusData.list(
                    PlutusData.integer(1),
                    PlutusData.integer(2),
                    PlutusData.integer(3));
            var roundTripped = PlutusDataAdapter.fromClientLib(PlutusDataAdapter.toClientLib(original));
            assertEquals(original, roundTripped);
        }

        @Test
        void roundTripMap() {
            var original = PlutusData.map(
                    new PlutusData.Pair(PlutusData.integer(1), PlutusData.bytes(new byte[]{0x01})));
            var roundTripped = PlutusDataAdapter.fromClientLib(PlutusDataAdapter.toClientLib(original));
            assertEquals(original, roundTripped);
        }

        @Test
        void roundTripDeeplyNested() {
            var original = PlutusData.constr(0,
                    PlutusData.list(
                            PlutusData.constr(1, PlutusData.integer(42)),
                            PlutusData.map(
                                    new PlutusData.Pair(
                                            PlutusData.bytes(new byte[]{0x01}),
                                            PlutusData.list(PlutusData.integer(1), PlutusData.integer(2))))));
            var roundTripped = PlutusDataAdapter.fromClientLib(PlutusDataAdapter.toClientLib(original));
            assertEquals(original, roundTripped);
        }
    }
}
