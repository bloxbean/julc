package com.bloxbean.cardano.julc.verification;

import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.core.text.UplcPrinter;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequiresSignerResolverTest {

    @Test
    void resolvesDatumByteStringThroughCompilerOwnedSchema() {
        String source = validator("byte[]", "@RequiresSigner(\"datum.owner\")");
        var compiled = new JulcCompiler(StdlibRegistry.defaultRegistry())
                .compileContract(source);

        var property = RequiresSignerResolver.resolve(
                source, "Authorized.java", "Authorized", compiled.contractSchema())
                .orElseThrow();

        assertEquals("julc.requires-signer/v1", property.template());
        assertEquals("Authorized.requires-signer.owner", property.propertyId());
        assertEquals("datum.owner", property.sourcePath());
        assertEquals("Datum", property.datumType());
        assertEquals("bytes", property.ownerType());
        assertTrue(property.domainAssumptions().isEmpty());
        assertEquals(2, property.path().size());
        assertTrue(property.source().line() > 1);
    }

    @Test
    void rejectsMissingAndIncompatibleFieldsAtAnnotationLocation() {
        for (String annotation : new String[]{
                "@RequiresSigner(\"datum.missing\")",
                "@RequiresSigner(\"redeemer.owner\")"}) {
            String source = validator("byte[]", annotation);
            var compiled = new JulcCompiler(StdlibRegistry.defaultRegistry())
                    .compileContract(source);
            var error = assertThrows(VerificationPropertyException.class,
                    () -> RequiresSignerResolver.resolve(
                            source, "Bad.java", "Authorized", compiled.contractSchema()));
            assertTrue(error.getMessage().contains("Bad.java"));
            assertTrue(error.getMessage().contains("@RequiresSigner"));
        }

        String source = validator("BigInteger", "@RequiresSigner(\"datum.owner\")")
                .replace("import com.bloxbean.cardano.julc.ledger.ScriptContext;",
                        "import com.bloxbean.cardano.julc.ledger.ScriptContext;\n"
                                + "import java.math.BigInteger;");
        var compiled = new JulcCompiler(StdlibRegistry.defaultRegistry())
                .compileContract(source);
        var error = assertThrows(VerificationPropertyException.class,
                () -> RequiresSignerResolver.resolve(
                        source, "WrongType.java", "Authorized", compiled.contractSchema()));
        assertTrue(error.getMessage().contains("must resolve to byte[] or a key-hash type"));
    }

    @Test
    void annotationHasZeroEffectOnEmittedUplc() {
        String annotated = validator("byte[]", "@RequiresSigner(\"datum.owner\")");
        String plain = annotated
                .replace("import com.bloxbean.cardano.julc.verification.annotation.RequiresSigner;\n", "")
                .replace("@RequiresSigner(\"datum.owner\")\n", "");
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry());

        String annotatedUplc = UplcPrinter.print(compiler.compile(annotated).program());
        String plainUplc = UplcPrinter.print(compiler.compile(plain).program());

        assertEquals(plainUplc, annotatedUplc);
    }

    @Test
    void acceptsCompilerKeyHashNewtypeButRejectsJavaString() {
        String keyHash = validator("PubKeyHash", "@RequiresSigner(\"datum.owner\")")
                .replace("import com.bloxbean.cardano.julc.ledger.ScriptContext;",
                        "import com.bloxbean.cardano.julc.ledger.ScriptContext;\n"
                                + "import com.bloxbean.cardano.julc.ledger.PubKeyHash;");
        var compiledKeyHash = new JulcCompiler(StdlibRegistry.defaultRegistry())
                .compileContract(keyHash);
        assertTrue(RequiresSignerResolver.resolve(
                keyHash, "KeyHash.java", "Authorized", compiledKeyHash.contractSchema())
                .isPresent());

        String string = validator("String", "@RequiresSigner(\"datum.owner\")");
        var compiledString = new JulcCompiler(StdlibRegistry.defaultRegistry())
                .compileContract(string);
        assertThrows(VerificationPropertyException.class,
                () -> RequiresSignerResolver.resolve(
                        string, "StringOwner.java", "Authorized",
                        compiledString.contractSchema()));
    }

    @Test
    void ignoresAnUnrelatedAnnotationWithTheSameSimpleName() {
        String source = validator("byte[]", "@RequiresSigner(\"datum.owner\")")
                .replace(
                        "import com.bloxbean.cardano.julc.verification.annotation.RequiresSigner;",
                        "@interface RequiresSigner { String value(); }");
        var compiled = new JulcCompiler(StdlibRegistry.defaultRegistry())
                .compileContract(source);

        assertTrue(RequiresSignerResolver.resolve(
                source, "Unrelated.java", "Authorized", compiled.contractSchema())
                .isEmpty());
    }

    @Test
    void acceptsTheFullyQualifiedVerificationAnnotation() {
        String source = validator("byte[]",
                        "@com.bloxbean.cardano.julc.verification.annotation.RequiresSigner(\"datum.owner\")")
                .replace(
                        "import com.bloxbean.cardano.julc.verification.annotation.RequiresSigner;\n",
                        "");
        var compiled = new JulcCompiler(StdlibRegistry.defaultRegistry())
                .compileContract(source);

        assertTrue(RequiresSignerResolver.resolve(
                source, "Qualified.java", "Authorized", compiled.contractSchema())
                .isPresent());
    }

    private static String validator(String ownerType, String property) {
        return """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import com.bloxbean.cardano.julc.verification.annotation.RequiresSigner;

                %s
                @SpendingValidator
                class Authorized {
                    record Datum(%s owner) {}
                    record Redeemer() {}

                    @Entrypoint
                    static boolean validate(Datum datum, Redeemer redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """.formatted(property, ownerType);
    }
}
