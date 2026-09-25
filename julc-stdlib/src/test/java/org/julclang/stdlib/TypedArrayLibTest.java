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
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.julclang.stdlib.TypedMapLibTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Typed array templates specialized at element types, and {@code JulcArray} in library signatures. */
class TypedArrayLibTest {
    static final String TYPED_ARRAY = "org.julclang.stdlib.lib.TypedArrayLib";
    static final JavaLibraryProvider ARRAYS = provider(TYPED_ARRAY);

    static LibraryRequest.Instantiate array(String name, Reference element) {
        return new LibraryRequest.Instantiate(TYPED_ARRAY + "." + name, List.of(element));
    }

    /** A list term typed {@code List element} from its Data encoding. */
    static PirTerm listOf(PirType element, PlutusData... items) {
        var decoded = call(DefaultFun.UnListData, new PirTerm.Const(Constant.data(PlutusData.list(items))));
        return new PirTerm.Let("xs", decoded, new PirTerm.Var("xs", new PirType.ListType(element)));
    }

    static PirTerm integer(long value) {
        return new PirTerm.Const(Constant.integer(value));
    }

    @Test
    void theCatalogueIsGenericOverTheElementType() {
        assertEquals(List.of("fromList", "get", "length"),
                ARRAYS.schemes(TYPED_ARRAY).stream().map(s -> s.symbol().substring(s.symbol().lastIndexOf('.') + 1)).toList());
        var get = ARRAYS.schemes(TYPED_ARRAY).stream().filter(s -> s.symbol().endsWith(".get")).findFirst().orElseThrow();
        assertEquals("Array", get.parameters().getFirst().name(), get::toString);
    }

    @Test
    void elementsAreIndexedAndDecodedAtTheirType() {
        var fromList = array("fromList", INT_REF);
        var get = array("get", INT_REF);
        var length = array("length", INT_REF);
        var group = ARRAYS.materialize(List.of(fromList, get, length));
        assertEquals(new PirType.FunType(new PirType.ListType(INT), new PirType.ArrayType(INT)), group.binding(fromList).type());
        var values = apply(ref(group, fromList), listOf(INT, PlutusData.integer(10), PlutusData.integer(20), PlutusData.integer(30)));
        assertEquals(new Term.Const(Constant.integer(20)), value(apply(ref(group, get), values, integer(1)), INT, group));
        assertEquals(new Term.Const(Constant.integer(3)), value(apply(ref(group, length), values), INT, group));

        var bytesGroup = ARRAYS.materialize(List.of(array("fromList", BYTES_REF), array("get", BYTES_REF)));
        var names = apply(ref(bytesGroup, array("fromList", BYTES_REF)),
                listOf(BYTESTRING, PlutusData.bytes("a".getBytes()), PlutusData.bytes("b".getBytes())));
        assertEquals(new Term.Const(Constant.byteString("b".getBytes())),
                value(apply(ref(bytesGroup, array("get", BYTES_REF)), names, integer(1)), BYTESTRING, bytesGroup));
    }

    @Test
    void indexingOutsideTheArrayFails() {
        var fromList = array("fromList", INT_REF);
        var get = array("get", INT_REF);
        var group = ARRAYS.materialize(List.of(fromList, get));
        var values = apply(ref(group, fromList), listOf(INT, PlutusData.integer(10)));
        for (long index : new long[]{1, -1}) {
            var program = new FunctionProgram(2, Set.of(), CompilerTarget.PLUTUS_V3_PV11, Map.of(), List.of(group),
                    new LinkedHashMap<>(), "test.main", apply(ref(group, get), values, integer(index)), INT);
            var compiled = new CompilerBackend().compile(program, new CompilerOptions());
            var result = JulcVm.create().evaluate(compiled.program(), CompilerTarget.PLUTUS_V3_PV11.ledgerTarget());
            assertInstanceOf(EvalResult.Failure.class, result, "index " + index);
        }
    }

    @Test
    void producerRecordsAreElements() {
        var order = new PirType.RecordType("dsl.Order", List.of(new PirType.Field("amount", INT)));
        var orderRef = new Reference("dsl.Order", List.of());
        var producer = Map.<String, PirType>of("dsl.Order", order);
        var fromList = new LibraryRequest.Instantiate(TYPED_ARRAY + ".fromList", List.of(orderRef), producer);
        var get = new LibraryRequest.Instantiate(TYPED_ARRAY + ".get", List.of(orderRef), producer);
        var group = ARRAYS.materialize(List.of(fromList, get));
        var first = PlutusData.constr(0, PlutusData.integer(7));
        var second = PlutusData.constr(0, PlutusData.integer(8));
        var orders = apply(ref(group, fromList), listOf(order, first, second));
        assertEquals(data(second), value(apply(ref(group, get), orders, integer(1)), order, group));
    }

    @Test
    void librarySignaturesMayTakeAndReturnArrays() {
        String source = """
                package test.arrays;

                import org.julclang.core.types.JulcArray;
                import org.julclang.core.types.JulcList;
                import org.julclang.stdlib.annotation.OnchainLibrary;

                import java.math.BigInteger;

                @OnchainLibrary
                public class Weights {
                    public static JulcArray<BigInteger> table(JulcList<BigInteger> weights) {
                        return JulcArray.fromList(weights);
                    }

                    public static BigInteger weightAt(JulcArray<BigInteger> weights, long index) {
                        return weights.get(index);
                    }
                }
                """;
        var provider = new JavaLibraryProvider(LibrarySources.resolve(List.of("test.arrays.Weights"),
                Map.of("test.arrays.Weights", source), TypedArrayLibTest.class.getClassLoader()),
                StdlibRegistry.defaultRegistry(), new CompilerOptions());
        var exports = provider.describe("test.arrays.Weights");
        assertEquals(List.of("table", "weightAt"), exports.stream().map(e -> e.symbol().substring(e.symbol().lastIndexOf('.') + 1)).toList());
        var table = new LibraryRequest.Export("test.arrays.Weights.table");
        var weightAt = new LibraryRequest.Export("test.arrays.Weights.weightAt");
        var group = provider.materialize(List.of(table, weightAt));
        assertEquals(new PirType.FunType(new PirType.ArrayType(INT), new PirType.FunType(INT, INT)), group.binding(weightAt).type());
        var weights = apply(ref(group, table), listOf(INT, PlutusData.integer(5), PlutusData.integer(9)));
        assertEquals(new Term.Const(Constant.integer(9)), value(apply(ref(group, weightAt), weights, integer(1)), INT, group));
    }

    @Test
    void arraysAreNotDataEncodable() {
        assertFalse(TypeReferences.dataEncodable(new PirType.ArrayType(INT)));
        assertEquals(new PirType.ArrayType(new PirType.ListType(INT)),
                TypeReferences.representation(new Reference("Array", List.of(new Reference("List", List.of(INT_REF)))), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> TypeReferences.representation(new Reference("Array", List.of()), Map.of()));
    }
}
