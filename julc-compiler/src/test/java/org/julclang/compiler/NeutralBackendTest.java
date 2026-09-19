package org.julclang.compiler;

import static org.junit.jupiter.api.Assertions.*;

import org.julclang.compiler.backend.*;
import org.julclang.compiler.pir.*;
import org.julclang.core.*;
import org.julclang.ledger.*;
import org.julclang.vm.*;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.*;

class NeutralBackendTest {
    private static final PirType INT = new PirType.IntegerType();
    private static final PirType BOOL = new PirType.BoolType();

    private static PirTerm integer(long n) {
        return new PirTerm.Const(Constant.integer(n));
    }

    private static FrontendProgram input(
            PirTerm term, PirType type, FrontendProgram.Purpose purpose) {
        return new FrontendProgram(
                term,
                type,
                CompilerTarget.PLUTUS_V3_PV11,
                new FrontendProgram.Entrypoint("example", purpose, "julc-strict-v1"),
                Map.of());
    }

    private static void evaluatesTo(PirTerm term, long expected) {
        var result =
                new CompilerBackend()
                        .compile(
                                input(term, INT, FrontendProgram.Purpose.FUNCTION),
                                new CompilerOptions());
        var evaluated =
                assertInstanceOf(
                        EvalResult.Success.class, CompilerTestVm.pv11().evaluate(result.program()));
        assertEquals(new Term.Const(Constant.integer(expected)), evaluated.resultTerm());
    }

    @Test
    void compilesClosedFunctionApplication() {
        evaluatesTo(
                new PirTerm.App(new PirTerm.Lam("x", INT, new PirTerm.Var("x", INT)), integer(42)),
                42);
    }

    @Test
    void rejectsFreeVariableBeforeLowering() {
        var error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> evaluatesTo(new PirTerm.Var("missing", INT), 0));
        assertTrue(error.getMessage().contains("missing"));
    }

    @Test
    void rejectsUnknownBoundaryPolicy() {
        var p =
                new FrontendProgram(
                        integer(1),
                        INT,
                        CompilerTarget.PLUTUS_V3_PV11,
                        new FrontendProgram.Entrypoint(
                                "example", FrontendProgram.Purpose.FUNCTION, "unknown"),
                        Map.of());
        assertThrows(
                IllegalArgumentException.class,
                () -> new CompilerBackend().compile(p, new CompilerOptions()));
    }

    @Test
    void rejectsMismatchedTargetProvenance() {
        var p =
                new FrontendProgram(
                        integer(1),
                        INT,
                        new CompilerTarget(
                                LedgerEvaluationTarget.pv10(PlutusLanguage.PLUTUS_V3),
                                UplcVersion.V1_1_0),
                        new FrontendProgram.Entrypoint(
                                "example", FrontendProgram.Purpose.FUNCTION, "julc-strict-v1"),
                        Map.of());
        assertThrows(
                IllegalArgumentException.class,
                () -> new CompilerBackend().compile(p, new CompilerOptions()));
    }

    @Test
    void rejectsMalformedValidatorSignatures() {
        for (var purpose : List.of(FrontendProgram.Purpose.SPEND, FrontendProgram.Purpose.MINT))
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new CompilerBackend()
                                    .compile(
                                            input(integer(1), INT, purpose),
                                            new CompilerOptions()));
    }

    @Test
    void linksForwardDependencies() {
        var definitions = new LinkedHashMap<String, PirTerm>();
        definitions.put("answer", new PirTerm.Var("dependency", INT));
        definitions.put("dependency", integer(42));
        evaluatesTo(PirLinker.link(definitions, new PirTerm.Var("answer", INT)), 42);
    }

    @Test
    void preservesStrictUnusedDefinitions() {
        var linked = PirLinker.link(Map.of("bad", new PirTerm.Error(INT)), integer(42));
        var result =
                new CompilerBackend()
                        .compile(
                                input(linked, INT, FrontendProgram.Purpose.FUNCTION),
                                new CompilerOptions());
        assertFalse(CompilerTestVm.pv11().evaluate(result.program()) instanceof EvalResult.Success);
    }

    @Test
    void rejectsEagerRecursiveValues() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PirLinker.link(Map.of("x", new PirTerm.Var("x", INT)), integer(0)));
    }

    @Test
    void ordinaryLetDoesNotBindItsOwnValue() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        PirClosure.check(
                                new PirTerm.Let("x", new PirTerm.Var("x", INT), integer(0))));
    }

    @Test
    void libraryMaterializationClosesPrivateDependencies() {
        var provider =
                new JavaLibraryProvider(
                        List.of(
                                """
                                package demo;
                                @OnchainLibrary
                                public class Arithmetic {
                                    private static long helper(long x) { return x + 1; }
                                    public static long answer(long x) { return helper(x); }
                                }
                                """),
                        null,
                        new CompilerOptions());
        assertEquals(
                List.of("demo.Arithmetic.answer"),
                provider.describe("demo.Arithmetic").stream().map(LibraryExport::symbol).toList());
        evaluatesTo(
                new PirTerm.App(provider.materialize("demo.Arithmetic.answer"), integer(41)), 42);
        assertThrows(
                IllegalArgumentException.class,
                () -> provider.materialize("demo.Arithmetic.helper"));
    }

    @Test
    void rejectsUnapprovedLibrarySource() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new JavaLibraryProvider(
                                List.of(
                                        "class Ordinary { public static long answer() { return 42;"
                                                + " } }"),
                                null,
                                new CompilerOptions()));
    }

    @Test
    void rejectsOverloadedExports() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new JavaLibraryProvider(
                                List.of(
                                        """
                                        @OnchainLibrary class Overloaded {
                                            public static long f(long x) { return x; }
                                            public static long f(long x, long y) { return x; }
                                        }
                                        """),
                                null,
                                new CompilerOptions()));
    }

    @Test
    void spendingBoundaryMatchesJavaAndRejectsMalformedData() {
        var bool = new PirType.BoolType();
        var data = new PirType.DataType();
        var handler =
                new PirTerm.Lam(
                        "datum",
                        INT,
                        new PirTerm.Lam(
                                "redeemer",
                                INT,
                                new PirTerm.Lam(
                                        "context",
                                        data,
                                        new PirTerm.App(
                                                new PirTerm.App(
                                                        new PirTerm.Builtin(
                                                                DefaultFun.LessThanEqualsInteger),
                                                        new PirTerm.Var("datum", INT)),
                                                new PirTerm.Var("redeemer", INT)))));
        var type =
                new PirType.FunType(INT, new PirType.FunType(INT, new PirType.FunType(data, bool)));
        var compiled =
                new CompilerBackend()
                        .compile(
                                input(handler, type, FrontendProgram.Purpose.SPEND),
                                new CompilerOptions());
        var javaCompiled =
                new JulcCompiler()
                        .compile(
                                """
                                import java.math.BigInteger;
                                @SpendingValidator class Threshold {
                                    @Entrypoint static boolean validate(BigInteger datum, BigInteger redeemer, ScriptContext context) {
                                        return datum.compareTo(redeemer) <= 0;
                                    }
                                }
                                """);
        var vm = CompilerTestVm.pv11();
        for (long[] pair : new long[][] {{5, 6}, {5, 4}, {-5, 0}, {0, 0}, {-1, -1}}) {
            var context = spendingContext(PlutusData.integer(pair[0]), PlutusData.integer(pair[1]));
            boolean success =
                    vm.evaluateWithArgs(compiled.program(), List.of(context))
                            instanceof EvalResult.Success;
            assertEquals(pair[0] <= pair[1], success);
            assertEquals(
                    vm.evaluateWithArgs(javaCompiled.program(), List.of(context))
                            instanceof EvalResult.Success,
                    success);
        }
        for (var bad :
                List.of(PlutusData.bytes(new byte[0]), PlutusData.list(), PlutusData.constr(0))) {
            assertFalse(
                    vm.evaluateWithArgs(
                                    compiled.program(),
                                    List.of(spendingContext(bad, PlutusData.integer(10))))
                            instanceof EvalResult.Success);
            assertFalse(
                    vm.evaluateWithArgs(
                                    compiled.program(),
                                    List.of(spendingContext(PlutusData.integer(0), bad)))
                            instanceof EvalResult.Success);
        }
    }

    private static PlutusData spendingContext(PlutusData datum, PlutusData redeemer) {
        return ScriptContextBuilder.spending(
                        new TxOutRef(new TxId(new byte[32]), BigInteger.ZERO), datum)
                .redeemer(redeemer)
                .build()
                .toPlutusData();
    }

    @Test
    void recursiveGroupDoesNotAbsorbUnrelatedDependencies() {
        var function = new PirType.FunType(INT, INT);
        var definitions = new LinkedHashMap<String, PirTerm>();
        definitions.put("answer", integer(42));
        definitions.put(
                "f",
                new PirTerm.Lam(
                        "x",
                        INT,
                        new PirTerm.App(
                                new PirTerm.Var("g", function), new PirTerm.Var("x", INT))));
        definitions.put(
                "g",
                new PirTerm.Lam(
                        "x",
                        INT,
                        new PirTerm.IfThenElse(
                                new PirTerm.Const(Constant.bool(true)),
                                new PirTerm.Var("answer", INT),
                                new PirTerm.App(
                                        new PirTerm.Var("f", function),
                                        new PirTerm.Var("x", INT)))));
        evaluatesTo(
                PirLinker.link(
                        definitions, new PirTerm.App(new PirTerm.Var("f", function), integer(0))),
                42);
    }

    @Test
    void linksAndEvaluatesThreeFunctionRecursiveGroup() {
        var function = new PirType.FunType(INT, INT);
        var definitions = new LinkedHashMap<String, PirTerm>();
        definitions.put("f", recursiveStep("g", function, 10));
        definitions.put("g", recursiveStep("h", function, 20));
        definitions.put("h", recursiveStep("f", function, 30));
        evaluatesTo(
                PirLinker.link(
                        definitions, new PirTerm.App(new PirTerm.Var("f", function), integer(3))),
                10);
    }

    @Test
    void linksFourRecursiveFunctionsWithDifferentSignatures() {
        var unaryInt = new PirType.FunType(INT, INT);
        var binaryInt = new PirType.FunType(INT, unaryInt);
        var predicate = new PirType.FunType(INT, BOOL);
        var definitions = new LinkedHashMap<String, PirTerm>();

        var fArgument = new PirTerm.Var("n", INT);
        definitions.put(
                "f",
                new PirTerm.Lam(
                        "n",
                        INT,
                        new PirTerm.IfThenElse(
                                equalsInteger(fArgument, integer(0)),
                                integer(10),
                                apply(
                                        new PirTerm.Var("g", binaryInt),
                                        subtractOne(fArgument),
                                        integer(1)))));

        var gArgument = new PirTerm.Var("n", INT);
        var extra = new PirTerm.Var("extra", INT);
        definitions.put(
                "g",
                new PirTerm.Lam(
                        "n",
                        INT,
                        new PirTerm.Lam(
                                "extra",
                                INT,
                                new PirTerm.IfThenElse(
                                        equalsInteger(gArgument, integer(0)),
                                        add(integer(20), extra),
                                        add(
                                                apply(
                                                        new PirTerm.Var("h", unaryInt),
                                                        subtractOne(gArgument)),
                                                extra)))));

        var hArgument = new PirTerm.Var("n", INT);
        definitions.put(
                "h",
                new PirTerm.Lam(
                        "n",
                        INT,
                        new PirTerm.IfThenElse(
                                equalsInteger(hArgument, integer(0)),
                                integer(30),
                                new PirTerm.IfThenElse(
                                        apply(
                                                new PirTerm.Var("p", predicate),
                                                subtractOne(hArgument)),
                                        integer(40),
                                        apply(
                                                new PirTerm.Var("f", unaryInt),
                                                subtractOne(hArgument))))));

        var pArgument = new PirTerm.Var("n", INT);
        definitions.put(
                "p",
                new PirTerm.Lam(
                        "n",
                        INT,
                        new PirTerm.IfThenElse(
                                equalsInteger(pArgument, integer(0)),
                                new PirTerm.Const(Constant.bool(true)),
                                equalsInteger(
                                        apply(
                                                new PirTerm.Var("h", unaryInt),
                                                subtractOne(pArgument)),
                                        integer(30)))));

        evaluatesTo(
                PirLinker.link(definitions, apply(new PirTerm.Var("f", unaryInt), integer(4))), 41);
    }

    private static PirTerm apply(PirTerm function, PirTerm... arguments) {
        for (var argument : arguments) function = new PirTerm.App(function, argument);
        return function;
    }

    private static PirTerm add(PirTerm left, PirTerm right) {
        return apply(new PirTerm.Builtin(DefaultFun.AddInteger), left, right);
    }

    private static PirTerm subtractOne(PirTerm value) {
        return apply(new PirTerm.Builtin(DefaultFun.SubtractInteger), value, integer(1));
    }

    private static PirTerm equalsInteger(PirTerm left, PirTerm right) {
        return apply(new PirTerm.Builtin(DefaultFun.EqualsInteger), left, right);
    }

    private static PirTerm recursiveStep(String next, PirType function, long base) {
        var x = new PirTerm.Var("x", INT);
        var zero =
                new PirTerm.App(
                        new PirTerm.App(new PirTerm.Builtin(DefaultFun.EqualsInteger), x),
                        integer(0));
        var decrement =
                new PirTerm.App(
                        new PirTerm.App(new PirTerm.Builtin(DefaultFun.SubtractInteger), x),
                        integer(1));
        return new PirTerm.Lam(
                "x",
                INT,
                new PirTerm.IfThenElse(
                        zero,
                        integer(base),
                        new PirTerm.App(new PirTerm.Var(next, function), decrement)));
    }
}
