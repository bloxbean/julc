package org.julclang.verification;

import org.julclang.compiler.JulcCompiler;
import org.julclang.core.text.UplcPrinter;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.verification.dsl.PropertyIrCodec;
import org.julclang.verification.dsl.StatefulSpendingDslLowering;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatefulSpendingResolverTest {

    @Test
    void resolvesCompleteProfileThroughCompilerTypes() {
        String source = validator("BigInteger", "BigInteger", completeAnnotations());
        var compiled = compiler().compileContract(source);

        var property = StatefulSpendingResolver.resolve(
                source, "Machine.java", "Machine", compiled.contractSchema()).orElseThrow();

        assertEquals(StatefulSpendingProperty.TEMPLATE, property.template());
        assertEquals("datum.owner", property.authority().path());
        assertEquals("datum.state", property.currentState().path());
        assertEquals("redeemer.nextState", property.nextState().path());
        assertEquals("GREATER_THAN", property.relation());
        assertEquals("SINGLE_CONTINUING_OUTPUT", property.outputSelection());
        assertTrue(property.domainAssumptions().isEmpty());
        assertTrue(property.canonicalDslJson().contains("strict-decode"));
        assertTrue(property.canonicalDslJson().contains("contains"));
        assertTrue(property.canonicalDslJson().contains("signatories"));
        assertEquals(property.canonicalDslJson(), PropertyIrCodec.canonicalJson(
                StatefulSpendingDslLowering.lower(
                        property, compiled.contractSchema())));
    }

    @Test
    void rejectsPartialAndMistypedProfilesAtSource() {
        String partial = validator("BigInteger", "BigInteger", """
                @RequiresSigner("datum.owner")
                @Monotonic(current="datum.state", next="redeemer.nextState",
                    relation=Relation.GREATER_THAN)
                """);
        var partialCompiled = compiler().compileContract(partial);
        var partialError = assertThrows(VerificationPropertyException.class,
                () -> StatefulSpendingResolver.resolve(
                        partial, "Partial.java", "Machine",
                        partialCompiled.contractSchema()));
        assertTrue(partialError.getMessage().contains("@PreservesValue"));
        assertTrue(partialError.getMessage().contains("Partial.java"));

        String wrong = validator("String", "BigInteger", completeAnnotations());
        var wrongCompiled = compiler().compileContract(wrong);
        var wrongError = assertThrows(VerificationPropertyException.class,
                () -> StatefulSpendingResolver.resolve(
                        wrong, "Wrong.java", "Machine", wrongCompiled.contractSchema()));
        assertTrue(wrongError.getMessage().contains("must resolve to integer"));
    }

    @Test
    void profileAnnotationsHaveZeroEffectOnUplc() {
        String annotated = validator("BigInteger", "BigInteger", completeAnnotations());
        String plain = annotated
                .replace("import org.julclang.verification.annotation.*;\n", "")
                .replace(completeAnnotations(), "");

        assertEquals(UplcPrinter.print(compiler().compile(plain).program()),
                UplcPrinter.print(compiler().compile(annotated).program()));
    }

    private static JulcCompiler compiler() {
        return new JulcCompiler(StdlibRegistry.defaultRegistry());
    }

    private static String completeAnnotations() {
        return """
                @RequiresSigner("datum.owner")
                @Monotonic(current="datum.state", next="redeemer.nextState",
                    relation=Relation.GREATER_THAN)
                @PreservesValue(output=OutputSelection.SINGLE_CONTINUING_OUTPUT)
                """;
    }

    private static String validator(
            String currentType, String nextType, String annotations) {
        return """
                import org.julclang.stdlib.annotation.*;
                import org.julclang.ledger.ScriptContext;
                import org.julclang.verification.annotation.*;
                import java.math.BigInteger;

                %s
                @SpendingValidator
                class Machine {
                    record Datum(byte[] owner, %s state) {}
                    record Redeemer(%s nextState) {}
                    @Entrypoint
                    static boolean validate(Datum datum, Redeemer redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """.formatted(annotations, currentType, nextType);
    }
}
