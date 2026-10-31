package org.julclang.compiler;

import org.julclang.compiler.backend.BackendException;
import org.julclang.compiler.frontend.javac.JavacGateMode;
import org.julclang.compiler.pir.StdlibLookup;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.OptimizationCostProfiles;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * ADR-068 D13: negative fixtures are never rewritten. M1b made the inputs of 83 negative tests Java, so that the rule
 * each test names is what rejects them; those tests keep their rewritten sources, and this test keeps the frozen
 * originals, each recorded through the entry point and with the options of its B0 row, so its row stays a rejection.
 * <p>
 * Each original in {@code src/test/resources/adr068/negative-originals} is compiled twice: with the gate off, it gets
 * B0's rejection (the reference frontend's code, or {@code uncoded/<exception>}); with the default gate, it gets
 * javac's keys (I2b), or, where javac accepts it, the same JuLC rejection.
 */
class Adr068NegativeOriginalsTest {

    private record Row(String id, String entry, String method, String lookup, boolean sourceMap, String costProfile,
                       String b0, String javac, String source, List<String> libraries, String origin) {
    }

    @TestFactory
    Stream<DynamicTest> everyOriginalStaysRejected() throws IOException, URISyntaxException {
        Path dir = Path.of(Objects.requireNonNull(getClass().getResource("/adr068/negative-originals")).toURI());
        var rows = new ArrayList<Row>();
        for (String line : Files.readAllLines(dir.resolve("index.tsv"))) {
            if (line.startsWith("#") || line.startsWith("id\t")) continue;
            String[] f = line.split("\t");
            rows.add(new Row(f[0], f[1], f[2], f[3], Boolean.parseBoolean(f[4]), f[5], f[6], f[7], f[8],
                    f[9].equals("-") ? List.of() : List.of(f[9].split(",")), f[10]));
        }
        assertFalse(rows.isEmpty());
        return rows.stream().map(row -> DynamicTest.dynamicTest(row.id() + " " + row.origin(), () -> check(dir, row)));
    }

    private static void check(Path dir, Row row) throws IOException {
        String source = row.source().equals("-") ? null : text(dir, row.source());
        var libraries = new ArrayList<String>();
        for (String library : row.libraries()) libraries.add(text(dir, library));

        assertEquals(row.b0(), reference(rejection(row, source, libraries, JavacGateMode.OFF)), row.origin());

        var enforced = rejection(row, source, libraries, null);
        if (row.javac().equals("accepted")) {
            assertEquals(row.b0(), reference(enforced), row.origin());
        } else {
            var compilerException = (CompilerException) enforced;
            var keys = compilerException.diagnostics().stream().map(d -> String.valueOf(d.message())).toList();
            for (String key : row.javac().split(",")) {
                if (key.equals("JULC0085")) continue;
                assertTrue(keys.stream().anyMatch(message -> message.contains("(" + key + ")")),
                        row.origin() + ": " + key + " in " + keys);
            }
        }
    }

    private static String text(Path dir, String digest) throws IOException {
        return Files.readString(dir.resolve("sources").resolve(digest + ".java"));
    }

    /** The B0 ledger's cell for a rejection: the first diagnostic's code, or {@code uncoded/<exception>}. */
    private static String reference(RuntimeException e) {
        if (e instanceof CompilerException c && !c.diagnostics().isEmpty() && c.diagnostics().getFirst().code() != null) {
            return c.diagnostics().getFirst().code();
        }
        if (e instanceof BackendException b && b.code() != null) return b.code();
        return "uncoded/" + e.getClass().getSimpleName();
    }

    private static RuntimeException rejection(Row row, String source, List<String> libraries, JavacGateMode mode) {
        var options = new CompilerOptions().setSourceMapEnabled(row.sourceMap()).setJavacGate(mode).setLogger(message -> { });
        if (!row.costProfile().equals("-")) {
            options.setOptimizationCostProfile(OptimizationCostProfiles.forId(row.costProfile()));
        }
        StdlibLookup lookup = row.lookup().equals("stdlib") ? StdlibRegistry.defaultRegistry() : null;
        try {
            switch (row.entry()) {
                case "compile" -> new JulcCompiler(lookup, options).compile(source, libraries);
                case "compileWithDetails" -> {
                    var result = new JulcCompiler(lookup, options).compileWithDetails(source);
                    if (result.hasErrors()) throw new CompilerException(result.diagnostics());
                }
                case "compileMethod" -> new JulcCompiler(lookup, options).compileMethod(source, row.method(), libraries);
                case "JavaLibraryProvider" -> {
                    var provider = new JavaLibraryProvider(libraries, lookup, options);
                    if (!provider.unsupportedExports().isEmpty()) {
                        throw new IllegalArgumentException(String.join("; ", provider.unsupportedExports().values()));
                    }
                }
                default -> throw new IllegalStateException("unknown entry " + row.entry());
            }
        } catch (RuntimeException e) {
            return e;
        }
        return fail(row.origin() + " compiled with mode " + mode);
    }
}
