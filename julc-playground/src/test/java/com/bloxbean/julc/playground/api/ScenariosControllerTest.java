package com.bloxbean.julc.playground.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScenariosControllerTest {

    static final ObjectMapper mapper = new ObjectMapper();
    static final ScenariosController controller = new ScenariosController();

    Javalin app() {
        return Javalin.create().get("/api/scenarios/{purpose}", controller::handle);
    }

    @Test
    void spendingScenarios_returnsTemplates() {
        JavalinTest.test(app(), (server, client) -> {
            var res = client.get("/api/scenarios/spending");
            assertEquals(200, res.code());

            var json = mapper.readTree(res.body().string());
            assertEquals("SPENDING", json.get("purpose").asText());
            assertTrue(json.get("scenarios").isArray());
            assertTrue(json.get("scenarios").size() > 0);
        });
    }

    @Test
    void mintingScenarios_returnsTemplates() {
        JavalinTest.test(app(), (server, client) -> {
            var res = client.get("/api/scenarios/minting");
            assertEquals(200, res.code());

            var json = mapper.readTree(res.body().string());
            assertEquals("MINTING", json.get("purpose").asText());
            assertTrue(json.get("scenarios").size() > 0);
        });
    }

    @Test
    void unknownPurpose_returnsEmpty() {
        JavalinTest.test(app(), (server, client) -> {
            var res = client.get("/api/scenarios/unknown");
            assertEquals(200, res.code());

            var json = mapper.readTree(res.body().string());
            assertEquals("UNKNOWN", json.get("purpose").asText());
            assertEquals(0, json.get("scenarios").size());
        });
    }
}
