package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import com.bloxbean.cardano.julc.verification.ControlledMintResolver;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslPropertySet;
import com.bloxbean.cardano.julc.verification.dsl.type.ContractTypeProjection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MintingDslTest {
    private static final String AUTHORITY =
            "4a554c435f5645524946595f415554484f524954595f303030303031";

    @Test
    void controlledAnnotationAndDslHaveIdenticalCanonicalIrAndLean() {
        String source = controlledSource();
        var compiled = compiler().compileContract(source);
        var annotation = ControlledMintResolver.resolve(
                source, "TokenPolicy.java", "TokenPolicy", compiled.contractSchema())
                .orElseThrow();
        DslPropertySet annotationIr = ControlledMintDslLowering.lower(annotation);
        String hash = ContractTypeProjection.sha256(
                ContractTypeProjection.project(compiled.contractSchema()));
        DslPropertySet dslIr = MintingDsl.controlledMintPropertySet(
                annotation.propertyId(), AUTHORITY, "4a554c43", "1", hash);

        DslPropertyValidator.validate(dslIr, compiled.contractSchema(), 10_000);
        assertEquals(PropertyIrCodec.canonicalJson(annotationIr),
                PropertyIrCodec.canonicalJson(dslIr));
        assertEquals(PropertyLeanRenderer.render(annotationIr),
                PropertyLeanRenderer.render(dslIr));
        assertEquals(PropertyIrCodec.canonicalJson(dslIr), annotation.canonicalDslJson());
        assertTrue(PropertyIrCodec.canonicalJson(dslIr)
                .contains("\"format\":\"julc.verification.dsl\""));
    }

    private static JulcCompiler compiler() {
        return new JulcCompiler(StdlibRegistry.defaultRegistry());
    }

    private static String controlledSource() {
        return """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import com.bloxbean.cardano.julc.verification.annotation.*;
                @ControlledMint(authority="%s", tokenName="4a554c43",
                    quantity=1, action=MintAction.MINT)
                @MintingValidator
                class TokenPolicy {
                    record Redeemer() {}
                    @Entrypoint static boolean validate(Redeemer r, ScriptContext c) {
                        return true;
                    }
                }
                """.formatted(AUTHORITY);
    }
}
