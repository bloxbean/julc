package org.julclang.compiler;

import com.github.javaparser.StaticJavaParser;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.uplc.UplcGenerator;
import org.julclang.core.Constant;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-060 G2: in tests, every binder that reaches UPLC generation must be a reserved {@code #}
 * name, a name the sources declare (a block-local rename {@code name'N} counts as its name), or
 * a qualified method name. The root build enables the check for every test task, so any lowering
 * that invents a legal-Java binder name fails the tests that exercise it.
 */
class BinderNamespaceCheckTest {
    @Test
    void testTasksEnableTheCheck() {
        assertTrue(Boolean.getBoolean(JulcCompiler.VERIFY_BINDER_NAMESPACE),
                "the root build.gradle test configuration must set " + JulcCompiler.VERIFY_BINDER_NAMESPACE);
    }

    @Test
    void uplcGenerationRejectsABinderOutsideTheNamespace() {
        var context = CompilationContext.pv11Defaults();
        context.verifyBinderNames(name -> name.startsWith("#") || name.equals("declared"));
        var unit = new PirTerm.Const(Constant.unit());
        for (var name : List.of("#acc", "declared"))
            assertDoesNotThrow(() -> new UplcGenerator(context, null).generate(new PirTerm.Lam(name, new PirType.UnitType(), unit)));
        var error = assertThrows(IllegalStateException.class, () -> new UplcGenerator(context, null)
                .generate(new PirTerm.Let("acc", unit, unit)));
        assertTrue(error.getMessage().contains("'acc'"), error.getMessage());
    }

    @Test
    void javaFrontendNamespace() {
        var validator = StaticJavaParser.parse("""
                class V {
                    static java.math.BigInteger fee(java.math.BigInteger amount) { return amount; }
                }
                """);
        var library = StaticJavaParser.parse("class Lib { static long total(long acc) { return acc; } }");
        var allowed = JulcCompiler.sourceBinderNamespace(validator, List.of(library));
        for (var name : List.of("#__match_tag", "amount", "amount'2", "acc", "V.fee", "org.example.Lib.total"))
            assertTrue(allowed.test(name), name);
        // Only declared names count: type names and names that only appear in calls do not.
        for (var name : List.of("__match_tag", "scriptContextData", "x", "xs__", "go", "V.missing", "V", "Lib",
                "java", "BigInteger"))
            assertFalse(allowed.test(name), name);
    }
}
