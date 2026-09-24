package org.julclang.compiler.backend;

import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.CompilerTarget;
import org.julclang.compiler.CompilerTestVm;
import org.julclang.compiler.JavaLibraryProvider;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.julclang.core.PlutusData;
import org.julclang.core.Term;
import org.julclang.vm.EvalResult;
import org.julclang.vm.LedgerEvaluationTarget;
import org.julclang.vm.PlutusLanguage;
import org.julclang.vm.UplcVersion;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Revision-2 function programs: contract checks, verifier invariants and trusted imports. */
class FunctionProgramTest {
    static final PirType INT = new PirType.IntegerType();
    static final PirType BYTES = new PirType.ByteStringType();
    static final PirType BOOL = new PirType.BoolType();
    static final PirType DATA = new PirType.DataType();
    static final PirType STRING = new PirType.StringType();
    static final PirType UNIT = new PirType.UnitType();
    static final PirType INT_TO_INT = new PirType.FunType(INT, INT);

    static final PirType.SumType SHAPE = new PirType.SumType("test.Shape", List.of(
            new PirType.Constructor("Circle", 0, List.of(new PirType.Field("radius", INT))),
            new PirType.Constructor("Square", 1, List.of(
                    new PirType.Field("side", INT), new PirType.Field("label", BYTES)))));
    static final PirType SHAPE_REF = new PirType.NamedTypeRef("test.Shape", "Shape", PirType.NamedKind.SUM);
    static final PirType.RecordType POINT = new PirType.RecordType("test.Point", List.of(
            new PirType.Field("x", INT), new PirType.Field("y", INT)));
    static final Map<String, PirType> TYPES = Map.of("test.Shape", SHAPE, "test.Point", POINT);

    // ---- builders ----

    static PirTerm integer(long n) {
        return new PirTerm.Const(Constant.integer(n));
    }

    static PirTerm var(String name, PirType type) {
        return new PirTerm.Var(name, type);
    }

    static PirTerm call(DefaultFun fun, PirTerm... arguments) {
        PirTerm term = new PirTerm.Builtin(fun);
        for (var argument : arguments) term = new PirTerm.App(term, argument);
        return term;
    }

    static PirTerm apply(PirTerm function, PirTerm... arguments) {
        for (var argument : arguments) function = new PirTerm.App(function, argument);
        return function;
    }

    static FunctionProgram program(PirTerm term, PirType type) {
        return program(term, type, new LinkedHashMap<>(), List.of());
    }

    static FunctionProgram program(PirTerm term, PirType type, SequencedMap<String, Definition> definitions,
                                   List<LibraryImports> imports) {
        return new FunctionProgram(BackendContract.REVISION, Set.of(), CompilerTarget.PLUTUS_V3_PV11,
                TYPES, imports, definitions, "test.main", term, type);
    }

    static PirBackend.Result compile(FunctionProgram program) {
        return new CompilerBackend().compile(program, new CompilerOptions());
    }

    static void evaluatesTo(FunctionProgram program, Constant expected) {
        var evaluated = assertInstanceOf(EvalResult.Success.class,
                CompilerTestVm.pv11().evaluate(compile(program).program()));
        assertEquals(new Term.Const(expected), evaluated.resultTerm());
    }

    static BackendException rejects(String code, FunctionProgram program) {
        return rejects(code, program, new CompilerOptions());
    }

    static BackendException rejects(String code, FunctionProgram program, CompilerOptions options) {
        var error = assertThrows(BackendException.class,
                () -> new CompilerBackend().compile(program, options));
        assertEquals(code, error.code(), error.getMessage());
        assertTrue(error.getMessage().startsWith("[" + code + "]"), error.getMessage());
        return error;
    }

    static PirTerm lessOrEqual(PirTerm a, PirTerm b) {
        return call(DefaultFun.LessThanEqualsInteger, a, b);
    }

    // ---- valid programs ----

    @Nested
    class Accepted {
        @Test
        void recursiveDefinitionsLinkAndEvaluate() {
            var n = var("n", INT);
            var definitions = new LinkedHashMap<String, Definition>();
            definitions.put("total", new Definition(INT_TO_INT, new PirTerm.Lam("n", INT,
                    new PirTerm.IfThenElse(lessOrEqual(n, integer(0)), integer(0),
                            call(DefaultFun.AddInteger, n, apply(var("total", INT_TO_INT),
                                    call(DefaultFun.SubtractInteger, n, integer(1))))))));
            evaluatesTo(program(apply(var("total", INT_TO_INT), integer(4)), INT, definitions, List.of()),
                    Constant.integer(10));
        }

        @Test
        void mutuallyRecursiveDefinitionsAndLocalLetRec() {
            var n = var("n", INT);
            var isEven = new PirType.FunType(INT, BOOL);
            var definitions = new LinkedHashMap<String, Definition>();
            definitions.put("even", new Definition(isEven, new PirTerm.Lam("n", INT,
                    new PirTerm.IfThenElse(call(DefaultFun.EqualsInteger, n, integer(0)),
                            new PirTerm.Const(Constant.bool(true)),
                            apply(var("odd", isEven), call(DefaultFun.SubtractInteger, n, integer(1)))))));
            definitions.put("odd", new Definition(isEven, new PirTerm.Lam("n", INT,
                    new PirTerm.IfThenElse(call(DefaultFun.EqualsInteger, n, integer(0)),
                            new PirTerm.Const(Constant.bool(false)),
                            apply(var("even", isEven), call(DefaultFun.SubtractInteger, n, integer(1)))))));
            var countDown = new PirTerm.LetRec(List.of(new PirTerm.Binding("down", new PirTerm.Lam("k", INT,
                    new PirTerm.IfThenElse(lessOrEqual(var("k", INT), integer(0)), integer(0),
                            apply(var("down", INT_TO_INT), call(DefaultFun.SubtractInteger, var("k", INT), integer(1))))))),
                    apply(var("down", INT_TO_INT), integer(3)));
            var term = new PirTerm.IfThenElse(apply(var("even", isEven), integer(6)), countDown, integer(1));
            evaluatesTo(program(term, INT, definitions, List.of()), Constant.integer(0));
        }

        @Test
        void higherOrderFunctionsAndPartialApplication() {
            var f = var("f", INT_TO_INT);
            var definitions = new LinkedHashMap<String, Definition>();
            var twice = new PirType.FunType(INT_TO_INT, INT_TO_INT);
            definitions.put("twice", new Definition(twice, new PirTerm.Lam("f", INT_TO_INT,
                    new PirTerm.Lam("x", INT, apply(f, apply(f, var("x", INT)))))));
            var addType = new PirType.FunType(INT, INT_TO_INT);
            var add = new PirTerm.Lam("a", INT, new PirTerm.Lam("b", INT,
                    call(DefaultFun.AddInteger, var("a", INT), var("b", INT))));
            var term = new PirTerm.Let("add", add, new PirTerm.Let("inc", apply(var("add", addType), integer(3)),
                    apply(var("twice", twice), var("inc", INT_TO_INT), integer(1))));
            evaluatesTo(program(term, INT, definitions, List.of()), Constant.integer(7));
        }

        @Test
        void sumsAreConstructedAndMatchedByDeclaredLayout() {
            var square = new PirTerm.DataConstr(1, SHAPE, List.of(integer(3),
                    new PirTerm.Const(Constant.byteString(new byte[] {1, 2}))));
            var match = new PirTerm.DataMatch(square, List.of(
                    new PirTerm.MatchBranch("Circle", List.of("r"), List.of(INT), var("r", INT)),
                    new PirTerm.MatchBranch("Square", List.of("s", "l"), List.of(INT, BYTES),
                            call(DefaultFun.AddInteger, var("s", INT),
                                    call(DefaultFun.LengthOfByteString, var("l", BYTES))))));
            evaluatesTo(program(match, INT), Constant.integer(5));
        }

        @Test
        void rawBuiltinProjectionUsesTheDataView() {
            // The frontend style of projecting a record field without a DataMatch.
            var point = new PirTerm.DataConstr(0, POINT, List.of(integer(7), integer(9)));
            var raw = call(DefaultFun.HeadList, call(DefaultFun.TailList,
                    call(DefaultFun.SndPair, call(DefaultFun.UnConstrData, var("p", POINT)))));
            var term = new PirTerm.Let("p", point, new PirTerm.Let("field", raw,
                    call(DefaultFun.UnIData, var("field", DATA))));
            evaluatesTo(program(term, INT), Constant.integer(9));
        }

        @Test
        void encodedListsAndListMatch() {
            var items = call(DefaultFun.MkCons, call(DefaultFun.IData, integer(2)),
                    call(DefaultFun.MkCons, call(DefaultFun.IData, integer(5)),
                            call(DefaultFun.MkNilData, new PirTerm.Const(Constant.unit()))));
            var intList = new PirType.ListType(INT);
            var sumType = new PirType.FunType(intList, INT);
            var sum = new PirTerm.LetRec(List.of(new PirTerm.Binding("sum", new PirTerm.Lam("xs", intList,
                    new PirTerm.ListMatch(var("xs", intList), "h", "t", integer(0),
                            call(DefaultFun.AddInteger, call(DefaultFun.UnIData, var("h", DATA)),
                                    apply(var("sum", sumType), var("t", intList))))))),
                    new PirTerm.Let("items", items, apply(var("sum", sumType), var("items", intList))));
            evaluatesTo(program(sum, INT), Constant.integer(7));
        }

        @Test
        void optionalConstructorPairMatchAndTrace() {
            var some = new PirTerm.DataConstr(0, new PirType.OptionalType(POINT),
                    List.of(new PirTerm.DataConstr(0, POINT, List.of(integer(1), integer(2)))));
            var pair = new PirType.PairType(INT, new PirType.ListType(DATA));
            var tag = new PirTerm.PairMatch(call(DefaultFun.UnConstrData, some), (PirType.PairType) pair,
                    "tag", "fields", var("tag", INT));
            evaluatesTo(program(new PirTerm.Trace(new PirTerm.Const(Constant.string("t")), tag), INT),
                    Constant.integer(0));
        }
    }

    // ---- contract ----

    @Nested
    class Contract {
        @Test
        void capabilitiesDescribeTheResolvedTarget() {
            var backend = new CompilerBackend();
            var pv11 = backend.capabilities(new CompilerOptions());
            assertEquals(BackendContract.REVISION, pv11.revision());
            assertEquals(BackendContract.MINIMUM_REVISION, pv11.minimumRevision());
            for (var capability : List.of(BackendCapability.FUNCTION_PROGRAM,
                    BackendCapability.STRICT_BOUNDARY_V1, BackendCapability.LIBRARY_IMPORTS,
                    BackendCapability.LIBRARY_REQUEST_EXPORT, BackendCapability.PIR_VERIFIER,
                    BackendCapability.PIR_BUILTIN_CASE, new BackendCapability("target.constr-case")))
                assertTrue(pv11.provides(capability), capability.id());
            var baseline = backend.capabilities(
                    new CompilerOptions().setOptimizationLevel(OptimizationLevel.BASELINE));
            assertFalse(baseline.provides(BackendCapability.PIR_BUILTIN_CASE));
        }

        @Test
        void revisionsOutsideTheRangeAreRejected() {
            for (int revision : List.of(1, 3)) {
                var error = rejects("JULC0044", new FunctionProgram(revision, Set.of(),
                        CompilerTarget.PLUTUS_V3_PV11, Map.of(), List.of(), new LinkedHashMap<>(),
                        "test.main", integer(1), INT));
                assertTrue(error.getMessage().contains("2..2"), error.getMessage());
            }
        }

        @Test
        void unavailableCapabilitiesAreRejectedBeforeAnyWork() {
            var error = rejects("JULC0045", new FunctionProgram(2,
                    Set.of(new BackendCapability("validator.handlers.future")),
                    CompilerTarget.PLUTUS_V3_PV11, Map.of(), List.of(), new LinkedHashMap<>(),
                    "test.main", new PirTerm.Var("unverified", INT), INT));
            assertEquals("function program test.main", error.subject());
            assertTrue(error.getMessage().contains("validator.handlers.future"));
        }

        @Test
        void targetMismatchIsRejected() {
            var pv10 = new CompilerTarget(LedgerEvaluationTarget.pv10(PlutusLanguage.PLUTUS_V3),
                    UplcVersion.V1_1_0);
            rejects("JULC0046", new FunctionProgram(2, Set.of(), pv10, Map.of(), List.of(),
                    new LinkedHashMap<>(), "test.main", integer(1), INT));
        }

        @Test
        void invalidCapabilityIdentifiersAreRejected() {
            assertThrows(IllegalArgumentException.class, () -> new BackendCapability("Not Valid"));
        }

        @Test
        void providerGroupsFromNewerRevisionsAreRejected() {
            var group = new LibraryImports("future", 3, new LinkedHashMap<>(), Map.of(), Map.of());
            rejects("JULC0044", program(integer(1), INT, new LinkedHashMap<>(), List.of(group)));
        }

        @Test
        void importGroupsMustBeClosedAndBindingsMustExist() {
            var open = new LinkedHashMap<String, PirTerm>();
            open.put("lib.f", var("elsewhere", INT));
            assertEquals("JULC0047", assertThrows(BackendException.class,
                    () -> new LibraryImports("p", 2, open, Map.of(), Map.of())).code());
            assertEquals("JULC0047", assertThrows(BackendException.class,
                    () -> new LibraryImports("p", 2, new LinkedHashMap<>(),
                            Map.of(new LibraryRequest.Export("lib.f"),
                                    new LibraryImports.Binding("lib.f", INT)), Map.of())).code());
        }

        @Test
        void conflictingNamedTypesAreRejected() {
            var group = new LibraryImports("p", 2, new LinkedHashMap<>(), Map.of(),
                    Map.of("test.Point", new PirType.RecordType("test.Point",
                            List.of(new PirType.Field("x", BYTES)))));
            rejects("JULC0047", program(integer(1), INT, new LinkedHashMap<>(), List.of(group)));
        }
    }

    // ---- verifier invariants ----

    @Nested
    class Rejected {
        @Test
        void reservedAndBlankBinderNames() {
            for (var name : List.of(".field", "__match_data", "__rest_0", "$julc$handler$spend", "x#1", " "))
                rejects("JULC0048", program(new PirTerm.Let(name, integer(1), integer(2)), INT));
            var definitions = new LinkedHashMap<String, Definition>();
            definitions.put("__helper", new Definition(INT, integer(1)));
            rejects("JULC0048", program(integer(1), INT, definitions, List.of()));
        }

        @Test
        void unboundSymbolsAndPrivateImportsAreRejected() {
            var error = rejects("JULC0048", program(var("missing", INT), INT));
            assertTrue(error.getMessage().contains("missing"));
            assertEquals("test.main", error.subject());
        }

        @Test
        void definitionNamesMustNotShadowImports() {
            var group = new LibraryImports("p", 2, new LinkedHashMap<>(Map.of("lib.one", integer(1))),
                    Map.of(new LibraryRequest.Export("lib.one"), new LibraryImports.Binding("lib.one", INT)),
                    Map.of());
            var definitions = new LinkedHashMap<String, Definition>();
            definitions.put("lib.one", new Definition(INT, integer(2)));
            rejects("JULC0048", program(integer(1), INT, definitions, List.of(group)));
        }

        @Test
        void variableAnnotationsMustAgreeWithBinders() {
            rejects("JULC0049", program(new PirTerm.Let("x", integer(1), var("x", BYTES)), BYTES));
            // A native integer is not Data, so this view is rejected.
            rejects("JULC0049", program(new PirTerm.Let("x", integer(1), var("x", DATA)), DATA));
            rejects("JULC0049", program(new PirTerm.Let("p",
                    new PirTerm.DataConstr(0, POINT, List.of(integer(1), integer(2))),
                    var("p", SHAPE_REF)), SHAPE_REF));
        }

        @Test
        void applicationsMustMatchFunctionTypes() {
            rejects("JULC0049", program(apply(integer(1), integer(2)), INT));
            rejects("JULC0049", program(apply(new PirTerm.Lam("s", BYTES, integer(0)), integer(1)), INT));
            rejects("JULC0049", program(call(DefaultFun.AddInteger,
                    new PirTerm.Const(Constant.data(PlutusData.integer(1))), integer(2)), INT));
        }

        @Test
        void encodingsMustBeExplicitForDataLists() {
            var nil = call(DefaultFun.MkNilData, new PirTerm.Const(Constant.unit()));
            rejects("JULC0049", program(call(DefaultFun.MkCons, integer(1), nil), new PirType.ListType(DATA)));
            rejects("JULC0049", program(call(DefaultFun.ListData, call(DefaultFun.MkCons,
                    new PirTerm.Const(Constant.bool(true)), nil)), DATA));
        }

        @Test
        void polymorphicBuiltinsMustBeSaturatedAndUnreleasedBuiltinsAreRejected() {
            rejects("JULC0050", program(new PirTerm.Builtin(DefaultFun.HeadList),
                    new PirType.FunType(new PirType.ListType(DATA), DATA)));
            rejects("JULC0050", program(call(DefaultFun.MultiIndexArray,
                    new PirTerm.Const(new Constant.ListConst(org.julclang.core.DefaultUni.INTEGER, List.of())),
                    new PirTerm.Const(new Constant.ArrayConst(org.julclang.core.DefaultUni.DATA, List.of()))),
                    new PirType.ListType(DATA)));
        }

        @Test
        void conditionsMustBeBoolAndBranchesMustAgree() {
            rejects("JULC0049", program(new PirTerm.IfThenElse(integer(1), integer(2), integer(3)), INT));
            rejects("JULC0049", program(new PirTerm.IfThenElse(
                    new PirTerm.Const(Constant.data(PlutusData.constr(1))), integer(2), integer(3)), INT));
            rejects("JULC0049", program(new PirTerm.IfThenElse(new PirTerm.Const(Constant.bool(true)),
                    integer(2), new PirTerm.Const(Constant.byteString(new byte[0]))), INT));
            rejects("JULC0049", program(call(DefaultFun.IfThenElse, integer(0), integer(2), integer(3)), INT));
            rejects("JULC0049", program(new PirTerm.Trace(integer(1), integer(2)), INT));
        }

        @Test
        void recursiveBindingsMustBeFunctions() {
            rejects("JULC0050", program(new PirTerm.LetRec(
                    List.of(new PirTerm.Binding("x", integer(1))), integer(2)), INT));
            var definitions = new LinkedHashMap<String, Definition>();
            definitions.put("loop", new Definition(INT, call(DefaultFun.AddInteger, var("loop", INT), integer(1))));
            rejects("JULC0050", program(integer(1), INT, definitions, List.of()));
        }

        @Test
        void generatorLocalIntegerCaseIsRejected() {
            rejects("JULC0050", program(new PirTerm.IntegerCase(integer(0),
                    List.of(integer(1), integer(2))), INT));
        }

        @Test
        void constructorsMustMatchTheirLayout() {
            rejects("JULC0050", program(new PirTerm.DataConstr(1, POINT, List.of(integer(1), integer(2))), POINT));
            rejects("JULC0050", program(new PirTerm.DataConstr(2, SHAPE, List.of(integer(1))), SHAPE));
            // Lowering encodes fields from the constructor's own type and does not resolve names.
            var named = rejects("JULC0050", program(
                    new PirTerm.DataConstr(0, SHAPE_REF, List.of(integer(1))), SHAPE_REF));
            assertTrue(named.getMessage().contains("resolved definition"), named.getMessage());
            rejects("JULC0050", program(new PirTerm.DataConstr(0, POINT, List.of(integer(1))), POINT));
            rejects("JULC0049", program(new PirTerm.DataConstr(0, POINT,
                    List.of(integer(1), new PirTerm.Const(Constant.byteString(new byte[0])))), POINT));
            rejects("JULC0050", program(new PirTerm.DataConstr(0, DATA, List.of()), DATA));
            var withUnit = new PirType.RecordType("test.WithUnit", List.of(new PirType.Field("u", UNIT)));
            rejects("JULC0050", program(new PirTerm.DataConstr(0, withUnit,
                    List.of(new PirTerm.Const(Constant.unit()))), withUnit));
            rejects("JULC0049", program(new PirTerm.DataConstr(0, new PirType.OptionalType(INT),
                    List.of(integer(1))), new PirType.OptionalType(INT)));
        }

        @Test
        void matchesMustHaveOneBranchPerConstructorInTagOrder() {
            var circle = new PirTerm.DataConstr(0, SHAPE, List.of(integer(3)));
            var circleBranch = new PirTerm.MatchBranch("Circle", List.of("r"), List.of(INT), var("r", INT));
            var squareBranch = new PirTerm.MatchBranch("Square", List.of(), List.of(), integer(0));
            rejects("JULC0050", program(new PirTerm.DataMatch(circle, List.of(circleBranch)), INT));
            rejects("JULC0050", program(new PirTerm.DataMatch(circle,
                    List.of(squareBranch, circleBranch)), INT));
            rejects("JULC0050", program(new PirTerm.DataMatch(circle, List.of(
                    new PirTerm.MatchBranch("Circle", List.of("r", "extra"), List.of(INT, INT), integer(0)),
                    squareBranch)), INT));
            rejects("JULC0049", program(new PirTerm.DataMatch(circle, List.of(
                    new PirTerm.MatchBranch("Circle", List.of("r"), List.of(BOOL), integer(0)),
                    squareBranch)), INT));
            rejects("JULC0050", program(new PirTerm.DataMatch(
                    new PirTerm.Const(Constant.data(PlutusData.constr(0))), List.of(circleBranch, squareBranch)), INT));
        }

        @Test
        void sumTagsMustBeDense() {
            var sparse = new PirType.SumType("test.Sparse", List.of(
                    new PirType.Constructor("A", 0, List.of()), new PirType.Constructor("B", 2, List.of())));
            var error = rejects("JULC0050", new FunctionProgram(2, Set.of(), CompilerTarget.PLUTUS_V3_PV11,
                    Map.of("test.Sparse", sparse), List.of(), new LinkedHashMap<>(), "test.main",
                    integer(1), INT));
            assertTrue(error.getMessage().contains("0..n-1"), error.getMessage());
        }

        @Test
        void namedTypesMustResolveWithTheirKind() {
            rejects("JULC0050", program(new PirTerm.Error(
                    new PirType.NamedTypeRef("test.Missing", "Missing", PirType.NamedKind.SUM)), INT));
            rejects("JULC0050", program(new PirTerm.Error(
                    new PirType.NamedTypeRef("test.Point", "Point", PirType.NamedKind.SUM)), INT));
        }

        @Test
        void listAndPairMatchesRequireTheirRepresentations() {
            var map = call(DefaultFun.MkNilPairData, new PirTerm.Const(Constant.unit()));
            rejects("JULC0049", program(new PirTerm.ListMatch(map, "h", "t", integer(0), integer(1)), INT));
            var data = new PirTerm.Const(Constant.data(PlutusData.integer(1)));
            rejects("JULC0049", program(new PirTerm.ListMatch(data, "h", "t", integer(0), integer(1)), INT));
            var nil = call(DefaultFun.MkNilData, new PirTerm.Const(Constant.unit()));
            rejects("JULC0050", program(new PirTerm.ListMatch(nil, "h", "t", integer(0), integer(1)), INT),
                    new CompilerOptions().setOptimizationLevel(OptimizationLevel.BASELINE));
            rejects("JULC0049", program(new PirTerm.PairMatch(call(DefaultFun.MkPairData, data, data),
                    new PirType.PairType(INT, new PirType.ListType(DATA)), "a", "b", integer(0)), INT));
        }

        @Test
        void declaredEntryTypeMustAgree() {
            var error = rejects("JULC0049", program(integer(1), BYTES));
            assertTrue(error.getMessage().contains("declared type Bytes"), error.getMessage());
        }
    }

    // ---- trusted imports ----

    @Nested
    class Imports {
        static final String LIBRARY = """
                package demo;
                import java.math.BigInteger;
                @OnchainLibrary
                public class Pricing {
                    public record Quote(BigInteger amount, boolean approved) {}
                    private static BigInteger scale(BigInteger x) { return x.multiply(BigInteger.TWO); }
                    public static BigInteger doubled(BigInteger x) { return scale(x); }
                    public static BigInteger quadrupled(BigInteger x) { return scale(scale(x)); }
                    public static Quote quote(BigInteger amount) { return new Quote(amount, true); }
                }
                """;

        static JavaLibraryProvider provider() {
            return new JavaLibraryProvider(List.of(LIBRARY), null, new CompilerOptions());
        }

        @Test
        void oneGroupLinksSharedPrivateDependenciesOnce() {
            var provider = provider();
            var doubled = new LibraryRequest.Export("demo.Pricing.doubled");
            var quadrupled = new LibraryRequest.Export("demo.Pricing.quadrupled");
            var group = provider.materialize(List.of(doubled, quadrupled));
            assertEquals(2, provider.revision());
            assertEquals(List.of("demo.Pricing.doubled", "demo.Pricing.scale", "demo.Pricing.quadrupled"),
                    List.copyOf(group.definitions().keySet()));
            var term = call(DefaultFun.AddInteger,
                    apply(var(group.binding(doubled).name(), INT_TO_INT), integer(1)),
                    apply(var(group.binding(quadrupled).name(), INT_TO_INT), integer(10)));
            evaluatesTo(program(term, INT, new LinkedHashMap<>(), List.of(group)), Constant.integer(42));
        }

        @Test
        void privateDependenciesAreNotReferenceable() {
            var group = provider().materialize(List.of(new LibraryRequest.Export("demo.Pricing.doubled")));
            rejects("JULC0048", program(apply(var("demo.Pricing.scale", INT_TO_INT), integer(1)), INT,
                    new LinkedHashMap<>(), List.of(group)));
        }

        @Test
        void importedRecordsAreProjectedThroughTheDataViewButNotMatched() {
            var request = new LibraryRequest.Export("demo.Pricing.quote");
            var group = provider().materialize(List.of(request));
            var binding = group.binding(request);
            var quoteType = ((PirType.FunType) binding.type()).returnType();
            var raw = call(DefaultFun.HeadList, call(DefaultFun.SndPair,
                    call(DefaultFun.UnConstrData, apply(var(binding.name(), binding.type()), integer(21)))));
            evaluatesTo(program(call(DefaultFun.MultiplyInteger, call(DefaultFun.UnIData, raw), integer(2)),
                    INT, new LinkedHashMap<>(), List.of(group)), Constant.integer(42));
            var match = new PirTerm.DataMatch(apply(var(binding.name(), binding.type()), integer(1)),
                    List.of(new PirTerm.MatchBranch("Quote", List.of("amount"), List.of(INT), var("amount", INT))));
            var error = rejects("JULC0050", program(match, INT, new LinkedHashMap<>(), List.of(group)));
            assertTrue(error.getMessage().contains("no approved match operation"), error.getMessage()
                    + " for " + quoteType);
        }

        @Test
        void unknownExportsAreUnsupportedRequests() {
            var error = assertThrows(BackendException.class, () -> provider().materialize(
                    List.of(new LibraryRequest.Export("demo.Pricing.scale"))));
            assertEquals("JULC0051", error.code());
        }

        @Test
        void revisionOneProvidersUseTheDefaultAdapter() {
            var legacy = new LibraryProvider() {
                @Override
                public List<LibraryExport> describe(String module) {
                    return module.equals("legacy")
                            ? List.of(new LibraryExport("legacy.answer", 0, INT))
                            : List.of();
                }

                @Override
                public PirTerm materialize(String symbol) {
                    return integer(42);
                }
            };
            var request = new LibraryRequest.Export("legacy.answer");
            var group = legacy.materialize(List.of(request));
            assertEquals(1, group.revision());
            evaluatesTo(program(var(group.binding(request).name(), INT), INT, new LinkedHashMap<>(),
                    List.of(group)), Constant.integer(42));
            assertEquals("JULC0051", assertThrows(BackendException.class,
                    () -> legacy.materialize(List.of(new LibraryRequest.Export("legacy.missing")))).code());
        }
    }
}
