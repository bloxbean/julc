package org.julclang.compiler.frontend.javac;

import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreeScanner;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerFrontend;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JavaLibraryProvider;
import org.julclang.compiler.JavaSourceParser;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.LedgerTypeProvider;
import org.julclang.compiler.backend.LibraryType;
import org.julclang.compiler.error.DiagnosticCodes;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.resolve.LedgerSourceLoader;
import org.julclang.compiler.resolve.TypeRegistrar;
import org.julclang.compiler.resolve.TypeRegistry;
import org.julclang.compiler.resolve.TypeResolver;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;
import javax.tools.ToolProvider;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.attribute.PermittedSubclassesAttribute;
import java.lang.constant.ClassDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/** ADR-068 D7: types from javac. Expected values here are written out, or come from the JavaParser frontend. */
class JavacTypesTest {

    private static final String HEADER = """
            import java.math.BigInteger;
            import org.julclang.core.PlutusData;
            import org.julclang.core.types.JulcArray;
            import org.julclang.core.types.JulcList;
            import org.julclang.core.types.JulcMap;
            import org.julclang.stdlib.annotation.NewType;
            import java.util.Optional;
            """;

    static JavacProgram read(String source) {
        return JavacProgram.analyze(List.of(JavacFrontend.Unit.of(source)), List.of());
    }

    private static TypeRegistry.Snapshot registry(String declarations) {
        return registryOf(HEADER + declarations);
    }

    private static TypeRegistry.Snapshot registryOf(String source) {
        return read(source).registry().snapshot();
    }

    private static CompilerException rejected(String declarations) {
        return assertThrows(CompilerException.class, () -> read(HEADER + declarations));
    }

    static Stream<Arguments> builtIns() {
        var data = new PirType.DataType();
        var string = new PirType.StringType();
        var bool = new PirType.BoolType();
        return Stream.of(
                arguments("boolean", bool), arguments("Boolean", bool), arguments("int", INTEGER), arguments("long", INTEGER),
                arguments("BigInteger", INTEGER), arguments("byte[]", BYTES), arguments("String", string),
                arguments("PlutusData", data), arguments("PlutusData.ConstrData", data), arguments("PlutusData.MapData", data),
                arguments("PlutusData.ListData", data), arguments("PlutusData.IntData", data), arguments("PlutusData.BytesData", data),
                arguments("JulcValue", new PirType.NativeValueType()), arguments("JulcG1", new PirType.NativeG1Type()),
                arguments("JulcG2", new PirType.NativeG2Type()), arguments("JulcMlResult", new PirType.NativeMlResultType()),
                arguments("JulcScalars", new PirType.NativeListType(INTEGER)),
                arguments("JulcG1Points", new PirType.NativeListType(new PirType.NativeG1Type())),
                arguments("JulcG2Points", new PirType.NativeListType(new PirType.NativeG2Type())),
                arguments("JulcList<BigInteger>", new PirType.ListType(INTEGER)), arguments("java.util.List<byte[]>", new PirType.ListType(BYTES)),
                arguments("JulcArray<BigInteger>", new PirType.ArrayType(INTEGER)), arguments("JulcArray", new PirType.ArrayType(data)),
                arguments("JulcMap<byte[], BigInteger>", new PirType.MapType(BYTES, INTEGER)),
                arguments("java.util.Map<BigInteger, byte[]>", new PirType.MapType(INTEGER, BYTES)),
                arguments("Optional<String>", new PirType.OptionalType(string)),
                arguments("Tuple2<BigInteger, byte[]>", new PirType.RecordType("Tuple2", List.of(field("first", INTEGER), field("second", BYTES)))),
                arguments("Tuple2", new PirType.RecordType("Tuple2", List.of(field("first", data), field("second", data)))),
                arguments("Tuple3<BigInteger, byte[], String>", new PirType.RecordType("Tuple3",
                        List.of(field("first", INTEGER), field("second", BYTES), field("third", string)))),
                arguments("AssetEntry", new PirType.RecordType("AssetEntry",
                        List.of(field("policyId", BYTES), field("tokenName", BYTES), field("amount", INTEGER)))),
                arguments("org.julclang.ledger.PubKeyHash", BYTES), arguments("org.julclang.ledger.TxId", BYTES));
    }

    /** The JavaParser path's reading of one source, as JulcCompiler runs it. */
    private static TypeResolver reference(String source) {
        var resolver = new TypeResolver();
        new TypeRegistrar().registerAll(LedgerSourceLoader.loadLedgerSources(JavacTypesTest.class.getClassLoader()), resolver);
        new TypeRegistrar().registerAll(List.of(JavaSourceParser.parseCompilationUnit(source)), resolver);
        return resolver;
    }

    private static PirType.Field field(String name, PirType type) {
        return new PirType.Field(name, type);
    }

    private static final PirType INTEGER = new PirType.IntegerType();
    private static final PirType BYTES = new PirType.ByteStringType();

    @Nested
    class Ledger {
        @Test
        void registryEqualsTheJavaParserPath() {
            var javac = read("class P { static boolean m() { return true; } }").registry().snapshot();
            var source = new TypeResolver();
            new TypeRegistrar().registerAll(LedgerSourceLoader.loadLedgerSources(JavacTypesTest.class.getClassLoader()), source);
            var reference = source.snapshot();
            assertEquals(new TreeMap<>(reference.records()), new TreeMap<>(javac.records()));
            assertEquals(new TreeMap<>(reference.sums()), new TreeMap<>(javac.sums()));
            assertEquals(new TreeMap<>(reference.variants()), new TreeMap<>(javac.variants()));
            assertEquals(new TreeMap<>(reference.newTypes()), new TreeMap<>(javac.newTypes()));
            assertEquals(reference.parameterized(), javac.parameterized());
            assertEquals(List.copyOf(reference.records().keySet()), List.copyOf(javac.records().keySet()), "registration order");
            assertEquals(List.copyOf(reference.sums().keySet()), List.copyOf(javac.sums().keySet()), "registration order");
        }

        @Test
        void providerOutputEqualsTheJavaParserPath() {
            var reference = new LedgerTypeProvider();
            var javac = new LedgerTypeProvider(new CompilerOptions().setFrontend(CompilerFrontend.JAVAC));
            assertEquals(new TreeMap<>(reference.types()), new TreeMap<>(javac.types()));
            assertEquals(List.copyOf(reference.types().keySet()), List.copyOf(javac.types().keySet()), "iteration order");
            assertEquals(new TreeMap<>(reference.namedDefinitions()), new TreeMap<>(javac.namedDefinitions()));
            assertEquals(34, javac.types().size());
            assertEquals(79, javac.namedDefinitions().size());
        }

        @Test
        void credentialTagsAreTheLedgers() {
            var credential = read("class P {}").registry().snapshot().sums().get("org.julclang.ledger.Credential");
            assertEquals(List.of("PubKeyCredential", "ScriptCredential"),
                    credential.constructors().stream().map(PirType.Constructor::name).toList());
            assertEquals(0, credential.constructors().get(0).tag());
            assertEquals(1, credential.constructors().get(1).tag());
        }

        /** I11: a stub cannot move a tag the ledger fixes, whatever else it says. */
        @Test
        void aStubThatMovesAFixedTagIsABrokenBuild() {
            String credential = "org.julclang.ledger.Credential";
            assertDoesNotThrow(() -> JavacTypeRegistrar.requireFixedTags(credential, List.of("PubKeyCredential", "ScriptCredential")));
            assertDoesNotThrow(() -> JavacTypeRegistrar.requireFixedTags("org.julclang.ledger.Vote", List.of("anything")));
            var e = assertThrows(BrokenJulcBuildException.class,
                    () -> JavacTypeRegistrar.requireFixedTags(credential, List.of("ScriptCredential", "PubKeyCredential")));
            assertTrue(e.getMessage().contains("permits [ScriptCredential, PubKeyCredential]"), e.getMessage());
        }

        /** The same rule, through a real read: blobs whose Credential stub lists its variants the other way round. */
        @Test
        void aReadOverAMovedCredentialStubFails() {
            String path = "org/julclang/ledger/Credential.class";
            var stubs = new TreeMap<>(JavacPlatform.read(JavacPlatform.resource(JavacPlatform.STUBS_RESOURCE)));
            var model = ClassFile.of().parse(stubs.get(path));
            var reversed = model.findAttribute(Attributes.permittedSubclasses()).orElseThrow().permittedSubclasses().reversed()
                    .stream().map(c -> ClassDesc.ofInternalName(c.asInternalName())).toList();
            stubs.put(path, ClassFile.of().transformClass(model, ClassTransform.dropping(e -> e instanceof PermittedSubclassesAttribute)
                    .andThen(ClassTransform.endHandler(b -> b.with(PermittedSubclassesAttribute.ofSymbols(reversed))))));
            var platform = JavacPlatform.of(JavacPlatform.resource(JavacPlatform.PLATFORM_RESOURCE), JavacPlatform.write(stubs));
            var frontend = new JavacFrontend(platform, ToolProvider::getSystemJavaCompiler, JavacFrontend.BundledSources.none());
            var e = assertThrows(BrokenJulcBuildException.class,
                    () -> JavacProgram.analyze(frontend, List.of(JavacFrontend.Unit.of("class P {}")), List.of()));
            assertTrue(e.getMessage().contains("org.julclang.ledger.Credential"), e.getMessage());
        }

        @Test
        void aHashTypeIsItsBytesAndAValueKeepsItsDeclaredShape() {
            var types = read("class P {}").registry();
            assertEquals(BYTES, types.lookupNominal("org.julclang.ledger.PubKeyHash").orElseThrow());
            var value = (PirType.RecordType) types.lookupNominal("org.julclang.ledger.Value").orElseThrow();
            assertEquals(List.of("inner"), value.fields().stream().map(PirType.Field::name).toList());
            var described = new LedgerTypeProvider(new CompilerOptions().setFrontend(CompilerFrontend.JAVAC))
                    .types().get("org.julclang.ledger.Value");
            assertEquals(Set.of(), described.operations(), "a map-encoded record has no constructor operations");
        }
    }

    /** #284, #286, T33: a name means what Java binds it to (JLS 6.4.1, 7.5). */
    @Nested
    class Names {
        @Test
        void aTopLevelRecordShadowsTheOnDemandImportOfALedgerName() {
            var program = read("""
                    import java.math.BigInteger;
                    import org.julclang.ledger.*;
                    record Rational(BigInteger denominator, BigInteger numerator) {}
                    record Holder(Rational r) {}
                    class P {}
                    """);
            var rational = program.registry().snapshot().records().get("Rational");
            assertEquals(List.of("denominator", "numerator"), rational.fields().stream().map(PirType.Field::name).toList());
            assertEquals(rational, program.registry().snapshot().records().get("Holder").fields().getFirst().type());
            var ledger = program.registry().snapshot().records().get("org.julclang.ledger.Rational");
            assertEquals(List.of("numerator", "denominator"), ledger.fields().stream().map(PirType.Field::name).toList());
        }

        @Test
        void aSingleTypeImportBindsTheLedgerType() {
            var program = read("""
                    import org.julclang.ledger.Rational;
                    record Holder(Rational r) {}
                    class P {}
                    """);
            var ledger = program.registry().snapshot().records().get("org.julclang.ledger.Rational");
            assertEquals(ledger, program.registry().snapshot().records().get("Holder").fields().getFirst().type());
        }

        @Test
        void aUserHashNamedRecordIsARecord() {
            var registry = registry("""
                    record PubKeyHash(byte[] h) {}
                    record Holder(PubKeyHash owner, org.julclang.ledger.PubKeyHash ledger) {}
                    class P {}
                    """);
            var user = new PirType.RecordType("PubKeyHash", List.of(field("h", BYTES)));
            assertEquals(user, registry.records().get("PubKeyHash"));
            assertEquals(List.of(field("owner", user), field("ledger", BYTES)), registry.records().get("Holder").fields());
        }

        /** Every simple name the reference binds to a built-in: on javac, the user's declaration wins, as in Java. */
        @ParameterizedTest
        @ValueSource(strings = {"BigInteger", "String", "Boolean", "PlutusData", "ConstrData", "MapData", "ListData",
                "IntData", "BytesData", "JulcValue", "JulcG1", "JulcG2", "JulcMlResult", "JulcScalars", "JulcG1Points",
                "JulcG2Points", "List", "JulcList", "JulcArray", "Map", "JulcMap", "Tuple2", "Tuple3", "AssetEntry",
                "Optional"})
        void aUserTypeNamedLikeABuiltInIsTheUsersType(String name) {
            var user = new PirType.RecordType(name, List.of(field("v", BYTES)));
            var topLevel = read("record " + name + "(byte[] v) {}\nrecord Holder(" + name + " h) {}\nclass P {}\n");
            assertEquals(user, topLevel.registry().snapshot().records().get("Holder").fields().getFirst().type());
            var nested = read("class P { record " + name + "(byte[] v) {} record Holder(" + name + " h) {} }");
            assertEquals(user, nested.registry().snapshot().records().get("P.Holder").fields().getFirst().type());
        }

        @Test
        void aNestedTypeShadowsATopLevelOneOfTheSameName() {
            var registry = read("""
                    import java.math.BigInteger;
                    record Item(byte[] outer) {}
                    class P {
                        record Item(BigInteger inner) {}
                        record Holder(Item i) {}
                    }
                    """).registry().snapshot();
            assertEquals(new PirType.RecordType("Item", List.of(field("inner", INTEGER))),
                    registry.records().get("P.Holder").fields().getFirst().type());
        }

        /** The saved #284 reproducer's names: the local's type and the accessor javac binds. */
        @Test
        void javacFactsBindTheLocalAndTheAccessorOfTheReproducer() {
            var program = read("""
                    import java.math.BigInteger;
                    record Rational(BigInteger denominator, BigInteger numerator) {}
                    public class Probe {
                        public static BigInteger m(BigInteger a) {
                            Rational r = new Rational(BigInteger.valueOf(5), BigInteger.valueOf(7));
                            return r.numerator();
                        }
                    }
                    """);
            var facts = program.facts();
            var found = new ArrayList<ExecutableElement>();
            var locals = new ArrayList<PirType>();
            new TreeScanner<Void, Void>() {
                @Override
                public Void visitVariable(VariableTree tree, Void unused) {
                    if (tree.getName().contentEquals("r")) locals.add(facts.pirType(facts.type(tree), null));
                    return super.visitVariable(tree, null);
                }

                @Override
                public Void visitMethodInvocation(MethodInvocationTree tree, Void unused) {
                    if (tree.getMethodSelect() instanceof MemberSelectTree select
                            && select.getIdentifier().contentEquals("numerator")) {
                        found.add((ExecutableElement) facts.element(select));
                    }
                    return super.visitMethodInvocation(tree, null);
                }
            }.scan(program.roots().getFirst(), null);
            assertEquals(new PirType.RecordType("Rational", List.of(field("denominator", INTEGER), field("numerator", INTEGER))),
                    locals.getFirst());
            assertEquals("Rational", facts.identity((TypeElement) found.getFirst().getEnclosingElement()));
        }

        /** #286, at every placement the simple-name check could take for the ledger's: packaged and nested too. */
        @ParameterizedTest
        @ValueSource(strings = {"PubKeyHash", "ScriptHash", "ValidatorHash", "PolicyId", "TokenName", "DatumHash", "TxId"})
        void aHashNameIsARecordWhereverItIsDeclared(String name) {
            var user = new PirType.RecordType(name, List.of(field("h", BYTES)));
            var packaged = read("package a.b;\nrecord " + name + "(byte[] h) {}\nrecord H(" + name + " v) {}\nclass P {}\n");
            assertEquals(user, packaged.registry().snapshot().records().get("a.b.H").fields().getFirst().type());
            var nested = read("class P { record " + name + "(byte[] h) {} record H(" + name + " v) {} }");
            assertEquals(user, nested.registry().snapshot().records().get("P.H").fields().getFirst().type());
            var topLevel = read("record " + name + "(byte[] h) {}\nrecord H(" + name + " v) {}\nclass P {}\n");
            assertEquals(user, topLevel.registry().snapshot().records().get("H").fields().getFirst().type());
        }

        /** D3: no source takes an API type's name, at any depth; Ownership sees only top-level names (F804). */
        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
                "class java { static class math { record BigInteger(byte[] b) {} } } record H(java.math.BigInteger x) {} class P {}|java.math.BigInteger",
                "class org { static class julclang { static class ledger { record Address(byte[] b) {} } } } class P {}|org.julclang.ledger.Address",
                "class org { static class julclang { static class ledger { record PubKeyHash(byte[] b) {} } } } class P {}|org.julclang.ledger.PubKeyHash"})
        void aNestedTypeCannotTakeAnApiName(String source, String name) {
            var diagnostic = assertThrows(CompilerException.class, () -> read(source)).diagnostics().getFirst();
            assertEquals(DiagnosticCodes.LEDGER_TYPE_REDECLARED.code(), diagnostic.code(), diagnostic.message());
            assertTrue(diagnostic.message().contains(name), diagnostic.message());
            assertTrue(diagnostic.line() > 0);
        }

        /**
         * F804: a user type named like a package obscures it for a lookup by name, and that once ended in a raw
         * NullPointerException. The ledger is read from its package, so these are valid Java with a registry.
         */
        @ParameterizedTest
        @ValueSource(strings = {"class org {}", "package org;\nclass julclang {}", "package org.julclang;\nclass ledger {}"})
        void aTypeNamedLikeAPackageDoesNotLoseTheLedger(String source) {
            var registry = read(source).registry().snapshot();
            assertEquals(67, registry.records().keySet().stream().filter(n -> n.startsWith("org.julclang.ledger.")).count());
            assertTrue(registry.sums().containsKey("org.julclang.ledger.Credential"));
        }
    }

    /** Local types: Java gives them no qualified name, and the JavaParser frontend keys them by their simple name. */
    @Nested
    class LocalTypes {
        /** F801: on the reference a generic method's body is never lowered, so its local record is not rejected. */
        @Test
        void aLocalRecordDoesNotReplaceTheSealedInterfaceOfItsName() {
            var program = JavacProgram.analyze(List.of(JavacFrontend.Unit.of("""
                    import java.math.BigInteger;
                    import lib.L;
                    class P { static L.S m() { return new L.B(BigInteger.TWO); } }
                    """)), List.of(JavacFrontend.Unit.of("""
                    package lib;
                    import java.math.BigInteger;
                    import org.julclang.stdlib.annotation.OnchainLibrary;
                    @OnchainLibrary
                    public class L {
                        public sealed interface S permits A, B {}
                        public record A() implements S {}
                        public record B(BigInteger v) implements S {}
                        public static <T> boolean unused(T x) { record S(byte[] b) {} return true; }
                    }
                    """)));
            var sum = assertInstanceOf(PirType.SumType.class, program.registry().lookupNominal("lib.L.S").orElseThrow());
            assertEquals(List.of("A", "B"), sum.constructors().stream().map(PirType.Constructor::name).toList());
            assertEquals(1, sum.constructors().get(1).tag());
            assertEquals(new PirType.RecordType("S", List.of(field("b", BYTES))),
                    program.registry().lookupNominal("lib.L$1S").orElseThrow());
        }

        @Test
        void aMethodReturningTheInterfaceBindsTheInterfaceNotTheLocalRecord() {
            var program = read("""
                    class P {
                        sealed interface S permits A, B {}
                        record A() implements S {}
                        record B() implements S {}
                        static <T> boolean unused(T x) { record S(byte[] b) {} return true; }
                        static S m() { return new B(); }
                    }
                    """);
            var returned = new ArrayList<PirType>();
            new TreeScanner<Void, Void>() {
                @Override
                public Void visitMethod(MethodTree tree, Void unused) {
                    if (tree.getName().contentEquals("m")) returned.add(program.facts().pirType(program.facts().type(tree.getReturnType()), null));
                    return super.visitMethod(tree, null);
                }
            }.scan(program.roots().getFirst(), null);
            assertInstanceOf(PirType.SumType.class, returned.getFirst());
        }

        /** The reference keys both {@code R} and rejects them as duplicates; here each has an identity of its own. */
        @Test
        void twoLocalRecordsOfOneNameHaveIdentitiesOfTheirOwn() {
            String source = """
                    class P {
                        static boolean a() { record R(boolean v) {} return new R(true).v(); }
                        static byte[] b() { record R(byte[] v) {} return new R(new byte[0]).v(); }
                    }
                    """;
            var records = read(source).registry().snapshot().records();
            assertEquals(List.of(field("v", new PirType.BoolType())), records.get("P$1R").fields());
            assertEquals(List.of(field("v", BYTES)), records.get("P$2R").fields());
            assertTrue(assertThrows(RuntimeException.class, () -> reference(source)).getMessage().contains("Duplicate record type 'R'"));
        }

        @Test
        void aMemberTypeOfALocalRecordKeepsItsOwnIdentity() {
            var registry = read("""
                    class P {
                        record Inner(boolean top) {}
                        static boolean a() {
                            record R(boolean v) { record Inner(byte[] nested) {} }
                            return new R(true).v();
                        }
                    }
                    """).registry().snapshot();
            assertEquals(List.of(field("top", new PirType.BoolType())), registry.records().get("P.Inner").fields());
            assertEquals(List.of(field("nested", BYTES)), registry.records().get("P$1R$Inner").fields());
        }

        /** The invariant the registries need; a key that collides would otherwise erase a sum silently (F801). */
        @Test
        void aSharedIdentityIsRejected() {
            var sum = assertThrows(CompilerException.class,
                    () -> JavacTypeRegistrar.requireDistinctIdentities(Set.of("a.S"), Set.of("a.S"), Set.of()));
            assertTrue(sum.getMessage().contains("Type identity 'a.S'"), sum.getMessage());
            assertThrows(CompilerException.class,
                    () -> JavacTypeRegistrar.requireDistinctIdentities(Set.of("a.T"), Set.of(), Set.of("a.T")));
            assertDoesNotThrow(() -> JavacTypeRegistrar.requireDistinctIdentities(Set.of("a.R"), Set.of("a.S"), Set.of("a.T")));
        }
    }

    /** I11's source rules. */
    @Nested
    class Tags {
        private List<String> variants(TypeRegistry.Snapshot registry, String sum) {
            var constructors = registry.sums().get(sum).constructors();
            for (int i = 0; i < constructors.size(); i++) assertEquals(i, constructors.get(i).tag());
            return constructors.stream().map(PirType.Constructor::name).toList();
        }

        @Test
        void aPermitsClauseDecidesTheTagsWhateverTheMemberOrder() {
            var registry = registry("""
                    sealed interface S permits S.C, S.A, S.B {
                        record A() implements S {}
                        record B() implements S {}
                        record C() implements S {}
                    }
                    class P {}
                    """);
            assertEquals(List.of("C", "A", "B"), variants(registry, "S"));
        }

        @Test
        void aPermitsClauseMayNameTopLevelRecords() {
            var registry = registry("""
                    sealed interface S permits B, A {}
                    record A(BigInteger v) implements S {}
                    record B(byte[] b) implements S {}
                    class P {}
                    """);
            assertEquals(List.of("B", "A"), variants(registry, "S"));
            assertEquals(List.of(field("b", BYTES)), registry.sums().get("S").constructors().getFirst().fields());
            assertEquals(List.of(registry.sums().get("S")), registry.variants().get("A"));
        }

        @Test
        void withoutPermitsTheNestedRecordsInMemberOrder() {
            var registry = registry("""
                    sealed interface S {
                        record B() implements S {}
                        static boolean helper() { return true; }
                        record A() implements S {}
                    }
                    class P {}
                    """);
            assertEquals(List.of("B", "A"), variants(registry, "S"));
        }

        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
                "sealed interface S { record A() implements S {} } record B() implements S {} class P {}",
                "sealed interface S {} record A() implements S {} class P {}",
                "class P { sealed interface S { record A() implements S {} } record B() implements S {} }",
                "sealed interface S { record A() implements S {} sealed interface T extends S { record B() implements T {} } } class P {}"
        })
        void withoutPermitsAnyOtherPermittedClassIsRejected(String source) {
            var e = rejected(source);
            var diagnostic = e.diagnostics().getFirst();
            assertEquals(DiagnosticCodes.SEALED_PERMITS_REQUIRED.code(), diagnostic.code());
            assertTrue(diagnostic.message().contains("'S'"), diagnostic.message());
            assertTrue(diagnostic.line() > 0, "the rejection has a position");
            assertTrue(diagnostic.suggestion().contains("permits"), diagnostic.suggestion());
        }

        /** F800: on the reference A has a tag, by the name in its implements clause, so it must not vanish here. */
        @Test
        void aNestedRecordImplementingAnotherTypeOfTheNameIsJulc0092NotATagShift() {
            var e = assertThrows(CompilerException.class, () -> read("""
                    class Other { interface S {} }
                    sealed interface S { record A() implements Other.S {} record B() implements S {} }
                    class P {}
                    """));
            assertEquals(DiagnosticCodes.SEALED_PERMITS_REQUIRED.code(), e.diagnostics().getFirst().code());
        }

        @Test
        void aNestedRecordThatImplementsNothingIsNotAVariant() {
            var registry = registry("""
                    sealed interface S {
                        record Helper(BigInteger v) {}
                        record A(Helper h) implements S {}
                        record B() implements S {}
                    }
                    class P {}
                    """);
            assertEquals(List.of("A", "B"), variants(registry, "S"));
            assertFalse(registry.variants().containsKey("S.Helper"));
            assertTrue(registry.records().containsKey("S.Helper"));
        }

        @Test
        void aGenericSealedInterfaceIsMarked() {
            var registry = registry("""
                    sealed interface S<T> { record A() implements S<BigInteger> {} }
                    class P {}
                    """);
            assertTrue(registry.parameterized().contains("S"));
        }
    }

    @Nested
    class NewTypes {
        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {"byte[]|ByteStringType", "BigInteger|IntegerType", "String|StringType", "boolean|BoolType"})
        void aNewTypeIsItsUnderlyingType(String component, String kind) {
            var registry = registry("@NewType record N(" + component + " v) {} record H(N n) {} class P {}");
            assertEquals(kind, registry.newTypes().get("N").getClass().getSimpleName());
            assertEquals(registry.newTypes().get("N"), registry.records().get("H").fields().getFirst().type());
            assertFalse(registry.records().containsKey("N"), "a @NewType is not a record");
        }

        @Test
        void twoComponentsAreRejected() {
            assertTrue(rejected("@NewType record N(byte[] a, byte[] b) {} class P {}").diagnostics().getFirst().message()
                    .contains("@NewType record 'N' must have exactly 1 field, got 2"));
        }

        @Test
        void anUnsupportedComponentIsRejected() {
            assertTrue(rejected("@NewType record N(JulcList<byte[]> v) {} class P {}").diagnostics().getFirst().message()
                    .contains("field type 'JulcList<byte[]>' is not supported"));
        }

        @Test
        void aRecursiveNewTypeIsRejected() {
            assertTrue(rejected("@NewType record N(JulcList<N> v) {} class P {}").diagnostics().getFirst().message()
                    .contains("@NewType cannot be recursive: 'N'"));
        }

        /** The annotation is JuLC's by identity. The reference goes by the spelling, so the encoding differs (F803). */
        @Test
        void aUsersAnnotationNamedNewTypeIsNotJulcs() {
            String source = """
                    import java.math.BigInteger;
                    @interface NewType {}
                    @NewType record N(BigInteger v) {}
                    class P {}
                    """;
            var registry = read(source).registry().snapshot();
            assertFalse(registry.newTypes().containsKey("N"));
            assertEquals(new PirType.RecordType("N", List.of(field("v", INTEGER))), registry.records().get("N"));
            assertEquals(INTEGER, reference(source).snapshot().newTypes().get("N"), "the reference reads a @NewType");
        }

        /** Every spelling of JuLC's annotation is the annotation; the reference matches one marker form (F803, F828). */
        @ParameterizedTest
        @ValueSource(strings = {"@org.julclang.stdlib.annotation.NewType", "@NewType()", "@org.julclang.stdlib.annotation.NewType()"})
        void anyWayOfWritingTheAnnotationIsTheAnnotation(String annotation) {
            String source = HEADER + annotation + " record N(BigInteger v) {}\nclass P {}\n";
            assertEquals(INTEGER, registryOf(source).newTypes().get("N"));
            assertEquals(new PirType.RecordType("N", List.of(field("v", INTEGER))), reference(source).snapshot().records().get("N"),
                    "the reference reads a record, so its Data is a Constr where javac's is the integer");
        }

        /** F806: the same type, written out. The reference compares strings and rejects it; identity cannot tell. */
        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {"java.math.BigInteger|IntegerType", "java.lang.String|StringType"})
        void aQualifiedSpellingOfASupportedTypeIsThatType(String component, String kind) {
            String source = HEADER + "@NewType record N(" + component + " v) {}\nclass P {}\n";
            assertEquals(kind, registryOf(source).newTypes().get("N").getClass().getSimpleName());
            assertTrue(assertThrows(RuntimeException.class, () -> reference(source)).getMessage().contains("is not supported"));
        }

        /** F802: the reference rejects it, so the door through @NewType stays shut. */
        @Test
        void aNewTypeOverByteVarargsIsRejected() {
            assertTrue(rejected("@NewType record N(byte... v) {} class P {}").diagnostics().getFirst().message()
                    .contains("field type 'byte' is not supported"));
        }
    }

    @Nested
    class Components {
        @Test
        void containersKeepTheirElementTypes() {
            var registry = registry("""
                    record R(JulcList<BigInteger> a, java.util.List<byte[]> b, JulcMap<byte[], BigInteger> c,
                             Optional<String> d, PlutusData e, PlutusData.ConstrData f, boolean g, int h, long i) {}
                    class P {}
                    """);
            assertEquals(List.of(field("a", new PirType.ListType(INTEGER)), field("b", new PirType.ListType(BYTES)),
                    field("c", new PirType.MapType(BYTES, INTEGER)), field("d", new PirType.OptionalType(new PirType.StringType())),
                    field("e", new PirType.DataType()), field("f", new PirType.DataType()), field("g", new PirType.BoolType()),
                    field("h", INTEGER), field("i", INTEGER)), registry.records().get("R").fields());
        }

        @Test
        void aRawContainerHoldsData() {
            var registry = registry("record R(JulcList a, JulcMap b) {} class P {}");
            var data = new PirType.DataType();
            assertEquals(List.of(field("a", new PirType.ListType(data)), field("b", new PirType.MapType(data, data))),
                    registry.records().get("R").fields());
        }

        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
                "float|Unsupported primitive type: float. Supported types: boolean, int, long",
                "byte|Unsupported primitive type: byte. Supported types: boolean, int, long",
                "int[]|Arrays are not supported except byte[]. Use List<T> instead. Got: int[]",
                "Object|Unknown type: Object",
                "Integer|Unknown type: Integer"
        })
        void anUnsupportedComponentIsRejectedAsTheSourcePathRejectsIt(String component, String message) {
            var diagnostic = rejected("record R(" + component + " v) {} class P {}").diagnostics().getFirst();
            assertTrue(diagnostic.message().contains(message), diagnostic.message());
            assertEquals(HEADER.lines().count() + 1, diagnostic.line(), "the rejection keeps its position (F843)");
            assertEquals(10, diagnostic.column());
            assertTrue(diagnostic.fileName().endsWith(".java"), diagnostic.fileName());
        }

        /** F830: each built-in by its identity, written out, in a position the Data-encoded rules do not touch. */
        @ParameterizedTest
        @MethodSource("org.julclang.compiler.frontend.javac.JavacTypesTest#builtIns")
        void aBuiltInReadsAsItsPirType(String written, PirType expected) {
            var program = read(HEADER + """
                    import org.julclang.core.types.*;
                    class P { static %s x; }
                    """.formatted(written));
            var field = ElementFilter.fieldsIn(program.facts().elements().getTypeElement("P").getEnclosedElements()).getFirst();
            assertEquals(expected, program.facts().pirType(field.asType(), null));
        }

        /** I7: a column is a character offset, whatever the tabs before it. */
        @Test
        void aComponentsColumnIsACharacterOffset() {
            var diagnostic = assertThrows(CompilerException.class, () -> read("""
                    import java.math.BigInteger;
                    import org.julclang.core.types.JulcArray;
                    class P {
                    \trecord R(JulcArray<BigInteger> arr) {}
                    }
                    """)).diagnostics().getFirst();
            assertEquals(4, diagnostic.line());
            assertEquals(11, diagnostic.column(), "one tab, 'record R(', then the type");
        }

        /** The subset does not widen: the source path rejects {@code byte...}, and {@code byte[]} is not the same call. */
        @Test
        void byteVarargsAreRejectedAsTheSourcePathRejectsThem() {
            assertTrue(rejected("record R(byte... v) {} class P {}").diagnostics().getFirst().message()
                    .contains("Unsupported primitive type: byte"));
        }

        /** F802: only the canonical constructor says what a component is; this one says nothing, and the reference compiles it. */
        @Test
        void aVarargsConstructorThatIsNotCanonicalChangesNothing() {
            var registry = registry("""
                    class P {
                        record R(BigInteger x, byte[] a) { R(byte[] y, byte... z) { this(BigInteger.ONE, z); } }
                    }
                    """);
            assertEquals(List.of(field("x", INTEGER), field("a", BYTES)), registry.records().get("P.R").fields());
        }

        /** Java's type is an array, which the source path misreads as one integer: rejected, not misread. */
        @Test
        void anyOtherVarargsComponentIsAnArray() {
            String source = HEADER + "record R(BigInteger... v) {} class P {}";
            assertTrue(assertThrows(CompilerException.class, () -> read(source)).diagnostics().getFirst().message()
                    .contains("Arrays are not supported except byte[]"));
            var reference = new TypeResolver();
            new TypeRegistrar().registerAll(LedgerSourceLoader.loadLedgerSources(JavacTypesTest.class.getClassLoader()), reference);
            new TypeRegistrar().registerAll(List.of(JavaSourceParser.parseCompilationUnit(source)), reference);
            assertEquals(List.of(field("v", INTEGER)), reference.snapshot().records().get("R").fields());
        }

        @Test
        void aGenericComponentIsRejected() {
            assertTrue(rejected("record R<T>(T v) {} class P {}").diagnostics().getFirst().message().contains("Unknown type: T"));
        }

        @Test
        void aWildcardIsRejected() {
            assertTrue(rejected("record R(JulcList<?> v) {} class P {}").diagnostics().getFirst().message().contains("Cannot resolve type: ?"));
        }

        @Test
        void aNativeArrayComponentIsJulc0091AtItsType() {
            var diagnostic = rejected("record R(JulcArray<BigInteger> arr) {} class P {}").diagnostics().getFirst();
            assertEquals(DiagnosticCodes.NATIVE_ARRAY_NOT_DATA_ENCODABLE.code(), diagnostic.code());
            assertTrue(diagnostic.message().contains("Component 'arr' of record R"), diagnostic.message());
            assertTrue(diagnostic.line() > 0);
        }

        @Test
        void aNativeArrayTypeArgumentIsJulc0091() {
            var diagnostic = rejected("record R(JulcList<JulcArray<BigInteger>> v) {} class P {}").diagnostics().getFirst();
            assertEquals(DiagnosticCodes.NATIVE_ARRAY_NOT_DATA_ENCODABLE.code(), diagnostic.code());
            assertTrue(diagnostic.message().contains("A type argument of JulcList"), diagnostic.message());
        }
    }

    @Nested
    class Recursion {
        @Test
        void aRecursiveGroupReadsAsTheJavaParserPathReadsIt() {
            String source = HEADER + """
                    sealed interface Tree {
                        record Leaf(BigInteger v) implements Tree {}
                        record Node(JulcList<Tree> kids, Tree first) implements Tree {}
                    }
                    record A(Optional<B> b) {}
                    record B(Optional<A> a) {}
                    class P {}
                    """;
            var cu = JavaSourceParser.parseCompilationUnit(source);
            var reference = new TypeResolver();
            new TypeRegistrar().registerAll(LedgerSourceLoader.loadLedgerSources(JavacTypesTest.class.getClassLoader()), reference);
            new TypeRegistrar().registerAll(List.of(cu), reference);
            var javac = read(source).registry().snapshot();
            assertEquals(reference.snapshot().sums().get("Tree"), javac.sums().get("Tree"));
            assertEquals(reference.snapshot().records().get("A"), javac.records().get("A"));
            assertEquals(reference.snapshot().records().get("B"), javac.records().get("B"));
        }

        @Test
        void aRecursiveGroupRejectionIsAtTheDeclaration() {
            var diagnostic = rejected("record A(B b) {}\nrecord B(A a) {}\nclass P {}").diagnostics().getFirst();
            assertEquals(HEADER.lines().count() + 1, diagnostic.line());
            assertEquals(1, diagnostic.column());
        }

        @Test
        void aRecursiveGroupWithNoBaseIsRejected() {
            assertTrue(rejected("record A(B b) {} record B(A a) {} class P {}").diagnostics().getFirst().message()
                    .contains("Recursive type dependency has no finite base constructor: [A, B]"));
        }
    }

    /** D4, I8: the option, and no fallback. */
    @Nested
    class Selection {
        private static final String VALIDATOR = """
                import org.julclang.ledger.ScriptContext;
                import org.julclang.stdlib.annotation.Entrypoint;
                import org.julclang.stdlib.annotation.SpendingValidator;
                import org.julclang.core.PlutusData;
                @SpendingValidator
                class V {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) { return true; }
                }
                """;
        private static final CompilerOptions JAVAC = new CompilerOptions().setFrontend(CompilerFrontend.JAVAC);

        @Test
        void theDefaultIsJavaParser() {
            assertEquals(CompilerFrontend.JAVAPARSER, new CompilerOptions().getFrontend());
        }

        private static void assertNotPorted(Executable compile) {
            var e = assertThrows(CompilerException.class, compile);
            var diagnostic = e.diagnostics().getFirst();
            assertEquals(DiagnosticCodes.FRONTEND_CONSTRUCT_NOT_PORTED.code(), diagnostic.code(), e.getMessage());
            assertTrue(diagnostic.message().contains("the method 'validate' of V"), diagnostic.message());
            assertEquals(7, diagnostic.line(), "the method's first line, its annotation");
        }

        @Test
        void everyStringEntryPointIsNotPortedNeverAFallback() {
            var compiler = new JulcCompiler(null, JAVAC);
            assertNotPorted(() -> compiler.compile(VALIDATOR));
            assertNotPorted(() -> compiler.compile(VALIDATOR, List.of()));
            assertNotPorted(() -> compiler.compileWithDetails(VALIDATOR));
            assertNotPorted(() -> compiler.compileWithDetails(VALIDATOR, List.of()));
            assertNotPorted(() -> compiler.compileContract(VALIDATOR));
            assertNotPorted(() -> compiler.compileContract(VALIDATOR, List.of()));
            assertNotPorted(() -> compiler.compileContractWithDetails(VALIDATOR));
            assertNotPorted(() -> compiler.compileContractWithDetails(VALIDATOR, List.of()));
            assertNotPorted(() -> compiler.compileMethod(VALIDATOR, "validate"));
            assertNotPorted(() -> compiler.compileMethod(VALIDATOR, "validate", List.of()));
        }

        @Test
        void theDebugEntryPointsAreNotPorted() {
            var compiler = new JulcCompiler(null, new CompilerOptions().setFrontend(CompilerFrontend.JAVAC).setSourceMapEnabled(true));
            assertNotPorted(() -> compiler.compileForDebug(VALIDATOR));
            assertNotPorted(() -> compiler.compileForDebug(VALIDATOR, List.of()));
        }

        @Test
        void thePathEntryPointsAreNotPorted(@TempDir Path dir) throws Exception {
            Path file = dir.resolve("V.java");
            Files.writeString(file, VALIDATOR);
            var compiler = new JulcCompiler(null, JAVAC);
            assertNotPorted(() -> compiler.compile(file));
            assertNotPorted(() -> compiler.compile(file, List.of()));
            assertNotPorted(() -> compiler.compile(file.toFile()));
            assertNotPorted(() -> compiler.compileContract(file));
            assertNotPorted(() -> compiler.compileWithSiblings(file));
        }

        @Test
        void javacsRejectionStandsWhateverTheGateSays() {
            String dialect = "import java.math.BigInteger;\nclass P { static BigInteger m(BigInteger a) { return a + a; } }\n";
            var options = new CompilerOptions().setFrontend(CompilerFrontend.JAVAC)
                    .setJavacGate(JavacGateMode.OFF);
            var e = assertThrows(CompilerException.class, () -> new JulcCompiler(null, options).compileMethod(dialect, "m", List.of()));
            assertEquals(DiagnosticCodes.JAVAC_ERROR.code(), e.diagnostics().getFirst().code());
        }

        /** Source the JavaParser frontend cannot read reaches JULC0084: nothing of it went to that frontend (I8). */
        @Test
        void sourceTheJavaParserFrontendRejectsNeverReachesIt() {
            String source = "class V { static String m() { return \"\\uD800\"; } }";
            var reference = assertThrows(CompilerException.class, () -> new JulcCompiler(null,
                    new CompilerOptions().setJavacGate(JavacGateMode.OFF)).compileMethod(source, "m", List.of()));
            assertNotEquals(DiagnosticCodes.JAVAC_ERROR.code(), reference.diagnostics().isEmpty() ? null : reference.diagnostics().getFirst().code());
            var javac = assertThrows(CompilerException.class, () -> new JulcCompiler(null, JAVAC).compileMethod(source, "m", List.of()));
            assertEquals(DiagnosticCodes.FRONTEND_CONSTRUCT_NOT_PORTED.code(), javac.diagnostics().getFirst().code(), javac.getMessage());
        }

        @Test
        void aLibraryIsReadThroughTheSourcePath() {
            String library = """
                    package lib;
                    import java.math.BigInteger;
                    import org.julclang.stdlib.annotation.OnchainLibrary;
                    @OnchainLibrary
                    public class L {
                        public record Point(BigInteger x, BigInteger y) {}
                        public static BigInteger x(Point p) { return p.x(); }
                    }
                    """;
            String unused = """
                    package other;
                    import java.math.BigInteger;
                    public record Unused(BigInteger v) {}
                    """;
            var program = JavacProgram.analyze(List.of(JavacFrontend.Unit.of("""
                    import lib.L;
                    record Holder(L.Point p) {}
                    class P {}
                    """)), List.of(JavacFrontend.Unit.of(library), JavacFrontend.Unit.of(unused)));
            var registry = program.registry().snapshot();
            assertEquals(registry.records().get("lib.L.Point"), registry.records().get("Holder").fields().getFirst().type());
            assertFalse(registry.records().containsKey("other.Unused"), "javac reads the pool units a root references (D8)");
            assertEquals(1, program.libraries().size());
        }
    }

    /** D8, I5: the provider's types. */
    @Nested
    class Provider {
        private static final String LIBRARY = """
                package lib;
                import java.math.BigInteger;
                import org.julclang.core.types.JulcList;
                import org.julclang.stdlib.annotation.OnchainLibrary;
                import org.julclang.stdlib.annotation.NewType;
                @OnchainLibrary
                public class L {
                    @NewType public record Id(byte[] v) {}
                    public record Point(BigInteger x, JulcList<Id> ids) {}
                    public sealed interface Shape {
                        record Circle(Point centre) implements Shape {}
                        record Dot() implements Shape {}
                    }
                    public static BigInteger x(Point p) { return p.x(); }
                }
                """;

        @Test
        void aProvidersTypesEqualTheJavaParserPath() {
            var reference = new JavaLibraryProvider(List.of(LIBRARY), null, new CompilerOptions().setJavacGate(JavacGateMode.OFF));
            var javac = new JavaLibraryProvider(List.of(LIBRARY), null, new CompilerOptions().setFrontend(CompilerFrontend.JAVAC));
            assertEquals(new TreeMap<>(reference.types()), new TreeMap<>(javac.types()));
            assertEquals(new TreeMap<>(reference.namedDefinitions()), new TreeMap<>(javac.namedDefinitions()));
            assertTrue(javac.types().containsKey("lib.L.Shape"));
        }

        @Test
        void whatNeedsLoweringIsNotPorted() {
            var javac = new JavaLibraryProvider(List.of(LIBRARY), null, new CompilerOptions().setFrontend(CompilerFrontend.JAVAC));
            for (Executable call : List.<Executable>of(
                    () -> javac.describe("lib.L"), () -> javac.schemes("lib.L"), javac::unsupportedExports,
                    () -> javac.materialize("lib.L.x"), () -> javac.materialize(List.of()))) {
                var e = assertThrows(CompilerException.class, call);
                assertEquals(DiagnosticCodes.FRONTEND_CONSTRUCT_NOT_PORTED.code(), e.diagnostics().getFirst().code());
            }
        }

        @Test
        void anEmptyProviderHasNoExports() {
            var javac = new JavaLibraryProvider(List.of(), null, new CompilerOptions().setFrontend(CompilerFrontend.JAVAC));
            assertEquals(List.of(), javac.describe("lib.L"));
            assertEquals(Map.of(), javac.unsupportedExports());
        }

        @Test
        void aClassThatIsNotALibraryIsRejected() {
            var e = assertThrows(IllegalArgumentException.class, () -> new JavaLibraryProvider(List.of("class Ordinary {}"),
                    null, new CompilerOptions().setFrontend(CompilerFrontend.JAVAC)));
            assertTrue(e.getMessage().contains("Only @OnchainLibrary sources can be exported: Ordinary"));
        }

        @Test
        void aKindOfTypeJavaAcceptsHasARecordElement() {
            var element = read("record R() {} class P {}").facts().elements().getTypeElement("R");
            assertEquals(ElementKind.RECORD, element.getKind());
        }

        @Test
        void aLibraryTypeCarriesItsConstructors() {
            var javac = new JavaLibraryProvider(List.of(LIBRARY), null, new CompilerOptions().setFrontend(CompilerFrontend.JAVAC));
            LibraryType shape = javac.types().get("lib.L.Shape");
            assertEquals(List.of("Circle", "Dot"), shape.constructors().stream().map(LibraryType.Constructor::name).toList());
        }
    }
}
