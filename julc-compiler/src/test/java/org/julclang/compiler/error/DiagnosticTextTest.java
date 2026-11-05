package org.julclang.compiler.error;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** What a developer reads names their code. It never names a design document, a review or a compiler's internal type. */
class DiagnosticTextTest {

    private static final Pattern INTERNAL = Pattern.compile(
            "ADR-?\\s?\\d|\\bM\\d[a-z]\\b|\\bF\\d{3,}\\b|JavaParser|\\b[A-Z][A-Za-z]*(?:Expr|Stmt)\\b");
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"");

    @Test
    void theCatalogueNamesNoInternalDocumentOrType() throws IOException {
        var problems = new ArrayList<String>();
        String code = "";
        var field = Pattern.compile("^\\s*\"(code|title|template|summary|fix)\": \"(.*)\",?$");
        for (var line : Files.readAllLines(Path.of("src/main/resources/diagnostics.json"))) {
            var matcher = field.matcher(line);
            if (!matcher.find()) continue;
            if (matcher.group(1).equals("code")) code = matcher.group(2);
            else if (INTERNAL.matcher(matcher.group(2)).find()) problems.add(code + " " + matcher.group(1) + ": " + matcher.group(2));
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void noStringInTheCompilerNamesAnInternalDocument() throws IOException {
        var problems = new ArrayList<String>();
        var document = Pattern.compile("ADR-?\\s?\\d");
        for (String root : List.of("src/main/java", "../julc-cli/src/main/java", "../julc-gradle-plugin/src/main/java",
                "../julc-annotation-processor/src/main/java", "../julc-testkit/src/main/java")) {
            try (var files = Files.walk(Path.of(root))) {
                for (var file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                    int number = 0;
                    for (var line : Files.readAllLines(file)) {
                        number++;
                        var code = line.strip();
                        if (code.startsWith("*") || code.startsWith("/*") || code.startsWith("//")) continue;
                        var literal = STRING_LITERAL.matcher(line);
                        while (literal.find()) {
                            if (document.matcher(literal.group()).find()) problems.add(file + ":" + number + " " + literal.group());
                        }
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void anErrorShowsItsSourceLine() {
        var source = """
                import java.math.BigInteger;
                public class Probe {
                    static BigInteger m(BigInteger n) {
                        n = n.add(n);
                        return n;
                    }
                }
                """;
        var error = assertThrows(CompilerException.class,
                () -> new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions()).compileMethod(source, "m"));
        var diagnostic = error.diagnostics().getFirst();
        assertEquals("""
                4 |         n = n.add(n);
                  |         ^""", diagnostic.excerpt());
        assertTrue(error.describe().contains("n = n.add(n);"), error.describe());
    }

    @Test
    void javacsOwnErrorShowsItsSourceLineToo() {
        var source = """
                public class Probe {
                    static int m() { return missing; }
                }
                """;
        var error = assertThrows(CompilerException.class,
                () -> new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions()).compileMethod(source, "m"));
        var diagnostic = error.diagnostics().getFirst();
        assertEquals("JULC0085", diagnostic.code());
        assertTrue(diagnostic.excerpt().contains("return missing;"), String.valueOf(diagnostic.excerpt()));
        assertTrue(diagnostic.excerpt().contains("^^^^^^^"), diagnostic.excerpt());
    }

    @Test
    void aMissingPackageSaysHowToMakeALibraryVisible() {
        var source = """
                import com.example.lib.MathLib;
                public class Probe { static int m() { return 1; } }
                """;
        var error = assertThrows(CompilerException.class,
                () -> new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions()).compileMethod(source, "m"));
        assertTrue(error.diagnostics().getFirst().suggestion().contains("annotation processor path"), error.describe());
    }
}
