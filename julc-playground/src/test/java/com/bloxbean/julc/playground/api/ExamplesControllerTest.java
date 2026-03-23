package com.bloxbean.julc.playground.api;

import com.bloxbean.julc.playground.model.ExampleDto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExamplesControllerTest {

    static final ObjectMapper mapper = new ObjectMapper();
    static final ExamplesController controller = new ExamplesController();

    Javalin app() {
        return Javalin.create(c -> {})
                .get("/api/examples", controller::list)
                .get("/api/examples/{name}", controller::get);
    }

    @Test
    void listExamples_returnsAll() {
        JavalinTest.test(app(), (server, client) -> {
            var res = client.get("/api/examples");
            assertEquals(200, res.code());

            var list = mapper.readValue(res.body().string(), new TypeReference<List<ExampleDto>>() {});
            assertTrue(list.size() >= 7, "Expected at least 7 examples, got " + list.size());
            assertTrue(list.stream().anyMatch(e -> e.name().equals("Vesting.jrl")));
            assertTrue(list.stream().anyMatch(e -> e.name().equals("SimpleTransfer.jrl")));
        });
    }

    @Test
    void getExample_returnsSource() {
        JavalinTest.test(app(), (server, client) -> {
            var res = client.get("/api/examples/Vesting.jrl");
            assertEquals(200, res.code());

            var ex = mapper.readValue(res.body().string(), ExampleDto.class);
            assertEquals("Vesting.jrl", ex.name());
            assertNotNull(ex.source());
            assertTrue(ex.source().contains("Vesting"));
        });
    }

    @Test
    void getExample_notFound() {
        JavalinTest.test(app(), (server, client) -> {
            var res = client.get("/api/examples/NonExistent.jrl");
            assertEquals(404, res.code());
        });
    }
}
