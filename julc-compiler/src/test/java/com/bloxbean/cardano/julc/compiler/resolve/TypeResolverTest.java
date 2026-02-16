package com.bloxbean.cardano.julc.compiler.resolve;

import com.bloxbean.cardano.julc.compiler.pir.PirType;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.type.VoidType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TypeResolverTest {

    @BeforeAll
    static void configureParser() {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
    }

    private final TypeResolver resolver = new TypeResolver();

    @Test void resolveBoolean() { assertEquals(new PirType.BoolType(), resolver.resolve(PrimitiveType.booleanType())); }
    @Test void resolveInt() { assertEquals(new PirType.IntegerType(), resolver.resolve(PrimitiveType.intType())); }
    @Test void resolveLong() { assertEquals(new PirType.IntegerType(), resolver.resolve(PrimitiveType.longType())); }
    @Test void resolveVoid() { assertEquals(new PirType.UnitType(), resolver.resolve(new VoidType())); }

    @Test void resolveBigInteger() {
        assertEquals(new PirType.IntegerType(), resolver.resolve(StaticJavaParser.parseClassOrInterfaceType("BigInteger")));
    }

    @Test void resolveString() {
        assertEquals(new PirType.StringType(), resolver.resolve(StaticJavaParser.parseClassOrInterfaceType("String")));
    }

    @Test void resolvePlutusData() {
        assertEquals(new PirType.DataType(), resolver.resolve(StaticJavaParser.parseClassOrInterfaceType("PlutusData")));
    }

    @Test void resolveConstrData() {
        assertEquals(new PirType.DataType(), resolver.resolve(StaticJavaParser.parseClassOrInterfaceType("ConstrData")));
    }

    @Test void resolveMapData() {
        assertEquals(new PirType.DataType(), resolver.resolve(StaticJavaParser.parseClassOrInterfaceType("MapData")));
    }

    @Test void resolveListData() {
        assertEquals(new PirType.DataType(), resolver.resolve(StaticJavaParser.parseClassOrInterfaceType("ListData")));
    }

    @Test void resolveIntData() {
        assertEquals(new PirType.DataType(), resolver.resolve(StaticJavaParser.parseClassOrInterfaceType("IntData")));
    }

    @Test void resolveBytesData() {
        assertEquals(new PirType.DataType(), resolver.resolve(StaticJavaParser.parseClassOrInterfaceType("BytesData")));
    }

    @Test void resolveByteArray() {
        var arrayType = StaticJavaParser.parseType("byte[]");
        assertEquals(new PirType.ByteStringType(), resolver.resolve(arrayType));
    }

    @Test void resolveList() {
        var type = StaticJavaParser.parseClassOrInterfaceType("List<BigInteger>");
        var result = resolver.resolve(type);
        assertInstanceOf(PirType.ListType.class, result);
        assertEquals(new PirType.IntegerType(), ((PirType.ListType) result).elemType());
    }

    @Test void resolveMap() {
        var type = StaticJavaParser.parseClassOrInterfaceType("Map<String, BigInteger>");
        var result = resolver.resolve(type);
        assertInstanceOf(PirType.MapType.class, result);
        assertEquals(new PirType.StringType(), ((PirType.MapType) result).keyType());
        assertEquals(new PirType.IntegerType(), ((PirType.MapType) result).valueType());
    }

    @Test void resolveOptional() {
        var type = StaticJavaParser.parseClassOrInterfaceType("Optional<BigInteger>");
        var result = resolver.resolve(type);
        assertInstanceOf(PirType.OptionalType.class, result);
        assertEquals(new PirType.IntegerType(), ((PirType.OptionalType) result).elemType());
    }

    @Test void resolvePubKeyHash() {
        assertEquals(new PirType.ByteStringType(), resolver.resolve(StaticJavaParser.parseClassOrInterfaceType("PubKeyHash")));
    }

    @Test void resolveScriptContextAsRecordType() {
        // ScriptContext resolves to RecordType after LedgerTypeRegistry registration
        LedgerTypeRegistry.registerAll(resolver);
        var result = resolver.resolve(StaticJavaParser.parseClassOrInterfaceType("ScriptContext"));
        assertInstanceOf(PirType.RecordType.class, result);
        var rt = (PirType.RecordType) result;
        assertEquals("ScriptContext", rt.name());
        assertEquals(3, rt.fields().size());
        assertEquals("txInfo", rt.fields().get(0).name());
        assertEquals("redeemer", rt.fields().get(1).name());
        assertEquals("scriptInfo", rt.fields().get(2).name());
    }

    @Test void resolveTxInfoAsRecordType() {
        LedgerTypeRegistry.registerAll(resolver);
        var result = resolver.resolve(StaticJavaParser.parseClassOrInterfaceType("TxInfo"));
        assertInstanceOf(PirType.RecordType.class, result);
        var rt = (PirType.RecordType) result;
        assertEquals("TxInfo", rt.name());
        assertEquals(16, rt.fields().size());
        assertEquals("signatories", rt.fields().get(8).name());
        assertInstanceOf(PirType.ListType.class, rt.fields().get(8).type());
    }

    @Test void resolveCredentialAsSumType() {
        LedgerTypeRegistry.registerAll(resolver);
        var result = resolver.resolve(StaticJavaParser.parseClassOrInterfaceType("Credential"));
        assertInstanceOf(PirType.SumType.class, result);
        var st = (PirType.SumType) result;
        assertEquals(2, st.constructors().size());
        assertEquals("PubKeyCredential", st.constructors().get(0).name());
        assertEquals("ScriptCredential", st.constructors().get(1).name());
    }

    @Test void resolveGovernanceTypeAsOpaque() {
        // Governance types not yet registered still resolve as DataType
        assertEquals(new PirType.DataType(), resolver.resolve(StaticJavaParser.parseClassOrInterfaceType("Vote")));
    }

    @Test void resolveUnknownTypeThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve(StaticJavaParser.parseClassOrInterfaceType("UnknownType")));
    }

    @Test void resolveFloatThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve(PrimitiveType.floatType()));
    }

    @Test void resolveRecord() {
        var cu = StaticJavaParser.parse("record MyDatum(java.math.BigInteger value, byte[] hash) {}");
        var rd = cu.findFirst(com.github.javaparser.ast.body.RecordDeclaration.class).orElseThrow();
        resolver.registerRecord(rd, rd.getFullyQualifiedName().orElse(rd.getNameAsString()));

        var result = resolver.lookupRecord("MyDatum");
        assertTrue(result.isPresent());
        assertEquals(2, result.get().fields().size());
        assertEquals("value", result.get().fields().get(0).name());
        assertInstanceOf(PirType.IntegerType.class, result.get().fields().get(0).type());
        assertEquals("hash", result.get().fields().get(1).name());
        assertInstanceOf(PirType.ByteStringType.class, result.get().fields().get(1).type());
    }
}
