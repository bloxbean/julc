package com.bloxbean.julc.playground.api;

import com.bloxbean.cardano.julc.jrl.JrlCompiler;
import com.bloxbean.julc.playground.model.TranspileRequest;
import com.bloxbean.julc.playground.model.TranspileResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TranspileControllerTest {

    static final ObjectMapper mapper = new ObjectMapper();
    static final JrlCompiler compiler = new JrlCompiler();
    static final TranspileController controller = new TranspileController(compiler);

    Javalin app() {
        return Javalin.create().post("/api/transpile", controller::handle);
    }

    @Test
    void validContract_returnsJavaSource() {
        JavalinTest.test(app(), (server, client) -> {
            var body = mapper.writeValueAsString(new TranspileRequest("""
                    contract "SimpleTransfer" version "1.0" purpose spending
                    params:
                        receiver : PubKeyHash
                    rule "Receiver can spend"
                    when
                        Transaction( signedBy: receiver )
                    then allow
                    default: deny
                    """));
            var res = client.post("/api/transpile", body);
            assertEquals(200, res.code());

            var json = mapper.readValue(res.body().string(), TranspileResponse.class);
            assertNotNull(json.javaSource());
            assertTrue(json.javaSource().contains("SpendingValidator"));
            assertTrue(json.javaSource().contains("SimpleTransfer"));
            assertTrue(json.diagnostics().isEmpty());
        });
    }

    @Test
    void invalidContract_returnsErrors() {
        JavalinTest.test(app(), (server, client) -> {
            var body = mapper.writeValueAsString(new TranspileRequest("not valid jrl"));
            var res = client.post("/api/transpile", body);
            var json = mapper.readValue(res.body().string(), TranspileResponse.class);
            assertNull(json.javaSource());
            assertFalse(json.diagnostics().isEmpty());
        });
    }
}
