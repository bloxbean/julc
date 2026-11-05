package org.julclang.playground.frontend;

import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.LibrarySource;
import org.julclang.compiler.LibrarySourceResolver;
import org.julclang.tools.model.CompileRequest;
import org.julclang.tools.service.ToolsService;
import org.julclang.vm.JulcVm;
import org.julclang.vm.java.JavaVmProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Java the playground's frontend puts in the editor must compile: the code it opens with and the validator snippets
 * its completion inserts. They live in TypeScript, so a change of the language's rules does not reach them by itself, and
 * the Wasm smoke tests do not compile them. The first thing a user sees should not be a rejection.
 */
class FrontendSourcesCompileTest {

    private static final Path LIB = Path.of("frontend/src/lib");

    private static final Map<String, LibrarySource> LIBRARIES =
            LibrarySourceResolver.scanClasspathSources(JulcCompiler.class.getClassLoader());
    private static final ToolsService SERVICE = new ToolsService(ToolsService.compilerFor(LIBRARIES), LIBRARIES,
            () -> JulcVm.withProvider(new JavaVmProvider()));

    /** Through the service the playground compiles with, so with the same libraries. */
    private static void assertCompiles(String source) {
        var result = SERVICE.compile(new CompileRequest(source));
        assertEquals(200, result.status(), () -> result.body() + "\n" + source);
        assertNotNull(result.body().uplcText(), source);
    }

    /** The control: the code the editor used to open with, which JULC0103 rejects, does not pass {@link #assertCompiles}. */
    @Test
    void aRejectedSourceFailsTheCheck() {
        assertThrows(AssertionError.class, () -> assertCompiles("""
                import java.math.BigInteger;
                import org.julclang.stdlib.annotation.Entrypoint;
                import org.julclang.stdlib.annotation.SpendingValidator;

                @SpendingValidator
                class SimpleValidator {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        return true;
                    }
                }
                """));
    }

    @Test
    void theCodeTheEditorOpensWithCompiles() throws IOException {
        Matcher starter = Pattern.compile("DEFAULT_JAVA_SOURCE = `(.*?)`;", Pattern.DOTALL)
                .matcher(Files.readString(LIB.resolve("stores/editor.ts")));
        assertTrue(starter.find(), "DEFAULT_JAVA_SOURCE");
        assertCompiles(starter.group(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"spending validator", "minting validator"})
    void aValidatorSnippetCompiles(String label) throws IOException {
        assertCompiles(snippet(Files.readString(LIB.resolve("monaco/java-completions.ts")), label));
    }

    /** The snippet's lines as Java, each placeholder at its default and a bare tab stop empty. */
    private static String snippet(String completions, String label) {
        int start = completions.indexOf("label: '" + label + "'");
        assertTrue(start >= 0, label);
        int from = completions.indexOf("insertText: [", start);
        String body = completions.substring(from, completions.indexOf("].join(", from));
        List<String> lines = new ArrayList<>();
        Matcher line = Pattern.compile("^\\s*'(.*)',$", Pattern.MULTILINE).matcher(body);
        while (line.find()) lines.add(line.group(1));
        assertFalse(lines.isEmpty(), label);
        return String.join("\n", lines).replaceAll("\\$\\{\\d+:([^}]*)}", "$1").replaceAll("\\$\\{\\d+}", "");
    }
}
