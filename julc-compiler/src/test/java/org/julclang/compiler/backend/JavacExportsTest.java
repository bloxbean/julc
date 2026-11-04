package org.julclang.compiler.backend;

import org.julclang.compiler.frontend.javac.lower.FrozenReference;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JavaLibraryProvider;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.backend.LibraryType.Reference;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeMap;

import static org.julclang.compiler.backend.FunctionProgramTest.program;
import static org.julclang.compiler.backend.GenericExportsTest.BOOL_REF;
import static org.julclang.compiler.backend.GenericExportsTest.BYTES_REF;
import static org.julclang.compiler.backend.GenericExportsTest.INT_REF;
import static org.julclang.compiler.backend.GenericExportsTest.ORDER;
import static org.julclang.compiler.backend.GenericExportsTest.ORDER_REF;
import static org.julclang.compiler.backend.GenericExportsTest.SEQ;
import static org.julclang.compiler.backend.GenericExportsTest.SIDE;
import static org.julclang.compiler.backend.GenericExportsTest.SIDE_REF;
import static org.julclang.compiler.backend.GenericExportsTest.instantiate;
import static org.julclang.compiler.backend.GenericExportsTest.producer;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-068 D8 and I5 on {@code frontend=JAVAC}: Elci's {@link JavaLibraryProvider} describes, exports, materializes and
 * specializes as the reference does, compared with the reference's provider over the same sources.
 */
class JavacExportsTest {

    /** A library nothing else references: every unit a provider is given is a root, and exported. */
    static final String UNREFERENCED = """
            package other;
            import java.math.BigInteger;
            import org.julclang.core.types.JulcList;
            import org.julclang.ledger.PubKeyHash;
            import org.julclang.stdlib.annotation.OnchainLibrary;
            @OnchainLibrary
            public class Lone {
                public static BigInteger step(BigInteger x) { return x.add(BigInteger.TWO); }
                public static <A> A last(JulcList<A> items, A fallback) {
                    if (items.isEmpty()) return fallback;
                    if (items.tail().isEmpty()) return items.head();
                    return last(items.tail(), fallback);
                }
                public static BigInteger f(long x) { return BigInteger.valueOf(x); }
                public static BigInteger f(BigInteger x) { return x; }
                /** Overloads of one lowering and two source signatures: every export of this class is unsupported. */
                @OnchainLibrary
                public static class Ambiguous {
                    public static byte[] p(byte[] x) { return x; }
                    public static PubKeyHash p(PubKeyHash x) { return x; }
                }
            }
            """;

    private static JavaLibraryProvider provider(List<String> sources) {
        return new JavaLibraryProvider(sources, StdlibRegistry.defaultRegistry(), new CompilerOptions());
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    private static byte[] bytes(PirTerm term) {
        return UplcFlatEncoder.encodeProgram(new JulcCompiler().compilePirToProgram(term));
    }

    @Test
    void anExportedLibraryIsTheReferences() {
        for (var sources : List.of(List.of(SEQ), List.of(UNREFERENCED, SEQ))) {
            var javac = provider(sources);
            var seen = new StringBuilder();
            for (String module : List.of("demo.Seq", "other.Lone", "other.Lone.Ambiguous", "demo.Seq.Quote")) {
                seen.append(module).append(javac.describe(module)).append(javac.schemes(module));
                for (var export : javac.describe(module)) seen.append(export.symbol()).append(hex(bytes(javac.materialize(export.symbol()))));
            }
            seen.append(new TreeMap<>(javac.unsupportedExports())).append(new TreeMap<>(javac.types())).append(new TreeMap<>(javac.namedDefinitions()));
            FrozenReference.assertSame("an exported library|" + sources, seen.toString());
        }
    }

    @Test
    void aSpecializationIsTheReferences() {
        var sources = List.of(SEQ, UNREFERENCED);
        var javac = provider(sources);
        var quote = new Reference("demo.Seq.Quote", List.of());
        var requests = List.<LibraryRequest.Instantiate>of(
                instantiate("firstOr", INT_REF), instantiate("firstOr", BYTES_REF), instantiate("count", quote),
                instantiate("contains", INT_REF), instantiate("contains", BYTES_REF), instantiate("pair", BOOL_REF),
                instantiate("scaledFirst", INT_REF), instantiate("firstOr", new Reference("demo.Seq.Price", List.of())),
                producer("firstOr", ORDER_REF, "dsl.Order", ORDER), producer("contains", SIDE_REF, "dsl.Side", SIDE),
                new LibraryRequest.Instantiate("other.Lone.last", List.of(INT_REF)));
        for (var request : requests) {
            var actual = javac.materialize(List.of(request));
            var binding = actual.binding(request);
            var term = new PirTerm.Var(binding.name(), binding.type());
            FrozenReference.assertSame("a specialization|" + request.describe(), binding + "|" + actual.definitions().keySet() + "|"
                    + hex(UplcFlatEncoder.encodeProgram(FunctionProgramTest.compile(program(term, binding.type(),
                            new LinkedHashMap<>(), List.of(actual))).program())));
        }
    }

    @Test
    void whatTheReferenceCannotSpecializeIsRejected() {
        var javac = provider(List.of(SEQ));
        for (var request : List.of(instantiate("bounded", INT_REF), instantiate("wildcard", INT_REF),
                instantiate("scaled", INT_REF), instantiate("missing", INT_REF), instantiate("countTwice", INT_REF),
                instantiate("firstOr", INT_REF, INT_REF))) {
            var actual = assertThrows(BackendException.class, () -> javac.materialize(List.of(request)));
            FrozenReference.assertSame("what cannot specialize|" + request.describe(), actual.code());
        }
    }

    @Test
    void anEmptyProviderIsTheLedgersTypes() {
        var javac = provider(List.of());
        FrozenReference.assertSame("an empty provider", new TreeMap<>(javac.types()) + "|" + new TreeMap<>(javac.namedDefinitions()) + "|"
                + javac.describe("demo.Seq") + "|" + new TreeMap<>(javac.unsupportedExports()));
    }

    /** One type two units declare is the reference's IllegalArgumentException, before anything is read (review F6). */
    @Test
    void aTypeTwoUnitsDeclareIsTheReferencesRejection() {
        for (var sources : List.of(List.of(SEQ, SEQ), List.of(SEQ, SEQ.replace("BigInteger.TEN", "BigInteger.TWO")))) {
            var actual = assertThrows(IllegalArgumentException.class, () -> provider(sources));
            FrozenReference.assertSame("a type two units declare|" + sources, actual.getMessage());
        }
    }

    /** A template the subset rejects is one unsupported export: the provider's other exports stand (review F6). */
    @Test
    void aTemplateTheSubsetRejectsIsOneUnsupportedExport() {
        var source = """
                package e;
                import java.math.BigInteger;
                import org.julclang.stdlib.annotation.OnchainLibrary;
                @OnchainLibrary
                public class Q {
                    public static <X> boolean same(X a, X b) { return a == b; }
                    public static BigInteger twice(BigInteger x) { return x.add(x); }
                }
                """;
        var javac = provider(List.of(source));
        assertEquals(List.of("e.Q.twice"), javac.describe("e.Q").stream().map(LibraryExport::symbol).toList());
        assertTrue(javac.unsupportedExports().get("e.Q.same").contains("by reference"), javac.unsupportedExports()::toString);
        FrozenReference.assertSame("a body nothing lowers|" + source, hex(bytes(javac.materialize("e.Q.twice"))));
    }
}
