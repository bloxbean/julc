package com.bloxbean.cardano.plutus.ledger;

import com.bloxbean.cardano.plutus.core.PlutusData;

import java.math.BigInteger;
import java.util.List;

/**
 * The purpose of a script execution (6 variants).
 */
public sealed interface ScriptPurpose extends PlutusDataConvertible {

    record Minting(PolicyId policyId) implements ScriptPurpose {
        @Override
        public PlutusData toPlutusData() {
            return new PlutusData.Constr(0, List.of(policyId.toPlutusData()));
        }
    }

    record Spending(TxOutRef txOutRef) implements ScriptPurpose {
        @Override
        public PlutusData toPlutusData() {
            return new PlutusData.Constr(1, List.of(txOutRef.toPlutusData()));
        }
    }

    record Rewarding(Credential credential) implements ScriptPurpose {
        @Override
        public PlutusData toPlutusData() {
            return new PlutusData.Constr(2, List.of(credential.toPlutusData()));
        }
    }

    record Certifying(BigInteger index, TxCert cert) implements ScriptPurpose {
        @Override
        public PlutusData toPlutusData() {
            return new PlutusData.Constr(3, List.of(
                    new PlutusData.IntData(index),
                    cert.toPlutusData()));
        }
    }

    record Voting(Voter voter) implements ScriptPurpose {
        @Override
        public PlutusData toPlutusData() {
            return new PlutusData.Constr(4, List.of(voter.toPlutusData()));
        }
    }

    record Proposing(BigInteger index, ProposalProcedure procedure) implements ScriptPurpose {
        @Override
        public PlutusData toPlutusData() {
            return new PlutusData.Constr(5, List.of(
                    new PlutusData.IntData(index),
                    procedure.toPlutusData()));
        }
    }

    static ScriptPurpose fromPlutusData(PlutusData data) {
        var c = PlutusDataHelper.expectConstr(data);
        var f = c.fields();
        return switch (c.tag()) {
            case 0 -> new Minting(PolicyId.fromPlutusData(f.get(0)));
            case 1 -> new Spending(TxOutRef.fromPlutusData(f.get(0)));
            case 2 -> new Rewarding(Credential.fromPlutusData(f.get(0)));
            case 3 -> new Certifying(PlutusDataHelper.decodeInteger(f.get(0)),
                    TxCert.fromPlutusData(f.get(1)));
            case 4 -> new Voting(Voter.fromPlutusData(f.get(0)));
            case 5 -> new Proposing(PlutusDataHelper.decodeInteger(f.get(0)),
                    ProposalProcedure.fromPlutusData(f.get(1)));
            default -> throw new IllegalArgumentException("Invalid ScriptPurpose tag: " + c.tag());
        };
    }
}
