package org.julclang.compiler.frontend.javac.lower;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerFrontend;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * F904, over entry points of every purpose, parameter count and last parameter type: an entry point whose count its purpose
 * takes is {@code JULC0103} unless its last parameter is {@code ScriptContext} or {@code PlutusData}; one whose count its
 * purpose does not take is the count's {@code JULC0008}; and both frontends answer alike, the default one through its gate.
 */
class EntrypointContextPropertyTest {

    private static final Set<String> CONTEXTS = Set.of("ScriptContext", "PlutusData", "org.julclang.ledger.ScriptContext",
            "org.julclang.core.PlutusData", "final ScriptContext");

    /** A validator annotation and the {@code @Entrypoint} it carries. */
    record Purpose(String annotation, String entrypoint, boolean takesThree) {
    }

    record Shape(Purpose purpose, int count, String last) {
        String source() {
            var parameters = new ArrayList<String>();
            for (int i = 0; i + 1 < count; i++) parameters.add("PlutusData p" + i);
            parameters.add(last + " ctx");
            return """
                    import java.math.BigInteger;
                    import org.julclang.core.PlutusData;
                    import org.julclang.ledger.ScriptContext;
                    import org.julclang.stdlib.annotation.*;
                    @%s
                    class V {
                        %s
                        static boolean validate(%s) { return true; }
                    }
                    """.formatted(purpose.annotation(), purpose.entrypoint(), String.join(", ", parameters));
        }

        String expected() {
            boolean counted = count == 2 || count == 3 && purpose.takesThree();
            if (!counted) return "JULC0008";
            return CONTEXTS.contains(last) ? "accepted" : "JULC0103";
        }
    }

    @Provide
    Arbitrary<Shape> shapes() {
        var purposes = Arbitraries.of(
                new Purpose("SpendingValidator", "@Entrypoint", true),
                new Purpose("MintingValidator", "@Entrypoint", false),
                new Purpose("WithdrawValidator", "@Entrypoint", false),
                new Purpose("CertifyingValidator", "@Entrypoint", false),
                new Purpose("VotingValidator", "@Entrypoint", false),
                new Purpose("ProposingValidator", "@Entrypoint", false),
                new Purpose("MultiValidator", "@Entrypoint", false),
                new Purpose("MultiValidator", "@Entrypoint(purpose = Purpose.SPEND)", true),
                new Purpose("MultiValidator", "@Entrypoint(purpose = Purpose.MINT)", false));
        var lasts = Arbitraries.of(List.copyOf(Set.of("ScriptContext", "PlutusData", "org.julclang.ledger.ScriptContext",
                "org.julclang.core.PlutusData", "final ScriptContext", "BigInteger", "long", "int", "boolean", "String",
                "byte[]", "PlutusData.ConstrData", "PlutusData.MapData", "org.julclang.ledger.TxInfo",
                "org.julclang.core.types.JulcList<PlutusData>")).stream().sorted().toList());
        return Combinators.combine(purposes, Arbitraries.integers().between(1, 4), lasts).as(Shape::new);
    }

    private static String outcome(Shape shape, CompilerFrontend frontend) {
        try {
            new JulcCompiler(null, new CompilerOptions().setFrontend(frontend)).compile(shape.source());
            return "accepted";
        } catch (CompilerException e) {
            return e.diagnostics().isEmpty() ? "uncoded" : e.diagnostics().getFirst().code();
        }
    }

    @Property(tries = 120, seed = "904")
    void theContextIsTheLastParameterOnBothFrontends(@ForAll("shapes") Shape shape) {
        assertEquals(shape.expected(), outcome(shape, CompilerFrontend.JAVAC), shape.source());
        assertEquals(shape.expected(), outcome(shape, CompilerFrontend.JAVAPARSER), shape.source());
    }
}
