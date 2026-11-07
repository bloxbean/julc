package org.julclang.compiler.frontend.javac.lower;

import org.julclang.compiler.CompilationContext;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.frontend.javac.JavacFrontend;
import org.julclang.compiler.frontend.javac.JavacProgram;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import java.util.List;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-070 D1 in the compiler alone: which cast targets have a constructor to test. {@code TypeTests.constructorOf} answers
 * from the registry and never rejects, so a cast it does not cover stays the view it was.
 */
class Adr070CastsTest {

    private static final String SOURCE = """
            import java.math.BigInteger;
            import org.julclang.core.PlutusData;
            import org.julclang.ledger.ScriptInfo;
            import org.julclang.stdlib.annotation.NewType;
            class C {
                sealed interface S { record A(BigInteger v) implements S { } record B(BigInteger v, BigInteger w) implements S { } record D() implements S { } }
                sealed interface T permits N { }
                @NewType record N(BigInteger v) implements T { }
                sealed interface Top permits Mid, E { }
                sealed interface Mid extends Top permits F { }
                record E() implements Top { }
                record F() implements Mid { }
                static long m(S s, ScriptInfo info, PlutusData d) { return 1; }
                static <T extends S, U extends T, W, X extends S.A> long g(T t, U u, W w, X x, Object o) { return 1; }
            }
            """;

    private static void withTypeTests(BiConsumer<JavacFacts, TypeTests> check) {
        var program = JavacProgram.analyze(List.of(JavacFrontend.Unit.of(SOURCE)), List.of());
        program.read(facts -> {
            var lowering = new Lowering(facts, CompilationContext.resolve(new CompilerOptions()));
            check.accept(facts, new TypeTests(facts, lowering.terms(), new Typing(facts), lowering));
            return null;
        });
    }

    @Test
    void aVariantOfAConstrSumHasItsConstructor() {
        withTypeTests((facts, typeTests) -> {
            assertEquals(0, typeTests.constructorOf(facts.elements().getTypeElement("C.S.A")).orElseThrow().constructor().tag());
            assertEquals(1, typeTests.constructorOf(facts.elements().getTypeElement("C.S.B")).orElseThrow().constructor().tag());
            assertEquals(2, typeTests.constructorOf(facts.elements().getTypeElement("C.S.D")).orElseThrow().constructor().tag());
            // A sealed sub-interface is a sum of its own: F is Mid's constructor.
            assertEquals(0, typeTests.constructorOf(facts.elements().getTypeElement("C.F")).orElseThrow().constructor().tag());
        });
    }

    /**
     * The cast's operand rule reads a type variable through its upper bound, and a variable bounded by a variable through
     * that one's: {@code T extends S} and {@code U extends T} are of S's family; an unbounded {@code W} and {@code Object} are
     * not; {@code X extends S.A} is of A, so a cast of it to A is a same-type cast (review M1-C2).
     */
    @Test
    void anOperandTypeIsReadThroughItsBounds() {
        withTypeTests((facts, typeTests) -> {
            var g = facts.elements().getTypeElement("C").getEnclosedElements().stream()
                    .filter(e -> e.getSimpleName().contentEquals("g")).map(ExecutableElement.class::cast).findFirst().orElseThrow();
            var s = facts.elements().getTypeElement("C.S");
            var a = facts.elements().getTypeElement("C.S.A");
            var types = g.getParameters().stream().map(VariableElement::asType).toList();
            assertTrue(typeTests.isOfThroughBounds(types.get(0), s), "T extends S");
            assertTrue(typeTests.isOfThroughBounds(types.get(1), s), "U extends T");
            assertFalse(typeTests.isOfThroughBounds(types.get(2), s), "W");
            assertTrue(typeTests.isOfThroughBounds(types.get(3), a), "X extends S.A");
            assertFalse(typeTests.isOfThroughBounds(types.get(3), s), "X extends S.A is A, not S");
            assertFalse(typeTests.isOfThroughBounds(types.get(4), s), "Object");
        });
    }

    /** A ledger sum is one: ScriptInfo's variants are its constructors in their declared order. */
    @Test
    void aLedgerSumsVariantHasItsConstructor() {
        withTypeTests((facts, typeTests) -> {
            assertEquals(0, typeTests.constructorOf(facts.elements().getTypeElement("org.julclang.ledger.ScriptInfo.MintingScript")).orElseThrow().constructor().tag());
            assertEquals(1, typeTests.constructorOf(facts.elements().getTypeElement("org.julclang.ledger.ScriptInfo.SpendingScript")).orElseThrow().constructor().tag());
        });
    }

    /**
     * No constructor, and no rejection: PlutusData's own variants, a @NewType variant, and E, whose sum Top also permits
     * the sealed interface Mid. Mid's variants would share tags with Top's own (M1-S1); the subset rejects such a hierarchy
     * (JULC0109) before lowering, and this analysis skips the scan.
     */
    @Test
    void anythingElseHasNone() {
        withTypeTests((facts, typeTests) -> {
            for (String name : List.of("org.julclang.core.PlutusData.MapData", "org.julclang.core.PlutusData.BytesData",
                    "org.julclang.core.PlutusData.IntData", "org.julclang.core.PlutusData.ConstrData", "C.N", "C.E")) {
                var element = facts.elements().getTypeElement(name);
                assertTrue(typeTests.constructorOf(element).isEmpty(), name);
            }
        });
    }
}
