package org.julclang.cli;

import com.sun.source.tree.MethodTree;
import org.julclang.cli.check.TestDiscovery;
import org.julclang.cli.mcp.lint.LintEngine;
import org.julclang.cli.mcp.lint.LintTrees;
import org.julclang.cli.mcp.tools.EvaluateToolAccess;
import org.julclang.compiler.JavaSourceIntrospector;
import org.julclang.compiler.frontend.javac.JavacSyntax;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ADR-068 M6's equivalence gate for julc-cli's tools (lint findings, {@code julc check}'s discovery, the project scan, the MCP evaluate tool's arity): on every {@code .java} file of the corpus, what it gives equals what it gave at
 * M6's start, when javac's reading had been compared with JavaParser's ({@code adr/evidence/068-m6a-tools.md}, and the
 * baseline's digests in {@code adr/evidence/068/m6-tools-baseline.tsv}). Run by
 * {@code :julc-cli:adr068Tools -Padr068.tools.sources=<dir>[:<dir>...] -Padr068.tools.baseline=<dir> [-Padr068.tools.out=<dir>]}.
 */
@Tag("adr068-tools")
class Adr068ToolsCorpusTest {

    private final LintEngine lint = new LintEngine();

    @Test
    void theToolsAreTheBaselines() throws IOException {
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
        var outputs = new ArrayList<String>();
        for (Path file : files) {
            String text;
            try {
                text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(Files.readAllBytes(file))).toString();
            } catch (CharacterCodingException e) {
                continue;
            }
            outputs.add(output("lint", sources, file, lint.lint(text)));
            outputs.add(output("discovery", sources, file, discovery(file, text)));
            outputs.add(output("introspection", sources, file, introspection(text)));
            var names = new ArrayList<String>();
            var syntax = JavacSyntax.read(text);
            if (!syntax.hasErrors()) {
                LintTrees.methods(syntax).stream().limit(4).forEach(p -> names.add(((MethodTree) p.getLeaf()).getName().toString()));
            }
            names.add("noSuchMethod");
            for (String name : names) {
                outputs.add(output("arity", sources, file, name + "=" + EvaluateToolAccess.arity(text, name)));
            }
        }
        Files.write(out.resolve("cli-outputs.tsv"), outputs);
        assertBaseline("cli-outputs.tsv", outputs);
    }

    /** The source's {@code SourceInfo}, or its parse failure and whether the source then declares no JuLC role. */
    private static String introspection(String text) {
        try {
            return JavaSourceIntrospector.inspect(text).toString();
        } catch (JavaSourceIntrospector.SourceParseException e) {
            return "ERROR noRole=" + JavaSourceIntrospector.declaresNoJulcRole(text);
        }
    }

    private static String discovery(Path file, String text) {
        try {
            return TestDiscovery.discover(file, text).stream()
                    .map(t -> t.className() + "." + t.methodName())
                    .toList().toString();
        } catch (IllegalArgumentException e) {
            return "ERROR " + e.getMessage().substring(0, e.getMessage().indexOf(": "));
        }
    }

    /** What the tool gives for a corpus file, one line. */
    private static String output(String tool, String sources, Path file, Object value) {
        String key = file.toString();
        for (String dir : sources.split(File.pathSeparator)) {
            if (file.startsWith(dir)) key = Path.of(dir).getFileName() + "/" + Path.of(dir).relativize(file);
        }
        return tool + "\t" + key + "\t" + String.valueOf(value).replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    /** ADR-068 M6's gate: this run's lines against the frozen baseline {@code adr068.tools.baseline}, if one is named; they must be equal. */
    private static void assertBaseline(String file, List<String> lines) throws IOException {
        String baseline = System.getProperty("adr068.tools.baseline");
        if (baseline == null) return;
        var frozen = Files.readAllLines(Path.of(baseline).resolve(file));
        var moved = new ArrayList<String>();
        for (int i = 0; i < Math.max(frozen.size(), lines.size()) && moved.size() < 20; i++) {
            String was = i < frozen.size() ? frozen.get(i) : "(none)";
            String is = i < lines.size() ? lines.get(i) : "(none)";
            if (!was.equals(is)) moved.add(was + "  ->  " + is);
        }
        assertEquals(List.of(), moved, file + " moved");
        assertEquals(frozen.size(), lines.size());
    }
}
