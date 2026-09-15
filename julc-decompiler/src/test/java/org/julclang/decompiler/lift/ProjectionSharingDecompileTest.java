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
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * ADR-044 (O15): a shared projection is an ordinary strict binding, so the decompiler needs no
 * new recognizer. At BASELINE the program decodes the record three times; at the safe profile it
 * projects once and the decompiler still lifts the program and renders the binding as a local.
 */
class ProjectionSharingDecompileTest {

    @Test
    void sharedProjectionDecompilesAsAnOrdinaryBinding() {
        var source = """
                import org.julclang.core.PlutusData;
                import java.math.BigInteger;
                class Repeated {
                    record Box(BigInteger amount, byte[] owner) {}
                    static BigInteger repeated(Box b, BigInteger limit) {
                        if (b.amount().compareTo(limit) > 0) {
                            return b.amount().subtract(limit);
                        }
                        return b.amount().add(limit);
                    }
                }
                """;
        var expectedProjections = List.of(4, 1);
        var levels = List.of(OptimizationLevel.BASELINE, OptimizationLevel.PV11_SAFE);
        for (int i = 0; i < levels.size(); i++) {
            var level = levels.get(i);
            var compiled = new JulcCompiler(StdlibRegistry.defaultRegistry(),
                    new CompilerOptions().setOptimizationLevel(level)).compileMethod(source, "repeated");
            assertFalse(compiled.hasErrors(), compiled.diagnostics().toString());
            var program = UplcFlatDecoder.decodeProgram(UplcFlatEncoder.encodeProgram(compiled.program()));
            assertEquals(expectedProjections.get(i), countBuiltin(program.term(), DefaultFun.UnConstrData), level.toString());
            var decompiled = JulcDecompiler.decompile(program, DecompileOptions.defaults());
            assertNotNull(decompiled.hir(), level.toString());
            assertNotNull(decompiled.javaSource(), level.toString());
            // BASELINE inlines the projection at every site; the safe profile binds it once as a local.
            assertEquals(level == OptimizationLevel.PV11_SAFE,
                    SHARED_BINDING.matcher(decompiled.javaSource()).find(), decompiled.javaSource());
        }
    }

    private static final Pattern SHARED_BINDING =
            Pattern.compile("var \\w+ = Builtins\\.unIData\\(Builtins\\.headList\\(");

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
            case Term.Case c -> count(c.scrutinee(), match) + c.branches().stream().mapToInt(b -> count(b, match)).sum();
            case Term.Constr c -> c.fields().stream().mapToInt(f -> count(f, match)).sum();
            default -> 0;
        };
    }
}
