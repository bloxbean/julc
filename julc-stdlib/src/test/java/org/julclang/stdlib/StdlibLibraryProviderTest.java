package org.julclang.stdlib;

import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.CompilerTarget;
import org.julclang.compiler.JavaLibraryProvider;
import org.julclang.compiler.LibrarySources;
import org.julclang.compiler.backend.*;
import org.julclang.compiler.backend.LibraryType.Reference;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.julclang.core.PlutusData;
import org.julclang.core.Term;
import org.julclang.vm.EvalResult;
import org.julclang.vm.JulcVm;
import org.julclang.vm.LedgerEvaluationTarget;
import org.julclang.vm.PlutusLanguage;
import org.julclang.vm.UplcVersion;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Programmatic PIR exports through the neutral provider contract (#182). */
class StdlibLibraryProviderTest {
    static final PirType INT = new PirType.IntegerType();
    static final PirType BOOL = new PirType.BoolType();
    static final PirType DATA = new PirType.DataType();
    static final PirType INT_LIST = new PirType.ListType(INT);
    static final Reference INT_REF = new Reference("Int", List.of());
    static final Reference BOOL_REF = new Reference("Bool", List.of());
    static final StdlibLibraryProvider PROVIDER = new StdlibLibraryProvider(new CompilerOptions());

    static PirTerm integer(long n) {
        return new PirTerm.Const(Constant.integer(n));
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

    static PirTerm ints(long... values) {
        PirTerm list = call(DefaultFun.MkNilData, new PirTerm.Const(Constant.unit()));
        for (int i = values.length - 1; i >= 0; i--)
            list = call(DefaultFun.MkCons, call(DefaultFun.IData, integer(values[i])), list);
        return new PirTerm.Let("items", list, new PirTerm.Var("items", INT_LIST));
    }

    static LibraryRequest.Instantiate hof(String name, Reference... arguments) {
        return new LibraryRequest.Instantiate(StdlibLibraryProvider.LISTS + "." + name, List.of(arguments));
    }

    static PirTerm ref(LibraryImports group, LibraryRequest request) {
        var binding = group.binding(request);
        return new PirTerm.Var(binding.name(), binding.type());
    }

    static EvalResult run(PirTerm term, PirType type, LibraryImports... groups) {
        var program = new FunctionProgram(2, Set.of(), CompilerTarget.PLUTUS_V3_PV11, Map.of(), List.of(groups),
                new LinkedHashMap<>(), "test.main", term, type);
        var compiled = new CompilerBackend().compile(program, new CompilerOptions());
        return JulcVm.create().evaluate(compiled.program(), CompilerTarget.PLUTUS_V3_PV11.ledgerTarget());
    }

    static EvalResult.Success succeeds(PirTerm term, PirType type, LibraryImports... groups) {
        return assertInstanceOf(EvalResult.Success.class, run(term, type, groups));
    }

    static Term value(PirTerm term, PirType type, LibraryImports... groups) {
        return succeeds(term, type, groups).resultTerm();
    }

    static Term data(PlutusData value) {
        return new Term.Const(Constant.data(value));
    }

    static PlutusData intData(long... values) {
        return PlutusData.list(Arrays.stream(values).mapToObj(PlutusData::integer).toArray(PlutusData[]::new));
    }

    /** {@code \name:Int -> body}. */
    static PirTerm lam(String name, PirType type, PirTerm body) {
        return new PirTerm.Lam(name, type, body);
    }

    @Test
    void schemesDescribeTheProgrammaticCatalogue() {
        var schemes = PROVIDER.schemes(StdlibLibraryProvider.LISTS);
        assertEquals(List.of("map", "filter", "any", "all", "find", "foldl"),
                schemes.stream().map(s -> s.symbol().substring(s.symbol().lastIndexOf('.') + 1)).toList());
        var map = schemes.getFirst();
        assertEquals(StdlibLibraryProvider.LISTS + ".map<a,b>(List['a],Function['a,'b])List['b]", map.identity());
        assertEquals(2, map.arity());
        assertEquals(CompilerTarget.PLUTUS_V3_PV11, map.target());
        var foldl = schemes.getLast();
        assertEquals(List.of(new Reference("Function", List.of(Reference.variable("b"),
                        new Reference("Function", List.of(Reference.variable("a"), Reference.variable("b"))))),
                Reference.variable("b"), new Reference("List", List.of(Reference.variable("a")))), foldl.parameters());
        assertTrue(PROVIDER.describe(StdlibLibraryProvider.LISTS).isEmpty());
        assertTrue(PROVIDER.schemes("org.julclang.stdlib.lib").isEmpty());
    }

    @Test
    void higherOrderExportsEvaluateWithTypedFunctions() {
        var map = hof("map", INT_REF, INT_REF);
        var toBool = hof("map", INT_REF, BOOL_REF);
        var filter = hof("filter", INT_REF);
        var any = hof("any", INT_REF);
        var all = hof("all", INT_REF);
        var find = hof("find", INT_REF);
        var foldl = hof("foldl", INT_REF, INT_REF);
        var group = PROVIDER.materialize(List.of(map, toBool, filter, any, all, find, foldl));
        var x = new PirTerm.Var("n", INT);
        var inc = lam("n", INT, call(DefaultFun.AddInteger, x, integer(1)));
        var even = lam("n", INT, call(DefaultFun.EqualsInteger, call(DefaultFun.ModInteger, x, integer(2)), integer(0)));
        var positive = lam("n", INT, call(DefaultFun.LessThanInteger, integer(0), x));
        assertEquals(data(intData(2, 3, 4)), value(call(DefaultFun.ListData, apply(ref(group, map), ints(1, 2, 3), inc)),
                DATA, group));
        assertEquals(data(PlutusData.list(PlutusData.constr(0), PlutusData.constr(1))),
                value(call(DefaultFun.ListData, apply(ref(group, toBool), ints(1, 2), even)), DATA, group));
        assertEquals(data(intData(2, 4)), value(call(DefaultFun.ListData, apply(ref(group, filter), ints(1, 2, 3, 4), even)),
                DATA, group));
        assertEquals(new Term.Const(Constant.bool(true)), value(apply(ref(group, any), ints(1, 3, 4), even), BOOL, group));
        assertEquals(new Term.Const(Constant.bool(false)), value(apply(ref(group, all), ints(1, -3), positive), BOOL, group));
        assertEquals(data(PlutusData.constr(0, PlutusData.integer(4))),
                value(apply(ref(group, find), ints(1, 3, 4, 6), even), DATA, group));
        assertEquals(data(PlutusData.constr(1)), value(apply(ref(group, find), ints(1, 3), even), DATA, group));
        var add = lam("acc", INT, lam("n", INT, call(DefaultFun.AddInteger, new PirTerm.Var("acc", INT), x)));
        assertEquals(new Term.Const(Constant.integer(10)),
                value(apply(ref(group, foldl), add, integer(0), ints(1, 2, 3, 4)), INT, group));
    }

    @Test
    void nominalElementTypesUseTheirDescribedRepresentation() {
        // TxOutRef is a constructor-encoded ledger record: elements stay raw Data.
        var txOutRef = new Reference("org.julclang.ledger.TxOutRef", List.of());
        var count = hof("foldl", txOutRef, INT_REF);
        var group = PROVIDER.materialize(List.of(count));
        var type = new LedgerTypeProviderAccess().txOutRef();
        var ref = PlutusData.constr(0, PlutusData.bytes(new byte[32]), PlutusData.integer(7));
        var items = new PirTerm.Let("refs", new PirTerm.Const(Constant.data(PlutusData.list(ref, ref))),
                call(DefaultFun.UnListData, new PirTerm.Var("refs", DATA)));
        var indexOf = call(DefaultFun.UnIData, call(DefaultFun.HeadList, call(DefaultFun.TailList,
                call(DefaultFun.SndPair, call(DefaultFun.UnConstrData, new PirTerm.Var("r", type))))));
        var sumIndexes = lam("acc", INT, lam("r", type, call(DefaultFun.AddInteger, new PirTerm.Var("acc", INT), indexOf)));
        assertEquals(new Term.Const(Constant.integer(14)),
                value(apply(ref(group, count), sumIndexes, integer(0), items), INT, group));
    }

    /** Resolves the ledger TxOutRef representation through the public ledger descriptions. */
    static final class LedgerTypeProviderAccess {
        PirType txOutRef() {
            return new org.julclang.compiler.LedgerTypeProvider().types().get("org.julclang.ledger.TxOutRef")
                    .representation();
        }
    }

    @Test
    void producerFunctionsAreNeverCapturedByBuilderBinders() {
        var any = hof("any", INT_REF);
        var group = PROVIDER.materialize(List.of(any));
        // Outer producer variables named like the builders' internal binders.
        for (var name : List.of("x", "acc", "lst", "go", "h", "x_map")) {
            var outer = new PirTerm.Var(name, INT);
            var matches = lam("n", INT, call(DefaultFun.EqualsInteger, new PirTerm.Var("n", INT), outer));
            var present = new PirTerm.Let(name, integer(3), apply(ref(group, any), ints(1, 2, 3), matches));
            var absent = new PirTerm.Let(name, integer(9), apply(ref(group, any), ints(1, 2, 3), matches));
            assertEquals(new Term.Const(Constant.bool(true)), value(present, BOOL, group), name);
            assertEquals(new Term.Const(Constant.bool(false)), value(absent, BOOL, group), name);
        }
    }

    @Test
    void argumentsAreEvaluatedOnceStrictlyAndLeftToRight() {
        var map = hof("map", INT_REF, INT_REF);
        var group = PROVIDER.materialize(List.of(map));
        var traced = new PirTerm.Trace(new PirTerm.Const(Constant.string("function")),
                lam("n", INT, call(DefaultFun.AddInteger, new PirTerm.Var("n", INT), integer(1))));
        var list = new PirTerm.Trace(new PirTerm.Const(Constant.string("list")), ints(1, 2, 3));
        var result = succeeds(call(DefaultFun.ListData, apply(ref(group, map), list, traced)), DATA, group);
        assertEquals(List.of("list", "function"), result.traces());
        // Unlike Java inlining, the function argument is evaluated even for an empty list.
        var empty = new PirTerm.Trace(new PirTerm.Const(Constant.string("list")), ints());
        assertEquals(List.of("list", "function"),
                succeeds(call(DefaultFun.ListData, apply(ref(group, map), empty, traced)), DATA, group).traces());
        // A failing element function fails the traversal at the failing element.
        var failing = lam("n", INT, new PirTerm.IfThenElse(call(DefaultFun.EqualsInteger, new PirTerm.Var("n", INT),
                integer(2)), new PirTerm.Error(INT), new PirTerm.Var("n", INT)));
        assertInstanceOf(EvalResult.Failure.class,
                run(call(DefaultFun.ListData, apply(ref(group, map), ints(1, 2, 3), failing)), DATA, group));
    }

    @Test
    void javaSourceAndProgrammaticExportsShareOneContract() {
        var sources = LibrarySources.resolve(List.of(StdlibLibraryProvider.LISTS), Map.of(),
                StdlibLibraryProviderTest.class.getClassLoader());
        var java = new JavaLibraryProvider(sources, StdlibRegistry.defaultRegistry(), new CompilerOptions());
        var composite = LibraryProviders.compose(List.of(java, PROVIDER));
        var exported = composite.describe(StdlibLibraryProvider.LISTS).stream().map(LibraryExport::symbol).toList();
        assertTrue(exported.contains(StdlibLibraryProvider.LISTS + ".reverse"), exported.toString());
        assertEquals(6, composite.schemes(StdlibLibraryProvider.LISTS).size());
        var concat = new LibraryRequest.Export(StdlibLibraryProvider.LISTS + ".concat");
        var map = hof("map", INT_REF, INT_REF);
        var group = composite.materialize(List.of(concat, map));
        // concat's private dependency (reverse) is linked with it.
        assertTrue(group.definitions().containsKey(StdlibLibraryProvider.LISTS + ".reverse"),
                group.definitions().keySet().toString());
        var doubled = lam("n", INT, call(DefaultFun.MultiplyInteger, new PirTerm.Var("n", INT), integer(2)));
        var dataList = new PirType.ListType(DATA);
        var mapped = new PirTerm.Let("mapped", apply(ref(group, map), ints(1, 2), doubled),
                new PirTerm.Var("mapped", dataList));
        var term = call(DefaultFun.ListData, apply(ref(group, concat), mapped, mapped));
        assertEquals(data(intData(2, 4, 2, 4)), value(term, DATA, group));
    }

    @Test
    void duplicateOwnershipAndBadRequestsAreRejected() {
        var twice = LibraryProviders.compose(List.of(PROVIDER, new StdlibLibraryProvider(new CompilerOptions())));
        assertEquals("JULC0047", assertThrows(BackendException.class,
                () -> twice.schemes(StdlibLibraryProvider.LISTS)).code());
        assertEquals("JULC0047", assertThrows(BackendException.class,
                () -> twice.materialize(List.of(hof("map", INT_REF, INT_REF)))).code());
        for (var request : List.of(hof("map", INT_REF), hof("map", INT_REF, new Reference("Unit", List.of())),
                hof("map", INT_REF, Reference.variable("b")), hof("filter", new Reference("demo.Missing", List.of())),
                hof("zip", INT_REF), (LibraryRequest) new LibraryRequest.Export(StdlibLibraryProvider.LISTS + ".map")))
            assertEquals("JULC0051", assertThrows(BackendException.class,
                    () -> PROVIDER.materialize(List.of(request))).code(), request.describe());
    }

    @Test
    void compositionChecksRevisionsAndTargets() {
        var future = new LibraryProvider() {
            @Override public List<LibraryExport> describe(String module) { return List.of(); }
            @Override public PirTerm materialize(String symbol) { throw new UnsupportedOperationException(); }
            @Override public int revision() { return BackendContract.REVISION + 1; }
        };
        assertEquals("JULC0044", assertThrows(BackendException.class,
                () -> LibraryProviders.compose(List.of(PROVIDER, future))).code());
        var map = hof("map", INT_REF, INT_REF);
        var group = PROVIDER.materialize(List.of(map));
        var pv10 = new CompilerTarget(LedgerEvaluationTarget.pv10(PlutusLanguage.PLUTUS_V3), UplcVersion.V1_1_0);
        var foreign = new LibraryImports(group.provider(), group.revision(), pv10, group.definitions(),
                group.bindings(), group.namedTypes(), group.types());
        var error = assertThrows(BackendException.class, () -> run(apply(ref(foreign, map), ints(1),
                lam("n", INT, new PirTerm.Var("n", INT))), INT_LIST, foreign));
        assertEquals("JULC0046", error.code());
    }

    @Test
    void producerOwnedTypesAreTypeArgumentsOfProgrammaticExports() {
        var order = new PirType.RecordType("dsl.Order", List.of(new PirType.Field("amount", INT),
                new PirType.Field("open", BOOL)));
        var orderRef = new Reference("dsl.Order", List.of());
        var anyOpen = new LibraryRequest.Instantiate(StdlibLibraryProvider.LISTS + ".any", List.of(orderRef),
                Map.of("dsl.Order", order));
        var group = PROVIDER.materialize(List.of(anyOpen));
        // \o:Order -> the Bool at field 1 (open), decoded from the record's Data fields.
        var fields = call(DefaultFun.SndPair, call(DefaultFun.UnConstrData, new PirTerm.Var("o", order)));
        var open = new PirTerm.Lam("o", order, new PirTerm.IfThenElse(call(DefaultFun.EqualsInteger,
                call(DefaultFun.FstPair, call(DefaultFun.UnConstrData,
                        call(DefaultFun.HeadList, call(DefaultFun.TailList, fields)))), integer(1)),
                new PirTerm.Const(Constant.bool(true)), new PirTerm.Const(Constant.bool(false))));
        PirTerm items = call(DefaultFun.MkNilData, new PirTerm.Const(Constant.unit()));
        for (var value : List.of(PlutusData.constr(0, PlutusData.integer(1), PlutusData.constr(0)),
                PlutusData.constr(0, PlutusData.integer(2), PlutusData.constr(1))))
            items = call(DefaultFun.MkCons, new PirTerm.Const(Constant.data(value)), items);
        var list = new PirTerm.Let("orders", items, new PirTerm.Var("orders", new PirType.ListType(order)));
        assertEquals(new Term.Const(Constant.bool(true)), value(apply(ref(group, anyOpen), list, open), BOOL, group));
        var unknown = new LibraryRequest.Instantiate(StdlibLibraryProvider.LISTS + ".any", List.of(orderRef));
        assertEquals("JULC0051", assertThrows(BackendException.class,
                () -> PROVIDER.materialize(List.of(unknown))).code());
    }
}
