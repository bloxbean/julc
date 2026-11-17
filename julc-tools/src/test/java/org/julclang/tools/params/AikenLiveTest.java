package org.julclang.tools.params;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.julclang.core.PlutusData;
import org.julclang.core.cbor.PlutusDataCborEncoder;
import org.julclang.tools.model.ApplyParamsModels.Request;
import org.julclang.tools.model.ApplyParamsModels.Response;
import org.julclang.tools.model.MockTransaction.DataInput;
import org.julclang.tools.model.UplcModels.ScriptInput;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ADR-077 I4, opt-in: runs only when {@code aiken} is on the PATH. It builds the fixture project in a temporary
 * directory, applies generated conforming values with {@code aiken blueprint apply} as a separate process, one value
 * per process, and compares with {@code vm.applyParams} at I4's layers. Aiken is given each value in the canonical
 * encoding (D6), which it keeps.
 */
class AikenLiveTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final HexFormat HEX = HexFormat.of();
    static String aiken;
    @TempDir
    static Path project;
    static String version;

    @BeforeAll
    static void build() throws Exception {
        aiken = onPath("aiken");
        assumeTrue(aiken != null, "aiken is not on the PATH; the live Aiken oracle is skipped");
        Files.createDirectories(project.resolve("validators"));
        Files.writeString(project.resolve("aiken.toml"), resource("aiken.toml"));
        Files.writeString(project.resolve("validators/oracle.ak"), resource("validators/oracle.ak"));
        version = run(List.of(aiken, "--version")).strip();
        run(List.of(aiken, "build"));
    }

    static String onPath(String name) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(File.pathSeparator)) {
            File file = new File(dir, name);
            if (file.canExecute()) return file.getAbsolutePath();
        }
        return null;
    }

    static String resource(String name) throws IOException {
        try (InputStream in = AikenLiveTest.class.getResourceAsStream("/adr077/aiken/" + name)) {
            assertNotNull(in, name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static String run(List<String> command) throws Exception {
        var process = new ProcessBuilder(command).directory(project.toFile()).redirectErrorStream(true);
        process.environment().put("NO_COLOR", "1");
        Process started = process.start();
        String output = new String(started.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!started.waitFor(120, TimeUnit.SECONDS) || started.exitValue() != 0) {
            throw new AssertionError(command + " failed:\n" + output);
        }
        return output;
    }

    /** Applies the values one at a time with Aiken; gives the last blueprint's entry. */
    static JsonNode aikenApply(String validator, String title, List<PlutusData> values, int round) throws Exception {
        String in = "plutus.json";
        for (int i = 0; i < values.size(); i++) {
            String out = "applied-" + validator + "-" + round + "-" + i + ".json";
            run(List.of(aiken, "blueprint", "apply", "-i", in, "-o", out, "-m", "oracle", "-v", validator,
                    HEX.formatHex(PlutusDataCborEncoder.encode(values.get(i)))));
            in = out;
        }
        for (JsonNode entry : JSON.readTree(project.resolve(in).toFile()).get("validators")) {
            if (entry.get("title").asText().equals(title)) return entry;
        }
        throw new AssertionError(title);
    }

    static Response julcApply(String title, List<PlutusData> values) throws IOException {
        var params = new ArrayList<DataInput>();
        for (PlutusData value : values) params.add(new DataInput("cbor", HEX.formatHex(PlutusDataCborEncoder.encode(value))));
        return ApplyParams.apply(new Request("V3", new ScriptInput(Files.readString(project.resolve("plutus.json")), null,
                null, title), params, true, null));
    }

    static void assertSameLayers(JsonNode aiken, Response julc) {
        String code = aiken.get("compiledCode").asText();
        assertEquals(code, julc.cborHex(), version);
        assertEquals(HEX.formatHex(CanonicalScript.wrap(HEX.parseHex(code))), julc.compiledCode(), version);
        assertEquals(aiken.get("hash").asText(), julc.scriptHash(), version);
    }

    static PlutusData bytes(Random random, int n) {
        byte[] b = new byte[n];
        random.nextBytes(b);
        return PlutusData.bytes(b);
    }

    @Test
    void generatedConformingValuesMatchAiken() throws Exception {
        var random = new Random(1123);
        for (int round = 0; round < 4; round++) {
            var tags = new ArrayList<PlutusData>();
            for (int i = random.nextInt(4); i > 0; i--) tags.add(PlutusData.integer(new BigInteger(70, random)));
            PlutusData mode = random.nextBoolean() ? new PlutusData.ConstrData(0, List.of())
                    : new PlutusData.ConstrData(1, List.of(PlutusData.integer(random.nextInt(1000) - 500)));
            var values = List.of(bytes(random, 28), PlutusData.integer(new BigInteger(80, random).negate()),
                    new PlutusData.ConstrData(0, List.of(bytes(random, random.nextInt(90)),
                            PlutusData.integer(random.nextLong()), new PlutusData.ListData(tags), mode)));
            assertSameLayers(aikenApply("oracle", "oracle.oracle.else", values, round),
                    julcApply("oracle.oracle.else", values));
        }
    }

    @Test
    void generatedOpaqueValuesMatchAiken() throws Exception {
        var random = new Random(2311);
        for (int round = 0; round < 4; round++) {
            var values = List.of(DataReadersDifferentialTest.random(random, 4, true),
                    new PlutusData.ConstrData(0, List.of(PlutusData.integer(1 + random.nextInt(1000)))));
            assertSameLayers(aikenApply("anydata", "oracle.anydata.else", values, round),
                    julcApply("oracle.anydata.else", values));
        }
    }
}
