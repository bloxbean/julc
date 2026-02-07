package com.bloxbean.cardano.plutus.ledger;

import com.bloxbean.cardano.plutus.core.PlutusData;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A governance action (7 variants).
 */
public sealed interface GovernanceAction extends PlutusDataConvertible {

    record ParameterChange(Optional<GovernanceActionId> id, PlutusData parameters,
                           Optional<ScriptHash> constitutionScript) implements GovernanceAction {
        @Override
        public PlutusData toPlutusData() {
            return new PlutusData.Constr(0, List.of(
                    PlutusDataHelper.encodeOptional(id, GovernanceActionId::toPlutusData),
                    parameters,
                    PlutusDataHelper.encodeOptional(constitutionScript, ScriptHash::toPlutusData)));
        }
    }

    record HardForkInitiation(Optional<GovernanceActionId> id,
                              ProtocolVersion protocolVersion) implements GovernanceAction {
        @Override
        public PlutusData toPlutusData() {
            return new PlutusData.Constr(1, List.of(
                    PlutusDataHelper.encodeOptional(id, GovernanceActionId::toPlutusData),
                    protocolVersion.toPlutusData()));
        }
    }

    record TreasuryWithdrawals(Map<Credential, BigInteger> withdrawals,
                               Optional<ScriptHash> constitutionScript) implements GovernanceAction {
        @Override
        public PlutusData toPlutusData() {
            return new PlutusData.Constr(2, List.of(
                    PlutusDataHelper.encodeMap(withdrawals,
                            Credential::toPlutusData, PlutusDataHelper::encodeInteger),
                    PlutusDataHelper.encodeOptional(constitutionScript, ScriptHash::toPlutusData)));
        }
    }

    record NoConfidence(Optional<GovernanceActionId> id) implements GovernanceAction {
        @Override
        public PlutusData toPlutusData() {
            return new PlutusData.Constr(3, List.of(
                    PlutusDataHelper.encodeOptional(id, GovernanceActionId::toPlutusData)));
        }
    }

    record UpdateCommittee(Optional<GovernanceActionId> id, List<Credential> removedMembers,
                           Map<Credential, BigInteger> addedMembers, Rational newQuorum) implements GovernanceAction {
        @Override
        public PlutusData toPlutusData() {
            return new PlutusData.Constr(4, List.of(
                    PlutusDataHelper.encodeOptional(id, GovernanceActionId::toPlutusData),
                    PlutusDataHelper.encodeList(removedMembers, Credential::toPlutusData),
                    PlutusDataHelper.encodeMap(addedMembers,
                            Credential::toPlutusData, PlutusDataHelper::encodeInteger),
                    newQuorum.toPlutusData()));
        }
    }

    record NewConstitution(Optional<GovernanceActionId> id,
                           Optional<ScriptHash> constitution) implements GovernanceAction {
        @Override
        public PlutusData toPlutusData() {
            return new PlutusData.Constr(5, List.of(
                    PlutusDataHelper.encodeOptional(id, GovernanceActionId::toPlutusData),
                    PlutusDataHelper.encodeOptional(constitution, ScriptHash::toPlutusData)));
        }
    }

    record InfoAction() implements GovernanceAction {
        @Override
        public PlutusData toPlutusData() {
            return new PlutusData.Constr(6, List.of());
        }
    }

    static GovernanceAction fromPlutusData(PlutusData data) {
        var c = PlutusDataHelper.expectConstr(data);
        var f = c.fields();
        return switch (c.tag()) {
            case 0 -> new ParameterChange(
                    PlutusDataHelper.decodeOptional(f.get(0), GovernanceActionId::fromPlutusData),
                    f.get(1),
                    PlutusDataHelper.decodeOptional(f.get(2), ScriptHash::fromPlutusData));
            case 1 -> new HardForkInitiation(
                    PlutusDataHelper.decodeOptional(f.get(0), GovernanceActionId::fromPlutusData),
                    ProtocolVersion.fromPlutusData(f.get(1)));
            case 2 -> new TreasuryWithdrawals(
                    PlutusDataHelper.decodeMap(f.get(0), Credential::fromPlutusData, PlutusDataHelper::decodeInteger),
                    PlutusDataHelper.decodeOptional(f.get(1), ScriptHash::fromPlutusData));
            case 3 -> new NoConfidence(
                    PlutusDataHelper.decodeOptional(f.get(0), GovernanceActionId::fromPlutusData));
            case 4 -> new UpdateCommittee(
                    PlutusDataHelper.decodeOptional(f.get(0), GovernanceActionId::fromPlutusData),
                    PlutusDataHelper.decodeList(f.get(1), Credential::fromPlutusData),
                    PlutusDataHelper.decodeMap(f.get(2), Credential::fromPlutusData, PlutusDataHelper::decodeInteger),
                    Rational.fromPlutusData(f.get(3)));
            case 5 -> new NewConstitution(
                    PlutusDataHelper.decodeOptional(f.get(0), GovernanceActionId::fromPlutusData),
                    PlutusDataHelper.decodeOptional(f.get(1), ScriptHash::fromPlutusData));
            case 6 -> new InfoAction();
            default -> throw new IllegalArgumentException("Invalid GovernanceAction tag: " + c.tag());
        };
    }
}
