package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.verification.dsl.ir.*;
import com.bloxbean.cardano.julc.verification.dsl.type.ContractTypeProjection;
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

class TypedMetamodelV4Test {

    @Test
    void rejectsGeneratedJavaMemberCollisionsDeterministically() {
        var compiled = new JulcCompiler().compileContract("""
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import java.math.BigInteger;
                @SpendingValidator class CollisionGate {
                    record Datum(BigInteger value, BigInteger Value) {}
                    record Redeemer() {}
                    @Entrypoint static boolean validate(
                            Datum datum, Redeemer redeemer, ScriptContext ctx) { return true; }
                }
                """);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> ContractMetamodelGenerator.generate(
                        compiled.contractSchema(), "evidence", "CollisionModel"))
                .getMessage().contains("collide"));
    }
    @TempDir
    Path tempDir;

    @Test
    void generatedNestedRecordVariantOptionalAndRecursiveModelCompilesAndValidates()
            throws Exception {
        var compiled = new JulcCompiler().compileContract(validatorSource());
        Path sources = tempDir.resolve("sources/evidence");
        Files.createDirectories(sources);
        Path model = sources.resolve("GateModel.java");
        String generated = ContractMetamodelGenerator.generate(
                compiled.contractSchema(), "evidence", "GateModel");
        Files.writeString(model, generated);
        Path specification = sources.resolve("GateProperties.java");
        Files.writeString(specification, """
                package evidence;
                import com.bloxbean.cardano.julc.verification.dsl.*;
                import com.bloxbean.cardano.julc.verification.dsl.ir.*;
                import static com.bloxbean.cardano.julc.verification.dsl.VerificationDsl.*;
                public final class GateProperties implements VerificationSpecification {
                    public GateProperties() {}
                    public DslPropertySet properties() {
                        var contract = new GateModel();
                        var generatedPresence = contract.datum().isPresent();
                        var datumType = new com.bloxbean.cardano.julc.verification.dsl.type.NominalTypeRef(
                            "TypedGate.Datum",
                            com.bloxbean.cardano.julc.verification.dsl.type.NominalTypeRef.NominalKind.RECORD);
                        var manualPresence = new TypedOptionExpr(
                            new TypedRootNode("typedDatum",
                                new com.bloxbean.cardano.julc.verification.dsl.type.OptionalTypeRef(datumType)),
                            datumType).isPresent();
                        var guarantee = contract.datum().exists(datum ->
                            contract.context().txInfo().signatories()
                                .contains(datum.child().owner())
                            .and(datum.backup().exists(backup ->
                                contract.context().txInfo().signatories()
                                    .contains(backup)))
                            .and(contract.redeemer().exists(action ->
                                action.whenUpdate(update ->
                                    update.amount().gt(integer(0)))))
                            .and(datum.values().exactlyOne(value ->
                                value.gt(integer(0))))
                            .and(datum.values().at(integer(0)).exists(first ->
                                first.eq(integer(1))))
                            .and(datum.values().at(integer(1).subtract(integer(1)))
                                .exists(first -> first.eq(integer(1))))
                            .and(datum.balances().existsEntry((key, amount) ->
                                contract.context().txInfo().signatories().contains(key)
                                  .and(amount.gt(integer(0)))))
                            .and(datum.balances().lookupFirst(datum.child().owner())
                                .exists(amount -> amount.gt(integer(0))))
                            .and(datum.balances().lookupAll(datum.child().owner())
                                .count(amount -> amount.gt(integer(0))).ge(integer(1))));
                        return contract.properties(
                            property("gate.generated-presence", DslDomain.NONE,
                                generatedPresence),
                            property("gate.manual-presence", DslDomain.NONE,
                                manualPresence),
                            property("gate.nested",
                                DslDomain.VALID_SPENDING_V3_PINNED, guarantee));
                    }
                }
                """);
        Path classes = compile(model, specification);
        DslPropertySet candidate = new DslWorkerRunner().run(
                classes + File.pathSeparator + System.getProperty("java.class.path"),
                "evidence.GateProperties", compiled.contractSchema(),
                tempDir.resolve("worker"), Duration.ofSeconds(10));

        assertEquals(DslPropertySet.SCHEMA_VERSION, candidate.schemaVersion());
        assertEquals(ContractTypeProjection.sha256(
                ContractTypeProjection.project(compiled.contractSchema())),
                candidate.contractSchemaSha256());
        String canonical = PropertyIrCodec.canonicalJson(candidate);
        assertTrue(canonical.contains("option-exists"));
        assertTrue(canonical.contains("variant-when"));
        assertTrue(canonical.contains("list-count"));
        assertTrue(canonical.contains("map-quantifier"));
        assertTrue(canonical.contains("map-lookup-first"));
        assertTrue(canonical.contains("map-lookup-all"));
        assertTrue(canonical.contains("TypedGate.Child"));
        PropertyNode generatedPresence = candidate.properties().stream()
                .filter(property -> property.id().equals("gate.generated-presence"))
                .findFirst().orElseThrow().expression();
        PropertyNode manualPresence = candidate.properties().stream()
                .filter(property -> property.id().equals("gate.manual-presence"))
                .findFirst().orElseThrow().expression();
        assertEquals(generatedPresence, manualPresence,
                "Generated and manually composed helpers must lower to one canonical IR");
        assertEquals(TypedPropertyLeanRenderer.renderExpression(
                        generatedPresence, ContractTypeProjection.project(compiled.contractSchema())),
                TypedPropertyLeanRenderer.renderExpression(
                        manualPresence, ContractTypeProjection.project(compiled.contractSchema())));
        String lean = TypedPropertyLeanRenderer.renderExpression(
                candidate.properties().stream()
                        .filter(property -> property.id().equals("gate.nested"))
                        .findFirst().orElseThrow().expression(),
                ContractTypeProjection.project(compiled.contractSchema()));
        assertTrue(lean.contains("typedDatum ctx"));
        assertTrue(lean.contains("typedRedeemer ctx"));
        assertTrue(lean.contains(".Update"));
        assertTrue(lean.contains("_0 > 0"), lean);
        assertTrue(lean.contains("julcListAt"), lean);
        assertTrue(lean.contains("julcMapLookupFirst"), lean);
        assertTrue(lean.contains("julcMapLookupAll"), lean);
        assertFalse(generated.contains(" asUpdate("));
        assertFalse(generated.contains(" parameter()"));
    }

    @Test
    void forgedOwnerAndUnguardedVariantPayloadFailInParent() {
        var schema = new JulcCompiler().compileContract(validatorSource()).contractSchema();
        var projection = ContractTypeProjection.project(schema);
        var datum = (com.bloxbean.cardano.julc.verification.dsl.type.NominalTypeRef)
                projection.datumType();
        var wrongOwner = (com.bloxbean.cardano.julc.verification.dsl.type.NominalTypeRef)
                projection.redeemerType();
        var datumRoot = new TypedRootNode("typedDatum",
                new com.bloxbean.cardano.julc.verification.dsl.type.OptionalTypeRef(datum));
        var forgedField = new TypedFieldNode(
                new TypedVariableNode("v0", datum), wrongOwner, "child",
                new com.bloxbean.cardano.julc.verification.dsl.type.BuiltinTypeRef(
                        com.bloxbean.cardano.julc.verification.dsl.type.BuiltinTypeRef
                                .BuiltinKind.INTEGER));
        var expression = new OptionExistsNode(datumRoot, "v0", datum,
                new CompareNode(CompareOperator.EQ, forgedField,
                        new LiteralNode(DslType.INTEGER, "0")));
        var candidate = DslPropertySet.schema1(DslPurpose.SPENDING,
                ContractTypeProjection.sha256(projection),
                new DslProperty("forged.owner", DslDomain.NONE, expression));

        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> DslPropertyValidator.validate(candidate, schema, 100))
                .getMessage().contains("owner"));
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
            assertTrue(result, "Generated schema-4 model and specification must compile");
        }
        return classes;
    }

    private static String validatorSource() {
        return """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import java.math.BigInteger;
                import java.util.List;
                import java.util.Map;
                import java.util.Optional;
                @SpendingValidator class TypedGate {
                    record Child(byte[] owner, String label) {}
                    sealed interface Node permits End, Next {}
                    record End() implements Node {}
                    record Next(BigInteger value, Optional<Node> next) implements Node {}
                    record Datum(Child child, Optional<byte[]> backup,
                                 List<BigInteger> values,
                                 Map<byte[], BigInteger> balances,
                                 Node recursive) {}
                    sealed interface Action permits Update, Close {}
                    record Update(BigInteger amount, Optional<Action> next) implements Action {}
                    record Close() implements Action {}
                    @Entrypoint static boolean validate(
                            Datum datum, Action redeemer, ScriptContext context) {
                        return true;
                    }
                }
                """;
    }
}
