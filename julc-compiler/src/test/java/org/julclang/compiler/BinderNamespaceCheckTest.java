package org.julclang.compiler;

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

    /** G2 on the javac frontend: a compilation under the check, which the test tasks enable, has nothing outside the namespace. */
    @Test
    void aCompilationKeepsToTheNamespace() {
        var result = new JulcCompiler().compileMethod("""
                class V {
                    static long total(long acc) { return acc + 1; }
                    static long m(long amount) { return total(amount); }
                }
                """, "m", List.of());
        assertFalse(result.hasErrors(), result.diagnostics()::toString);
    }
}
