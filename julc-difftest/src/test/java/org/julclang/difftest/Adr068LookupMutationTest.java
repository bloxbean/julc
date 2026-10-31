package org.julclang.difftest;

import org.julclang.compiler.OptimizationLevel;
import org.julclang.compiler.corpus.CorpusRecorder;
import org.julclang.compiler.corpus.FileCorpusRecorder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F320: a provider records its case at construction, with the lookup it was given. A specialization requested after
 * the caller registered into that registry is compiled against the changed lookup, so it is recorded as unreplayable
 * and the ledger does not replay it with the case's default registry. Recorded by {@link Adr068LookupMutationProbe}
 * in a child JVM.
 */
class Adr068LookupMutationTest {

    @Test
    void aSpecializationAfterTheLookupChangedIsUnreplayable(@TempDir Path temp) throws Exception {
        Path corpus = temp.resolve("corpus");
        String output = run(corpus);
        // The specialization was compiled with the swapped builder.
        assertTrue(output.contains("(builtin lessThanByteString)"), output);
        assertFalse(output.contains("equalsByteString"), output);

        var files = new ArrayList<Path>();
        try (Stream<Path> walk = Files.walk(corpus.resolve("cases"))) {
            walk.filter(Files::isRegularFile).forEach(files::add);
        }
        var caseFiles = files.stream().filter(f -> f.getFileName().toString().equals("case.txt")).toList();
        assertEquals(1, caseFiles.size(), files.toString());
        String caseText = Files.readString(caseFiles.getFirst());
        assertTrue(caseText.contains("entry: JavaLibraryProvider\n"), caseText);
        assertTrue(caseText.contains("lookup: " + Adr068Corpus.STDLIB_LOOKUP + "\n"), caseText);
        assertFalse(files.stream().anyMatch(f -> f.getFileName().toString().startsWith("instantiate-")), files.toString());
        var unreplayable = files.stream()
                .filter(f -> f.getFileName().toString().startsWith("unreplayable-instantiate-")).toList();
        assertEquals(1, unreplayable.size(), files.toString());
        assertTrue(Files.readString(unreplayable.getFirst()).startsWith(FileCorpusRecorder.LOOKUP_MUTATED + "\n"
                + "(instantiate \"probe.Bytes.same\""), Files.readString(unreplayable.getFirst()));

        var recorded = new Adr068Corpus(temp);
        recorded.addRecorded(corpus, "recorded");
        var ledger = new Adr068Ledger(recorded);
        ledger.compileAll(1);
        var c = recorded.cases.values().stream().filter(k -> k.entry.equals("JavaLibraryProvider")).findFirst().orElseThrow();
        var result = ledger.result(c.key);
        // The case itself is the default registry's and replays; the request made after the change does not.
        result.outcomes().forEach((level, o) -> assertTrue(o.accepted(), level + " " + o));
        assertEquals(1, result.instantiations().size(), result.instantiations().toString());
        var perLevel = result.instantiations().values().iterator().next();
        assertEquals(List.of(OptimizationLevel.values()), List.copyOf(perLevel.keySet()));
        perLevel.forEach((level, o) -> assertEquals("U:" + FileCorpusRecorder.LOOKUP_MUTATED, o.cell(), level.toString()));
        var rows = ledger.instantiationLines();
        assertEquals(2, rows.size(), rows.toString());
        assertTrue(rows.get(1).endsWith(("\tU:" + FileCorpusRecorder.LOOKUP_MUTATED).repeat(4)), rows.get(1));
    }

    private static String run(Path corpus) throws IOException, InterruptedException {
        var command = new ArrayList<String>();
        command.add(ProcessHandle.current().info().command().orElse("java"));
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add("-D" + CorpusRecorder.PROPERTY + "=" + corpus);
        command.add(Adr068LookupMutationProbe.class.getName());
        var process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(5, TimeUnit.MINUTES));
        assertEquals(0, process.exitValue(), output);
        return output;
    }
}
