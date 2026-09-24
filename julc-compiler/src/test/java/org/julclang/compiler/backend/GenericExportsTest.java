package org.julclang.compiler.backend;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.CompilerTestVm;
import org.julclang.compiler.JavaLibraryProvider;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.backend.LibraryType.Reference;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.julclang.core.PlutusData;
import org.julclang.core.Term;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalResult;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.julclang.compiler.backend.FunctionProgramTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Generic export schemes specialized through the actual Java compiler (#181). */
class GenericExportsTest {
    static final String SEQ = """
            package demo;
            import java.math.BigInteger;
            import org.julclang.core.types.JulcList;
            @OnchainLibrary
            public class Seq {
                public record Quote(BigInteger amount, boolean approved) {}
                @NewType public record Price(BigInteger value) {}
                @NewType public record Quantity(BigInteger value) {}
                public static <T> T firstOr(JulcList<T> items, T fallback) {
                    if (items.isEmpty()) return fallback;
                    return items.head();
                }
                public static <T> BigInteger count(JulcList<T> items) {
                    if (items.isEmpty()) return BigInteger.ZERO;
                    return count(items.tail()).add(BigInteger.ONE);
                }
                public static <T> boolean contains(JulcList<T> items, T target) {
                    if (items.isEmpty()) return false;
                    if (items.head().equals(target)) return true;
                    return contains(items.tail(), target);
                }
                public static <T> JulcList<T> pair(T first, T second) {
                    return JulcList.of(first, second);
                }
                public static BigInteger scaled(BigInteger x) { return x.multiply(BigInteger.TEN); }
                public static <T> BigInteger scaledFirst(JulcList<T> items, BigInteger fallback) {
                    if (items.isEmpty()) return scaled(fallback);
                    return scaled(BigInteger.ONE);
                }
                public static <T> BigInteger countTwice(JulcList<T> items) {
                    return count(items).add(count(items));
                }
                public static <T extends Comparable<T>> T bounded(T value) { return value; }
                public static <T> BigInteger wildcard(JulcList<? extends T> items) { return BigInteger.ZERO; }
            }
            """;
    static final JavaLibraryProvider PROVIDER = provider(SEQ);

    static JavaLibraryProvider provider(String source) {
        return new JavaLibraryProvider(List.of(source), StdlibRegistry.defaultRegistry(), new CompilerOptions());
    }

    static final Reference INT_REF = new Reference("Int", List.of());
    static final Reference BYTES_REF = new Reference("Bytes", List.of());
    static final Reference BOOL_REF = new Reference("Bool", List.of());

    static LibraryRequest.Instantiate instantiate(String name, Reference... arguments) {
        return new LibraryRequest.Instantiate("demo.Seq." + name, List.of(arguments));
    }

    static PirTerm ref(LibraryImports group, LibraryRequest request) {
        var binding = group.binding(request);
        return new PirTerm.Var(binding.name(), binding.type());
    }

    /** A list of Data elements built from already encoded items, typed as a list of {@code element}. */
    static PirTerm list(PirType element, PirTerm... encoded) {
        PirTerm list = call(DefaultFun.MkNilData, new PirTerm.Const(Constant.unit()));
        for (int i = encoded.length - 1; i >= 0; i--) list = call(DefaultFun.MkCons, encoded[i], list);
        return new PirTerm.Let("items", list, new PirTerm.Var("items", new PirType.ListType(element)));
    }

    static PirTerm ints(long... values) {
        var encoded = new PirTerm[values.length];
        for (int i = 0; i < values.length; i++) encoded[i] = call(DefaultFun.IData, integer(values[i]));
        return list(INT, encoded);
    }

    static PirTerm bytes(int... values) {
        return new PirTerm.Const(Constant.byteString(Arrays.stream(values).collect(
                java.io.ByteArrayOutputStream::new, (o, v) -> o.write(v), (a, b) -> {}).toByteArray()));
    }

    static EvalResult run(PirTerm term, PirType type, LibraryImports group) {
        return CompilerTestVm.pv11().evaluate(FunctionProgramTest.compile(
                program(term, type, new LinkedHashMap<>(), List.of(group))).program());
    }

    static Term value(PirTerm term, PirType type, LibraryImports group) {
        return assertInstanceOf(EvalResult.Success.class, run(term, type, group)).resultTerm();
    }

    @Test
    void schemesDescribeGenericExportsBeforeSpecialization() {
        var schemes = PROVIDER.schemes("demo.Seq");
        assertEquals(List.of("demo.Seq.firstOr", "demo.Seq.count", "demo.Seq.contains", "demo.Seq.pair",
                "demo.Seq.scaledFirst", "demo.Seq.countTwice"), schemes.stream().map(LibraryScheme::symbol).toList());
        var firstOr = schemes.getFirst();
        assertEquals(List.of(new LibraryScheme.TypeParameter("T", Set.of(LibraryScheme.Constraint.DATA_ENCODABLE))),
                firstOr.typeParameters());
        assertEquals(List.of(new Reference("List", List.of(Reference.variable("T"))), Reference.variable("T")),
                firstOr.parameters());
        assertEquals(Reference.variable("T"), firstOr.result());
        assertEquals("demo.Seq.firstOr<T>(List['T],'T)'T", firstOr.identity());
        assertEquals(2, firstOr.arity());
        // Revision-1 consumers keep seeing only concrete exports.
        assertEquals(List.of("demo.Seq.scaled"),
                PROVIDER.describe("demo.Seq").stream().map(LibraryExport::symbol).toList());
        assertTrue(PROVIDER.unsupportedExports().get("demo.Seq.bounded").contains("bound"));
        assertTrue(PROVIDER.unsupportedExports().containsKey("demo.Seq.wildcard"));
    }

    @Test
    void oneGenericExportRunsAtTwoConcreteTypes() {
        var atInt = instantiate("firstOr", INT_REF);
        var atBytes = instantiate("firstOr", BYTES_REF);
        var group = PROVIDER.materialize(List.of(atInt, atBytes));
        assertEquals(new PirType.FunType(new PirType.ListType(INT), new PirType.FunType(INT, INT)),
                group.binding(atInt).type());
        assertEquals(new Term.Const(Constant.integer(1)),
                value(apply(ref(group, atInt), ints(1, 2), integer(0)), INT, group));
        assertEquals(new Term.Const(Constant.integer(7)),
                value(apply(ref(group, atInt), ints(), integer(7)), INT, group));
        var byteItems = list(BYTES, call(DefaultFun.BData, bytes(0xaa)));
        assertEquals(new Term.Const(Constant.byteString(new byte[] {(byte) 0xaa})),
                value(apply(ref(group, atBytes), byteItems, bytes(0xbb)), BYTES, group));
        assertNotEquals(group.binding(atInt).name(), group.binding(atBytes).name());
    }

    @Test
    void specializationDecidesEncodingAndEquality() {
        // pair<Bool> encodes its elements as Bool constructors, not as integers or raw Data.
        var pair = instantiate("pair", BOOL_REF);
        var containsInt = instantiate("contains", INT_REF);
        var containsBytes = instantiate("contains", BYTES_REF);
        var group = PROVIDER.materialize(List.of(pair, containsInt, containsBytes));
        var encoded = call(DefaultFun.ListData, apply(ref(group, pair), new PirTerm.Const(Constant.bool(true)),
                new PirTerm.Const(Constant.bool(false))));
        assertEquals(new Term.Const(Constant.data(PlutusData.list(PlutusData.constr(1), PlutusData.constr(0)))),
                value(encoded, DATA, group));
        assertEquals(new Term.Const(Constant.bool(true)),
                value(apply(ref(group, containsInt), ints(4, 5, 6), integer(6)), BOOL, group));
        assertEquals(new Term.Const(Constant.bool(false)),
                value(apply(ref(group, containsInt), ints(4, 5, 6), integer(7)), BOOL, group));
        var byteItems = list(BYTES, call(DefaultFun.BData, bytes(1)), call(DefaultFun.BData, bytes(2)));
        assertEquals(new Term.Const(Constant.bool(true)),
                value(apply(ref(group, containsBytes), byteItems, bytes(2)), BOOL, group));
    }

    @Test
    void selfRecursionAndDependencyClosureAreLinked() {
        var quote = new Reference("demo.Seq.Quote", List.of());
        var countQuotes = instantiate("count", quote);
        var scaledFirst = instantiate("scaledFirst", INT_REF);
        var group = PROVIDER.materialize(List.of(countQuotes, scaledFirst));
        assertTrue(group.definitions().containsKey("demo.Seq.scaled"), group.definitions().keySet().toString());
        var quoteType = PROVIDER.types().get("demo.Seq.Quote").representation();
        var quoteData = new PirTerm.Const(Constant.data(PlutusData.constr(0, PlutusData.integer(1), PlutusData.constr(1))));
        assertEquals(new Term.Const(Constant.integer(3)), value(apply(ref(group, countQuotes),
                list(quoteType, quoteData, quoteData, quoteData)), INT, group));
        assertEquals(new Term.Const(Constant.integer(50)),
                value(apply(ref(group, scaledFirst), ints(), integer(5)), INT, group));
    }

    @Test
    void nominalTypeArgumentsStayDistinctAndRequestsDeduplicate() {
        var atPrice = instantiate("firstOr", new Reference("demo.Seq.Price", List.of()));
        var atQuantity = instantiate("firstOr", new Reference("demo.Seq.Quantity", List.of()));
        var group = PROVIDER.materialize(List.of(atPrice, atQuantity, atPrice));
        // Equal representations, distinct nominal specializations.
        assertEquals(group.binding(atPrice).type(), group.binding(atQuantity).type());
        assertNotEquals(group.binding(atPrice).name(), group.binding(atQuantity).name());
        assertEquals(2, group.definitions().keySet().stream().filter(n -> n.startsWith("demo.Seq.firstOr#")).count());
        // An isolated provider over the same content produces the same keys; changed content does not.
        var again = provider(SEQ).materialize(List.of(atPrice));
        assertEquals(group.binding(atPrice).name(), again.binding(atPrice).name());
        var changed = provider(SEQ.replace("BigInteger.TEN", "BigInteger.TWO")).materialize(List.of(atPrice));
        assertNotEquals(group.binding(atPrice).name(), changed.binding(atPrice).name());
        evaluatesTo(program(apply(ref(group, atPrice), ints(3), integer(9)), INT, new LinkedHashMap<>(),
                List.of(group, again)), Constant.integer(3));
    }

    @Test
    void keysCoverEveryIdentityInput() {
        var base = new SpecializationKey("content", "id", List.of(INT_REF), 1, 2, "plutus-v3-pv11-uplc-1.1.0");
        var variants = List.of(
                new SpecializationKey("other", "id", List.of(INT_REF), 1, 2, "plutus-v3-pv11-uplc-1.1.0"),
                new SpecializationKey("content", "id2", List.of(INT_REF), 1, 2, "plutus-v3-pv11-uplc-1.1.0"),
                new SpecializationKey("content", "id", List.of(BYTES_REF), 1, 2, "plutus-v3-pv11-uplc-1.1.0"),
                new SpecializationKey("content", "id", List.of(INT_REF), 2, 2, "plutus-v3-pv11-uplc-1.1.0"),
                new SpecializationKey("content", "id", List.of(INT_REF), 1, 3, "plutus-v3-pv11-uplc-1.1.0"),
                new SpecializationKey("content", "id", List.of(INT_REF), 1, 2, "plutus-v3-pv10-uplc-1.1.0"));
        for (var variant : variants) assertNotEquals(base.digest(), variant.digest(), variant.toString());
        assertEquals(base.digest(), new SpecializationKey("content", "id", List.of(INT_REF), 1, 2,
                "plutus-v3-pv11-uplc-1.1.0").digest());
    }

    @Test
    void partialApplicationEvaluatesAndCapturesArgumentsOnce() {
        var atInt = instantiate("firstOr", INT_REF);
        var group = PROVIDER.materialize(List.of(atInt));
        var captured = new PirTerm.Trace(new PirTerm.Const(Constant.string("captured")), ints(4));
        var partial = new PirTerm.Let("partial", apply(ref(group, atInt), captured),
                call(DefaultFun.AddInteger, apply(new PirTerm.Var("partial", INT_TO_INT), integer(1)),
                        apply(new PirTerm.Var("partial", INT_TO_INT), integer(2))));
        var result = assertInstanceOf(EvalResult.Success.class, run(partial, INT, group));
        assertEquals(new Term.Const(Constant.integer(8)), result.resultTerm());
        assertEquals(List.of("captured"), result.traces());
    }

    @Test
    void invalidInstantiationsAreRejected() {
        for (var request : List.of(
                instantiate("firstOr"),
                instantiate("firstOr", INT_REF, INT_REF),
                instantiate("firstOr", Reference.variable("T")),
                instantiate("firstOr", new Reference("Unit", List.of())),
                instantiate("firstOr", new Reference("demo.Missing", List.of())),
                instantiate("firstOr", new Reference("List", List.of())),
                instantiate("bounded", INT_REF),
                instantiate("wildcard", INT_REF),
                instantiate("countTwice", INT_REF),
                instantiate("scaled", INT_REF),
                instantiate("missing", INT_REF)))
            assertEquals("JULC0051", assertThrows(BackendException.class,
                    () -> PROVIDER.materialize(List.of(request))).code(), request.describe());
        var generic = assertThrows(BackendException.class,
                () -> PROVIDER.materialize(List.of(instantiate("countTwice", INT_REF))));
        assertTrue(generic.getMessage().contains("does not compile"), generic.getMessage());
    }

    @Test
    void javaValidatorsCannotCallTemplatesYet() {
        var validator = """
                import java.math.BigInteger;
                import org.julclang.core.types.JulcList;
                import demo.Seq;
                @MintingValidator
                class UsesTemplate {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, ScriptContext ctx) {
                        JulcList<BigInteger> items = JulcList.of(redeemer);
                        return Seq.firstOr(items, BigInteger.ZERO).equals(BigInteger.ONE);
                    }
                }
                """;
        var error = assertThrows(CompilerException.class,
                () -> new JulcCompiler(StdlibRegistry.defaultRegistry()).compile(validator, List.of(SEQ)));
        assertTrue(error.getMessage().contains("Generic library method demo.Seq.firstOr"), error.getMessage());
        // A library with templates still serves its concrete methods to Java validators.
        var concrete = new JulcCompiler(StdlibRegistry.defaultRegistry()).compile("""
                import java.math.BigInteger;
                import demo.Seq;
                @MintingValidator
                class UsesConcrete {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, ScriptContext ctx) {
                        return Seq.scaled(redeemer).equals(BigInteger.TEN);
                    }
                }
                """, List.of(SEQ));
        assertFalse(concrete.hasErrors(), concrete::toString);
    }
}
