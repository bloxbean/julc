package com.bloxbean.cardano.julc.ledger;

import com.bloxbean.cardano.julc.core.PlutusData;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

/**
 * Information about the currently executing script (6 variants).
 */
public sealed interface ScriptInfo extends PlutusDataConvertible {

    record MintingScript(PolicyId policyId) implements ScriptInfo {
        @Override
        public PlutusData toPlutusData() {
            return new PlutusData.Constr(0, List.of(policyId.toPlutusData()));
        }
    }

    record SpendingScript(TxOutRef txOutRef, Optional<PlutusData> datum) implements ScriptInfo {
        @Override
        public PlutusData toPlutusData() {
            return new PlutusData.Constr(1, List.of(
                    txOutRef.toPlutusData(),
                    PlutusDataHelper.encodeOptional(datum, d -> d)));
        }
    }

    record RewardingScript(Credential credential) implements ScriptInfo {
        @Override
        public PlutusData toPlutusData() {
            return new PlutusData.Constr(2, List.of(credential.toPlutusData()));
        }
    }

    record CertifyingScript(BigInteger index, TxCert cert) implements ScriptInfo {
        @Override
        public PlutusData toPlutusData() {
            return new PlutusData.Constr(3, List.of(
                    new PlutusData.IntData(index),
                    cert.toPlutusData()));
        }
    }

    record VotingScript(Voter voter) implements ScriptInfo {
        @Override
        public PlutusData toPlutusData() {
            return new PlutusData.Constr(4, List.of(voter.toPlutusData()));
        }
    }

    record ProposingScript(BigInteger index, ProposalProcedure procedure) implements ScriptInfo {
        @Override
        public PlutusData toPlutusData() {
            return new PlutusData.Constr(5, List.of(
                    new PlutusData.IntData(index),
                    procedure.toPlutusData()));
        }
    }

    static ScriptInfo fromPlutusData(PlutusData data) {
        var c = PlutusDataHelper.expectConstr(data);
        var f = c.fields();
        return switch (c.tag()) {
            case 0 -> new MintingScript(PolicyId.fromPlutusData(f.get(0)));
            case 1 -> new SpendingScript(TxOutRef.fromPlutusData(f.get(0)),
                    PlutusDataHelper.decodeOptional(f.get(1), d -> d));
            case 2 -> new RewardingScript(Credential.fromPlutusData(f.get(0)));
            case 3 -> new CertifyingScript(PlutusDataHelper.decodeInteger(f.get(0)),
                    TxCert.fromPlutusData(f.get(1)));
            case 4 -> new VotingScript(Voter.fromPlutusData(f.get(0)));
            case 5 -> new ProposingScript(PlutusDataHelper.decodeInteger(f.get(0)),
                    ProposalProcedure.fromPlutusData(f.get(1)));
            default -> throw new IllegalArgumentException("Invalid ScriptInfo tag: " + c.tag());
        };
    }
}
