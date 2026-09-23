package org.julclang.decompiler.lift;

import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.core.DefaultFun;
import org.julclang.core.Term;
import org.julclang.core.flat.UplcFlatDecoder;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.decompiler.DecompileOptions;
import org.julclang.decompiler.JulcDecompiler;
import org.julclang.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-043 (O9): a promoted program binds one {@code ListToArray} and indexes it with plain
 * {@code IndexArray} applications, so the decompiler needs no new recognizer: the recursive
 * {@code get} traversals disappear and the array operations decompile as builtin calls.
 */
class ListIndexPromotionDecompileTest {

    @Test
    void promotedIndexingDecompilesAsArrayBuiltinCalls() {
        var source = """
                import org.julclang.core.types.JulcList;
                import java.math.BigInteger;
                class Two {
                    static BigInteger two(JulcList<BigInteger> xs, BigInteger i, BigInteger j) {
                        return xs.get(i).add(xs.get(j));
                    }
                }
                """;
        var levels = List.of(OptimizationLevel.PV11_SAFE, OptimizationLevel.PV11_COSTED);
        var expectedArrays = List.of(0, 1);
        var expectedIndexing = List.of(0, 2);
        for (int k = 0; k < levels.size(); k++) {
            var level = levels.get(k);
            var compiled = new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions()
                    .setOptimizationLevel(level))
                    .compileMethod(source, "two");
            assertFalse(compiled.hasErrors(), compiled.diagnostics().toString());
            var program = UplcFlatDecoder.decodeProgram(UplcFlatEncoder.encodeProgram(compiled.program()));
            assertEquals(expectedArrays.get(k), countBuiltin(program.term(), DefaultFun.ListToArray), level.toString());
            assertEquals(expectedIndexing.get(k), countBuiltin(program.term(), DefaultFun.IndexArray), level.toString());
            // The safe profile still carries the traversal: two TailList sites (one per get).
            assertEquals(level == OptimizationLevel.PV11_SAFE ? 2 : 0, countBuiltin(program.term(), DefaultFun.TailList), level.toString());
            var decompiled = JulcDecompiler.decompile(program, DecompileOptions.defaults());
            assertNotNull(decompiled.hir(), level.toString());
            assertNotNull(decompiled.javaSource(), level.toString());
            boolean promoted = level == OptimizationLevel.PV11_COSTED;
            assertEquals(promoted, decompiled.javaSource().contains("Builtins.listToArray("), decompiled.javaSource());
            assertEquals(promoted, decompiled.javaSource().contains("Builtins.indexArray("), decompiled.javaSource());
            if (promoted) assertTrue(decompiled.javaSource().indexOf("Builtins.indexArray(") < decompiled.javaSource().lastIndexOf("Builtins.indexArray("), decompiled.javaSource());
        }
    }

    private static int countBuiltin(Term term, DefaultFun fun) {
        return count(term, t -> t instanceof Term.Builtin b && b.fun() == fun);
    }

    private static int count(Term term, Predicate<Term> match) {
        int here = match.test(term) ? 1 : 0;
        return here + switch (term) {
            case Term.Apply a -> count(a.function(), match) + count(a.argument(), match);
            case Term.Lam l -> count(l.body(), match);
            case Term.Force f -> count(f.term(), match);
            case Term.Delay d -> count(d.term(), match);
            case Term.Constr c -> c.fields().stream().mapToInt(f -> count(f, match)).sum();
            case Term.Case c -> count(c.scrutinee(), match) + c.branches().stream().mapToInt(b -> count(b, match)).sum();
            default -> 0;
        };
    }
}
