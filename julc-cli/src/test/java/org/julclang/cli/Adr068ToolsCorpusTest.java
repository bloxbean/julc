package org.julclang.cli;

import org.julclang.compiler.frontend.javac.SourceReader;

import com.sun.source.tree.MethodTree;
import org.julclang.cli.check.TestDiscovery;
import org.julclang.cli.mcp.lint.LintEngine;
import org.julclang.cli.mcp.lint.LintFinding;
import org.julclang.cli.mcp.lint.LintTrees;
import org.julclang.cli.mcp.tools.EvaluateToolAccess;
import org.julclang.compiler.JavaSourceIntrospector;
import org.julclang.compiler.JavaSourceParser;
import org.julclang.compiler.frontend.javac.JavacSyntax;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ADR-068 M6's equivalence gate for julc-cli's tools: on every {@code .java} file of the corpus, the lint findings
 * (positions and messages included), {@code julc check}'s discovered tests and the MCP evaluate tool's arity are the
 * same read by JavaParser and by javac's parser. Run by {@code :julc-cli:adr068Tools}, with
 * {@code -Padr068.tools.sources=<dir>[:<dir>...]}; it writes {@code summary.tsv} and {@code differences.tsv} to
 * {@code -Padr068.tools.out}. A difference is allowed only in a class listed in {@link #ALLOWED}, with its reason.
 */
@Tag("adr068-tools")
class Adr068ToolsCorpusTest {

    private static final String READER_REJECTS = "JuLC's strict reader (JavaSourceParser, ADR-062 T24) rejects a"
            + " source javac's parser reads; ADR-068 keeps javac's reading (D5), and compiling the source reports its"
            + " problem";

    /** The differences a reason accounts for, by class (the evidence names each file). */
    private static final Map<String, String> ALLOWED = Map.of(
            "lint:javac-rejects",
            "javac's parser rejects the source, so it has no findings; JavaParser lints the tree it recovered",
            "arity:javac-rejects",
            "javac's parser rejects the source, so the arity is unknown (-1) and the compiler reports the error",
            "lint:reader-rejects", READER_REJECTS,
            "arity:reader-rejects", READER_REJECTS,
            "introspection:reader-rejects", READER_REJECTS,
            "introspection:javac-rejects", "javac's parser rejects a source JavaParser's reading accepted (a lexical"
                    + " error, such as an int literal out of range): the compiler rejects it too (D5)",
            "introspection:javac-reads-later-java", "both reject, and javac's errors are only of language level (Java 22+"
                    + " syntax JavaParser 3.26 cannot read): javac's tree is whole and shows no JuLC role, so the source"
                    + " is skipped where JavaParser's reading failed",
            "introspection:javac-fails-closed", "both reject; JavaParser's lenient reading found no role, javac's error is"
                    + " lexical (an illegal escape), so the role cannot be read and the scan reports the error, as javac"
                    + " compiling the project would",
            "discovery:javac-reads-later-java", "javac's discovery skips a source of later Java that declares no Test"
                    + " method, as JavaParser's lenient reading did",
            "discovery:reader-rejects", READER_REJECTS
                    + " (a literal with an unpaired surrogate: the test is found and its compilation reports JULC0093)");

    private final LintEngine lintJavaParser = new LintEngine(LintEngine.DEFAULT_RULES, SourceReader.JAVAPARSER);
    private final LintEngine lintJavac = new LintEngine(LintEngine.DEFAULT_RULES, SourceReader.JAVAC);

    @Test
    void toolsReadTheCorpusAlikeOnBothParsers() throws IOException {
        String sources = System.getProperty("adr068.tools.sources");
        Assumptions.assumeTrue(sources != null && !sources.isBlank(), "-Padr068.tools.sources is not set");
        Path out = Path.of(System.getProperty("adr068.tools.out", "build/adr068-tools"));
        Files.createDirectories(out);

        var files = new ArrayList<Path>();
        for (String dir : sources.split(File.pathSeparator)) {
            try (Stream<Path> walk = Files.walk(Path.of(dir))) {
                walk.filter(p -> p.toString().endsWith(".java") && Files.isRegularFile(p)).sorted().forEach(files::add);
            }
        }

        var counts = new TreeMap<String, Integer>();
        var differences = new ArrayList<String>();
        for (Path file : files) {
            String text;
            try {
                text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(file)))
                        .toString();
            } catch (CharacterCodingException e) {
                count(counts, "files:not-utf8");
                continue;
            }
            count(counts, "files");
            var syntax = JavacSyntax.read(text);
            if (syntax.hasErrors()) count(counts, "files:javac-rejects");
            boolean readerRejects = !syntax.hasErrors() && !JavaSourceParser.parse(text).isSuccessful();
            if (readerRejects) count(counts, "files:reader-rejects");
            String other = syntax.hasErrors() ? ":javac-rejects" : readerRejects ? ":reader-rejects" : ":DIFFERENT";

            // Lint
            var expected = lintJavaParser.lint(text);
            var actual = lintJavac.lint(text);
            for (LintFinding f : expected) count(counts, "lint-findings:" + f.ruleId());
            if (expected.equals(actual)) {
                count(counts, "lint:same");
            } else {
                var missing = new ArrayList<>(expected);
                missing.removeAll(actual);
                var extra = new ArrayList<>(actual);
                extra.removeAll(expected);
                record(counts, differences, file, "lint" + other,
                        missing.isEmpty() && extra.isEmpty() ? "order " + expected : "only JavaParser: " + missing,
                        "only javac: " + extra);
            }

            // julc check's discovery
            String found = discovery(file, text, SourceReader.JAVAPARSER);
            String foundOnJavac = discovery(file, text, SourceReader.JAVAC);
            if (found.equals(foundOnJavac)) {
                count(counts, found.equals("[]") ? "discovery:same-none" : "discovery:same");
            } else {
                boolean laterJava = found.startsWith("ERROR") && foundOnJavac.equals("[]") && syntax.hasErrors()
                        && syntax.onlyLanguageLevelErrors();
                record(counts, differences, file, laterJava ? "discovery:javac-reads-later-java" : "discovery" + other,
                        found, foundOnJavac);
            }

            // The project scan's introspection (ProjectScanner, the gradle plugin, the testkit)
            String inspected = introspection(text, SourceReader.JAVAPARSER);
            String inspectedOnJavac = introspection(text, SourceReader.JAVAC);
            if (inspected.equals(inspectedOnJavac)) {
                count(counts, "introspection:same");
            } else if (inspected.equals("ERROR noRole=false") && inspectedOnJavac.equals("ERROR noRole=true")) {
                record(counts, differences, file, "introspection:javac-reads-later-java", inspected, inspectedOnJavac);
            } else if (inspected.equals("ERROR noRole=true") && inspectedOnJavac.equals("ERROR noRole=false")) {
                record(counts, differences, file, "introspection:javac-fails-closed", inspected, inspectedOnJavac);
            } else {
                record(counts, differences, file, "introspection" + other, inspected, inspectedOnJavac);
            }

            // MCP evaluate's arity, for the first methods javac finds and a missing one
            var names = new ArrayList<String>();
            if (!syntax.hasErrors()) {
                LintTrees.methods(syntax).stream().limit(4)
                        .forEach(p -> names.add(((MethodTree) p.getLeaf()).getName().toString()));
            }
            names.add("noSuchMethod");
            for (String name : names) {
                int arity = EvaluateToolAccess.arity(text, name, SourceReader.JAVAPARSER);
                int arityOnJavac = EvaluateToolAccess.arity(text, name, SourceReader.JAVAC);
                if (arity == arityOnJavac) {
                    count(counts, "arity:same");
                } else {
                    record(counts, differences, file, "arity" + other, name + "=" + arity, name + "=" + arityOnJavac);
                }
            }
        }

        var summary = new ArrayList<String>();
        counts.forEach((k, v) -> summary.add(k + "\t" + v));
        Files.write(out.resolve("summary.tsv"), summary);
        Files.write(out.resolve("differences.tsv"), differences);

        var unexplained = counts.keySet().stream()
                .filter(k -> k.startsWith("lint:") || k.startsWith("discovery:") || k.startsWith("arity:")
                        || k.startsWith("introspection:"))
                .filter(k -> !k.endsWith(":same") && !k.endsWith(":same-none") && !ALLOWED.containsKey(k))
                .toList();
        assertEquals(List.of(), unexplained, () -> String.join("\n", differences.stream().limit(40).toList()));
    }

    /** The source's {@code SourceInfo}, or its parse failure and whether the source then declares no JuLC role. */
    private static String introspection(String text, SourceReader reader) {
        try {
            return JavaSourceIntrospector.inspect(text, reader).toString();
        } catch (JavaSourceIntrospector.SourceParseException e) {
            return "ERROR noRole=" + JavaSourceIntrospector.declaresNoJulcRole(text, reader);
        }
    }

    private static String discovery(Path file, String text, SourceReader reader) {
        try {
            return TestDiscovery.discover(file, text, reader).stream()
                    .map(t -> t.className() + "." + t.methodName())
                    .toList().toString();
        } catch (IllegalArgumentException e) {
            return "ERROR " + e.getMessage().substring(0, e.getMessage().indexOf(": "));
        }
    }

    private static void count(Map<String, Integer> counts, String key) {
        counts.merge(key, 1, Integer::sum);
    }

    private static void record(Map<String, Integer> counts, List<String> differences, Path file, String kind,
                               Object expected, Object actual) {
        count(counts, kind);
        differences.add(kind + "\t" + file + "\t" + clip(Objects.toString(expected)) + "\t" + clip(Objects.toString(actual)));
    }

    private static String clip(String s) {
        s = s.replace('\t', ' ').replace('\n', ' ');
        return s.length() > 600 ? s.substring(0, 600) + "..." : s;
    }
}
