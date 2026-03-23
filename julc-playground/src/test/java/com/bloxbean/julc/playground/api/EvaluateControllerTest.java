package com.bloxbean.julc.playground.api;

import com.bloxbean.cardano.julc.jrl.JrlCompiler;
import com.bloxbean.julc.playground.model.EvaluateRequest;
import com.bloxbean.julc.playground.model.EvaluateResponse;
import com.bloxbean.julc.playground.model.RedeemerInput;
import com.bloxbean.julc.playground.model.ScenarioOverrides;
import com.bloxbean.julc.playground.sandbox.CompilationSandbox;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EvaluateControllerTest {

    static final ObjectMapper mapper = new ObjectMapper();
    static final JrlCompiler compiler = new JrlCompiler();
    static final CompilationSandbox sandbox = new CompilationSandbox(2, 30);
    static final EvaluateController controller = new EvaluateController(compiler, sandbox);

    static final String PKH1 = "01010101010101010101010101010101010101010101010101010101";
    static final String PKH2 = "02020202020202020202020202020202020202020202020202020202";

    @AfterAll
    static void tearDown() {
        sandbox.shutdown();
    }

    Javalin app() {
        return Javalin.create().post("/api/evaluate", controller::handle);
    }

    static final String SIMPLE_TRANSFER = """
            contract "SimpleTransfer" version "1.0" purpose spending
            params:
                receiver : PubKeyHash
            rule "Receiver can spend"
            when
                Transaction( signedBy: receiver )
            then allow
            default: deny
            """;

    @Test
    void correctSigner_succeeds() {
        JavalinTest.test(app(), (server, client) -> {
            var req = new EvaluateRequest(
                    SIMPLE_TRANSFER,
                    Map.of("receiver", PKH1),
                    new ScenarioOverrides(List.of(PKH1), null, null),
                    Map.of(),
                    null
            );
            var res = client.post("/api/evaluate", mapper.writeValueAsString(req));
            assertEquals(200, res.code());

            var json = mapper.readValue(res.body().string(), EvaluateResponse.class);
            assertTrue(json.success(), "Expected success but got: " + json.error());
            assertTrue(json.budgetCpu() > 0);
            assertTrue(json.budgetMem() > 0);
        });
    }

    @Test
    void wrongSigner_fails() {
        JavalinTest.test(app(), (server, client) -> {
            var req = new EvaluateRequest(
                    SIMPLE_TRANSFER,
                    Map.of("receiver", PKH1),
                    new ScenarioOverrides(List.of(PKH2), null, null),
                    Map.of(),
                    null
            );
            var res = client.post("/api/evaluate", mapper.writeValueAsString(req));
            var json = mapper.readValue(res.body().string(), EvaluateResponse.class);
            assertFalse(json.success());
        });
    }

    static final String VESTING = """
            contract "Vesting" version "1.0" purpose spending
            datum VestingDatum:
                owner       : PubKeyHash
                beneficiary : PubKeyHash
                deadline    : POSIXTime
            rule "Owner can always withdraw"
            when
                Datum( VestingDatum( owner: $owner ) )
                Transaction( signedBy: $owner )
            then allow
            rule "Beneficiary can withdraw after deadline"
            when
                Datum( VestingDatum( beneficiary: $ben, deadline: $deadline ) )
                Transaction( signedBy: $ben )
                Transaction( validAfter: $deadline )
            then allow
            default: deny
            """;

    @Test
    void vestingOwner_succeeds() {
        JavalinTest.test(app(), (server, client) -> {
            var req = new EvaluateRequest(
                    VESTING,
                    Map.of(),
                    new ScenarioOverrides(List.of(PKH1), null, null),
                    Map.of("owner", PKH1, "beneficiary", PKH2, "deadline", "1000"),
                    null
            );
            var res = client.post("/api/evaluate", mapper.writeValueAsString(req));
            var json = mapper.readValue(res.body().string(), EvaluateResponse.class);
            assertTrue(json.success(), "Expected success but got: " + json.error());
        });
    }

    @Test
    void vestingBeneficiaryAfterDeadline_succeeds() {
        JavalinTest.test(app(), (server, client) -> {
            var req = new EvaluateRequest(
                    VESTING,
                    Map.of(),
                    new ScenarioOverrides(List.of(PKH2), 1000L, null),
                    Map.of("owner", PKH1, "beneficiary", PKH2, "deadline", "1000"),
                    null
            );
            var res = client.post("/api/evaluate", mapper.writeValueAsString(req));
            var json = mapper.readValue(res.body().string(), EvaluateResponse.class);
            assertTrue(json.success(), "Expected success but got: " + json.error());
        });
    }

    @Test
    void vestingBeneficiaryBeforeDeadline_fails() {
        JavalinTest.test(app(), (server, client) -> {
            var req = new EvaluateRequest(
                    VESTING,
                    Map.of(),
                    new ScenarioOverrides(List.of(PKH2), null, 999L),
                    Map.of("owner", PKH1, "beneficiary", PKH2, "deadline", "1000"),
                    null
            );
            var res = client.post("/api/evaluate", mapper.writeValueAsString(req));
            var json = mapper.readValue(res.body().string(), EvaluateResponse.class);
            assertFalse(json.success());
        });
    }

    static final String HTLC = """
            contract "HTLC" version "1.0" purpose spending
            params:
                secretHash : ByteString
                expiration : POSIXTime
                owner      : PubKeyHash
            redeemer HtlcAction:
                | Guess:
                    answer : ByteString
                | Withdraw
            rule "Correct guess unlocks"
            when
                Redeemer( Guess( answer: $answer ) )
                Condition( sha2_256($answer) == secretHash )
            then allow
            rule "Owner can withdraw after expiry"
            when
                Redeemer( Withdraw )
                Transaction( signedBy: owner )
                Transaction( validAfter: expiration )
            then allow
            default: deny
            """;

    @Test
    void htlcOwnerWithdrawAfterExpiry_succeeds() {
        JavalinTest.test(app(), (server, client) -> {
            var req = new EvaluateRequest(
                    HTLC,
                    Map.of(
                            "secretHash", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                            "expiration", "5000",
                            "owner", PKH1
                    ),
                    new ScenarioOverrides(List.of(PKH1), 5000L, null),
                    Map.of(),
                    new RedeemerInput(1, Map.of())
            );
            var res = client.post("/api/evaluate", mapper.writeValueAsString(req));
            var json = mapper.readValue(res.body().string(), EvaluateResponse.class);
            assertTrue(json.success(), "Expected success but got: " + json.error());
        });
    }
}
