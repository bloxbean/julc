package org.julclang.compiler.depth;

import org.julclang.compiler.CompileResult;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.CompilerTestVm;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.core.PlutusData;
import org.julclang.core.flat.UplcFlatDecoder;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.TermExtractor;
import org.julclang.vm.JulcVm;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.LongPredicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Issue #327, ADR-074: a program that is long but flat in Java compiles on a small stack, whatever its length.
 * <p>
 * Each shape below is flat in Java (javac handles it in a loop) but nests in PIR and UPLC once per item: a chain of
 * helper methods, a block of local variables, a run of early returns, a long list literal. Before ADR-074 each pass
 * that recursed on the term overflowed on these shapes once they were a few hundred items long, on a 256 KB stack
 * and in a GraalVM Web Image on Node's default stack. Here each compiles on a 256 KB stack at every optimization
 * level, with a source map and with debug metadata, and the script computes what the Java does.
 */
class DeepProgramCompileTest {

    /** The stack every compilation here runs on: a quarter of a typical thread's, and less than Node's. */
    private static final long STACK = 256 * 1024;

    private static JulcVm vm;

    @BeforeAll
    static void vm() {
        vm = CompilerTestVm.pv11();
    }

    /** A shape: its Java, at a size, and what {@code f(x)} returns. */
    record Shape(String name, int size, Function<Integer, String> source, Function<Integer, LongPredicate> expected) {
        @Override
        public String toString() {
            return name + " x" + size;
        }
    }

    static Stream<Arguments> shapes() {
        var shapes = List.of(
                new Shape("helper chain", 2_000, DeepProgramCompileTest::helperChain,
                        n -> x -> x >= 0 && x < n ? x % 3 == 0 : x > n),
                new Shape("local variables", 2_000, DeepProgramCompileTest::locals,
                        n -> x -> x + n - 1 > n),
                new Shape("early returns", 2_000, DeepProgramCompileTest::earlyReturns,
                        n -> x -> x >= 0 && x < n && x % 2 == 0),
                new Shape("list literal", 1_000, DeepProgramCompileTest::listLiteral,
                        n -> x -> x >= 0 && x < n));
        var cases = new ArrayList<Arguments>();
        for (var shape : shapes) {
            for (var level : OptimizationLevel.values()) cases.add(Arguments.of(shape, level));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} at {1}")
    @MethodSource("shapes")
    void compilesOnASmallStackAndComputesWhatJavaDoes(Shape shape, OptimizationLevel level) throws Exception {
        String source = shape.source().apply(shape.size());
        var options = new CompilerOptions().setOptimizationLevel(level).setSourceMapEnabled(true);
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(), options);

        CompileResult result = onSmallStack(() -> compiler.compileMethod(source, "f"));
        assertFalse(result.hasErrors(), () -> String.valueOf(result.diagnostics()));
        var debug = onSmallStack(() -> compiler.compileForDebug(methodValidator(source), List.of()));
        assertFalse(debug.compileResult().hasErrors(), () -> String.valueOf(debug.compileResult().diagnostics()));

        // The script round-trips through FLAT on the same stack.
        byte[] flat = onSmallStack(() -> UplcFlatEncoder.encodeProgram(result.program()));
        assertArrayEquals(flat, onSmallStack(() -> UplcFlatEncoder.encodeProgram(UplcFlatDecoder.decodeProgram(flat))));

        var expected = shape.expected().apply(shape.size());
        for (long x : new long[]{-2, -1, 0, 1, 2, 3, 7, shape.size() / 2, shape.size() - 1, shape.size(), shape.size() + 1}) {
            var evaluation = vm.evaluateWithArgs(result.program(), List.of(PlutusData.integer(BigInteger.valueOf(x))));
            boolean actual = TermExtractor.extractBoolean(TermExtractor.extractResultTerm(evaluation));
            assertEquals(expected.test(x), actual, shape + " at " + level + ", f(" + x + ")");
        }
    }

    // --- Shapes ---

    private static String header() {
        return """
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;
                import org.julclang.core.types.JulcList;
                import org.julclang.stdlib.annotation.Entrypoint;
                import org.julclang.stdlib.annotation.SpendingValidator;

                class Deep {
                """;
    }

    /** {@code f} calls {@code b0}, and each {@code bi} returns, or calls {@code b(i+1)}. */
    static String helperChain(int n) {
        var java = new StringBuilder(header());
        java.append("    static boolean f(BigInteger x) { return b0(x); }\n");
        for (int i = 0; i < n; i++) {
            java.append("    static boolean b").append(i).append("(BigInteger x) {\n")
                    .append("        if (x.equals(BigInteger.valueOf(").append(i).append("))) { return ")
                    .append(i % 3 == 0).append("; }\n")
                    .append("        return b").append(i + 1).append("(x);\n    }\n");
        }
        java.append("    static boolean b").append(n).append("(BigInteger x) { return x.compareTo(BigInteger.valueOf(")
                .append(n).append(")) > 0; }\n}\n");
        return java.toString();
    }

    /** {@code n} locals, each one more than the last. */
    static String locals(int n) {
        var java = new StringBuilder(header());
        java.append("    static boolean f(BigInteger x) {\n        BigInteger a0 = x;\n");
        for (int i = 1; i < n; i++) {
            java.append("        BigInteger a").append(i).append(" = a").append(i - 1).append(".add(BigInteger.ONE);\n");
        }
        java.append("        return a").append(n - 1).append(".compareTo(BigInteger.valueOf(").append(n).append(")) > 0;\n    }\n}\n");
        return java.toString();
    }

    /** {@code n} statements {@code if (x == i) return ...;} one after another. */
    static String earlyReturns(int n) {
        var java = new StringBuilder(header());
        java.append("    static boolean f(BigInteger x) {\n");
        for (int i = 0; i < n; i++) {
            java.append("        if (x.equals(BigInteger.valueOf(").append(i).append("))) { return ")
                    .append(i % 2 == 0).append("; }\n");
        }
        java.append("        return false;\n    }\n}\n");
        return java.toString();
    }

    /** A list literal of {@code 0 .. n-1}, searched for {@code x}. */
    static String listLiteral(int n) {
        var java = new StringBuilder(header());
        java.append("    static boolean f(BigInteger x) {\n        JulcList<BigInteger> xs = JulcList.of(");
        for (int i = 0; i < n; i++) {
            if (i > 0) java.append(", ");
            java.append("BigInteger.valueOf(").append(i).append(')');
        }
        java.append(");\n        return xs.contains(x);\n    }\n}\n");
        return java.toString();
    }

    /** The shape's class as a spending validator whose redeemer is {@code x}, for the debug-metadata path. */
    private static String methodValidator(String source) {
        return source
                .replace("class Deep {", """
                        @SpendingValidator
                        class Deep {
                            @Entrypoint
                            static boolean validate(BigInteger redeemer, PlutusData ctx) {
                                return f(redeemer);
                            }
                        """);
    }

    private static <T> T onSmallStack(Supplier<T> work) throws InterruptedException {
        var result = new AtomicReference<T>();
        var failure = new AtomicReference<Throwable>();
        var thread = new Thread(null, () -> {
            try {
                result.set(work.get());
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "issue-327-small-stack", STACK);
        thread.start();
        thread.join();
        if (failure.get() != null) throw new AssertionError("failed on a " + STACK / 1024 + " KB stack", failure.get());
        return result.get();
    }
}
