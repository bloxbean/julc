package com.bloxbean.cardano.julc.clientlib;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.Tuple2;
import com.bloxbean.cardano.julc.core.types.Tuple3;
import com.bloxbean.cardano.julc.ledger.Address;
import com.bloxbean.cardano.julc.ledger.Credential;
import com.bloxbean.cardano.julc.ledger.PubKeyHash;
import com.bloxbean.cardano.julc.stdlib.annotation.NewType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ReflectivePlutusDataConverterTest {

    // -----------------------------------------------------------------------
    // Test types — defined inline for self-contained tests
    // -----------------------------------------------------------------------

    record SimpleDatum(byte[] owner, BigInteger amount) {
        @Override
        public boolean equals(Object o) {
            return o instanceof SimpleDatum other
                    && java.util.Arrays.equals(this.owner, other.owner)
                    && this.amount.equals(other.amount);
        }

        @Override
        public int hashCode() {
            return java.util.Arrays.hashCode(owner) * 31 + amount.hashCode();
        }
    }

    record ThreeFieldRecord(BigInteger a, byte[] b, boolean c) {
        @Override
        public boolean equals(Object o) {
            return o instanceof ThreeFieldRecord other
                    && this.a.equals(other.a)
                    && java.util.Arrays.equals(this.b, other.b)
                    && this.c == other.c;
        }

        @Override
        public int hashCode() {
            return a.hashCode() * 31 + java.util.Arrays.hashCode(b) * 17 + Boolean.hashCode(c);
        }
    }

    sealed interface Action permits Bid, Close, Update {}
    record Bid(byte[] bidder, BigInteger amount) implements Action {
        @Override
        public boolean equals(Object o) {
            return o instanceof Bid other
                    && java.util.Arrays.equals(this.bidder, other.bidder)
                    && this.amount.equals(other.amount);
        }

        @Override
        public int hashCode() {
            return java.util.Arrays.hashCode(bidder) * 31 + amount.hashCode();
        }
    }
    record Close() implements Action {}
    record Update(BigInteger newPrice) implements Action {}

    @NewType
    record MyHash(byte[] hash) {
        @Override
        public boolean equals(Object o) {
            return o instanceof MyHash other && java.util.Arrays.equals(this.hash, other.hash);
        }

        @Override
        public int hashCode() {
            return java.util.Arrays.hashCode(hash);
        }
    }

    @NewType
    record TokenAmount(BigInteger value) {}

    record NestedOuter(SimpleDatum inner, BigInteger extra) {}

    record WithOptional(byte[] name, Optional<BigInteger> amount) {
        @Override
        public boolean equals(Object o) {
            return o instanceof WithOptional other
                    && java.util.Arrays.equals(this.name, other.name)
                    && this.amount.equals(other.amount);
        }

        @Override
        public int hashCode() {
            return java.util.Arrays.hashCode(name) * 31 + amount.hashCode();
        }
    }

    record WithList(List<BigInteger> values) {}

    record WithMap(Map<byte[], BigInteger> entries) {}

    record WithString(String name, BigInteger value) {}

    record WithPlutusDataField(BigInteger id, PlutusData payload) {}

    record EmptyRecord() {}

    // -----------------------------------------------------------------------
    // Encode tests
    // -----------------------------------------------------------------------

    @Nested
    class EncodeTests {

        @Test
        void encodesBigInteger() {
            var result = ReflectivePlutusDataConverter.toPlutusData(BigInteger.valueOf(42));
            assertEquals(PlutusData.integer(42), result);
        }

        @Test
        void encodesInt() {
            var result = ReflectivePlutusDataConverter.toPlutusData(7);
            assertEquals(PlutusData.integer(7), result);
        }

        @Test
        void encodesLong() {
            var result = ReflectivePlutusDataConverter.toPlutusData(100L);
            assertEquals(PlutusData.integer(100), result);
        }

        @Test
        void encodesBoolean() {
            // False → Constr(0, []), True → Constr(1, [])
            assertEquals(PlutusData.constr(0), ReflectivePlutusDataConverter.toPlutusData(false));
            assertEquals(PlutusData.constr(1), ReflectivePlutusDataConverter.toPlutusData(true));
        }

        @Test
        void encodesByteArray() {
            byte[] data = {0x01, 0x02, 0x03};
            var result = ReflectivePlutusDataConverter.toPlutusData(data);
            assertEquals(PlutusData.bytes(data), result);
        }

        @Test
        void encodesString() {
            var result = ReflectivePlutusDataConverter.toPlutusData("hello");
            assertEquals(PlutusData.bytes("hello".getBytes(StandardCharsets.UTF_8)), result);
        }

        @Test
        void encodesPlutusDataPassThrough() {
            var original = PlutusData.constr(5, PlutusData.integer(1));
            assertSame(original, ReflectivePlutusDataConverter.toPlutusData(original));
        }

        @Test
        void encodesSimpleRecord() {
            var datum = new SimpleDatum(new byte[]{0x01, 0x02}, BigInteger.TEN);
            var result = ReflectivePlutusDataConverter.toPlutusData(datum);

            assertInstanceOf(PlutusData.ConstrData.class, result);
            var constr = (PlutusData.ConstrData) result;
            assertEquals(0, constr.tag());
            assertEquals(2, constr.fields().size());
            assertEquals(PlutusData.bytes(new byte[]{0x01, 0x02}), constr.fields().get(0));
            assertEquals(PlutusData.integer(10), constr.fields().get(1));
        }

        @Test
        void encodesThreeFieldRecord() {
            var rec = new ThreeFieldRecord(BigInteger.ONE, new byte[]{(byte) 0xAB}, true);
            var result = ReflectivePlutusDataConverter.toPlutusData(rec);

            var constr = (PlutusData.ConstrData) result;
            assertEquals(0, constr.tag());
            assertEquals(3, constr.fields().size());
            assertEquals(PlutusData.integer(1), constr.fields().get(0));
            assertEquals(PlutusData.bytes(new byte[]{(byte) 0xAB}), constr.fields().get(1));
            assertEquals(PlutusData.constr(1), constr.fields().get(2)); // true
        }

        @Test
        void encodesEmptyRecord() {
            var result = ReflectivePlutusDataConverter.toPlutusData(new EmptyRecord());
            assertEquals(PlutusData.constr(0), result);
        }

        @Test
        void encodesSealedInterfaceVariants() {
            // Bid is permits[0] → tag 0
            var bid = new Bid(new byte[]{0x01}, BigInteger.valueOf(500));
            var bidResult = (PlutusData.ConstrData) ReflectivePlutusDataConverter.toPlutusData(bid);
            assertEquals(0, bidResult.tag());
            assertEquals(2, bidResult.fields().size());

            // Close is permits[1] → tag 1
            var close = new Close();
            var closeResult = (PlutusData.ConstrData) ReflectivePlutusDataConverter.toPlutusData(close);
            assertEquals(1, closeResult.tag());
            assertEquals(0, closeResult.fields().size());

            // Update is permits[2] → tag 2
            var update = new Update(BigInteger.valueOf(999));
            var updateResult = (PlutusData.ConstrData) ReflectivePlutusDataConverter.toPlutusData(update);
            assertEquals(2, updateResult.tag());
            assertEquals(1, updateResult.fields().size());
        }

        @Test
        void encodesNewTypeByteArray() {
            var hash = new MyHash(new byte[]{0x01, 0x02, 0x03});
            var result = ReflectivePlutusDataConverter.toPlutusData(hash);

            // @NewType → no ConstrData wrapper, just the underlying BytesData
            assertInstanceOf(PlutusData.BytesData.class, result);
            assertArrayEquals(new byte[]{0x01, 0x02, 0x03}, ((PlutusData.BytesData) result).value());
        }

        @Test
        void encodesNewTypeBigInteger() {
            var amount = new TokenAmount(BigInteger.valueOf(1000));
            var result = ReflectivePlutusDataConverter.toPlutusData(amount);

            assertInstanceOf(PlutusData.IntData.class, result);
            assertEquals(BigInteger.valueOf(1000), ((PlutusData.IntData) result).value());
        }

        @Test
        void encodesOptionalPresent() {
            var result = ReflectivePlutusDataConverter.toPlutusData(Optional.of(BigInteger.valueOf(42)));

            var constr = (PlutusData.ConstrData) result;
            assertEquals(0, constr.tag());  // Some
            assertEquals(1, constr.fields().size());
            assertEquals(PlutusData.integer(42), constr.fields().get(0));
        }

        @Test
        void encodesOptionalEmpty() {
            var result = ReflectivePlutusDataConverter.toPlutusData(Optional.empty());

            var constr = (PlutusData.ConstrData) result;
            assertEquals(1, constr.tag());  // None
            assertEquals(0, constr.fields().size());
        }

        @Test
        void encodesList() {
            var list = List.of(BigInteger.ONE, BigInteger.TWO, BigInteger.TEN);
            var result = ReflectivePlutusDataConverter.toPlutusData(list);

            assertInstanceOf(PlutusData.ListData.class, result);
            var ld = (PlutusData.ListData) result;
            assertEquals(3, ld.items().size());
            assertEquals(PlutusData.integer(1), ld.items().get(0));
            assertEquals(PlutusData.integer(2), ld.items().get(1));
            assertEquals(PlutusData.integer(10), ld.items().get(2));
        }

        @Test
        void encodesMap() {
            var map = new java.util.LinkedHashMap<byte[], BigInteger>();
            map.put(new byte[]{0x01}, BigInteger.ONE);
            map.put(new byte[]{0x02}, BigInteger.TWO);
            var result = ReflectivePlutusDataConverter.toPlutusData(map);

            assertInstanceOf(PlutusData.MapData.class, result);
            var md = (PlutusData.MapData) result;
            assertEquals(2, md.entries().size());
        }

        @Test
        void encodesNestedRecord() {
            var inner = new SimpleDatum(new byte[]{0x01}, BigInteger.TEN);
            var outer = new NestedOuter(inner, BigInteger.valueOf(99));
            var result = (PlutusData.ConstrData) ReflectivePlutusDataConverter.toPlutusData(outer);

            assertEquals(0, result.tag());
            assertEquals(2, result.fields().size());

            // First field is the nested record (ConstrData)
            var innerData = (PlutusData.ConstrData) result.fields().get(0);
            assertEquals(0, innerData.tag());
            assertEquals(2, innerData.fields().size());

            assertEquals(PlutusData.integer(99), result.fields().get(1));
        }

        @Test
        void encodesTuple2() {
            var tuple = new Tuple2<>(BigInteger.ONE, new byte[]{0x02});
            var result = (PlutusData.ConstrData) ReflectivePlutusDataConverter.toPlutusData(tuple);

            assertEquals(0, result.tag());
            assertEquals(2, result.fields().size());
            assertEquals(PlutusData.integer(1), result.fields().get(0));
            assertEquals(PlutusData.bytes(new byte[]{0x02}), result.fields().get(1));
        }

        @Test
        void encodesTuple3() {
            var tuple = new Tuple3<>(BigInteger.ONE, BigInteger.TWO, BigInteger.TEN);
            var result = (PlutusData.ConstrData) ReflectivePlutusDataConverter.toPlutusData(tuple);

            assertEquals(0, result.tag());
            assertEquals(3, result.fields().size());
        }

        @Test
        void encodesPlutusDataConvertible() {
            byte[] hash = new byte[28];
            hash[0] = 0x01;
            var pkh = PubKeyHash.of(hash);
            var result = ReflectivePlutusDataConverter.toPlutusData(pkh);

            // PubKeyHash.toPlutusData() returns BytesData
            assertInstanceOf(PlutusData.BytesData.class, result);
            assertArrayEquals(hash, ((PlutusData.BytesData) result).value());
        }

        @Test
        void encodesRecordWithPlutusDataField() {
            var payload = PlutusData.list(PlutusData.integer(1), PlutusData.integer(2));
            var rec = new WithPlutusDataField(BigInteger.valueOf(5), payload);
            var result = (PlutusData.ConstrData) ReflectivePlutusDataConverter.toPlutusData(rec);

            assertEquals(0, result.tag());
            assertEquals(2, result.fields().size());
            assertEquals(PlutusData.integer(5), result.fields().get(0));
            assertEquals(payload, result.fields().get(1));
        }

        @Test
        void throwsOnNull() {
            assertThrows(IllegalArgumentException.class,
                    () -> ReflectivePlutusDataConverter.toPlutusData(null));
        }

        @Test
        void throwsOnUnsupportedType() {
            assertThrows(IllegalArgumentException.class,
                    () -> ReflectivePlutusDataConverter.toPlutusData(new Object()));
        }
    }

    // -----------------------------------------------------------------------
    // Decode tests
    // -----------------------------------------------------------------------

    @Nested
    class DecodeTests {

        @Test
        void decodesBigInteger() {
            var data = PlutusData.integer(42);
            assertEquals(BigInteger.valueOf(42),
                    ReflectivePlutusDataConverter.fromPlutusData(data, BigInteger.class));
        }

        @Test
        void decodesInt() {
            var data = PlutusData.integer(7);
            assertEquals(7, ReflectivePlutusDataConverter.fromPlutusData(data, Integer.class));
        }

        @Test
        void decodesLong() {
            var data = PlutusData.integer(100);
            assertEquals(100L, ReflectivePlutusDataConverter.fromPlutusData(data, Long.class));
        }

        @Test
        void decodesBoolean() {
            assertEquals(false, ReflectivePlutusDataConverter.fromPlutusData(PlutusData.constr(0), Boolean.class));
            assertEquals(true, ReflectivePlutusDataConverter.fromPlutusData(PlutusData.constr(1), Boolean.class));
        }

        @Test
        void decodesByteArray() {
            var data = PlutusData.bytes(new byte[]{0x01, 0x02});
            assertArrayEquals(new byte[]{0x01, 0x02},
                    ReflectivePlutusDataConverter.fromPlutusData(data, byte[].class));
        }

        @Test
        void decodesString() {
            var data = PlutusData.bytes("hello".getBytes(StandardCharsets.UTF_8));
            assertEquals("hello", ReflectivePlutusDataConverter.fromPlutusData(data, String.class));
        }

        @Test
        void decodesPlutusDataPassThrough() {
            var original = PlutusData.constr(5, PlutusData.integer(1));
            assertSame(original,
                    ReflectivePlutusDataConverter.fromPlutusData(original, PlutusData.class));
        }

        @Test
        void decodesSimpleRecord() {
            var data = PlutusData.constr(0,
                    PlutusData.bytes(new byte[]{0x01, 0x02}),
                    PlutusData.integer(10));
            var result = ReflectivePlutusDataConverter.fromPlutusData(data, SimpleDatum.class);

            assertArrayEquals(new byte[]{0x01, 0x02}, result.owner());
            assertEquals(BigInteger.TEN, result.amount());
        }

        @Test
        void decodesEmptyRecord() {
            var data = PlutusData.constr(0);
            var result = ReflectivePlutusDataConverter.fromPlutusData(data, EmptyRecord.class);
            assertNotNull(result);
        }

        @Test
        void decodesSealedInterface() {
            // Bid is tag 0
            var bidData = PlutusData.constr(0,
                    PlutusData.bytes(new byte[]{0x01}),
                    PlutusData.integer(500));
            Action bid = ReflectivePlutusDataConverter.fromPlutusData(bidData, Action.class);
            assertInstanceOf(Bid.class, bid);
            assertEquals(BigInteger.valueOf(500), ((Bid) bid).amount());

            // Close is tag 1
            var closeData = PlutusData.constr(1);
            Action close = ReflectivePlutusDataConverter.fromPlutusData(closeData, Action.class);
            assertInstanceOf(Close.class, close);

            // Update is tag 2
            var updateData = PlutusData.constr(2, PlutusData.integer(999));
            Action update = ReflectivePlutusDataConverter.fromPlutusData(updateData, Action.class);
            assertInstanceOf(Update.class, update);
            assertEquals(BigInteger.valueOf(999), ((Update) update).newPrice());
        }

        @Test
        void decodesNewTypeByteArray() {
            var data = PlutusData.bytes(new byte[]{0x01, 0x02, 0x03});
            var result = ReflectivePlutusDataConverter.fromPlutusData(data, MyHash.class);
            assertArrayEquals(new byte[]{0x01, 0x02, 0x03}, result.hash());
        }

        @Test
        void decodesNewTypeBigInteger() {
            var data = PlutusData.integer(1000);
            var result = ReflectivePlutusDataConverter.fromPlutusData(data, TokenAmount.class);
            assertEquals(BigInteger.valueOf(1000), result.value());
        }

        @Test
        void decodesNestedRecord() {
            var data = PlutusData.constr(0,
                    PlutusData.constr(0,
                            PlutusData.bytes(new byte[]{0x01}),
                            PlutusData.integer(10)),
                    PlutusData.integer(99));
            var result = ReflectivePlutusDataConverter.fromPlutusData(data, NestedOuter.class);

            assertArrayEquals(new byte[]{0x01}, result.inner().owner());
            assertEquals(BigInteger.TEN, result.inner().amount());
            assertEquals(BigInteger.valueOf(99), result.extra());
        }

        @Test
        void decodesRecordWithPlutusDataField() {
            var payload = PlutusData.list(PlutusData.integer(1));
            var data = PlutusData.constr(0, PlutusData.integer(5), payload);
            var result = ReflectivePlutusDataConverter.fromPlutusData(data, WithPlutusDataField.class);

            assertEquals(BigInteger.valueOf(5), result.id());
            assertEquals(payload, result.payload());
        }

        @Test
        void decodesLedgerTypeViaFromPlutusData() {
            // PubKeyHash has static fromPlutusData(PlutusData)
            byte[] hash = new byte[28];
            hash[0] = 0x01;
            var data = PlutusData.bytes(hash);
            var result = ReflectivePlutusDataConverter.fromPlutusData(data, PubKeyHash.class);
            assertArrayEquals(hash, result.hash());
        }

        @Test
        void throwsOnNullData() {
            assertThrows(IllegalArgumentException.class,
                    () -> ReflectivePlutusDataConverter.fromPlutusData(null, SimpleDatum.class));
        }

        @Test
        void throwsOnFieldCountMismatch() {
            // SimpleDatum expects 2 fields, we provide 1
            var data = PlutusData.constr(0, PlutusData.bytes(new byte[]{0x01}));
            assertThrows(IllegalArgumentException.class,
                    () -> ReflectivePlutusDataConverter.fromPlutusData(data, SimpleDatum.class));
        }

        @Test
        void throwsOnTagOutOfRange() {
            var data = PlutusData.constr(99); // Action only has tags 0-2
            assertThrows(IllegalArgumentException.class,
                    () -> ReflectivePlutusDataConverter.fromPlutusData(data, Action.class));
        }

        @Test
        void throwsOnUnsupportedType() {
            var data = PlutusData.integer(1);
            assertThrows(IllegalArgumentException.class,
                    () -> ReflectivePlutusDataConverter.fromPlutusData(data, Object.class));
        }
    }

    // -----------------------------------------------------------------------
    // Round-trip tests
    // -----------------------------------------------------------------------

    @Nested
    class RoundTripTests {

        @Test
        void roundTripBigInteger() {
            var original = BigInteger.valueOf(123456789L);
            var data = ReflectivePlutusDataConverter.toPlutusData(original);
            var restored = ReflectivePlutusDataConverter.fromPlutusData(data, BigInteger.class);
            assertEquals(original, restored);
        }

        @Test
        void roundTripBoolean() {
            for (boolean b : new boolean[]{true, false}) {
                var data = ReflectivePlutusDataConverter.toPlutusData(b);
                assertEquals(b, ReflectivePlutusDataConverter.fromPlutusData(data, Boolean.class));
            }
        }

        @Test
        void roundTripByteArray() {
            var original = new byte[]{0x01, 0x02, (byte) 0xFF};
            var data = ReflectivePlutusDataConverter.toPlutusData(original);
            assertArrayEquals(original, ReflectivePlutusDataConverter.fromPlutusData(data, byte[].class));
        }

        @Test
        void roundTripString() {
            var data = ReflectivePlutusDataConverter.toPlutusData("Cardano");
            assertEquals("Cardano", ReflectivePlutusDataConverter.fromPlutusData(data, String.class));
        }

        @Test
        void roundTripSimpleRecord() {
            var original = new SimpleDatum(new byte[]{0x01, 0x02}, BigInteger.valueOf(42));
            var data = ReflectivePlutusDataConverter.toPlutusData(original);
            var restored = ReflectivePlutusDataConverter.fromPlutusData(data, SimpleDatum.class);
            assertEquals(original, restored);
        }

        @Test
        void roundTripSealedVariants() {
            var bid = new Bid(new byte[]{0x01}, BigInteger.valueOf(500));
            var bidData = ReflectivePlutusDataConverter.toPlutusData(bid);
            var restoredBid = ReflectivePlutusDataConverter.fromPlutusData(bidData, Action.class);
            assertEquals(bid, restoredBid);

            var close = new Close();
            var closeData = ReflectivePlutusDataConverter.toPlutusData(close);
            var restoredClose = ReflectivePlutusDataConverter.fromPlutusData(closeData, Action.class);
            assertInstanceOf(Close.class, restoredClose);

            var update = new Update(BigInteger.valueOf(999));
            var updateData = ReflectivePlutusDataConverter.toPlutusData(update);
            var restoredUpdate = ReflectivePlutusDataConverter.fromPlutusData(updateData, Action.class);
            assertEquals(update, restoredUpdate);
        }

        @Test
        void roundTripNewType() {
            var original = new MyHash(new byte[]{0x01, 0x02, 0x03});
            var data = ReflectivePlutusDataConverter.toPlutusData(original);
            var restored = ReflectivePlutusDataConverter.fromPlutusData(data, MyHash.class);
            assertEquals(original, restored);
        }

        @Test
        void roundTripNewTypeBigInteger() {
            var original = new TokenAmount(BigInteger.valueOf(1000));
            var data = ReflectivePlutusDataConverter.toPlutusData(original);
            var restored = ReflectivePlutusDataConverter.fromPlutusData(data, TokenAmount.class);
            assertEquals(original, restored);
        }

        @Test
        void roundTripNestedRecord() {
            var inner = new SimpleDatum(new byte[]{0x01}, BigInteger.TEN);
            var original = new NestedOuter(inner, BigInteger.valueOf(99));
            var data = ReflectivePlutusDataConverter.toPlutusData(original);
            var restored = ReflectivePlutusDataConverter.fromPlutusData(data, NestedOuter.class);

            assertEquals(original.inner(), restored.inner());
            assertEquals(original.extra(), restored.extra());
        }

        @Test
        void roundTripPubKeyHash() {
            byte[] hash = new byte[28];
            hash[0] = 0x01;
            hash[27] = (byte) 0xFF;
            var original = PubKeyHash.of(hash);
            var data = ReflectivePlutusDataConverter.toPlutusData(original);
            var restored = ReflectivePlutusDataConverter.fromPlutusData(data, PubKeyHash.class);
            assertEquals(original, restored);
        }

        @Test
        void roundTripEmptyRecord() {
            var original = new EmptyRecord();
            var data = ReflectivePlutusDataConverter.toPlutusData(original);
            var restored = ReflectivePlutusDataConverter.fromPlutusData(data, EmptyRecord.class);
            assertEquals(original, restored);
        }

        @Test
        void roundTripRecordWithString() {
            var original = new WithString("hello", BigInteger.valueOf(42));
            var data = ReflectivePlutusDataConverter.toPlutusData(original);
            var restored = ReflectivePlutusDataConverter.fromPlutusData(data, WithString.class);
            assertEquals(original, restored);
        }
    }
}
