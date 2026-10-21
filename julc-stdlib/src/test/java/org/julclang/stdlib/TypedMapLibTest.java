package org.julclang.stdlib;

import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.CompilerTarget;
import org.julclang.compiler.JavaLibraryProvider;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.LibrarySources;
import org.julclang.compiler.backend.*;
import org.julclang.compiler.backend.LibraryType.Reference;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.julclang.core.PlutusData;
import org.julclang.core.Term;
import org.julclang.ledger.ScriptContextBuilder;
import org.julclang.ledger.PolicyId;
import org.julclang.vm.EvalResult;
import org.julclang.vm.JulcVm;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Typed map templates specialized at key and value types (ADR-059 generic exports). */
class TypedMapLibTest {
    static final String TYPED_MAP = "org.julclang.stdlib.lib.TypedMapLib";
    static final PirType INT = new PirType.IntegerType();
    static final PirType BYTESTRING = new PirType.ByteStringType();
    static final PirType BOOL = new PirType.BoolType();
    static final PirType DATA = new PirType.DataType();
    static final Reference INT_REF = new Reference("Int", List.of());
    static final Reference BYTES_REF = new Reference("Bytes", List.of());

    static JavaLibraryProvider provider(String... owners) {
        return new JavaLibraryProvider(LibrarySources.resolve(List.of(owners), Map.of(),
                TypedMapLibTest.class.getClassLoader()), StdlibRegistry.defaultRegistry(), new CompilerOptions());
    }

    static final JavaLibraryProvider MAPS = provider(TYPED_MAP);

    static LibraryRequest.Instantiate map(String name, Reference key, Reference value) {
        return new LibraryRequest.Instantiate(TYPED_MAP + "." + name, List.of(key, value));
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

    static PirTerm ref(LibraryImports group, LibraryRequest request) {
        var binding = group.binding(request);
        return new PirTerm.Var(binding.name(), binding.type());
    }

    static Term value(PirTerm term, PirType type, LibraryImports... groups) {
        var program = new FunctionProgram(2, Set.of(), CompilerTarget.PLUTUS_V3_PV11, Map.of(), List.of(groups),
                new LinkedHashMap<>(), "test.main", term, type);
        var compiled = new CompilerBackend().compile(program, new CompilerOptions());
        var result = JulcVm.create().evaluate(compiled.program(), CompilerTarget.PLUTUS_V3_PV11.ledgerTarget());
        return assertInstanceOf(EvalResult.Success.class, result, result::toString).resultTerm();
    }

    static Term data(PlutusData value) {
        return new Term.Const(Constant.data(value));
    }

    /** A map term typed {@code Map key value} from its Data encoding. */
    static PirTerm mapOf(PirType key, PirType value, PlutusData.Pair... entries) {
        var decoded = call(DefaultFun.UnMapData, new PirTerm.Const(Constant.data(PlutusData.map(entries))));
        return new PirTerm.Let("m", decoded, new PirTerm.Var("m", new PirType.MapType(key, value)));
    }

    static PlutusData.Pair entry(String key, long value) {
        return new PlutusData.Pair(PlutusData.bytes(key.getBytes()), PlutusData.integer(value));
    }

    static PirTerm bytes(String text) {
        return new PirTerm.Const(Constant.byteString(text.getBytes()));
    }

    @Test
    void theCatalogueIsGenericOverKeysAndValues() {
        assertEquals(List.of("empty", "lookup", "member", "insert", "remove", "keys", "values", "size", "isEmpty"),
                MAPS.schemes(TYPED_MAP).stream().map(s -> s.symbol().substring(s.symbol().lastIndexOf('.') + 1)).toList());
        assertTrue(MAPS.describe(TYPED_MAP).isEmpty(), "templates are schemes, not concrete exports");
    }

    @Test
    void operationsKeepOneEntryPerKeyAndMapOrder() {
        var lookup = map("lookup", BYTES_REF, INT_REF);
        var member = map("member", BYTES_REF, INT_REF);
        var insert = map("insert", BYTES_REF, INT_REF);
        var remove = map("remove", BYTES_REF, INT_REF);
        var keys = map("keys", BYTES_REF, INT_REF);
        var values = map("values", BYTES_REF, INT_REF);
        var size = map("size", BYTES_REF, INT_REF);
        var empty = map("empty", BYTES_REF, INT_REF);
        var isEmpty = map("isEmpty", BYTES_REF, INT_REF);
        var group = MAPS.materialize(List.of(lookup, member, insert, remove, keys, values, size, empty, isEmpty));
        var m = mapOf(BYTESTRING, INT, entry("a", 1), entry("b", 2), entry("c", 3));
        assertEquals(data(PlutusData.constr(0, PlutusData.integer(2))), value(apply(ref(group, lookup), m, bytes("b")), DATA, group));
        assertEquals(data(PlutusData.constr(1)), value(apply(ref(group, lookup), m, bytes("z")), DATA, group));
        assertEquals(new Term.Const(Constant.bool(true)), value(apply(ref(group, member), m, bytes("c")), BOOL, group));
        // Keys and values keep the map's order.
        assertEquals(data(PlutusData.list(PlutusData.bytes("a".getBytes()), PlutusData.bytes("b".getBytes()),
                PlutusData.bytes("c".getBytes()))), value(call(DefaultFun.ListData, apply(ref(group, keys), m)), DATA, group));
        assertEquals(data(PlutusData.list(PlutusData.integer(1), PlutusData.integer(2), PlutusData.integer(3))),
                value(call(DefaultFun.ListData, apply(ref(group, values), m)), DATA, group));
        // Inserting an existing key replaces it; size counts distinct keys.
        var updated = apply(ref(group, insert), m, bytes("b"), new PirTerm.Const(Constant.integer(20)));
        assertEquals(new Term.Const(Constant.integer(3)), value(apply(ref(group, size), updated), INT, group));
        assertEquals(data(PlutusData.constr(0, PlutusData.integer(20))),
                value(apply(ref(group, lookup), updated, bytes("b")), DATA, group));
        assertEquals(new Term.Const(Constant.integer(2)),
                value(apply(ref(group, size), apply(ref(group, remove), m, bytes("a"))), INT, group));
        assertEquals(new Term.Const(Constant.bool(true)), value(apply(ref(group, isEmpty), ref(group, empty)), BOOL, group));
    }

    @Test
    void producerRecordsAreKeys() {
        var order = new PirType.RecordType("dsl.Order", List.of(new PirType.Field("amount", INT)));
        var orderRef = new Reference("dsl.Order", List.of());
        var lookup = new LibraryRequest.Instantiate(TYPED_MAP + ".lookup", List.of(orderRef, INT_REF),
                Map.of("dsl.Order", order));
        var group = MAPS.materialize(List.of(lookup));
        var key = PlutusData.constr(0, PlutusData.integer(7));
        var m = mapOf(order, INT, new PlutusData.Pair(key, PlutusData.integer(70)));
        var keyTerm = new PirTerm.Let("k", new PirTerm.Const(Constant.data(key)), new PirTerm.Var("k", order));
        assertEquals(data(PlutusData.constr(0, PlutusData.integer(70))), value(apply(ref(group, lookup), m, keyTerm), DATA, group));
    }

}
