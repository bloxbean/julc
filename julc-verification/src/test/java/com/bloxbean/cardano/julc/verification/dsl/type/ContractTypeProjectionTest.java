package com.bloxbean.cardano.julc.verification.dsl.type;

import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.compiler.pir.PirType;
import com.bloxbean.cardano.julc.compiler.schema.ContractSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ContractTypeProjectionTest {
    @Test
    void projectsNestedContainersSumsAndDistinctStringBytesDeterministically() {
        ContractSchema schema = compile("""
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import java.math.BigInteger;
                import java.util.List;
                import java.util.Map;
                import java.util.Optional;

                @SpendingValidator class TypedGate {
                    record Child(String label, byte[] digest) {}
                    record Datum(Child child, List<Optional<BigInteger>> values,
                                 Map<byte[], Child> children, boolean active) {}
                    sealed interface Action permits Update, Close {}
                    record Update(Child child) implements Action {}
                    record Close() implements Action {}
                    @Entrypoint static boolean validate(
                            Datum datum, Action redeemer, ScriptContext context) {
                        return true;
                    }
                }
                """);

        ProjectedContractTypes first = ContractTypeProjection.project(schema);
        ProjectedContractTypes second = ContractTypeProjection.project(schema);

        assertEquals(first, second);
        assertEquals(ContractTypeProjection.canonicalJson(first),
                ContractTypeProjection.canonicalJson(second));
        assertEquals(64, ContractTypeProjection.sha256(first).length());
        assertInstanceOf(NominalTypeRef.class, first.datumType());
        assertInstanceOf(NominalTypeRef.class, first.redeemerType());

        var child = definition(first, "Child");
        assertEquals(NominalTypeRef.NominalKind.RECORD, child.nominalKind());
        assertEquals(new BuiltinTypeRef(BuiltinTypeRef.BuiltinKind.STRING),
                child.fields().get(0).type());
        assertEquals(new BuiltinTypeRef(BuiltinTypeRef.BuiltinKind.BYTE_STRING),
                child.fields().get(1).type());

        var datum = definition(first, "Datum");
        assertInstanceOf(ListTypeRef.class, datum.fields().get(1).type());
        var values = (ListTypeRef) datum.fields().get(1).type();
        assertInstanceOf(OptionalTypeRef.class, values.elementType());
        assertInstanceOf(AssocMapTypeRef.class, datum.fields().get(2).type());
        assertEquals(NominalTypeRef.NominalKind.SUM,
                definition(first, "Action").nominalKind());
    }

    @Test
    void retainsProductiveRecursiveBackReferencesWithoutExpansion() {
        ContractSchema schema = compile("""
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import java.math.BigInteger;
                import java.util.Optional;
                @MintingValidator class RecursiveGate {
                    sealed interface Node permits End, Cons {}
                    record End() implements Node {}
                    record Cons(BigInteger value, Optional<Node> next) implements Node {}
                    @Entrypoint static boolean validate(Node redeemer, ScriptContext context) {
                        return true;
                    }
                }
                """);

        ProjectedContractTypes projected = ContractTypeProjection.project(schema);
        var node = definition(projected, "Node");
        var cons = node.constructors().stream()
                .filter(constructor -> constructor.name().equals("Cons"))
                .findFirst().orElseThrow();
        var optional = assertInstanceOf(OptionalTypeRef.class,
                cons.fields().get(1).type());
        var backReference = assertInstanceOf(NominalTypeRef.class,
                optional.elementType());

        assertEquals(node.stableId(), backReference.stableId());
        assertTrue(ContractTypeProjection.canonicalJson(projected).length() < 20_000);
    }

    @Test
    void retainsMutualContainerRecursiveBackReferences() {
        ContractSchema schema = compile("""
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import java.util.List;
                import java.util.Optional;
                @MintingValidator class MutualGate {
                    sealed interface Left permits LeftEnd, LeftNext {}
                    record LeftEnd() implements Left {}
                    record LeftNext(Optional<Right> right) implements Left {}
                    sealed interface Right permits RightEnd, RightNext {}
                    record RightEnd() implements Right {}
                    record RightNext(List<Left> lefts) implements Right {}
                    @Entrypoint static boolean validate(Left redeemer, ScriptContext context) {
                        return true;
                    }
                }
                """);

        ProjectedContractTypes projected = ContractTypeProjection.project(schema);
        var left = definition(projected, "Left");
        var right = definition(projected, "Right");
        var rightReference = assertInstanceOf(NominalTypeRef.class,
                assertInstanceOf(OptionalTypeRef.class,
                        left.constructors().stream()
                                .filter(value -> value.name().equals("LeftNext"))
                                .findFirst().orElseThrow().fields().getFirst().type())
                        .elementType());
        var leftReference = assertInstanceOf(NominalTypeRef.class,
                assertInstanceOf(ListTypeRef.class,
                        right.constructors().stream()
                                .filter(value -> value.name().equals("RightNext"))
                                .findFirst().orElseThrow().fields().getFirst().type())
                        .elementType());
        assertEquals(right.stableId(), rightReference.stableId());
        assertEquals(left.stableId(), leftReference.stableId());
    }

    @Test
    void rejectsForgedDanglingAndWrongKindNominalReferences() {
        ContractSchema schema = compile("""
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                @MintingValidator class Gate {
                    record Redeemer(byte[] owner) {}
                    @Entrypoint static boolean validate(Redeemer r, ScriptContext c) {
                        return true;
                    }
                }
                """);
        ProjectedContractTypes valid = ContractTypeProjection.project(schema);

        var dangling = new ProjectedContractTypes(valid.schemaVersion(), valid.purpose(),
                valid.datumType(), new NominalTypeRef(
                        "forged.Missing", NominalTypeRef.NominalKind.RECORD),
                valid.parameters(), valid.definitions());
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> ContractTypeProjection.validate(dangling))
                .getMessage().contains("Unknown or mismatched"));

        var actual = (NominalTypeRef) valid.redeemerType();
        var wrongKind = new ProjectedContractTypes(valid.schemaVersion(), valid.purpose(),
                valid.datumType(), new NominalTypeRef(
                        actual.stableId(), NominalTypeRef.NominalKind.SUM),
                valid.parameters(), valid.definitions());
        assertThrows(IllegalArgumentException.class,
                () -> ContractTypeProjection.validate(wrongKind));
    }

    @Test
    void rejectsUnsupportedCompilerTypeInsteadOfApproximatingIt() {
        var redeemer = new ContractSchema.Argument("redeemer",
                new PirType.PairType(new PirType.IntegerType(),
                        new PirType.ByteStringType()), null);
        var schema = new ContractSchema(
                ContractSchema.Purpose.MINT, null, redeemer, List.of(), Map.of());

        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> ContractTypeProjection.project(schema))
                .getMessage().contains("pair"));
    }

    @Test
    void rejectsOpaqueNativeValueInsteadOfApproximatingItAsData() {
        var redeemer = new ContractSchema.Argument(
                "redeemer", new PirType.NativeValueType(), null);
        var schema = new ContractSchema(
                ContractSchema.Purpose.MINT, null, redeemer, List.of(), Map.of());

        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> ContractTypeProjection.project(schema))
                .getMessage().contains("native value"));
    }

    @Test
    void documentsCompilerErasedNewtypeAsItsAuthoritativeRepresentation() {
        ContractSchema schema = compile("""
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import java.math.BigInteger;
                @SpendingValidator class NewtypeGate {
                    @NewType record Amount(BigInteger value) {}
                    record Datum(Amount amount) {}
                    record Redeemer() {}
                    @Entrypoint static boolean validate(
                            Datum datum, Redeemer redeemer, ScriptContext context) {
                        return true;
                    }
                }
                """);

        ProjectedContractTypes projection = ContractTypeProjection.project(schema);
        var datum = definition(projection, "Datum");
        assertEquals(new BuiltinTypeRef(BuiltinTypeRef.BuiltinKind.INTEGER),
                datum.fields().getFirst().type());
        assertTrue(projection.definitions().stream()
                .noneMatch(definition -> definition.sourceName().equals("Amount")),
                "Verification must not invent nominal identity erased by ContractSchema");
    }

    private static ProjectedContractTypes.NominalDefinition definition(
            ProjectedContractTypes projection, String sourceName) {
        return projection.definitions().stream()
                .filter(definition -> definition.sourceName().equals(sourceName))
                .findFirst().orElseThrow();
    }

    private static ContractSchema compile(String source) {
        return new JulcCompiler().compileContract(source).contractSchema();
    }
}
