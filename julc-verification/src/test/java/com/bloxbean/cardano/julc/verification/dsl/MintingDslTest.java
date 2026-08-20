package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import com.bloxbean.cardano.julc.verification.ControlledMintProperty;
import com.bloxbean.cardano.julc.verification.ControlledMintResolver;
import com.bloxbean.cardano.julc.verification.OneShotMintProperty;
import com.bloxbean.cardano.julc.verification.dsl.ir.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MintingDslTest {
    private static final String AUTHORITY =
            "4a554c435f5645524946595f415554484f524954595f303030303031";
    private static final String TX_ID =
            "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20";

    @TempDir
    Path tempDir;

    @Test
    void controlledAnnotationAndDslHaveIdenticalCanonicalIrAndLean() {
        String source = controlledSource();
        var compiled = compiler().compileContract(source);
        ControlledMintProperty annotation = ControlledMintResolver.resolve(
                source, "TokenPolicy.java", "TokenPolicy", compiled.contractSchema())
                .orElseThrow();
        DslPropertySet annotationIr = ControlledMintDslLowering.lower(annotation);
        DslPropertySet dslIr = MintingDsl.controlledMintPropertySet(
                annotation.propertyId(), AUTHORITY, "4a554c43", "1");

        DslPropertyValidator.validate(dslIr, compiled.contractSchema(), 10_000);
        assertEquals(PropertyIrCodec.canonicalJson(annotationIr),
                PropertyIrCodec.canonicalJson(dslIr));
        assertEquals(PropertyLeanRenderer.render(annotationIr),
                PropertyLeanRenderer.render(dslIr));
        assertEquals(PropertyIrCodec.canonicalJson(dslIr), annotation.canonicalDslJson());
    }

    @Test
    void resolvesOneShotMintWithExplicitLedgerDomainAndNoDatumModel() {
        var schema = compiler().compileContract(mintSource()).contractSchema();
        DslPropertySet candidate = MintingDsl.oneShotPropertySet(
                "TokenPolicy.one-shot", AUTHORITY, TX_ID, 2, "4a554c43");
        var resolved = assertInstanceOf(OneShotMintProperty.class,
                MintingDsl.resolve(candidate, schema, "TokenPolicy", "OneShotSpec.java"));

        assertTrue(resolved.ledgerValidityModeled());
        assertEquals(List.of("validMintingContext/v3-pinned"),
                resolved.domainAssumptions());
        assertEquals("2", resolved.anchorOutputIndex());
        assertEquals(PropertyIrCodec.canonicalJson(candidate), resolved.canonicalDslJson());

        String model = ContractMetamodelGenerator.generate(schema, "evidence", "TokenModel");
        assertTrue(model.contains("MintingContractModel"));
        assertTrue(model.contains("ownPolicy()"));
        assertTrue(model.contains("redeemerStrictlyDecodes()"));
        assertFalse(model.contains("datum()"));
    }

    @Test
    void rejectsMovedOrDuplicatedExecutionDomainAndMultipleProperties() {
        var schema = compiler().compileContract(mintSource()).contractSchema();
        var model = new MintingContractModel();
        var malformed = DslPropertySet.minting(new DslProperty("moved",
                model.redeemerStrictlyDecodes()
                        .and(model.exactUplcSucceeds()).node()));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> DslPropertyValidator.validate(malformed, schema, 100))
                .getMessage().contains("normalized implication"));

        var duplicated = DslPropertySet.minting(new DslProperty("duplicate",
                model.exactUplcSucceeds().implies(
                        model.redeemerStrictlyDecodes()
                                .and(model.exactUplcSucceeds())).node()));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> DslPropertyValidator.validate(duplicated, schema, 100))
                .getMessage().contains("only once"));

        var multiple = new DslPropertySet(2, List.of(
                new DslProperty("one", model.exactUplcSucceeds()
                        .implies(model.redeemerStrictlyDecodes()).node()),
                new DslProperty("two", model.exactUplcSucceeds()
                        .implies(model.redeemerStrictlyDecodes()).node())));
        assertThrows(IllegalArgumentException.class,
                () -> DslPropertyValidator.validate(multiple, schema, 100));
    }

    @Test
    void rejectsForgedMintTypesAndNoncanonicalBoundedLiterals() {
        var schema = compiler().compileContract(mintSource()).contractSchema();
        var model = new MintingContractModel();
        PropertyNode forged = new ExactOwnPolicyAssetNode(
                model.context().txInfo().mint().node(),
                new RootNode("ownPolicy", DslType.BYTE_STRING),
                new BytesLiteralNode(DslType.BYTE_STRING,
                        BytesLiteralKind.TOKEN_NAME, "aa"),
                new LiteralNode(DslType.INTEGER, "1"));
        var property = DslPropertySet.minting(new DslProperty("forged",
                model.exactUplcSucceeds().implies(new BoolExpr(forged)).node()));
        assertThrows(IllegalArgumentException.class,
                () -> DslPropertyValidator.validate(property, schema, 100));

        assertLiteralRejected(schema, new BytesLiteralNode(
                DslType.BYTE_STRING, BytesLiteralKind.KEY_HASH, "AA"));
        assertLiteralRejected(schema, new BytesLiteralNode(
                DslType.BYTE_STRING, BytesLiteralKind.KEY_HASH, "00"));
        assertLiteralRejected(schema, new TxOutRefLiteralNode(
                DslType.TX_OUT_REF, "00", "0"));
        assertLiteralRejected(schema, new TxOutRefLiteralNode(
                DslType.TX_OUT_REF, TX_ID, "01"));
        assertLiteralRejected(schema, new TxOutRefLiteralNode(
                DslType.TX_OUT_REF, TX_ID, "-1"));
        assertLiteralRejected(schema, new TxOutRefLiteralNode(
                DslType.TX_OUT_REF, TX_ID, "9999999999999999999"));
        assertLiteralRejected(schema, new LiteralNode(
                DslType.INTEGER, "1".repeat(4097)));
    }

    @Test
    void schemaOneSpendingBytesRemainStable() {
        var schema = compiler().compileContract(spendingSource()).contractSchema();
        var before = RequiresSignerDslLowering.lower(
                new com.bloxbean.cardano.julc.verification.RequiresSignerProperty(
                        1, "julc.requires-signer/v1", "Gate.requires-signer.owner",
                        "Gate", "spending", "datum.owner",
                        List.of(
                                new com.bloxbean.cardano.julc.verification.RequiresSignerProperty.PathSegment(
                                        "root", "datum", "record:Datum"),
                                new com.bloxbean.cardano.julc.verification.RequiresSignerProperty.PathSegment(
                                        "field", "owner", "bytes")),
                        "Datum", "bytes",
                        new com.bloxbean.cardano.julc.verification.RequiresSignerProperty.SourceReference(
                                "Gate.java", 1, 1, "@RequiresSigner"),
                        List.of(), List.of("signer"), false));
        byte[] first = PropertyIrCodec.canonicalBytes(before);
        DslPropertyValidator.validate(before, schema, 100);
        assertArrayEquals(first, PropertyIrCodec.canonicalBytes(before));
        assertEquals(1, before.schemaVersion());
    }

    @Test
    void schemaOneRejectsSchemaTwoNodesAndTransactionFields() {
        var schema = compiler().compileContract(spendingSource()).contractSchema();
        var context = new RootNode("context", DslType.SCRIPT_CONTEXT);
        var txInfo = new FieldNode(context, "txInfo", DslType.TX_INFO);
        var inputs = new FieldNode(txInfo, "inputs", DslType.LIST_TX_IN_INFO);
        var mint = new FieldNode(txInfo, "mint", DslType.MINT_VALUE);
        var reference = new TxOutRefLiteralNode(DslType.TX_OUT_REF, TX_ID, "0");
        var policy = new BytesLiteralNode(DslType.POLICY_ID,
                BytesLiteralKind.POLICY_ID, AUTHORITY);
        var token = new BytesLiteralNode(DslType.BYTE_STRING,
                BytesLiteralKind.TOKEN_NAME, "4a554c43");
        var quantity = new LiteralNode(DslType.INTEGER, "1");

        for (PropertyNode mintingNode : List.of(
                new ConsumesNode(inputs, reference),
                new ExactOwnPolicyAssetNode(mint, policy, token, quantity),
                new CompareNode(CompareOperator.EQ, token, token),
                new CompareNode(CompareOperator.EQ, reference, reference))) {
            var property = DslPropertySet.of(new DslProperty("schema-two-node", mintingNode));
            assertTrue(assertThrows(IllegalArgumentException.class,
                    () -> DslPropertyValidator.validate(property, schema, 100))
                    .getMessage().contains("does not admit minting node"));
        }

        for (FieldNode field : List.of(inputs, mint)) {
            var property = DslPropertySet.of(new DslProperty("schema-two-field",
                    new CompareNode(CompareOperator.EQ, field, field)));
            assertTrue(assertThrows(IllegalArgumentException.class,
                    () -> DslPropertyValidator.validate(property, schema, 100))
                    .getMessage().contains("does not admit field TX_INFO." + field.name()));
        }
    }

    @Test
    void schemaTwoCanonicalJsonIsStableAndRejectsUnknownInput() throws Exception {
        var candidate = MintingDsl.oneShotPropertySet(
                "TokenPolicy.one-shot", AUTHORITY, TX_ID, 0, "4a554c43");
        byte[] first = PropertyIrCodec.canonicalBytes(candidate);
        assertArrayEquals(first, PropertyIrCodec.canonicalBytes(candidate));

        Path unknownField = tempDir.resolve("unknown-field.json");
        String json = new String(first, java.nio.charset.StandardCharsets.UTF_8);
        Files.writeString(unknownField,
                json.substring(0, json.length() - 1) + ",\"rawLean\":\"False\"}");
        assertThrows(java.io.IOException.class,
                () -> PropertyIrCodec.read(unknownField, 1_000_000));

        Path unknownSubtype = tempDir.resolve("unknown-subtype.json");
        assertTrue(json.contains("\"op\":\"root\""), json);
        Files.writeString(unknownSubtype,
                json.replaceFirst("\"op\":\"root\"", "\"op\":\"raw-lean\""));
        assertThrows(java.io.IOException.class,
                () -> PropertyIrCodec.read(unknownSubtype, 1_000_000));
    }

    @Test
    void schemaTwoFailsForWrongPurposeAndAstBudget() {
        var candidate = MintingDsl.oneShotPropertySet(
                "TokenPolicy.one-shot", AUTHORITY, TX_ID, 0, "4a554c43");
        var spending = compiler().compileContract(spendingSource()).contractSchema();
        var minting = compiler().compileContract(mintSource()).contractSchema();

        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> DslPropertyValidator.validate(candidate, spending, 10_000))
                .getMessage().contains("requires a MINT"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> DslPropertyValidator.validate(candidate, minting, 2))
                .getMessage().contains("exceeds 2 nodes"));
    }

    private static void assertLiteralRejected(
            com.bloxbean.cardano.julc.compiler.schema.ContractSchema schema,
            PropertyNode literal) {
        var model = new MintingContractModel();
        var property = DslPropertySet.minting(new DslProperty("literal",
                model.exactUplcSucceeds().implies(
                        new BoolExpr(new CompareNode(
                                CompareOperator.EQ, literal, literal))).node()));
        assertThrows(IllegalArgumentException.class,
                () -> DslPropertyValidator.validate(property, schema, 100));
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

    private static String mintSource() {
        return """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                @MintingValidator class TokenPolicy {
                    record Redeemer() {}
                    @Entrypoint static boolean validate(Redeemer r, ScriptContext c) {
                        return true;
                    }
                }
                """;
    }

    private static String spendingSource() {
        return """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                @SpendingValidator class Gate {
                    record Datum(byte[] owner) {}
                    record Redeemer() {}
                    @Entrypoint static boolean validate(Datum d, Redeemer r, ScriptContext c) {
                        return true;
                    }
                }
                """;
    }
}
