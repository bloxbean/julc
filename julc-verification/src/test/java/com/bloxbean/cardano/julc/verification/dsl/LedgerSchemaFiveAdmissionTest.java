package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.verification.ComposedDslProperty;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslPropertySet;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslPurpose;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslDomain;
import com.bloxbean.cardano.julc.verification.dsl.type.ContractTypeProjection;
import com.bloxbean.cardano.julc.verification.dsl.type.LedgerTypeRef;
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

class LedgerSchemaFiveAdmissionTest {
    @TempDir Path tempDir;

    @Test
    void generatedSchemaFiveModelComposesClosedTransactionContext() throws Exception {
        var compiled = new JulcCompiler().compileContract(validatorSource());
        Path sources = tempDir.resolve("sources/evidence");
        Files.createDirectories(sources);
        Path model = sources.resolve("LedgerGateModel.java");
        String generated = ContractMetamodelGenerator.generate(
                compiled.contractSchema(), "evidence", "LedgerGateModel");
        Files.writeString(model, generated);
        Path specification = sources.resolve("LedgerGateProperties.java");
        Files.writeString(specification, """
                package evidence;
                import com.bloxbean.cardano.julc.verification.dsl.*;
                import com.bloxbean.cardano.julc.verification.dsl.ir.*;
                import static com.bloxbean.cardano.julc.verification.dsl.VerificationDsl.*;
                public final class LedgerGateProperties implements VerificationSpecification {
                    public DslPropertySet properties() {
                        var contract = new LedgerGateModel();
                        var tx = contract.context().txInfo();
                        var referenceShape = tx.referenceInputs().exactlyOne(input ->
                            input.resolved().datum().whenHash(hash ->
                                tx.datums().containsKey(hash))
                            .and(input.resolved().address().paymentCredential()
                                .whenPubKey(key -> tx.inputs()
                                    .forPaymentKey(key).isNotEmpty()))
                            .and(input.resolved().address().stakingCredential()
                                .isPresent()));
                        var ownRef = contract.ownInput().exists(input ->
                            input.outRef().eq(contract.currentOutputRef()));
                        var continuation = contract.continuingOutputs().exactlyOne(output ->
                            output.datum().isInline()
                              .and(output.referenceScript().isEmpty())
                              .and(output.value().lovelace().ge(integer(0))));
                        return contract.properties(property("ledger.context", DslDomain.NONE,
                            referenceShape.and(ownRef).and(continuation)
                                .and(tx.redeemers().lookupFirst(
                                    contract.context().scriptPurpose().typed()).isPresent())
                                .and(tx.datums().containsKey(LedgerExpressions.datumHash(
                                    bytes("00010203")).typed()))
                                .and(contract.context().scriptPurpose().isSpending())
                                .and(tx.fee().ge(integer(0)))));
                    }
                }
                """);
        Path classes = compile(model, specification);
        DslPropertySet candidate = new DslWorkerRunner().run(
                classes + File.pathSeparator + System.getProperty("java.class.path"),
                "evidence.LedgerGateProperties", compiled.contractSchema(),
                tempDir.resolve("worker"), Duration.ofSeconds(10));

        assertEquals(DslPropertySet.SCHEMA_VERSION, candidate.schemaVersion());
        var promoted = ComposedDslPromotion.promote(candidate, compiled.contractSchema(),
                "LedgerGate", "LedgerGateProperties.java");
        assertEquals(ComposedDslProperty.LEDGER_SCHEMA_VERSION, promoted.schemaVersion());
        assertEquals(ComposedDslProperty.LEDGER_TEMPLATE, promoted.template());
        assertTrue(promoted.guaranteeRules().contains("ledger-helper:FIND_OWN_INPUT"));
        assertTrue(promoted.guaranteeRules().contains("ledger-helper:CONTINUING_OUTPUTS"));
        String lean = TypedPropertyLeanRenderer.renderExpression(
                candidate.properties().getFirst().expression(),
                ContractTypeProjection.project(compiled.contractSchema()));
        assertTrue(lean.contains("findOwnInput"), lean);
        assertTrue(lean.contains("txInfoReferenceInputs"), lean);
        assertTrue(lean.contains("julcContinuingOutputs"), lean);
        assertTrue(lean.contains("txInfoData"), lean);
        assertTrue(lean.contains("toScriptPurpose"), lean);
    }

    @Test
    void forgedLedgerFieldAndSpendingHelperOnMintingFailClosed() {
        var spending = new JulcCompiler().compileContract(validatorSource()).contractSchema();
        String spendingHash = ContractTypeProjection.sha256(
                ContractTypeProjection.project(spending));
        var root = LedgerExpressions.context().node();
        var forged = new LedgerFieldNode(root, LedgerTypeAuthority.SCRIPT_CONTEXT,
                "txInfo", new LedgerTypeRef(LedgerTypeRef.LedgerKind.TX_OUT));
        var forgedSet = DslPropertySet.schema1(DslPurpose.SPENDING, spendingHash,
                VerificationDsl.property("forged.field", DslDomain.NONE,
                        new BoolExpr(new TypedEqualityNode(forged, forged,
                                new LedgerTypeRef(LedgerTypeRef.LedgerKind.TX_OUT), false))));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> DslPropertyValidator.validate(forgedSet, spending, 100))
                .getMessage().contains("pinned model"));

        var minting = new JulcCompiler().compileContract("""
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                @MintingValidator class MintGate {
                    record Redeemer() {}
                    @Entrypoint static boolean validate(Redeemer r, ScriptContext c) {
                        return true;
                    }
                }
                """).contractSchema();
        String mintHash = ContractTypeProjection.sha256(
                ContractTypeProjection.project(minting));
        var currentReference = new LedgerHelperNode(
                LedgerHelperNode.LedgerHelperKind.CURRENT_OUTPUT_REF,
                List.of(LedgerExpressions.context().node()),
                LedgerTypeAuthority.TX_OUT_REF);
        var wrongPurpose = DslPropertySet.schema1(DslPurpose.MINTING, mintHash,
                VerificationDsl.property("forged.purpose", DslDomain.NONE,
                        new BoolExpr(new TypedEqualityNode(currentReference, currentReference,
                                LedgerTypeAuthority.TX_OUT_REF, false))));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> DslPropertyValidator.validate(wrongPurpose, minting, 100))
                .getMessage().contains("only for spending"));
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
            assertTrue(result, "Generated schema-5 model and specification must compile");
        }
        return classes;
    }

    private static String validatorSource() {
        return """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import java.math.BigInteger;
                @SpendingValidator class LedgerGate {
                    record Datum(BigInteger state) {}
                    record Redeemer(BigInteger next) {}
                    @Entrypoint static boolean validate(
                            Datum datum, Redeemer redeemer, ScriptContext context) {
                        return true;
                    }
                }
                """;
    }
}
