package com.bloxbean.cardano.julc.ledger;

import com.bloxbean.cardano.julc.core.PlutusData;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the new ScriptContextBuilder factory methods (rewarding, certifying, voting, proposing).
 */
class ScriptContextBuilderTest {

    @Test
    void rewardingContextHasTag2() {
        var cred = new Credential.ScriptCredential(new ScriptHash(new byte[28]));
        var ctx = ScriptContextBuilder.rewarding(cred).build();
        var data = ctx.toPlutusData();

        // ScriptContext = Constr(0, [txInfo, redeemer, scriptInfo])
        var constr = (PlutusData.ConstrData) data;
        assertEquals(0, constr.tag());

        // scriptInfo = field 2
        var scriptInfo = (PlutusData.ConstrData) constr.fields().get(2);
        assertEquals(2, scriptInfo.tag(), "RewardingScript should have tag 2");
    }

    @Test
    void certifyingContextHasTag3() {
        var cred = new Credential.PubKeyCredential(new PubKeyHash(new byte[28]));
        var cert = new TxCert.RegStaking(cred, Optional.of(BigInteger.valueOf(2_000_000)));
        var ctx = ScriptContextBuilder.certifying(BigInteger.ZERO, cert).build();
        var data = ctx.toPlutusData();

        var constr = (PlutusData.ConstrData) data;
        var scriptInfo = (PlutusData.ConstrData) constr.fields().get(2);
        assertEquals(3, scriptInfo.tag(), "CertifyingScript should have tag 3");

        // Verify index field
        var indexData = (PlutusData.IntData) scriptInfo.fields().get(0);
        assertEquals(BigInteger.ZERO, indexData.value());
    }

    @Test
    void votingContextHasTag4() {
        var voter = new Voter.DRepVoter(
                new Credential.PubKeyCredential(new PubKeyHash(new byte[28])));
        var ctx = ScriptContextBuilder.voting(voter).build();
        var data = ctx.toPlutusData();

        var constr = (PlutusData.ConstrData) data;
        var scriptInfo = (PlutusData.ConstrData) constr.fields().get(2);
        assertEquals(4, scriptInfo.tag(), "VotingScript should have tag 4");
    }

    @Test
    void proposingContextHasTag5() {
        var cred = new Credential.PubKeyCredential(new PubKeyHash(new byte[28]));
        var procedure = new ProposalProcedure(
                BigInteger.valueOf(500_000_000),
                cred,
                new GovernanceAction.InfoAction());
        var ctx = ScriptContextBuilder.proposing(BigInteger.ZERO, procedure).build();
        var data = ctx.toPlutusData();

        var constr = (PlutusData.ConstrData) data;
        var scriptInfo = (PlutusData.ConstrData) constr.fields().get(2);
        assertEquals(5, scriptInfo.tag(), "ProposingScript should have tag 5");
    }

    @Test
    void contextRoundtrips() {
        var cred = new Credential.ScriptCredential(new ScriptHash(new byte[28]));
        var original = ScriptContextBuilder.rewarding(cred)
                .fee(BigInteger.valueOf(200_000))
                .build();

        var data = original.toPlutusData();
        var decoded = ScriptContext.fromPlutusData(data);

        assertNotNull(decoded);
        assertEquals(BigInteger.valueOf(200_000), decoded.txInfo().fee());

        var decodedInfo = decoded.scriptInfo();
        assertInstanceOf(ScriptInfo.RewardingScript.class, decodedInfo);
    }

    @Test
    void contextWithWithdrawals() {
        var cred = new Credential.ScriptCredential(new ScriptHash(new byte[28]));
        var ctx = ScriptContextBuilder.rewarding(cred)
                .withdrawal(cred, BigInteger.ZERO)
                .build();

        assertNotNull(ctx);
        assertFalse(ctx.txInfo().withdrawals().isEmpty());
    }

    @Test
    void spendingContextStillWorks() {
        var ref = new TxOutRef(new TxId(new byte[32]), BigInteger.ZERO);
        var ctx = ScriptContextBuilder.spending(ref).build();
        var data = ctx.toPlutusData();

        var constr = (PlutusData.ConstrData) data;
        var scriptInfo = (PlutusData.ConstrData) constr.fields().get(2);
        assertEquals(1, scriptInfo.tag(), "SpendingScript should have tag 1");
    }

    @Test
    void mintingContextStillWorks() {
        var ctx = ScriptContextBuilder.minting(new PolicyId(new byte[28])).build();
        var data = ctx.toPlutusData();

        var constr = (PlutusData.ConstrData) data;
        var scriptInfo = (PlutusData.ConstrData) constr.fields().get(2);
        assertEquals(0, scriptInfo.tag(), "MintingScript should have tag 0");
    }
}
