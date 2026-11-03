package org.julclang.compiler.backend;

import org.julclang.compiler.CompilerFrontend;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JavaLibraryProvider;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.backend.LibraryType.Reference;
import org.julclang.compiler.frontend.javac.JavacGateMode;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

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
                static final BigInteger STEP = BigInteger.TWO;
                public static BigInteger step(BigInteger x) { return x.add(STEP); }
                public static <A> A last(JulcList<A> items, A fallback) {
                    if (items.isEmpty()) return fallback;
                    if (items.tail().isEmpty()) return items.head();
                    return last(items.tail(), fallback);
                }
                public static BigInteger f(long x) { return BigInteger.valueOf(x); }
                public static BigInteger f(BigInteger x) { return x; }
                /** Overloads of one lowering and two source signatures: every export of this class is unsupported. */
                public static class Ambiguous {
                    public static byte[] p(byte[] x) { return x; }
                    public static PubKeyHash p(PubKeyHash x) { return x; }
                }
            }
            """;

    private static JavaLibraryProvider provider(CompilerFrontend frontend, List<String> sources) {
        return new JavaLibraryProvider(sources, StdlibRegistry.defaultRegistry(),
                new CompilerOptions().setFrontend(frontend).setJavacGate(JavacGateMode.OFF));
    }

    private static byte[] bytes(PirTerm term) {
        return UplcFlatEncoder.encodeProgram(new JulcCompiler().compilePirToProgram(term));
    }

    @Test
    void anExportedLibraryIsTheReferences() {
        for (var sources : List.of(List.of(SEQ), List.of(UNREFERENCED, SEQ))) {
            var reference = provider(CompilerFrontend.JAVAPARSER, sources);
            var javac = provider(CompilerFrontend.JAVAC, sources);
            for (String module : List.of("demo.Seq", "other.Lone", "other.Lone.Ambiguous", "demo.Seq.Quote")) {
                assertEquals(reference.describe(module), javac.describe(module), module);
                assertEquals(reference.schemes(module), javac.schemes(module), module);
                for (var export : reference.describe(module)) {
                    assertArrayEquals(bytes(reference.materialize(export.symbol())), bytes(javac.materialize(export.symbol())),
                            export.symbol());
                }
            }
            assertEquals(reference.unsupportedExports(), javac.unsupportedExports());
            assertEquals(reference.types(), javac.types());
            assertEquals(reference.namedDefinitions(), javac.namedDefinitions());
        }
    }

    @Test
    void aSpecializationIsTheReferences() {
        var sources = List.of(SEQ, UNREFERENCED);
        var reference = provider(CompilerFrontend.JAVAPARSER, sources);
        var javac = provider(CompilerFrontend.JAVAC, sources);
        var quote = new Reference("demo.Seq.Quote", List.of());
        var requests = List.<LibraryRequest.Instantiate>of(
                instantiate("firstOr", INT_REF), instantiate("firstOr", BYTES_REF), instantiate("count", quote),
                instantiate("contains", INT_REF), instantiate("contains", BYTES_REF), instantiate("pair", BOOL_REF),
                instantiate("scaledFirst", INT_REF), instantiate("firstOr", new Reference("demo.Seq.Price", List.of())),
                producer("firstOr", ORDER_REF, "dsl.Order", ORDER), producer("contains", SIDE_REF, "dsl.Side", SIDE),
                new LibraryRequest.Instantiate("other.Lone.last", List.of(INT_REF)));
        for (var request : requests) {
            var expected = reference.materialize(List.of(request));
            var actual = javac.materialize(List.of(request));
            assertEquals(expected.binding(request), actual.binding(request), request.describe());
            assertEquals(expected.definitions().keySet(), actual.definitions().keySet(), request.describe());
            var binding = expected.binding(request);
            var term = new PirTerm.Var(binding.name(), binding.type());
            assertArrayEquals(
                    UplcFlatEncoder.encodeProgram(FunctionProgramTest.compile(program(term, binding.type(),
                            new LinkedHashMap<>(), List.of(expected))).program()),
                    UplcFlatEncoder.encodeProgram(FunctionProgramTest.compile(program(term, binding.type(),
                            new LinkedHashMap<>(), List.of(actual))).program()), request.describe());
        }
    }

    @Test
    void whatTheReferenceCannotSpecializeIsRejected() {
        var reference = provider(CompilerFrontend.JAVAPARSER, List.of(SEQ));
        var javac = provider(CompilerFrontend.JAVAC, List.of(SEQ));
        for (var request : List.of(instantiate("bounded", INT_REF), instantiate("wildcard", INT_REF),
                instantiate("scaled", INT_REF), instantiate("missing", INT_REF), instantiate("countTwice", INT_REF),
                instantiate("firstOr", INT_REF, INT_REF))) {
            var expected = assertThrows(BackendException.class, () -> reference.materialize(List.of(request)));
            var actual = assertThrows(BackendException.class, () -> javac.materialize(List.of(request)));
            assertEquals(expected.code(), actual.code(), request.describe());
        }
    }

    @Test
    void anEmptyProviderIsTheLedgersTypes() {
        var reference = provider(CompilerFrontend.JAVAPARSER, List.of());
        var javac = provider(CompilerFrontend.JAVAC, List.of());
        assertEquals(reference.types(), javac.types());
        assertEquals(reference.namedDefinitions(), javac.namedDefinitions());
        assertEquals(reference.describe("demo.Seq"), javac.describe("demo.Seq"));
        assertEquals(reference.unsupportedExports(), javac.unsupportedExports());
    }
}
