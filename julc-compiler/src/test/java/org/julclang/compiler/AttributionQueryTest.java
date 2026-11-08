package org.julclang.compiler;

import com.sun.source.util.TreePath;
import org.julclang.compiler.frontend.javac.JavacFrontend;
import org.julclang.compiler.frontend.javac.JavacFrontend.Unit;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The embedder attribution query (ADR-068 addendum), from outside the frontend's package, as an embedder such as JOUST's
 * toolchain image uses it: read the attributed model inside the query, generate a validator from it, compile that.
 */
class AttributionQueryTest {

    static final String COUNTER = """
            package demo;

            import java.math.BigInteger;
            import org.julclang.stdlib.annotation.OnchainLibrary;

            @OnchainLibrary
            public class Counter {
                public static BigInteger next(BigInteger n) {
                    return n.add(BigInteger.ONE);
                }
            }
            """;

    @Test
    void anEmbedderGeneratesAValidatorFromTheModelAndCompilesIt() {
        String validator = JavacFrontend.shared().attribute(List.of(Unit.of(COUNTER)), List.of(), model -> {
            assertTrue(model.result().isAccepted(), () -> model.result().diagnostics().toString());
            var unit = model.roots().getFirst();
            var type = (TypeElement) model.trees().getElement(new TreePath(new TreePath(unit), unit.getTypeDecls().getFirst()));
            assertEquals("demo.Counter", type.getQualifiedName().toString());
            assertNotNull(model.trees().getPath(type));
            ExecutableElement next = ElementFilter.methodsIn(type.getEnclosedElements()).getFirst();
            assertTrue(model.types().isSameType(next.getReturnType(),
                    model.elements().getTypeElement("java.math.BigInteger").asType()));
            // A type of the on-chain API that the roots never name completes during the query.
            assertNotNull(model.elements().getTypeElement("org.julclang.ledger.TxOutRef"));
            return """
                    package demo;

                    import java.math.BigInteger;
                    import org.julclang.core.PlutusData;
                    import org.julclang.stdlib.annotation.*;

                    @SpendingValidator
                    class Generated {
                        @Entrypoint
                        static boolean validate(BigInteger redeemer, PlutusData ctx) {
                            return %s.%s(redeemer).signum() > 0;
                        }
                    }
                    """.formatted(type.getSimpleName(), next.getSimpleName());
        });

        var result = new JulcCompiler().compile(validator, List.of(COUNTER));

        assertFalse(result.hasErrors(), () -> result.diagnostics().toString());
        assertNotNull(result.program());
    }

    @Test
    void aJavacErrorRunsTheQueryOverThePartialModel() {
        String broken = COUNTER.replace("return n.add(BigInteger.ONE);", "return n.missing();");
        boolean hasModel = JavacFrontend.shared().attribute(List.of(Unit.of(broken)), List.of(), model -> {
            assertFalse(model.result().isAccepted());
            assertFalse(model.result().diagnostics().isEmpty());
            return model.elements() != null && model.types() != null && model.trees() != null;
        });
        assertTrue(hasModel);
    }

    @Test
    void aRootRejectedBeforeJavacRunsHasNoModel() {
        // A root that declares a type of JuLC's on-chain API is rejected before javac runs.
        String ledger = """
                package org.julclang.ledger;

                public record TxOutRef(byte[] id, long index) {}
                """;
        JavacFrontend.shared().attribute(List.of(Unit.of(ledger)), List.of(), model -> {
            assertFalse(model.result().isAccepted());
            assertTrue(model.result().diagnostics().stream().anyMatch(d -> "JULC0081".equals(d.code())),
                    () -> model.result().diagnostics().toString());
            assertNull(model.elements());
            assertNull(model.types());
            assertNull(model.trees());
            return null;
        });
    }

    @Test
    void noRootsIsAcceptedWithNoModel() {
        JavacFrontend.shared().attribute(List.of(), List.of(), model -> {
            assertTrue(model.result().isAccepted());
            assertTrue(model.roots().isEmpty());
            assertNull(model.elements());
            return null;
        });
    }

    @Test
    void queriesOnDifferentThreadsAreIndependent() throws Exception {
        var executor = Executors.newFixedThreadPool(4);
        try {
            var tasks = new ArrayList<Callable<String>>();
            for (int i = 0; i < 8; i++) {
                String source = COUNTER.replace("class Counter", "class Counter" + i);
                tasks.add(() -> JavacFrontend.shared().attribute(List.of(Unit.of(source)), List.of(), model -> {
                    var unit = model.roots().getFirst();
                    var type = (TypeElement) model.trees().getElement(
                            new TreePath(new TreePath(unit), unit.getTypeDecls().getFirst()));
                    return type.getQualifiedName().toString();
                }));
            }
            var names = new ArrayList<String>();
            for (Future<String> future : executor.invokeAll(tasks)) names.add(future.get());
            assertEquals(List.of("demo.Counter0", "demo.Counter1", "demo.Counter2", "demo.Counter3", "demo.Counter4",
                    "demo.Counter5", "demo.Counter6", "demo.Counter7"), names);
        } finally {
            executor.shutdownNow();
        }
    }
}
