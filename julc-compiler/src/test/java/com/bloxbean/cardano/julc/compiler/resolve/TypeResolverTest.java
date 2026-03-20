package com.bloxbean.cardano.julc.compiler.resolve;

import com.bloxbean.cardano.julc.compiler.CompilerException;
import com.bloxbean.cardano.julc.compiler.pir.PirType;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.type.VoidType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TypeResolver, including dynamic ledger type registration via
 * LedgerSourceLoader + TypeRegistrar (replacing the old LedgerTypeRegistry).
 */

class TypeResolverTest {

    @BeforeAll
    static void configureParser() {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
    }

    private final TypeResolver resolver = new TypeResolver();

    /** Helper: register all ledger types dynamically (replaces old LedgerTypeRegistry.registerAll). */
    private void registerLedgerTypes(TypeResolver tr) {
        var ledgerCus = LedgerSourceLoader.loadLedgerSources(
                Thread.currentThread().getContextClassLoader());
        new TypeRegistrar().registerAll(ledgerCus, tr);
        tr.addFlatVariantAliases("com.bloxbean.cardano.julc.ledger");
    }

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
        // ScriptContext resolves to RecordType after dynamic ledger type registration
        registerLedgerTypes(resolver);
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
        registerLedgerTypes(resolver);
        var result = resolver.resolve(StaticJavaParser.parseClassOrInterfaceType("TxInfo"));
        assertInstanceOf(PirType.RecordType.class, result);
        var rt = (PirType.RecordType) result;
        assertEquals("TxInfo", rt.name());
        assertEquals(16, rt.fields().size());
        assertEquals("signatories", rt.fields().get(8).name());
        assertInstanceOf(PirType.ListType.class, rt.fields().get(8).type());
    }

    @Test void resolveCredentialAsSumType() {
        registerLedgerTypes(resolver);
        var result = resolver.resolve(StaticJavaParser.parseClassOrInterfaceType("Credential"));
        assertInstanceOf(PirType.SumType.class, result);
        var st = (PirType.SumType) result;
        assertEquals(2, st.constructors().size());
        assertEquals("PubKeyCredential", st.constructors().get(0).name());
        assertEquals("ScriptCredential", st.constructors().get(1).name());
    }

    @Test void resolveGovernanceTypeAsSumType() {
        // Governance types resolve as SumType after dynamic ledger type registration
        registerLedgerTypes(resolver);
        var result = resolver.resolve(StaticJavaParser.parseClassOrInterfaceType("Vote"));
        assertInstanceOf(PirType.SumType.class, result);
        var st = (PirType.SumType) result;
        assertEquals("Vote", st.name());
        assertEquals(3, st.constructors().size());
        assertEquals("VoteNo", st.constructors().get(0).name());
        assertEquals("VoteYes", st.constructors().get(1).name());
        assertEquals("Abstain", st.constructors().get(2).name());
    }

    @Test void resolveUnknownTypeThrows() {
        assertThrows(CompilerException.class,
                () -> resolver.resolve(StaticJavaParser.parseClassOrInterfaceType("UnknownType")));
    }

    @Test void resolveFloatThrows() {
        assertThrows(CompilerException.class,
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

    @Nested
    class DynamicLedgerRegistrationTests {

        @Test
        void allLedgerSealedInterfacesRegistered() {
            // All ledger sealed interfaces should be registered as SumTypes
            var tr = new TypeResolver();
            registerLedgerTypes(tr);

            // Core sealed interfaces that use implicit permits (inner records)
            var expectedSumTypes = List.of(
                    "Credential", "OutputDatum", "ScriptInfo", "IntervalBoundType",
                    "Vote", "DRep", "Voter", "StakingCredential", "Delegatee",
                    "TxCert", "GovernanceAction", "ScriptPurpose"
            );
            for (var name : expectedSumTypes) {
                var st = tr.lookupSumType(name);
                assertTrue(st.isPresent(), name + " should be registered as SumType");
                assertFalse(st.get().constructors().isEmpty(), name + " should have constructors");
            }
        }

        @Test
        void allLedgerRecordsRegistered() {
            // Key ledger records should be registered as RecordTypes
            var tr = new TypeResolver();
            registerLedgerTypes(tr);

            var expectedRecords = List.of(
                    "ScriptContext", "TxInfo", "TxOut", "Address", "Value",
                    "TxOutRef", "IntervalBound", "Interval"
            );
            for (var name : expectedRecords) {
                var rt = tr.lookupRecord(name);
                assertTrue(rt.isPresent(), name + " should be registered as RecordType");
                assertFalse(rt.get().fields().isEmpty(), name + " should have fields");
            }
        }

        @Test
        void implicitSealedInterfaceVariantOrdering() {
            // Credential variants must have correct constructor tags (tag order = declaration order)
            var tr = new TypeResolver();
            registerLedgerTypes(tr);

            var credential = tr.lookupSumType("Credential").orElseThrow();
            assertEquals(2, credential.constructors().size());
            assertEquals("PubKeyCredential", credential.constructors().get(0).name());
            assertEquals(0, credential.constructors().get(0).tag());
            assertEquals("ScriptCredential", credential.constructors().get(1).name());
            assertEquals(1, credential.constructors().get(1).tag());
        }

        @Test
        void flatVariantAliasesWork() {
            // Variant lookup should work with both inner-class and flat FQCNs
            var tr = new TypeResolver();
            registerLedgerTypes(tr);

            // Inner-class FQCN
            var innerLookup = tr.lookupSumTypeForVariant("com.bloxbean.cardano.julc.ledger.Credential.PubKeyCredential");
            assertTrue(innerLookup.isPresent(), "Inner-class FQCN should resolve");
            assertEquals("Credential", innerLookup.get().name());

            // Flat FQCN (backward compatibility)
            var flatLookup = tr.lookupSumTypeForVariant("com.bloxbean.cardano.julc.ledger.PubKeyCredential");
            assertTrue(flatLookup.isPresent(), "Flat FQCN should resolve");
            assertEquals("Credential", flatLookup.get().name());
        }

        @Test
        void voteNameCollisionResolvesCorrectly() {
            // "Vote" should resolve to the Vote sealed interface, NOT to Delegatee.Vote variant
            var tr = new TypeResolver();
            registerLedgerTypes(tr);

            var result = tr.resolve(StaticJavaParser.parseClassOrInterfaceType("Vote"));
            assertInstanceOf(PirType.SumType.class, result);
            var st = (PirType.SumType) result;
            assertEquals("Vote", st.name());
            assertEquals(3, st.constructors().size());
            // Delegatee.Vote would have only 1 field (dRep), not 3 constructors
        }

        @Test
        void delegateeVariantsRegistered() {
            // Delegatee should have its own variants including Vote
            var tr = new TypeResolver();
            registerLedgerTypes(tr);

            var delegatee = tr.lookupSumType("Delegatee").orElseThrow();
            assertEquals(3, delegatee.constructors().size());
            assertEquals("Stake", delegatee.constructors().get(0).name());
            assertEquals("Vote", delegatee.constructors().get(1).name());
            assertEquals("StakeVote", delegatee.constructors().get(2).name());

            // The Delegatee.Vote variant should still be findable by inner-class FQCN
            var delegateeVote = tr.lookupSumTypeForVariant(
                    "com.bloxbean.cardano.julc.ledger.Delegatee.Vote");
            assertTrue(delegateeVote.isPresent());
            assertEquals("Delegatee", delegateeVote.get().name());
        }

        @Test
        void dependencyOrderingPreserved() {
            // IntervalBound depends on IntervalBoundType — both should be registered correctly
            var tr = new TypeResolver();
            registerLedgerTypes(tr);

            var bound = tr.lookupRecord("IntervalBound").orElseThrow();
            // IntervalBound should have a field "boundType" that's a SumType (IntervalBoundType)
            var boundTypeField = bound.fields().stream()
                    .filter(f -> f.name().equals("boundType"))
                    .findFirst()
                    .orElseThrow();
            assertInstanceOf(PirType.SumType.class, boundTypeField.type(),
                    "IntervalBound.boundType should be SumType, not DataType");
        }

        @Test
        void txInfoFieldTypesArePrecise() {
            // Dynamic registration should produce precise types for TxInfo fields
            var tr = new TypeResolver();
            registerLedgerTypes(tr);

            var txInfo = tr.lookupRecord("TxInfo").orElseThrow();
            // signatories should be List<PubKeyHash> -> ListType(ByteStringType)
            var signatories = txInfo.fields().stream()
                    .filter(f -> f.name().equals("signatories"))
                    .findFirst()
                    .orElseThrow();
            assertInstanceOf(PirType.ListType.class, signatories.type());
            assertInstanceOf(PirType.ByteStringType.class,
                    ((PirType.ListType) signatories.type()).elemType(),
                    "signatories element type should be ByteStringType (PubKeyHash)");
        }

        @Test
        void ledgerSourceLoaderFindsAllSources() {
            // LedgerSourceLoader should load sources without errors
            var ledgerCus = LedgerSourceLoader.loadLedgerSources(
                    Thread.currentThread().getContextClassLoader());
            assertFalse(ledgerCus.isEmpty(), "Should load at least 1 ledger source");
            // We bundle ~26 types (37 files minus 7 hash types minus 4 utility classes)
            assertTrue(ledgerCus.size() >= 20, "Should load at least 20 ledger sources, got " + ledgerCus.size());
        }
    }

    @Nested
    class ScopeAwareResolutionTests {

        private void registerAllTypes(TypeResolver resolver, CompilationUnit... cus) {
            var registrar = new TypeRegistrar();
            registrar.registerAll(List.of(cus), resolver);
        }

        @Test
        void sameNamedInnerRecordsInDifferentPackages() {
            // Two sealed interfaces in different packages, both with a "Branch" inner record
            var sourceA = """
                    package com.a;
                    import java.math.BigInteger;
                    sealed interface ProofStep permits ProofStep.Branch, ProofStep.Leaf {}
                    record Branch(BigInteger left, BigInteger right) implements ProofStep {}
                    record Leaf(BigInteger value) implements ProofStep {}
                    """;
            var sourceB = """
                    package com.b;
                    import java.math.BigInteger;
                    sealed interface Tree permits Tree.Branch, Tree.Leaf {}
                    record Branch(BigInteger data) implements Tree {}
                    record Leaf() implements Tree {}
                    """;
            var cuA = StaticJavaParser.parse(sourceA);
            var cuB = StaticJavaParser.parse(sourceB);

            var resolver = new TypeResolver();
            registerAllTypes(resolver, cuA, cuB);

            // Both ProofStep and Tree should be registered as sum types
            var proofStep = resolver.lookupSumType("com.a.ProofStep");
            assertTrue(proofStep.isPresent(), "ProofStep should be registered");
            assertEquals(2, proofStep.get().constructors().size());
            assertEquals("Branch", proofStep.get().constructors().get(0).name());
            assertEquals(2, proofStep.get().constructors().get(0).fields().size()); // left, right

            var tree = resolver.lookupSumType("com.b.Tree");
            assertTrue(tree.isPresent(), "Tree should be registered");
            assertEquals(2, tree.get().constructors().size());
            assertEquals("Branch", tree.get().constructors().get(0).name());
            assertEquals(1, tree.get().constructors().get(0).fields().size()); // data only

            // Variant lookup by FQCN should work
            var proofBranch = resolver.lookupSumTypeForVariant("com.a.Branch");
            assertTrue(proofBranch.isPresent());
            assertEquals("ProofStep", proofBranch.get().name());

            var treeBranch = resolver.lookupSumTypeForVariant("com.b.Branch");
            assertTrue(treeBranch.isPresent());
            assertEquals("Tree", treeBranch.get().name());
        }

        @Test
        void scopeAwareResolveTypeWithScope() {
            // Register types with same simple name in different packages
            var sourceA = """
                    package com.a;
                    import java.math.BigInteger;
                    sealed interface Shape permits Shape.Circle, Shape.Square {}
                    record Circle(BigInteger radius) implements Shape {}
                    record Square(BigInteger side) implements Shape {}
                    """;
            var sourceB = """
                    package com.b;
                    import java.math.BigInteger;
                    sealed interface Shape permits Shape.Circle, Shape.Triangle {}
                    record Circle(BigInteger r, BigInteger x) implements Shape {}
                    record Triangle(BigInteger a, BigInteger b, BigInteger c) implements Shape {}
                    """;
            var cuA = StaticJavaParser.parse(sourceA);
            var cuB = StaticJavaParser.parse(sourceB);

            var resolver = new TypeResolver();
            registerAllTypes(resolver, cuA, cuB);

            // resolveTypeWithScope with scope should disambiguate
            // Parse "Shape.Circle" as a ClassOrInterfaceType — scope="Shape" + name="Circle"
            var scopedType = StaticJavaParser.parseClassOrInterfaceType("Shape.Circle");
            assertEquals("Circle", scopedType.getNameAsString());
            assertTrue(scopedType.getScope().isPresent());

            // With com.a import context, Shape resolves to com.a.Shape, so Shape.Circle → com.a.Circle
            var knownFqcns = resolver.allRegisteredFqcns();
            resolver.setCurrentImportResolver(new ImportResolver(cuA, knownFqcns));
            var resolvedA = resolver.resolveTypeWithScope(scopedType);
            assertEquals("com.a.Circle", resolvedA);

            // With com.b import context, Shape resolves to com.b.Shape, so Shape.Circle → com.b.Circle
            resolver.setCurrentImportResolver(new ImportResolver(cuB, knownFqcns));
            var resolvedB = resolver.resolveTypeWithScope(scopedType);
            assertEquals("com.b.Circle", resolvedB);
        }

        @Test
        void noScopeFallsBackToResolveName() {
            var source = """
                    package com.a;
                    import java.math.BigInteger;
                    sealed interface Action permits Mint, Burn {}
                    record Mint(BigInteger amount) implements Action {}
                    record Burn(BigInteger amount) implements Action {}
                    """;
            var cu = StaticJavaParser.parse(source);

            var resolver = new TypeResolver();
            registerAllTypes(resolver, cu);

            // "Mint" without scope should still resolve via simpleNameIndex
            var noScopeType = StaticJavaParser.parseClassOrInterfaceType("Mint");
            assertFalse(noScopeType.getScope().isPresent());

            var resolved = resolver.resolveTypeWithScope(noScopeType);
            assertEquals("com.a.Mint", resolved);
        }

        @Test
        void packagelessInnerRecordScopeResolution() {
            // Packageless code: Shape.Circle should still resolve
            var source = """
                    import java.math.BigInteger;
                    sealed interface Shape permits Shape.Circle, Shape.Square {}
                    record Circle(BigInteger radius) implements Shape {}
                    record Square(BigInteger side) implements Shape {}
                    """;
            var cu = StaticJavaParser.parse(source);

            var resolver = new TypeResolver();
            registerAllTypes(resolver, cu);

            // "Shape.Circle" scoped type
            var scopedType = StaticJavaParser.parseClassOrInterfaceType("Shape.Circle");
            var resolved = resolver.resolveTypeWithScope(scopedType);
            // For packageless code, FQCN = simple name, so Shape → "Shape", Shape.Circle → "Shape.Circle"
            // But records are registered as "Circle" (no package). The scope "Shape" resolves to "Shape",
            // candidate "Shape.Circle" won't match "Circle". Falls back to resolveName("Circle") → "Circle"
            assertEquals("Circle", resolved);
        }
    }
}
