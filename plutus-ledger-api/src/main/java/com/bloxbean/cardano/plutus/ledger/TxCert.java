package com.bloxbean.cardano.plutus.ledger;

import com.bloxbean.cardano.plutus.core.PlutusData;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

/**
 * A transaction certificate (V3 Conway era, 11 variants).
 */
public sealed interface TxCert extends PlutusDataConvertible {

    record RegStaking(Credential credential, Optional<BigInteger> deposit) implements TxCert {
        @Override
        public PlutusData toPlutusData() {
            return new PlutusData.Constr(0, List.of(
                    credential.toPlutusData(),
                    PlutusDataHelper.encodeOptional(deposit, PlutusDataHelper::encodeInteger)));
        }
    }

    record UnRegStaking(Credential credential, Optional<BigInteger> refund) implements TxCert {
        @Override
        public PlutusData toPlutusData() {
            return new PlutusData.Constr(1, List.of(
                    credential.toPlutusData(),
                    PlutusDataHelper.encodeOptional(refund, PlutusDataHelper::encodeInteger)));
        }
    }

    record DelegStaking(Credential credential, Delegatee delegatee) implements TxCert {
        @Override
        public PlutusData toPlutusData() {
            return new PlutusData.Constr(2, List.of(
                    credential.toPlutusData(),
                    delegatee.toPlutusData()));
        }
    }

    record RegDeleg(Credential credential, Delegatee delegatee, BigInteger deposit) implements TxCert {
        @Override
        public PlutusData toPlutusData() {
            return new PlutusData.Constr(3, List.of(
                    credential.toPlutusData(),
                    delegatee.toPlutusData(),
                    new PlutusData.IntData(deposit)));
        }
    }

    record RegDRep(Credential credential, BigInteger deposit) implements TxCert {
        @Override
        public PlutusData toPlutusData() {
            return new PlutusData.Constr(4, List.of(
                    credential.toPlutusData(),
                    new PlutusData.IntData(deposit)));
        }
    }

    record UpdateDRep(Credential credential) implements TxCert {
        @Override
        public PlutusData toPlutusData() {
            return new PlutusData.Constr(5, List.of(credential.toPlutusData()));
        }
    }

    record UnRegDRep(Credential credential, BigInteger refund) implements TxCert {
        @Override
        public PlutusData toPlutusData() {
            return new PlutusData.Constr(6, List.of(
                    credential.toPlutusData(),
                    new PlutusData.IntData(refund)));
        }
    }

    record PoolRegister(PubKeyHash poolId, PubKeyHash poolVfr) implements TxCert {
        @Override
        public PlutusData toPlutusData() {
            return new PlutusData.Constr(7, List.of(
                    poolId.toPlutusData(),
                    poolVfr.toPlutusData()));
        }
    }

    record PoolRetire(PubKeyHash pubKeyHash, BigInteger epoch) implements TxCert {
        @Override
        public PlutusData toPlutusData() {
            return new PlutusData.Constr(8, List.of(
                    pubKeyHash.toPlutusData(),
                    new PlutusData.IntData(epoch)));
        }
    }

    record AuthHotCommittee(Credential cold, Credential hot) implements TxCert {
        @Override
        public PlutusData toPlutusData() {
            return new PlutusData.Constr(9, List.of(
                    cold.toPlutusData(),
                    hot.toPlutusData()));
        }
    }

    record ResignColdCommittee(Credential cold) implements TxCert {
        @Override
        public PlutusData toPlutusData() {
            return new PlutusData.Constr(10, List.of(cold.toPlutusData()));
        }
    }

    static TxCert fromPlutusData(PlutusData data) {
        var c = PlutusDataHelper.expectConstr(data);
        var f = c.fields();
        return switch (c.tag()) {
            case 0 -> new RegStaking(Credential.fromPlutusData(f.get(0)),
                    PlutusDataHelper.decodeOptional(f.get(1), PlutusDataHelper::decodeInteger));
            case 1 -> new UnRegStaking(Credential.fromPlutusData(f.get(0)),
                    PlutusDataHelper.decodeOptional(f.get(1), PlutusDataHelper::decodeInteger));
            case 2 -> new DelegStaking(Credential.fromPlutusData(f.get(0)),
                    Delegatee.fromPlutusData(f.get(1)));
            case 3 -> new RegDeleg(Credential.fromPlutusData(f.get(0)),
                    Delegatee.fromPlutusData(f.get(1)),
                    PlutusDataHelper.decodeInteger(f.get(2)));
            case 4 -> new RegDRep(Credential.fromPlutusData(f.get(0)),
                    PlutusDataHelper.decodeInteger(f.get(1)));
            case 5 -> new UpdateDRep(Credential.fromPlutusData(f.get(0)));
            case 6 -> new UnRegDRep(Credential.fromPlutusData(f.get(0)),
                    PlutusDataHelper.decodeInteger(f.get(1)));
            case 7 -> new PoolRegister(PubKeyHash.fromPlutusData(f.get(0)),
                    PubKeyHash.fromPlutusData(f.get(1)));
            case 8 -> new PoolRetire(PubKeyHash.fromPlutusData(f.get(0)),
                    PlutusDataHelper.decodeInteger(f.get(1)));
            case 9 -> new AuthHotCommittee(Credential.fromPlutusData(f.get(0)),
                    Credential.fromPlutusData(f.get(1)));
            case 10 -> new ResignColdCommittee(Credential.fromPlutusData(f.get(0)));
            default -> throw new IllegalArgumentException("Invalid TxCert tag: " + c.tag());
        };
    }
}
