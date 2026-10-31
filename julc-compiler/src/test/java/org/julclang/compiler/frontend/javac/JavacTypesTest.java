package org.julclang.compiler.frontend.javac;

import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerFrontend;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JavaLibraryProvider;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.LedgerTypeProvider;
import org.julclang.compiler.backend.LibraryType;
import org.julclang.compiler.error.DiagnosticCodes;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.resolve.LedgerSourceLoader;
import org.julclang.compiler.resolve.TypeRegistrar;
import org.julclang.compiler.resolve.TypeResolver;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

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

    private static TypeResolver.Registry registry(String declarations) {
        return read(HEADER + declarations).types().registry();
    }

    private static CompilerException rejected(String declarations) {
        return assertThrows(CompilerException.class, () -> read(HEADER + declarations));
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
            var javac = read("class P { static boolean m() { return true; } }").types().registry();
            var source = new TypeResolver();
            new TypeRegistrar().registerAll(LedgerSourceLoader.loadLedgerSources(JavacTypesTest.class.getClassLoader()), source);
            var reference = source.registry();
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
            var credential = read("class P {}").types().registry().sums().get("org.julclang.ledger.Credential");
            assertEquals(List.of("PubKeyCredential", "ScriptCredential"),
                    credential.constructors().stream().map(PirType.Constructor::name).toList());
            assertEquals(0, credential.constructors().get(0).tag());
            assertEquals(1, credential.constructors().get(1).tag());
        }

        @Test
        void aHashTypeIsItsBytesAndAValueKeepsItsDeclaredShape() {
            var types = read("class P {}").types();
            assertEquals(BYTES, types.lookupNominal("org.julclang.ledger.PubKeyHash").orElseThrow());
            var value = (PirType.RecordType) types.lookupNominal("org.julclang.ledger.Value").orElseThrow();
            assertEquals(List.of("inner"), value.fields().stream().map(PirType.Field::name).toList());
            var described = new LedgerTypeProvider(new CompilerOptions().setFrontend(CompilerFrontend.JAVAC))
                    .types().get("org.julclang.ledger.Value");
            assertEquals(java.util.Set.of(), described.operations(), "a map-encoded record has no constructor operations");
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
            var rational = program.types().registry().records().get("Rational");
            assertEquals(List.of("denominator", "numerator"), rational.fields().stream().map(PirType.Field::name).toList());
            assertEquals(rational, program.types().registry().records().get("Holder").fields().getFirst().type());
            var ledger = program.types().registry().records().get("org.julclang.ledger.Rational");
            assertEquals(List.of("numerator", "denominator"), ledger.fields().stream().map(PirType.Field::name).toList());
        }

        @Test
        void aSingleTypeImportBindsTheLedgerType() {
            var program = read("""
                    import org.julclang.ledger.Rational;
                    record Holder(Rational r) {}
                    class P {}
                    """);
            var ledger = program.types().registry().records().get("org.julclang.ledger.Rational");
            assertEquals(ledger, program.types().registry().records().get("Holder").fields().getFirst().type());
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

        @ParameterizedTest
        @ValueSource(strings = {"Optional", "List", "Map", "Tuple2", "Tuple3", "AssetEntry", "JulcList", "JulcValue"})
        void aUserTypeNamedLikeABuiltInIsTheUsersType(String name) {
            var registry = read("""
                    import java.math.BigInteger;
                    record %s(BigInteger v) {}
                    record Holder(%s h) {}
                    class P {}
                    """.formatted(name, name)).types().registry();
            var user = new PirType.RecordType(name, List.of(field("v", INTEGER)));
            assertEquals(user, registry.records().get("Holder").fields().getFirst().type());
        }

        @Test
        void twoLocalRecordsOfOneNameAreRejectedAsTheSourcePathRejectsThem() {
            var e = assertThrows(CompilerException.class, () -> read("""
                    class P {
                        static boolean a() { record R(boolean v) {} return new R(true).v(); }
                        static byte[] b() { record R(byte[] v) {} return new R(new byte[0]).v(); }
                    }
                    """));
            assertTrue(e.diagnostics().getFirst().message().contains("Duplicate record type 'P.R'"), e.getMessage());
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
                    """).types().registry();
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
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitVariable(VariableTree tree, Void unused) {
                    if (tree.getName().contentEquals("r")) locals.add(facts.pirType(facts.type(getCurrentPath()), null));
                    return super.visitVariable(tree, null);
                }

                @Override
                public Void visitMethodInvocation(MethodInvocationTree tree, Void unused) {
                    if (tree.getMethodSelect() instanceof MemberSelectTree select
                            && select.getIdentifier().contentEquals("numerator")) {
                        found.add((ExecutableElement) facts.element(new TreePath(getCurrentPath(), select)));
                    }
                    return super.visitMethodInvocation(tree, null);
                }
            }.scan(program.roots().getFirst(), null);
            assertEquals(new PirType.RecordType("Rational", List.of(field("denominator", INTEGER), field("numerator", INTEGER))),
                    locals.getFirst());
            assertEquals("Rational", JavacFacts.identity((TypeElement) found.getFirst().getEnclosingElement()));
        }
    }

    /** I11's source rules. */
    @Nested
    class Tags {
        private List<String> variants(TypeResolver.Registry registry, String sum) {
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

        @Test
        void aUsersAnnotationNamedNewTypeIsNotJulcs() {
            var registry = read("""
                    import java.math.BigInteger;
                    @interface NewType {}
                    @NewType record N(BigInteger a, BigInteger b) {}
                    class P {}
                    """).types().registry();
            assertFalse(registry.newTypes().containsKey("N"));
            assertTrue(registry.records().containsKey("N"));
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
            assertTrue(rejected("record R(" + component + " v) {} class P {}").getMessage().contains(message)
                    || rejected("record R(" + component + " v) {} class P {}").diagnostics().getFirst().message().contains(message));
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
            var cu = org.julclang.compiler.JavaSourceParser.parseCompilationUnit(source);
            var reference = new TypeResolver();
            new TypeRegistrar().registerAll(LedgerSourceLoader.loadLedgerSources(JavacTypesTest.class.getClassLoader()), reference);
            new TypeRegistrar().registerAll(List.of(cu), reference);
            var javac = read(source).types().registry();
            assertEquals(reference.registry().sums().get("Tree"), javac.sums().get("Tree"));
            assertEquals(reference.registry().records().get("A"), javac.records().get("A"));
            assertEquals(reference.registry().records().get("B"), javac.records().get("B"));
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

        private static void assertNotPorted(org.junit.jupiter.api.function.Executable compile) {
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
            var registry = program.types().registry();
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
            for (org.junit.jupiter.api.function.Executable call : List.<org.junit.jupiter.api.function.Executable>of(
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
            assertEquals(java.util.Map.of(), javac.unsupportedExports());
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
