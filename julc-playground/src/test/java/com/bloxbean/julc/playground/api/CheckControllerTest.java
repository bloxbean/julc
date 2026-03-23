package com.bloxbean.julc.playground.api;

import com.bloxbean.cardano.julc.jrl.JrlCompiler;
import com.bloxbean.julc.playground.model.CheckRequest;
import com.bloxbean.julc.playground.model.CheckResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CheckControllerTest {

    static final ObjectMapper mapper = new ObjectMapper();
    static final JrlCompiler compiler = new JrlCompiler();
    static final CheckController controller = new CheckController(compiler);

    Javalin app() {
        return Javalin.create().post("/api/check", controller::handle);
    }

    @Test
    void validContract_returnsMetadata() {
        JavalinTest.test(app(), (server, client) -> {
            var body = mapper.writeValueAsString(new CheckRequest("""
                    contract "SimpleTransfer" version "1.0" purpose spending
                    params:
                        receiver : PubKeyHash
                    rule "Receiver can spend"
                    when
                        Transaction( signedBy: receiver )
                    then allow
                    default: deny
                    """));
            var res = client.post("/api/check", body);
            assertEquals(200, res.code());

            var json = mapper.readValue(res.body().string(), CheckResponse.class);
            assertTrue(json.valid());
            assertEquals("SimpleTransfer", json.contractName());
            assertEquals("SPENDING", json.purpose());
            assertEquals(1, json.params().size());
            assertEquals("receiver", json.params().getFirst().name());
            assertEquals("PubKeyHash", json.params().getFirst().type());
            assertTrue(json.diagnostics().isEmpty());
        });
    }

    @Test
    void contractWithDatum_returnsDatumFields() {
        JavalinTest.test(app(), (server, client) -> {
            var body = mapper.writeValueAsString(new CheckRequest("""
                    contract "Vesting" version "1.0" purpose spending
                    datum VestingDatum:
                        owner       : PubKeyHash
                        beneficiary : PubKeyHash
                        deadline    : POSIXTime
                    rule "Owner can withdraw"
                    when
                        Datum( VestingDatum( owner: $owner ) )
                        Transaction( signedBy: $owner )
                    then allow
                    default: deny
                    """));
            var res = client.post("/api/check", body);
            var json = mapper.readValue(res.body().string(), CheckResponse.class);
            assertTrue(json.valid());
            assertEquals("VestingDatum", json.datumName());
            assertEquals(3, json.datumFields().size());
            assertEquals("owner", json.datumFields().get(0).name());
            assertEquals("deadline", json.datumFields().get(2).name());
            assertEquals("POSIXTime", json.datumFields().get(2).type());
        });
    }

    @Test
    void contractWithRedeemerVariants_returnsVariants() {
        JavalinTest.test(app(), (server, client) -> {
            var body = mapper.writeValueAsString(new CheckRequest("""
                    contract "HTLC" version "1.0" purpose spending
                    params:
                        secretHash : ByteString
                    redeemer HtlcAction:
                        | Guess:
                            answer : ByteString
                        | Withdraw
                    rule "Guess"
                    when
                        Redeemer( Guess( answer: $a ) )
                        Condition( sha2_256($a) == secretHash )
                    then allow
                    default: deny
                    """));
            var res = client.post("/api/check", body);
            var json = mapper.readValue(res.body().string(), CheckResponse.class);
            assertTrue(json.valid());
            assertEquals(2, json.redeemerVariants().size());
            assertEquals("Guess", json.redeemerVariants().get(0).name());
            assertEquals(0, json.redeemerVariants().get(0).tag());
            assertEquals(1, json.redeemerVariants().get(0).fields().size());
            assertEquals("Withdraw", json.redeemerVariants().get(1).name());
            assertEquals(1, json.redeemerVariants().get(1).tag());
            assertTrue(json.redeemerVariants().get(1).fields().isEmpty());
        });
    }

    @Test
    void invalidContract_returnsErrors() {
        JavalinTest.test(app(), (server, client) -> {
            var body = mapper.writeValueAsString(new CheckRequest("""
                    contract "Bad" version "1.0" purpose spending
                    rule "Missing when"
                    then allow
                    """));
            var res = client.post("/api/check", body);
            var json = mapper.readValue(res.body().string(), CheckResponse.class);
            assertFalse(json.valid());
            assertFalse(json.diagnostics().isEmpty());
        });
    }

    @Test
    void emptySource_returnsError() {
        JavalinTest.test(app(), (server, client) -> {
            var body = mapper.writeValueAsString(new CheckRequest(""));
            var res = client.post("/api/check", body);
            var json = mapper.readValue(res.body().string(), CheckResponse.class);
            assertFalse(json.valid());
        });
    }
}
