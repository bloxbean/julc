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
 * ADR-042 (O8): the shared conversion is an ordinary strict binding, so the decompiler needs
 * no new recognizer. At BASELINE the eta-reduced wrapper is bound once as the bare builtin and
 * called twice; at the safe profile the program applies {@code unValueData} once and the
 * decompiler renders that application as a local variable binding.
 */
class ValueConversionSharingDecompileTest {

    private static final Pattern SHARED_BINDING = Pattern.compile("var \\w+ = Builtins\\.unValueData\\(\\w+\\);");

    @Test
    void sharedConversionDecompilesAsAnOrdinaryBinding() {
        var source = """
                import org.julclang.core.PlutusData;
                import org.julclang.stdlib.lib.NativeValueLib;
                import java.math.BigInteger;
                class Repeated {
                    static BigInteger repeated(PlutusData data, byte[] policy, byte[] token) {
                        return NativeValueLib.lookupCoin(policy, token, NativeValueLib.fromData(data))
                                .add(NativeValueLib.lookupCoin(policy, token, NativeValueLib.fromData(data)));
                    }
                }
                """;
        // Applications of the builtin to an argument: BASELINE calls the bound builtin
        // variable twice (no direct application); PV11_SAFE applies it once, in the binding.
        var expectedApplications = List.of(0, 1);
        var levels = List.of(OptimizationLevel.BASELINE, OptimizationLevel.PV11_SAFE);
        for (int i = 0; i < levels.size(); i++) {
            var level = levels.get(i);
            var compiled = new JulcCompiler(StdlibRegistry.defaultRegistry(),
                    new CompilerOptions().setOptimizationLevel(level)).compileMethod(source, "repeated");
            assertFalse(compiled.hasErrors(), compiled.diagnostics().toString());
            var program = UplcFlatDecoder.decodeProgram(UplcFlatEncoder.encodeProgram(compiled.program()));
            assertEquals(1, countBuiltin(program.term(), DefaultFun.UnValueData), level.toString());
            assertEquals(expectedApplications.get(i), countApplications(program.term(), DefaultFun.UnValueData), level.toString());
            var decompiled = JulcDecompiler.decompile(program, DecompileOptions.defaults());
            assertNotNull(decompiled.hir(), level.toString());
            assertNotNull(decompiled.javaSource(), level.toString());
            assertEquals(level == OptimizationLevel.PV11_SAFE,
                    SHARED_BINDING.matcher(decompiled.javaSource()).find(), decompiled.javaSource());
        }
    }

    private static int countBuiltin(Term term, DefaultFun fun) {
        return count(term, t -> t instanceof Term.Builtin b && b.fun() == fun);
    }

    private static int countApplications(Term term, DefaultFun fun) {
        return count(term, t -> t instanceof Term.Apply a && a.function() instanceof Term.Builtin b && b.fun() == fun);
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
