package org.julclang.compiler;

import org.julclang.compiler.backend.BackendException;
import org.julclang.compiler.backend.LibraryRequest;
import org.julclang.compiler.backend.LibraryType;
import org.julclang.stdlib.StdlibRegistry;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * ADR-068 I2a's rejection fixtures (F301): one small source per rejection site of the reference frontend that the
 * rest of the suite does not already compile through an entry point, each compiled through a public entry point
 * (so the corpus recorder records it) and checked for its expected diagnostics.
 * <p>
 * A fixture under {@code src/test/resources/adr068/rejections} starts with header comments:
 * {@code // entry: method <name>} ({@code compileMethod}), {@code // entry: validator} ({@code compile}) or
 * {@code // entry: library} ({@code JavaLibraryProvider}), {@code // entry: describe <class>} (a provider's
 * {@code describe}) or {@code // entry: instantiate <symbol> <type>} (a provider's specialization request); then one or more {@code // expect: JULC####} or
 * {@code // expect-message: <regex>} lines, every one of which must be met by some diagnostic of the rejection. A line
 * {@code //// library} starts a library source, passed to the entry point with the others.
 */
class Adr068RejectionFixturesTest {

    @TestFactory
    Stream<DynamicTest> everyFixtureIsRejectedAsExpected() throws IOException, URISyntaxException {
        Path dir = Path.of(Objects.requireNonNull(getClass().getResource("/adr068/rejections")).toURI());
        List<Path> fixtures;
        try (Stream<Path> list = Files.list(dir)) {
            fixtures = list.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
        assertFalse(fixtures.isEmpty());
        return fixtures.stream().map(file -> DynamicTest.dynamicTest(file.getFileName().toString(), () -> check(file)));
    }

    private static void check(Path file) throws IOException {
        String source = Files.readString(file);
        String entry = null;
        var codes = new ArrayList<String>();
        var messages = new ArrayList<Pattern>();
        for (String line : source.lines().toList()) {
            if (!line.startsWith("//")) break;
            String text = line.substring(2).strip();
            if (text.startsWith("entry: ")) entry = text.substring(7).strip();
            if (text.startsWith("expect: ")) codes.add(text.substring(8).strip());
            if (text.startsWith("expect-message: ")) messages.add(Pattern.compile(text.substring(16).strip()));
        }
        assertTrue(entry != null && (!codes.isEmpty() || !messages.isEmpty()), file + ": entry and expectations");
        var parts = source.split("(?m)^//// library\\R");
        var libraries = List.of(parts).subList(1, parts.length);
        var found = new ArrayList<String>();
        try {
            compile(entry, parts[0], libraries);
            fail(file.getFileName() + " compiled; expected " + codes + " " + messages);
        } catch (CompilerException e) {
            for (var d : e.diagnostics()) found.add(d.code() + " " + d.message());
            if (e.diagnostics().isEmpty()) found.add("null " + e.getMessage());
        } catch (RuntimeException e) {
            found.add(codeOf(e) + " " + e.getMessage());
        }
        for (String code : codes) {
            assertTrue(found.stream().anyMatch(f -> f.startsWith(code + " ")), file.getFileName() + ": " + code + " in " + found);
        }
        for (Pattern message : messages) {
            assertTrue(found.stream().anyMatch(f -> message.matcher(f).find()), file.getFileName() + ": " + message + " in " + found);
        }
    }

    private static String codeOf(RuntimeException e) {
        return e instanceof BackendException b ? b.code() : "null";
    }

    private static void compile(String entry, String source, List<String> libraries) {
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry());
        if (entry.startsWith("method ")) {
            if (libraries.isEmpty()) compiler.compileMethod(source, entry.substring(7).strip());
            else compiler.compileMethod(source, entry.substring(7).strip(), libraries);
        } else if (entry.equals("validator")) {
            if (libraries.isEmpty()) compiler.compile(source);
            else compiler.compile(source, libraries);
        } else if (entry.equals("library") || entry.startsWith("describe ") || entry.startsWith("instantiate ")) {
            var all = new ArrayList<String>();
            all.add(source);
            all.addAll(libraries);
            var provider = new JavaLibraryProvider(all, StdlibRegistry.defaultRegistry(), new CompilerOptions());
            String[] words = entry.split(" ");
            if (words[0].equals("describe")) provider.describe(words[1]);
            // An export the provider cannot describe is reported, not thrown: it is a rejection of that export.
            if (!provider.unsupportedExports().isEmpty()) {
                throw new IllegalArgumentException(String.join("; ", provider.unsupportedExports().values()));
            }
            if (words[0].equals("instantiate")) {
                var request = new LibraryRequest.Instantiate(words[1], List.of(new LibraryType.Reference(words[2], List.of())));
                provider.materialize(List.<LibraryRequest>of(request));
            }
        } else {
            throw new IllegalArgumentException("unknown entry " + entry);
        }
    }
}
