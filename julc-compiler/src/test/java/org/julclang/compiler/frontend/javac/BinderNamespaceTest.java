package org.julclang.compiler.frontend.javac;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ADR-060 G2's namespace on javac's trees: what counts as declared, and what does not. */
class BinderNamespaceTest {

    private static JavaFileObject source(String name, String text) {
        return new SimpleJavaFileObject(URI.create("string:///" + name + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return text;
            }
        };
    }

    @Test
    void onlyDeclaredNamesCount() throws IOException {
        var validator = source("V", """
                class V {
                    static java.math.BigInteger fee(java.math.BigInteger amount) { return amount; }
                }
                """);
        var library = source("Lib", "class Lib { static long total(long acc) { return acc; } }");
        var task = (JavacTask) ToolProvider.getSystemJavaCompiler().getTask(null, null, null, null, null, List.of(validator, library));
        var units = new ArrayList<CompilationUnitTree>();
        task.parse().forEach(units::add);

        var allowed = JavacCompilation.binderNamespace(units);
        for (var name : List.of("#__match_tag", "amount", "amount'2", "acc", "V.fee", "org.example.Lib.total")) {
            assertTrue(allowed.test(name), name);
        }
        // Type names and names that only appear in calls do not count.
        for (var name : List.of("__match_tag", "scriptContextData", "x", "xs__", "go", "V.missing", "V", "Lib", "java", "BigInteger")) {
            assertFalse(allowed.test(name), name);
        }
    }
}
