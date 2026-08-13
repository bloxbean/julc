package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import com.bloxbean.cardano.julc.verification.RequiresSignerResolver;
import com.bloxbean.cardano.julc.verification.dsl.ir.*;
import com.bloxbean.cardano.julc.verification.dsl.worker.DslWorkerRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TypedDslPrototypeTest {
    @TempDir
    Path tempDir;

    @Test
    void generatedDslAndAnnotationProduceIdenticalCanonicalIrAndLean() throws Exception {
        String source = validatorSource();
        var compiled = new JulcCompiler(StdlibRegistry.defaultRegistry())
                .compileContract(source);
        var annotation = RequiresSignerResolver.resolve(
                source, "Authorized.java", "Authorized", compiled.contractSchema())
                .orElseThrow();
        DslPropertySet annotationIr = RequiresSignerDslLowering.lower(annotation);

        Path sources = tempDir.resolve("sources/generated");
        Files.createDirectories(sources);
        Path model = sources.resolve("AuthorizedModel.java");
        Files.writeString(model, ContractMetamodelGenerator.generate(
                compiled.contractSchema(), "generated", "AuthorizedModel"));
        Path specification = sources.resolve("SignerSpec.java");
        Files.writeString(specification, """
                package generated;
                import com.bloxbean.cardano.julc.verification.dsl.*;
                import com.bloxbean.cardano.julc.verification.dsl.ir.*;
                import static com.bloxbean.cardano.julc.verification.dsl.VerificationDsl.property;
                public final class SignerSpec implements VerificationSpecification {
                    public SignerSpec() {}
                    public DslPropertySet properties() {
                        var contract = new AuthorizedModel();
                        var required = contract.context().txInfo().signatories()
                                .contains(contract.datum().owner());
                        return DslPropertySet.of(property(
                                "Authorized.requires-signer.owner",
                                contract.exactUplcSucceeds().implies(required)));
                    }
                }
                """);
        Path classes = compile(model, specification);
        String classPath = classes + File.pathSeparator + System.getProperty("java.class.path");
        DslPropertySet dslIr = new DslWorkerRunner().run(
                classPath, "generated.SignerSpec", compiled.contractSchema(),
                tempDir.resolve("worker"), Duration.ofSeconds(10));

        assertEquals(PropertyIrCodec.canonicalJson(annotationIr),
                PropertyIrCodec.canonicalJson(dslIr));
        assertEquals(PropertyLeanRenderer.render(annotationIr),
                PropertyLeanRenderer.render(dslIr));
        assertTrue(PropertyLeanRenderer.render(dslIr).contains("txInfoSignatories"));
        assertTrue(Files.isRegularFile(
                tempDir.resolve("worker/verification-property-dsl.json")));
    }

    @Test
    void authoritativeValidationRejectsForgedDatumFieldAndOversizedAst() {
        var schema = new JulcCompiler(StdlibRegistry.defaultRegistry())
                .compileContract(validatorSource()).contractSchema();
        var forged = DslPropertySet.of(new DslProperty("forged", new CompareNode(
                CompareOperator.EQ,
                new FieldNode(new RootNode("datum", DslType.DATA),
                        "notAField", DslType.BYTE_STRING),
                new LiteralNode(DslType.BYTE_STRING, "00"))));
        var fieldError = assertThrows(IllegalArgumentException.class,
                () -> DslPropertyValidator.validate(forged, schema, 100));
        assertTrue(fieldError.getMessage().contains("Unknown datum field"));

        var valid = RequiresSignerDslLowering.lower(RequiresSignerResolver.resolve(
                validatorSource(), "Authorized.java", "Authorized", schema).orElseThrow());
        assertThrows(IllegalArgumentException.class,
                () -> DslPropertyValidator.validate(valid, schema, 2));
    }

    @Test
    void authoritativeValidationRejectsNonIntegerAndNoncanonicalLiterals() {
        var schema = new JulcCompiler(StdlibRegistry.defaultRegistry())
                .compileContract(validatorSource()).contractSchema();
        var rawLean = DslPropertySet.of(new DslProperty("raw-lean",
                new LiteralNode(DslType.BOOL, "by exact True.intro")));
        var typeError = assertThrows(IllegalArgumentException.class,
                () -> DslPropertyValidator.validate(rawLean, schema, 100));
        assertEquals("DSL v1 supports only integer literals", typeError.getMessage());

        var noncanonicalInteger = DslPropertySet.of(new DslProperty("leading-zero",
                new CompareNode(CompareOperator.EQ,
                        new LiteralNode(DslType.INTEGER, "01"),
                        new LiteralNode(DslType.INTEGER, "1"))));
        var formatError = assertThrows(IllegalArgumentException.class,
                () -> DslPropertyValidator.validate(noncanonicalInteger, schema, 100));
        assertEquals("Invalid canonical integer literal", formatError.getMessage());
    }

    private Path compile(Path... sources) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        try (var files = compiler.getStandardFileManager(null, null, null)) {
            files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classes));
            var units = files.getJavaFileObjects(sources);
            Boolean result = compiler.getTask(null, files, null,
                    List.of("-classpath", System.getProperty("java.class.path")),
                    null, units).call();
            assertTrue(result, "Generated DSL sources must compile");
        }
        return classes;
    }

    private static String validatorSource() {
        return """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import com.bloxbean.cardano.julc.verification.annotation.RequiresSigner;
                @RequiresSigner("datum.owner")
                @SpendingValidator
                class Authorized {
                    record Datum(byte[] owner) {}
                    record Redeemer() {}
                    @Entrypoint
                    static boolean validate(Datum datum, Redeemer redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;
    }
}
