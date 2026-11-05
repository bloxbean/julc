package org.julclang.difftest;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-068 I1 after M6: every corpus case on the javac frontend against the committed {@code lowering-javac.tsv}, in both
 * directions. A moved row fails, and a case that leaves the corpus fails. Run by {@code ./gradlew :julc-difftest:adr068Lowering};
 * never part of {@code test}, since it needs the recorded corpora.
 * <p>
 * This gate belongs to the migration and ends with M6's merge (ADR-068 r21); its corpora are archived, as M6's evidence says.
 * {@code -Padr068.freeze=true} writes the table, for a change of the compiler's output that is meant: the diff of the table
 * shows every row it moved, and a review reads it as the release's change of bytes. It refuses to lose a case without
 * {@code -Padr068.allowShrink=true}.
 */
@Tag("adr068-lowering")
class Adr068LoweringTest {

    private static final Path REPO = Path.of(System.getProperty("adr068.repo", "..")).toAbsolutePath().normalize();
    private static final Path OUT = Path.of(System.getProperty("adr068.out", REPO.resolve("build/adr068-lowering").toString()));
    private static final Path EVIDENCE = REPO.resolve("adr/evidence/068");
    private static final Path FROZEN = EVIDENCE.resolve("lowering-javac.tsv");
    private static final boolean FREEZE = Boolean.parseBoolean(System.getProperty("adr068.freeze", "false"));
    private static final boolean ALLOW_SHRINK = Boolean.parseBoolean(System.getProperty("adr068.allowShrink", "false"));

    @Test
    void theJavacFrontendAgainstItsFrozenTable() throws Exception {
        Files.createDirectories(OUT);
        assertTrue(!Files.isSameFile(OUT, EVIDENCE), "adr068.out is the evidence directory: a table would be compared with itself");
        var corpus = Adr068Corpus.corpus(REPO);
        corpus.addFixtures("m3a", "julc-difftest/src/test/resources/adr068/m3a-fixtures");
        corpus.addFixtures("m3b", "julc-difftest/src/test/resources/adr068/m3b-fixtures");
        var tool = new Adr068Lowering();
        var lines = new ConcurrentHashMap<String, String>();
        int threads = Integer.parseInt(System.getProperty("adr068.threads",
                String.valueOf(Math.max(1, Runtime.getRuntime().availableProcessors() - 2))));
        var pool = new ForkJoinPool(threads);
        try {
            pool.submit(() -> corpus.sorted().parallelStream().forEach(c -> lines.put(c.key, tool.observation(c)))).get();
        } finally {
            pool.shutdown();
        }
        assertEquals(corpus.cases.size(), lines.size());

        var written = OUT.resolve("lowering-javac.tsv");
        Files.write(written, new TreeMap<>(lines).values());
        var frozen = frozen();
        var moved = Adr068Lowering.moved(frozen, lines);
        Files.write(OUT.resolve("lowering-javac-moved.tsv"), moved);
        System.out.println("cases\t" + lines.size() + "\nmoved\t" + moved.size());
        moved.stream().limit(20).forEach(line -> System.out.println("moved\t" + line));

        if (FREEZE) {
            var lost = new TreeMap<>(frozen);
            lost.keySet().removeAll(lines.keySet());
            assertTrue(ALLOW_SHRINK || lost.isEmpty(), lost.size() + " cases left the corpus; pass -Padr068.allowShrink=true if that is meant");
            Files.copy(written, FROZEN, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        assertEquals(0, moved.size(), moved.size() + " rows differ from " + FROZEN + "; see lowering-javac-moved.tsv");
        assertEquals(-1, Files.mismatch(written, FROZEN), written + " differs from " + FROZEN);
    }

    private static Map<String, String> frozen() throws IOException {
        var frozen = new TreeMap<String, String>();
        if (!Files.exists(FROZEN)) return frozen;
        for (String line : Files.readAllLines(FROZEN)) frozen.put(line.substring(0, line.indexOf('\t')), line);
        return frozen;
    }
}
