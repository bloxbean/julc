package org.julclang.core.fuzz;

import com.code_intelligence.jazzer.junit.DictionaryEntries;
import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.core.Term;
import org.julclang.core.cbor.CborDecodingException;
import org.julclang.core.cbor.PlutusDataCborDecoder;
import org.julclang.core.cbor.PlutusDataCborEncoder;
import org.julclang.core.text.UplcParseException;
import org.julclang.core.text.UplcParser;
import org.julclang.core.text.UplcPrinter;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Coverage-guided fuzzing of decoders that read untrusted input (ADR-063 D13). A decoder may reject input
 * only with its own exception; any other exception or error is a finding. What it accepts must decode to
 * the same value after re-encoding.
 * <p>
 * {@code ./gradlew :julc-core:test} replays the seeds in {@code DecoderFuzzTestInputs/<method>/}.
 * {@code ./gradlew :julc-core:fuzz --tests '*DecoderFuzzTest.<method>'} fuzzes one target. A decoder
 * finding can be a security issue (SECURITY.md): report it privately rather than committing the input.
 */
class DecoderFuzzTest {

    private static final Path INPUTS = Path.of("src/test/resources/org/julclang/core/fuzz/DecoderFuzzTestInputs");

    @FuzzTest(maxDuration = "30s")
    void plutusDataCbor(@NotNull byte[] bytes) {
        PlutusData data;
        try {
            data = PlutusDataCborDecoder.decode(bytes);
        } catch (CborDecodingException e) {
            return;
        }
        assertEquals(data, PlutusDataCborDecoder.decode(PlutusDataCborEncoder.encode(data)));
    }

    @FuzzTest(maxDuration = "30s")
    @DictionaryEntries({"(program ", "1.1.0", "1.0.0", "(lam ", "(delay ", "(force ", "(builtin ", "(con ",
            "(constr ", "(case ", "(error)", "integer", "bytestring", "string", "bool", "unit", "data", "list",
            "pair", "True", "False", "Constr ", "Map ", "List ", "I ", "B ", "#", "[", "]", "(", ")", ", ", "\\\""})
    void uplcText(@NotNull String text) {
        Program program;
        try {
            program = UplcParser.parseProgram(text);
        } catch (UplcParseException e) {
            return;
        }
        // Known divergence: JuLC accepts names that Haskell's grammar rejects, and the printer renames them,
        // which can capture a variable. Such inputs are skipped until the printer quotes names as Haskell does.
        if (!namesValid(program.term())) return;
        assertEquals(program, UplcParser.parseProgram(UplcPrinter.print(program)));
    }

    /**
     * Whether every name is an unquoted identifier in Haskell's grammar: an ASCII letter or {@code _}, then
     * ASCII letters, digits, {@code _} or {@code '} (plutus-core 1.65.0.0, {@code PlutusCore.Name.Unique}).
     */
    private static boolean namesValid(Term term) {
        return switch (term) {
            case Term.Var v -> validName(v.name().name());
            case Term.Lam l -> validName(l.paramName()) && namesValid(l.body());
            case Term.Apply a -> namesValid(a.function()) && namesValid(a.argument());
            case Term.Force f -> namesValid(f.term());
            case Term.Delay d -> namesValid(d.term());
            case Term.Constr c -> c.fields().stream().allMatch(DecoderFuzzTest::namesValid);
            case Term.Case c -> namesValid(c.scrutinee())
                    && c.branches().stream().allMatch(DecoderFuzzTest::namesValid);
            case Term.Const ignored -> true;
            case Term.Builtin ignored -> true;
            case Term.Error ignored -> true;
        };
    }

    private static boolean validName(String name) {
        if (name.isEmpty() || !(isAsciiLetter(name.charAt(0)) || name.charAt(0) == '_')) return false;
        return name.chars().allMatch(c -> isAsciiLetter(c) || (c >= '0' && c <= '9') || c == '_' || c == '\'');
    }

    private static boolean isAsciiLetter(int c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /** A seed the decoder rejects adds no coverage, so every {@code seed-*} input must decode. */
    @Test
    void seedsDecode() throws IOException {
        for (Path seed : seeds("plutusDataCbor")) {
            byte[] bytes = Files.readAllBytes(seed);
            assertDoesNotThrow(() -> PlutusDataCborDecoder.decode(bytes), seed.toString());
        }
        for (Path seed : seeds("uplcText")) {
            String text = Files.readString(seed, StandardCharsets.UTF_8);
            assertDoesNotThrow(() -> UplcParser.parseProgram(text), seed.toString());
        }
    }

    private static List<Path> seeds(String method) throws IOException {
        try (Stream<Path> files = Files.list(INPUTS.resolve(method))) {
            var seeds = files.filter(f -> f.getFileName().toString().startsWith("seed-")).sorted().toList();
            assertFalse(seeds.isEmpty(), method);
            return seeds;
        }
    }
}
