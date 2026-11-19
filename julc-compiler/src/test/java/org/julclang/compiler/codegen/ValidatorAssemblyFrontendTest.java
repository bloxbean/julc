package org.julclang.compiler.codegen;

import org.julclang.compiler.CompilationContext;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.resolve.LibraryMethodRegistry.LibraryMethod;
import org.julclang.core.Constant;
import org.julclang.core.flat.UplcFlatEncoder;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What the assembly tells a frontend of the binders it builds, so that I13's provenance reaches them. */
class ValidatorAssemblyFrontendTest {

    private static final PirType INT = new PirType.IntegerType();
    private static final PirType FUN = new PirType.FunType(INT, INT);

    private static PirTerm one() {
        return new PirTerm.Const(Constant.integer(BigInteger.ONE));
    }

    private static PirTerm self(String name) {
        return new PirTerm.Lam("x", INT, new PirTerm.App(new PirTerm.Var(name, new PirType.FunType(INT, INT)), new PirTerm.Var("x", INT)));
    }

    /**
     * reviewer1's finding 1 (ADR-078 r12), through the public assembly: library twins {@code q.L.alpha} and
     * {@code q.L.beta} (identity functions); {@code static q.L.alpha = 4; static use = q.L.alpha; T.f = q.L.beta(use)}.
     * Only beta is called, so beta wins the tie, whatever the field is spelled. Before r12 the walk read the second
     * initialiser's {@code q.L.alpha} as the library method: alpha won, and the field's spelling changed the bytes.
     */
    @Test
    void aStaticFieldSpelledLikeALibraryMethodIsTheField() {
        var bytes = new ArrayList<String>();
        for (String field : List.of("q.L.alpha", "field")) {
            var winners = new ArrayList<String>();
            var frontend = new ValidatorAssembly.Frontend(new IdentityHashMap<>(), null, null, term -> { }, null, null, null,
                    new ValidatorAssembly.AssemblyObserver() {
                        @Override
                        public void chose(String key, Map<String, Integer> contenders, String winner) {
                            winners.add(winner);
                        }
                    });
            var statics = List.of(new ValidatorAssembly.StaticField(field, new PirTerm.Const(Constant.integer(BigInteger.valueOf(4)))),
                    new ValidatorAssembly.StaticField("use", new PirTerm.Var(field, INT)));
            var methods = List.of(new ValidatorAssembly.Method("T.f",
                    new PirTerm.App(new PirTerm.Var("q.L.beta", FUN), new PirTerm.Var("use", INT))));
            var libraries = List.of(identity("alpha"), identity("beta"));
            var options = new CompilerOptions().setOptimizationLevel(OptimizationLevel.NONE);
            var result = ValidatorAssembly.method(new ValidatorAssembly.MethodProgram("T.f", INT, List.of(), methods, statics, libraries,
                    List.of()), CompilationContext.resolve(options), frontend);
            assertEquals(List.of("q.L.beta"), winners, field);
            bytes.add(HexFormat.of().formatHex(UplcFlatEncoder.encodeProgram(result.program())));
        }
        assertEquals(bytes.get(0), bytes.get(1), "the field's spelling reaches the bytes");
    }

    private static LibraryMethod identity(String name) {
        return new LibraryMethod("q.L", name, FUN, new PirTerm.Lam("x", INT, new PirTerm.Var("x", INT)));
    }

    /** Each method is told once with the term that binds it, and two binders on one node are told apart by name. */
    @Test
    void everyMethodBinderIsReported() {
        var told = new ArrayList<String>();
        var frontend = new ValidatorAssembly.Frontend(new IdentityHashMap<>(), null, null, term -> { }, null,
                (handler, name, binding) -> told.add(name + " " + binding.getClass().getSimpleName()));
        var methods = List.of(
                new ValidatorAssembly.Method("P.m", one()),
                new ValidatorAssembly.Method("P.loop", self("P.loop")),
                new ValidatorAssembly.Method("P.even", self("P.odd")),
                new ValidatorAssembly.Method("P.odd", self("P.even")));
        ValidatorAssembly.method(new ValidatorAssembly.MethodProgram("P.m", INT, List.of(), methods, List.of(), List.of(), List.of()),
                CompilationContext.resolve(new CompilerOptions()), frontend);
        assertEquals(List.of("P.even LetRec", "P.loop LetRec", "P.m Let", "P.odd LetRec"), told.stream().sorted().toList());
    }
}
