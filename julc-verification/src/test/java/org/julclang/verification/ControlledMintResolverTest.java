package org.julclang.verification;

import org.julclang.compiler.JulcCompiler;
import org.julclang.core.text.UplcPrinter;
import org.julclang.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ControlledMintResolverTest {
    private static final String AUTHORITY =
            "4a554c435f5645524946595f415554484f524954595f303030303031";

    @Test
    void resolvesCanonicalFixedPolicyParameters() {
        String source = validator("""
                @ControlledMint(authority="%s", tokenName="4A554C43",
                    quantity=1, action=MintAction.MINT)
                """.formatted(AUTHORITY));
        var compiled = compiler().compileContract(source);

        var property = ControlledMintResolver.resolve(
                source, "TokenPolicy.java", "TokenPolicy",
                compiled.contractSchema()).orElseThrow();

        assertEquals(ControlledMintProperty.TEMPLATE, property.template());
        assertEquals(AUTHORITY, property.authorityHex());
        assertEquals("4a554c43", property.tokenNameHex());
        assertEquals("1", property.quantity());
        assertEquals("MINT", property.action());
        assertEquals("minting", property.scriptPurpose());

        String groupedQuantity = validator("""
                @ControlledMint(authority="%s", tokenName="",
                    quantity=1_000, action=MintAction.MINT)
                """.formatted(AUTHORITY));
        var groupedCompiled = compiler().compileContract(groupedQuantity);
        assertEquals("1000", ControlledMintResolver.resolve(
                groupedQuantity, "Grouped.java", "TokenPolicy",
                groupedCompiled.contractSchema()).orElseThrow().quantity());
    }

    @Test
    void burnNegatesMagnitudeAndInvalidAuthorityFailsAtSource() {
        String burn = validator("""
                @ControlledMint(authority="%s", tokenName="", quantity=7,
                    action=MintAction.BURN)
                """.formatted(AUTHORITY));
        var compiled = compiler().compileContract(burn);
        assertEquals("-7", ControlledMintResolver.resolve(
                burn, "Burn.java", "TokenPolicy", compiled.contractSchema())
                .orElseThrow().quantity());

        String invalid = validator("""
                @ControlledMint(authority="00", tokenName="", quantity=1,
                    action=MintAction.MINT)
                """);
        var invalidCompiled = compiler().compileContract(invalid);
        var error = assertThrows(VerificationPropertyException.class,
                () -> ControlledMintResolver.resolve(
                        invalid, "Invalid.java", "TokenPolicy",
                        invalidCompiled.contractSchema()));
        assertEquals("Invalid.java", error.sourceLocation().fileName());
    }

    @Test
    void annotationHasZeroEffectOnEmittedUplc() {
        String annotated = validator("""
                @ControlledMint(authority="%s", tokenName="4a554c43",
                    quantity=1, action=MintAction.MINT)
                """.formatted(AUTHORITY));
        String plain = annotated
                .replace("import org.julclang.verification.annotation.*;\n", "")
                .replaceAll("(?s)@ControlledMint\\(.*?MintAction\\.MINT\\)\\s*", "");

        assertEquals(UplcPrinter.print(compiler().compile(plain).program()),
                UplcPrinter.print(compiler().compile(annotated).program()));
    }

    private static JulcCompiler compiler() {
        return new JulcCompiler(StdlibRegistry.defaultRegistry());
    }

    private static String validator(String annotation) {
        return """
                import org.julclang.stdlib.annotation.*;
                import org.julclang.ledger.ScriptContext;
                import org.julclang.verification.annotation.*;

                %s
                @MintingValidator
                class TokenPolicy {
                    record Redeemer() {}
                    @Entrypoint
                    static boolean validate(Redeemer redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """.formatted(annotation);
    }
}
