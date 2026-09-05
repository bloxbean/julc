package com.bloxbean.cardano.julc.decompiler.lift;

import com.bloxbean.cardano.julc.compiler.*;
import com.bloxbean.cardano.julc.core.*;
import com.bloxbean.cardano.julc.core.flat.UplcFlatEncoder;
import com.bloxbean.cardano.julc.core.flat.UplcFlatDecoder;
import com.bloxbean.cardano.julc.decompiler.*;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ListCaseRecognitionTest {
    @Test
    void freshlyCompiledLoopsRetainClassificationAfterSerialization() {
        String source = """
                import java.math.BigInteger;
                import com.bloxbean.cardano.julc.core.types.JulcList;
                class Sum {
                    static BigInteger sum(JulcList<BigInteger> xs) {
                        BigInteger acc = BigInteger.ZERO;
                        for (BigInteger x : xs) { acc = acc.add(x); }
                        return acc;
                    }
                }
                """;
        for (var level : List.of(OptimizationLevel.NONE, OptimizationLevel.BASELINE,
                OptimizationLevel.PV11_SAFE)) {
            var compiled = new JulcCompiler(StdlibRegistry.defaultRegistry(),
                    new CompilerOptions().setOptimizationLevel(level)).compileMethod(source, "sum");
            assertFalse(compiled.hasErrors(), compiled.diagnostics().toString());
            var program = UplcFlatDecoder.decodeProgram(UplcFlatEncoder.encodeProgram(compiled.program()));
            assertTrue(countForEach(program.term()) > 0, level.toString());
            var decompiled = JulcDecompiler.decompile(program, DecompileOptions.defaults());
            assertNotNull(decompiled.hir());
            assertNotNull(decompiled.javaSource());
        }
    }

    @Test
    void requiresGuardOnSameListAndCorrectBranchShape() {
        Term cons = Term.lam("head", Term.lam("tail", Term.var(2)));
        Term match = Term.case_(Term.var(1), cons, new Term.Error());
        assertEquals(LoopRecognizer.LoopKind.FOR_EACH,
                LoopRecognizer.classifyBody(guard(1, match)));
        for (Term unrelated : List.of(
                match, // no guard
                guard(2, match), // guard of another value
                guard(1, Term.case_(Term.var(1), new Term.Error(), cons)), // reversed branches
                guard(1, Term.case_(Term.var(1), Term.lam("head", Term.var(1)), new Term.Error())),
                guard(1, Term.case_(Term.constr(0), cons, new Term.Error())), // constructor case
                Term.case_(Term.apply(Term.force(Term.builtin(DefaultFun.NullList)), Term.var(1)),
                        Term.const_(Constant.integer(0)), match))) { // match on empty path
            assertEquals(LoopRecognizer.LoopKind.GENERAL_RECURSION,
                    LoopRecognizer.classifyBody(unrelated));
        }
    }

    private static Term guard(int index, Term nonEmpty) {
        return Term.case_(Term.apply(Term.force(Term.builtin(DefaultFun.NullList)), Term.var(index)),
                nonEmpty, Term.const_(Constant.integer(0)));
    }

    private static int countForEach(Term term) {
        var match = LoopRecognizer.match(term);
        int here = match != null && LoopRecognizer.classifyBody(match.recBody())
                == LoopRecognizer.LoopKind.FOR_EACH ? 1 : 0;
        return here + switch (term) {
            case Term.Apply a -> countForEach(a.function()) + countForEach(a.argument());
            case Term.Lam l -> countForEach(l.body());
            case Term.Force f -> countForEach(f.term());
            case Term.Delay d -> countForEach(d.term());
            case Term.Case c -> countForEach(c.scrutinee())
                    + c.branches().stream().mapToInt(ListCaseRecognitionTest::countForEach).sum();
            case Term.Constr c -> c.fields().stream().mapToInt(ListCaseRecognitionTest::countForEach).sum();
            default -> 0;
        };
    }
}
