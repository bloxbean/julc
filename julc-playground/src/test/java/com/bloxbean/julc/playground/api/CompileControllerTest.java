package com.bloxbean.julc.playground.api;

import com.bloxbean.cardano.julc.jrl.JrlCompiler;
import com.bloxbean.julc.playground.model.CompileRequest;
import com.bloxbean.julc.playground.model.CompileResponse;
import com.bloxbean.julc.playground.sandbox.CompilationSandbox;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CompileControllerTest {

    static final ObjectMapper mapper = new ObjectMapper();
    static final JrlCompiler compiler = new JrlCompiler();
    static final CompilationSandbox sandbox = new CompilationSandbox(2, 30);
    static final CompileController controller = new CompileController(compiler, sandbox);

    @AfterAll
    static void tearDown() {
        sandbox.shutdown();
    }

    Javalin app() {
        return Javalin.create().post("/api/compile", controller::handle);
    }

    @Test
    void validContract_compilesToUplc() {
        JavalinTest.test(app(), (server, client) -> {
            var body = mapper.writeValueAsString(new CompileRequest("""
                    contract "SimpleTransfer" version "1.0" purpose spending
                    params:
                        receiver : PubKeyHash
                    rule "Receiver can spend"
                    when
                        Transaction( signedBy: receiver )
                    then allow
                    default: deny
                    """));
            var res = client.post("/api/compile", body);
            assertEquals(200, res.code());

            var json = mapper.readValue(res.body().string(), CompileResponse.class);
            assertNotNull(json.uplcText());
            assertTrue(json.uplcText().startsWith("(program"));
            assertNotNull(json.javaSource());
            assertTrue(json.scriptSizeBytes() > 0);
            assertNotNull(json.scriptSizeFormatted());
            assertTrue(json.diagnostics().isEmpty());
        });
    }

    @Test
    void validContractWithParams_includesParamInfo() {
        JavalinTest.test(app(), (server, client) -> {
            var body = mapper.writeValueAsString(new CompileRequest("""
                    contract "SimpleTransfer" version "1.0" purpose spending
                    params:
                        receiver : PubKeyHash
                    rule "Receiver can spend"
                    when
                        Transaction( signedBy: receiver )
                    then allow
                    default: deny
                    """));
            var res = client.post("/api/compile", body);
            var json = mapper.readValue(res.body().string(), CompileResponse.class);
            assertFalse(json.params().isEmpty());
            assertEquals("receiver", json.params().getFirst().name());
        });
    }

    @Test
    void invalidContract_returnsErrors() {
        JavalinTest.test(app(), (server, client) -> {
            var body = mapper.writeValueAsString(new CompileRequest("not valid jrl"));
            var res = client.post("/api/compile", body);
            var json = mapper.readValue(res.body().string(), CompileResponse.class);
            assertNull(json.uplcText());
            assertFalse(json.diagnostics().isEmpty());
        });
    }
}
