package com.bloxbean.julc.playground.api;

import com.bloxbean.julc.playground.model.CheckRequest;
import com.bloxbean.julc.playground.model.CheckResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CheckControllerTest {

    static final ObjectMapper mapper = new ObjectMapper();
    static final CheckController controller = new CheckController();

    Javalin app() {
        return Javalin.create().post("/api/check", controller::handle);
    }

    @Test
    void validJavaValidator_returnsMetadata() {
        JavalinTest.test(app(), (server, client) -> {
            var body = mapper.writeValueAsString(new CheckRequest("""
                    import java.math.BigInteger;

                    @Validator
                    class SimpleValidator {
                        @Entrypoint
                        static boolean validate(BigInteger redeemer, BigInteger ctx) {
                            return true;
                        }
                    }
                    """));
            var res = client.post("/api/check", body);
            assertEquals(200, res.code());

            var json = mapper.readValue(res.body().string(), CheckResponse.class);
            assertTrue(json.valid());
            assertNotNull(json.contractName());
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

    @Test
    void oversizedSource_returnsError() {
        JavalinTest.test(app(), (server, client) -> {
            String big = "x".repeat(InputValidator.MAX_SOURCE_LENGTH + 1);
            var body = mapper.writeValueAsString(new CheckRequest(big));
            var res = client.post("/api/check", body);
            var json = mapper.readValue(res.body().string(), CheckResponse.class);
            assertFalse(json.valid());
            assertTrue(json.diagnostics().getFirst().message().contains("maximum length"));
        });
    }
}
