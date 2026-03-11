package com.bloxbean.cardano.julc.clientlib;

import com.bloxbean.cardano.julc.ledger.PubKeyHash;
import com.bloxbean.cardano.julc.stdlib.annotation.NewType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PlutusDataAdapter#convert(Object)} and
 * {@link PlutusDataAdapter#convert(com.bloxbean.cardano.client.plutus.spec.PlutusData, Class)}.
 * Verifies full CCL round-trip: Java → JuLC PlutusData → CCL PlutusData → JuLC PlutusData → Java.
 */
class PlutusDataAdapterConvertTest {

    // -----------------------------------------------------------------------
    // Test types
    // -----------------------------------------------------------------------

    record AuctionDatum(byte[] seller, BigInteger deadline, BigInteger minBid) {
        @Override
        public boolean equals(Object o) {
            return o instanceof AuctionDatum other
                    && java.util.Arrays.equals(this.seller, other.seller)
                    && this.deadline.equals(other.deadline)
                    && this.minBid.equals(other.minBid);
        }

        @Override
        public int hashCode() {
            return java.util.Arrays.hashCode(seller) * 31 + deadline.hashCode() * 17 + minBid.hashCode();
        }
    }

    sealed interface Redeemer permits PlaceBid, CancelAuction {}
    record PlaceBid(byte[] bidder, BigInteger amount) implements Redeemer {
        @Override
        public boolean equals(Object o) {
            return o instanceof PlaceBid other
                    && java.util.Arrays.equals(this.bidder, other.bidder)
                    && this.amount.equals(other.amount);
        }

        @Override
        public int hashCode() {
            return java.util.Arrays.hashCode(bidder) * 31 + amount.hashCode();
        }
    }
    record CancelAuction() implements Redeemer {}

    @NewType
    record PolicyId(byte[] hash) {
        @Override
        public boolean equals(Object o) {
            return o instanceof PolicyId other && java.util.Arrays.equals(this.hash, other.hash);
        }

        @Override
        public int hashCode() {
            return java.util.Arrays.hashCode(hash);
        }
    }

    // -----------------------------------------------------------------------
    // Full CCL round-trip tests
    // -----------------------------------------------------------------------

    @Nested
    class CclRoundTrip {

        @Test
        void roundTripSimpleRecord() {
            var original = new AuctionDatum(
                    new byte[]{0x01, 0x02, 0x03},
                    BigInteger.valueOf(1000000),
                    BigInteger.valueOf(5000000));

            var cclData = PlutusDataAdapter.convert(original);
            assertNotNull(cclData);
            assertInstanceOf(com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData.class, cclData);

            var restored = PlutusDataAdapter.convert(cclData, AuctionDatum.class);
            assertEquals(original, restored);
        }

        @Test
        void roundTripSealedInterfaceVariants() {
            var bid = new PlaceBid(new byte[]{0x01}, BigInteger.valueOf(7_000_000));
            var bidCcl = PlutusDataAdapter.convert(bid);
            var restoredBid = PlutusDataAdapter.convert(bidCcl, Redeemer.class);
            assertEquals(bid, restoredBid);

            var cancel = new CancelAuction();
            var cancelCcl = PlutusDataAdapter.convert(cancel);
            var restoredCancel = PlutusDataAdapter.convert(cancelCcl, Redeemer.class);
            assertInstanceOf(CancelAuction.class, restoredCancel);
        }

        @Test
        void roundTripNewType() {
            var original = new PolicyId(new byte[]{0x01, 0x02, 0x03});
            var cclData = PlutusDataAdapter.convert(original);

            // @NewType should produce BytesPlutusData, not ConstrPlutusData
            assertInstanceOf(com.bloxbean.cardano.client.plutus.spec.BytesPlutusData.class, cclData);

            var restored = PlutusDataAdapter.convert(cclData, PolicyId.class);
            assertEquals(original, restored);
        }

        @Test
        void roundTripPrimitives() {
            // BigInteger
            var biCcl = PlutusDataAdapter.convert(BigInteger.valueOf(42));
            assertEquals(BigInteger.valueOf(42),
                    PlutusDataAdapter.convert(biCcl, BigInteger.class));

            // byte[]
            var bytesCcl = PlutusDataAdapter.convert(new byte[]{0x01, 0x02});
            assertArrayEquals(new byte[]{0x01, 0x02},
                    PlutusDataAdapter.convert(bytesCcl, byte[].class));

            // boolean
            var boolCcl = PlutusDataAdapter.convert(true);
            assertTrue(PlutusDataAdapter.convert(boolCcl, Boolean.class));
        }

        @Test
        void roundTripLedgerType() {
            byte[] hash = new byte[28];
            hash[0] = 0x01;
            hash[27] = (byte) 0xFF;
            var original = PubKeyHash.of(hash);

            var cclData = PlutusDataAdapter.convert(original);
            assertInstanceOf(com.bloxbean.cardano.client.plutus.spec.BytesPlutusData.class, cclData);

            var restored = PlutusDataAdapter.convert(cclData, PubKeyHash.class);
            assertEquals(original, restored);
        }
    }

    // -----------------------------------------------------------------------
    // Binary compatibility: manually built CCL data decoded to records
    // -----------------------------------------------------------------------

    @Nested
    class BinaryCompatibility {

        @Test
        void manuallyBuiltCclDataDecodesToRecord() {
            // Build ConstrPlutusData the old manual way
            var fields = new com.bloxbean.cardano.client.plutus.spec.ListPlutusData();
            fields.add(new com.bloxbean.cardano.client.plutus.spec.BytesPlutusData(new byte[]{0x01, 0x02, 0x03}));
            fields.add(new com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData(BigInteger.valueOf(1000000)));
            fields.add(new com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData(BigInteger.valueOf(5000000)));

            var cclData = com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData.builder()
                    .alternative(0)
                    .data(fields)
                    .build();

            var result = PlutusDataAdapter.convert(cclData, AuctionDatum.class);
            assertArrayEquals(new byte[]{0x01, 0x02, 0x03}, result.seller());
            assertEquals(BigInteger.valueOf(1000000), result.deadline());
            assertEquals(BigInteger.valueOf(5000000), result.minBid());
        }

        @Test
        void manuallyBuiltSealedVariantDecodes() {
            // PlaceBid is tag 0 in permits list
            var fields = new com.bloxbean.cardano.client.plutus.spec.ListPlutusData();
            fields.add(new com.bloxbean.cardano.client.plutus.spec.BytesPlutusData(new byte[]{0x01}));
            fields.add(new com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData(BigInteger.valueOf(7_000_000)));

            var cclData = com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData.builder()
                    .alternative(0)
                    .data(fields)
                    .build();

            var result = PlutusDataAdapter.convert(cclData, Redeemer.class);
            assertInstanceOf(PlaceBid.class, result);
            assertEquals(BigInteger.valueOf(7_000_000), ((PlaceBid) result).amount());

            // CancelAuction is tag 1
            var emptyFields = new com.bloxbean.cardano.client.plutus.spec.ListPlutusData();
            var cancelCcl = com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData.builder()
                    .alternative(1)
                    .data(emptyFields)
                    .build();

            var cancelResult = PlutusDataAdapter.convert(cancelCcl, Redeemer.class);
            assertInstanceOf(CancelAuction.class, cancelResult);
        }

        @Test
        void manuallyBuiltNewTypeDecodes() {
            var cclData = new com.bloxbean.cardano.client.plutus.spec.BytesPlutusData(
                    new byte[]{0x01, 0x02, 0x03});

            var result = PlutusDataAdapter.convert(cclData, PolicyId.class);
            assertArrayEquals(new byte[]{0x01, 0x02, 0x03}, result.hash());
        }
    }

    // -----------------------------------------------------------------------
    // Error cases
    // -----------------------------------------------------------------------

    @Nested
    class ErrorCases {

        @Test
        void convertNullObjectThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> PlutusDataAdapter.convert((Object) null));
        }

        @Test
        void convertNullCclDataThrows() {
            assertThrows(Exception.class,
                    () -> PlutusDataAdapter.convert(
                            (com.bloxbean.cardano.client.plutus.spec.PlutusData) null,
                            AuctionDatum.class));
        }
    }
}
