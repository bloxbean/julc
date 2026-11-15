package org.julclang.compiler.depth;

import org.julclang.compiler.backend.BackendException;
import org.julclang.compiler.backend.PirClosure;
import org.julclang.compiler.backend.PirLinker;
import org.julclang.compiler.backend.PirVerifier;
import org.julclang.compiler.codegen.StrictRecordEntrypoint;
import org.julclang.compiler.pir.PirHelpers;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The backend's term walks (verifier, closure check, linker, strict record entrypoint) on terms far deeper than a
 * recursive walk survives, each run on a thread with a 256 KB stack (issue #327, ADR-074). Comparisons run off the
 * small stack.
 */
class BackendDepthTest {

    static final int DEPTH = 50_000;
    static final long STACK = 256 * 1024;

    static <T> T onSmallStack(Supplier<T> work) throws InterruptedException {
        var result = new AtomicReference<T>();
        var failure = new AtomicReference<Throwable>();
        var thread = new Thread(null, () -> {
            try {
                result.set(work.get());
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "small-stack", STACK);
        thread.start();
        thread.join();
        if (failure.get() instanceof StackOverflowError overflow) throw new AssertionError("stack overflow", overflow);
        if (failure.get() instanceof RuntimeException e) throw e;
        if (failure.get() != null) throw new AssertionError(failure.get());
        return result.get();
    }

    // ---- Backend PIR walks ----

    static final PirType INT = new PirType.IntegerType();

    static PirTerm intConst(long value) {
        return new PirTerm.Const(Constant.integer(BigInteger.valueOf(value)));
    }

    /** let x0 = 0 in let x1 = x0 + 1 in ... in bottom, every name read by the next binding. */
    static PirTerm letChain(int depth, PirTerm bottom) {
        var names = new ArrayList<String>();
        for (int i = 0; i < depth; i++) names.add("x" + i);
        PirTerm term = bottom;
        for (int i = depth - 1; i >= 0; i--) {
            PirTerm value = i == 0 ? intConst(0)
                    : PirHelpers.builtinApp2(DefaultFun.AddInteger, new PirTerm.Var(names.get(i - 1), INT), intConst(1));
            term = new PirTerm.Let(names.get(i), value, term);
        }
        return term;
    }

    static PirVerifier verifier() {
        return new PirVerifier(Map.of(), List.of(), List.of(), true);
    }

    @Test
    void theVerifierTypesADeepLetChain() throws InterruptedException {
        var term = letChain(DEPTH, new PirTerm.Var("x" + (DEPTH - 1), INT));
        onSmallStack(() -> {
            verifier().verify("deep", term, INT, Map.of());
            return null;
        });
    }

    @Test
    void theVerifierTypesDeepArgumentAndElseSpines() throws InterruptedException {
        PirTerm sum = intConst(0);
        PirTerm conditional = intConst(0);
        for (int i = 0; i < DEPTH; i++) {
            sum = PirHelpers.builtinApp2(DefaultFun.AddInteger, intConst(i), sum);
            conditional = new PirTerm.IfThenElse(new PirTerm.Const(Constant.bool(i % 2 == 0)), intConst(i), conditional);
        }
        PirTerm sums = sum;
        PirTerm conditionals = conditional;
        onSmallStack(() -> {
            verifier().verify("sum", sums, INT, Map.of());
            verifier().verify("conditional", conditionals, INT, Map.of());
            return null;
        });
    }

    @Test
    void theVerifierReportsAFailureAtTheBottomOfADeepTerm() throws InterruptedException {
        var term = letChain(DEPTH, new PirTerm.Var("missing", INT));
        var failure = assertThrows(BackendException.class,
                () -> onSmallStack(() -> {
                    verifier().verify("deep", term, INT, Map.of());
                    return null;
                }));
        assertTrue(failure.getMessage().contains("unbound symbol missing"), failure.getMessage());
    }

    @Test
    void closureFindsFreeNamesInOrderAtTheBottomOfADeepTerm() throws InterruptedException {
        var bottom = PirHelpers.builtinApp2(DefaultFun.AddInteger, new PirTerm.Var("b", INT), new PirTerm.Var("a", INT));
        var term = letChain(DEPTH, bottom);
        assertEquals(List.of("b", "a"), List.copyOf(onSmallStack(() -> PirClosure.freeVariables(term))));
        assertThrows(IllegalArgumentException.class, () -> onSmallStack(() -> {
            PirClosure.check(term);
            return null;
        }));
        var closed = letChain(DEPTH, new PirTerm.Var("x0", INT));
        onSmallStack(() -> {
            PirClosure.check(closed);
            return null;
        });
    }

    @Test
    void theLinkerOrdersADeepChainOfDefinitions() throws InterruptedException {
        // d0 calls d1, which calls d2, ...: dependencies are bound outside the definitions that use them.
        var fn = new PirType.FunType(INT, INT);
        var definitions = new LinkedHashMap<String, PirTerm>();
        for (int i = 0; i < DEPTH; i++) {
            PirTerm body = i == DEPTH - 1 ? new PirTerm.Var("p", INT)
                    : new PirTerm.App(new PirTerm.Var("d" + (i + 1), fn), new PirTerm.Var("p", INT));
            definitions.put("d" + i, new PirTerm.Lam("p", INT, body));
        }
        var linked = onSmallStack(() -> PirLinker.link(definitions, new PirTerm.Var("d0", fn)));
        var names = new ArrayList<String>();
        PirTerm cursor = linked;
        while (cursor instanceof PirTerm.Let let) {
            names.add(let.name());
            cursor = let.body();
        }
        assertEquals(DEPTH, names.size());
        assertEquals("d" + (DEPTH - 1), names.getFirst());
        assertEquals("d0", names.getLast());
        assertEquals(new PirTerm.Var("d0", fn), cursor);
    }

    @Test
    void theStrictRecordEntrypointReplacesAProjectionAtTheBottomOfADeepBody() throws InterruptedException {
        var record = new PirType.RecordType("R", List.of(new PirType.Field("n", INT)));
        var data = new PirTerm.Var("r", record);
        var projection = PirHelpers.wrapDecode(new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList),
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.SndPair),
                        new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnConstrData), data))), INT);
        var function = new PirTerm.Lam("r", record, letChain(DEPTH, projection));
        var rebuilt = new int[1];
        var rebuilds = new StrictRecordEntrypoint.Rebuilds() {
            @Override
            public void rebuilt(PirTerm original, PirTerm copy) {
                rebuilt[0]++;
            }

            @Override
            public void parameterRead(PirTerm.Var read) {
            }
        };
        var result = onSmallStack(() -> StrictRecordEntrypoint.transform(function,
                List.of(new StrictRecordEntrypoint.Root("r", record)), Map.of(), rebuilds));
        // Every let of the chain and the function's lambda are rebuilt.
        assertEquals(DEPTH + 1, rebuilt[0]);
        var cached = new PirTerm.Var("#__boundary-field-r-0", INT);
        assertTrue(contains(result.function(), cached), "the projection is read from its cached field");
        assertFalse(contains(result.function(), projection), "no projection is left");
    }

    static boolean contains(PirTerm term, PirTerm target) {
        return PirHelpers.anyTerm(term, inner -> inner.equals(target));
    }
}
