package org.julclang.vm.java.ndc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The vendored corpus is exactly the pinned upstream files: no edit, no lost file, no line-ending rewrite. */
class UplcConformanceCorpusTest {

    @Test
    void theVendoredCorpusIsThePinnedFiles() {
        assertDoesNotThrow(() -> UplcConformanceCorpus.verify(UplcConformanceCorpus.root()));
    }

    @Test
    void theCorpusHoldsEveryVectorTheExpectationsName() throws IOException {
        int expectations = NdcReports.JSON.readTree(UplcConformanceCorpus.expectations().toFile())
                .get("scenarios").size();
        assertEquals(1064, expectations);
        assertEquals(expectations, UplcVector.list(UplcConformanceCorpus.corpus()).size());
    }

    @Test
    void anEditedExpectationIsRefused(@TempDir Path copy) throws IOException {
        copyCorpus(copy);
        Path file = copy.resolve("expectations.json");
        String text = Files.readString(file, StandardCharsets.UTF_8);
        int budget = text.indexOf("\"budget\": {");
        assertTrue(budget > 0, "the expectations hold a budget");
        // drop one expected budget, which would make the scenario stop checking that vector's budget
        int end = text.indexOf('}', budget);
        Files.writeString(file, text.substring(0, budget) + "\"budget\": null" + text.substring(end + 1),
                StandardCharsets.UTF_8);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> UplcConformanceCorpus.verify(copy));
        assertTrue(e.getMessage().contains("expectations.json"), e.getMessage());
    }

    @Test
    void aRemovedFlatExpectationIsRefused(@TempDir Path copy) throws IOException {
        copyCorpus(copy);
        Path flatExpected;
        try (Stream<Path> files = Files.walk(copy.resolve("corpus"))) {
            flatExpected = files.filter(p -> p.getFileName().toString().endsWith(".flat.expected")).findFirst()
                    .orElseThrow();
        }
        Files.delete(flatExpected);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> UplcConformanceCorpus.verify(copy));
        assertTrue(e.getMessage().contains("tree digest"), e.getMessage());
    }

    /** Copies the vendored corpus and expectations into {@code target}. */
    private static void copyCorpus(Path target) throws IOException {
        Path root = UplcConformanceCorpus.root();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path source : files.toList()) {
                Path destination = target.resolve(root.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(source, destination);
                }
            }
        }
        UplcConformanceCorpus.verify(target);
    }
}
