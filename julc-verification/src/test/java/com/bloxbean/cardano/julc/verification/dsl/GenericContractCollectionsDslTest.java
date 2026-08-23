package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.verification.dsl.ir.*;
import com.bloxbean.cardano.julc.verification.dsl.type.*;
import org.junit.jupiter.api.Test;

import static com.bloxbean.cardano.julc.verification.dsl.VerificationDsl.*;
import static org.junit.jupiter.api.Assertions.*;

class GenericContractCollectionsDslTest {

    @Test
    void alphaEquivalentBindersNormalizeToIdenticalCanonicalIr() {
        var compiled = new JulcCompiler().compileContract(source());
        var projection = ContractTypeProjection.project(compiled.contractSchema());
        var datum = (NominalTypeRef) projection.datumType();
        String hash = ContractTypeProjection.sha256(projection);

        DslPropertySet left = optionProperty("v7", datum, hash);
        DslPropertySet right = optionProperty("v2", datum, hash);
        var normalizedLeft = DslPropertyValidator.validateAndNormalize(
                left, compiled.contractSchema(), 100);
        var normalizedRight = DslPropertyValidator.validateAndNormalize(
                right, compiled.contractSchema(), 100);

        assertArrayEquals(PropertyIrCodec.canonicalBytes(normalizedLeft),
                PropertyIrCodec.canonicalBytes(normalizedRight));
        assertTrue(PropertyIrCodec.canonicalJson(normalizedLeft).contains("\"variable\":\"v0\""));
        assertEquals(normalizedLeft, DslPropertyCanonicalizer.normalize(normalizedLeft));
    }

    @Test
    void commutedQuantifiersCanonicalizeIndependentlyOfGlobalBinderOrder() {
        var compiled = new JulcCompiler().compileContract(source());
        var projection = ContractTypeProjection.project(compiled.contractSchema());
        var datum = (NominalTypeRef) projection.datumType();
        var definition = definition(projection, datum);
        var valuesType = (ListTypeRef) field(definition, "values");
        String hash = ContractTypeProjection.sha256(projection);

        java.util.function.Function<Boolean, DslPropertySet> buildProperty = reversed -> {
            var guarantee = TypedExpressions.optionalRoot("typedDatum", datum).exists(value -> {
                var values = new TypedListExpr(TypedExpressions.field(
                        value, datum, "values", valuesType).node(), valuesType.elementType());
                var positive = values.exists(item -> new IntegerExpr(item.node()).gt(integer(0)));
                var bounded = values.exists(item -> new IntegerExpr(item.node()).lt(integer(10)));
                return reversed ? bounded.and(positive) : positive.and(bounded);
            });
            return DslPropertySet.schema1(DslPurpose.SPENDING, hash,
                property("commutative", DslDomain.NONE, guarantee));
        };

        var left = DslPropertyValidator.validateAndNormalize(
                buildProperty.apply(false), compiled.contractSchema(), 200);
        var right = DslPropertyValidator.validateAndNormalize(
                buildProperty.apply(true), compiled.contractSchema(), 200);
        assertArrayEquals(PropertyIrCodec.canonicalBytes(left),
                PropertyIrCodec.canonicalBytes(right));
    }

    @Test
    void forgedContainerApplicationAndStringBytesCoercionFailInParent() {
        var compiled = new JulcCompiler().compileContract(source());
        var projection = ContractTypeProjection.project(compiled.contractSchema());
        var datum = (NominalTypeRef) projection.datumType();
        var definition = definition(projection, datum);
        String hash = ContractTypeProjection.sha256(projection);
        var list = (ListTypeRef) field(definition, "values");
        var root = TypedExpressions.optionalRoot("typedDatum", datum);
        var forged = root.exists(value -> {
            var values = TypedExpressions.field(value, datum, "values", list);
            return new BoolExpr(new ListStateNode(values.node(),
                    new BuiltinTypeRef(BuiltinTypeRef.BuiltinKind.STRING), ListState.EMPTY));
        });
        var forgedSet = DslPropertySet.schema1(DslPurpose.SPENDING,
                ContractTypeProjection.sha256(projection),
                property("forged", DslDomain.NONE, forged));

        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> DslPropertyValidator.validate(
                        forgedSet, compiled.contractSchema(), 100))
                .getMessage().contains("element type"));

        var string = new BuiltinTypeRef(BuiltinTypeRef.BuiltinKind.STRING);
        var bytes = new BuiltinTypeRef(BuiltinTypeRef.BuiltinKind.BYTE_STRING);
        var mismatch = root.exists(value -> {
            var label = TypedExpressions.field(value, datum, "label", string);
            var owner = TypedExpressions.field(value, datum, "owner", bytes);
            return new BoolExpr(new TypedEqualityNode(
                    label.node(), owner.node(), string, false));
        });
        var mismatchSet = DslPropertySet.schema1(DslPurpose.SPENDING,
                ContractTypeProjection.sha256(projection),
                property("mismatch", DslDomain.NONE, mismatch));
        assertThrows(IllegalArgumentException.class, () -> DslPropertyValidator.validate(
                mismatchSet, compiled.contractSchema(), 100));

        var balances = (AssocMapTypeRef) field(definition, "balances");
        var literalKey = new TypedValueExpr(bytes("00").node(), bytes);
        var literalLookup = root.exists(value -> new TypedAssocMapExpr(
                TypedExpressions.field(value, datum, "balances", balances).node(),
                balances.keyType(), balances.valueType())
                .lookupFirst(literalKey).isEmpty());
        var literalLookupSet = DslPropertySet.schema1(DslPurpose.SPENDING, hash,
                property("literal-lookup", DslDomain.NONE, literalLookup));
        assertDoesNotThrow(() -> DslPropertyValidator.validate(
                literalLookupSet, compiled.contractSchema(), 100));
    }

    @Test
    void rawDataEqualityIsRejectedThroughNestedContainers() {
        var compiled = new JulcCompiler().compileContract("""
                import com.bloxbean.cardano.julc.core.PlutusData;
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import java.util.List;
                @SpendingValidator class RawGate {
                    record Datum(List<PlutusData> values) {}
                    record Redeemer() {}
                    @Entrypoint static boolean validate(
                            Datum datum, Redeemer redeemer, ScriptContext ctx) { return true; }
                }
                """);
        var projection = ContractTypeProjection.project(compiled.contractSchema());
        var datum = (NominalTypeRef) projection.datumType();
        var valuesType = (ListTypeRef) field(definition(projection, datum), "values");
        var root = TypedExpressions.optionalRoot("typedDatum", datum);
        var equality = root.exists(value -> {
            var values = new TypedListExpr(TypedExpressions.field(
                    value, datum, "values", valuesType).node(), valuesType.elementType());
            return values.structurallyEquals(values);
        });
        var property = DslPropertySet.schema1(DslPurpose.SPENDING,
                ContractTypeProjection.sha256(projection),
                property("raw-data-list", DslDomain.NONE, equality));

        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> DslPropertyValidator.validate(
                        property, compiled.contractSchema(), 100))
                .getMessage().contains("Raw Data equality"));
    }

    @Test
    void linearArithmeticIsClosedAndCanonicalConstantsAreBounded() {
        var compiled = new JulcCompiler().compileContract(source());
        var projection = ContractTypeProjection.project(compiled.contractSchema());
        var datum = (NominalTypeRef) projection.datumType();
        var amountType = new BuiltinTypeRef(BuiltinTypeRef.BuiltinKind.INTEGER);
        var valid = TypedExpressions.optionalRoot("typedDatum", datum).exists(value -> {
            var amount = TypedExpressions.field(value, datum, "amount", amountType);
            return new IntegerExpr(amount.node()).negate().add(integer(4)).times(3)
                    .ge(integer(-12));
        });
        var validSet = DslPropertySet.schema1(DslPurpose.SPENDING,
                ContractTypeProjection.sha256(projection),
                property("linear", DslDomain.NONE, valid));
        assertDoesNotThrow(() -> DslPropertyValidator.validate(
                validSet, compiled.contractSchema(), 100));

        var invalid = TypedExpressions.optionalRoot("typedDatum", datum).exists(value -> {
            var amount = TypedExpressions.field(value, datum, "amount", amountType);
            return new IntegerExpr(new IntegerArithmeticNode(
                    IntegerArithmeticOperator.SCALE, amount.node(), null, "01"))
                    .eq(integer(1));
        });
        var invalidSet = DslPropertySet.schema1(DslPurpose.SPENDING,
                ContractTypeProjection.sha256(projection),
                property("invalid-linear", DslDomain.NONE, invalid));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> DslPropertyValidator.validate(
                        invalidSet, compiled.contractSchema(), 100))
                .getMessage().contains("canonical"));
    }

    private static DslPropertySet optionProperty(
            String variable, NominalTypeRef datum, String hash) {
        var optional = new TypedRootNode("typedDatum", new OptionalTypeRef(datum));
        var body = new OptionExistsNode(optional, variable, datum,
                new BoolNotNode(new BoolNotNode(new BoolLiteralNode(true))));
        return DslPropertySet.schema1(DslPurpose.SPENDING, hash,
                new DslProperty("alpha", DslDomain.NONE, body));
    }

    private static ProjectedContractTypes.NominalDefinition definition(
            ProjectedContractTypes projection, NominalTypeRef type) {
        return projection.definitions().stream()
                .filter(value -> value.stableId().equals(type.stableId()))
                .findFirst().orElseThrow();
    }

    private static VerificationTypeRef field(
            ProjectedContractTypes.NominalDefinition definition, String name) {
        return definition.fields().stream()
                .filter(value -> value.name().equals(name))
                .map(ProjectedContractTypes.Field::type)
                .findFirst().orElseThrow();
    }

    private static String source() {
        return """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import java.math.BigInteger;
                import java.util.List;
                import java.util.Map;
                @SpendingValidator class GenericGate {
                    record Datum(BigInteger amount, String label, byte[] owner,
                                 List<BigInteger> values,
                                 Map<byte[], BigInteger> balances) {}
                    record Redeemer() {}
                    @Entrypoint static boolean validate(
                            Datum datum, Redeemer redeemer, ScriptContext ctx) { return true; }
                }
                """;
    }
}
