package com.bloxbean.cardano.julc.vm.java.builtins;

import com.bloxbean.cardano.julc.core.BuiltinSemantics;
import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.core.DefaultUni;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.vm.ExBudget;
import com.bloxbean.cardano.julc.vm.JulcVm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards {@link BuiltinSemantics} (julc-core) against drift from the VM's
 * authoritative {@link BuiltinTable}. The compiler's optimizer relies on
 * BuiltinSemantics for soundness decisions — a wrong force count or a wrong
 * totality claim there is a miscompilation risk, so:
 * <ul>
 *   <li>every {@link DefaultFun} must have a BuiltinSemantics entry whose
 *       type/value arity exactly matches the VM signature, and</li>
 *   <li>every builtin claimed <b>total</b> must actually execute successfully,
 *       without emitting traces, on sample constants of its declared argument
 *       types (two sample pools: typical and edge values).</li>
 * </ul>
 */
class BuiltinSemanticsCrossCheckTest {

    static final ExBudget BUDGET = new ExBudget(10_000_000_000L, 1_000_000_000L);

    @ParameterizedTest
    @EnumSource(DefaultFun.class)
    void semanticsMatchVmSignature(DefaultFun fun) {
        var sig = BuiltinSemantics.find(fun);
        assertNotNull(sig, "BuiltinSemantics has no entry for " + fun
                + " — add one (conservatively partial) so the optimizer knows its arities");

        var vmSig = BuiltinTable.getSignature(fun);
        assertEquals(vmSig.forceCount(), sig.typeArity(),
                "type arity (force count) mismatch for " + fun);
        assertEquals(vmSig.arity(), sig.valueArity(),
                "value arity mismatch for " + fun);
    }

    @Test
    void everyTotalBuiltinExecutesSuccessfullyOnTypicalValues() {
        assertTotalBuiltinsSucceed(SamplePool.TYPICAL);
    }

    @Test
    void everyTotalBuiltinExecutesSuccessfullyOnEdgeValues() {
        assertTotalBuiltinsSucceed(SamplePool.EDGE);
    }

    private void assertTotalBuiltinsSucceed(SamplePool pool) {
        var vm = JulcVm.create("Java");
        var failures = new ArrayList<String>();
        for (var fun : DefaultFun.values()) {
            var sig = BuiltinSemantics.find(fun);
            if (sig == null || !sig.total()) continue;

            Term term = Term.builtin(fun);
            for (int i = 0; i < sig.typeArity(); i++) {
                term = Term.force(term);
            }
            for (var argType : sig.argTypes()) {
                term = Term.apply(term, Term.const_(pool.sample(argType)));
            }
            var result = vm.evaluate(Program.plutusV3(term), BUDGET);
            if (!result.isSuccess()) {
                failures.add(fun + " (" + pool + "): " + result);
            } else if (!result.traces().isEmpty()) {
                failures.add(fun + " (" + pool + "): emitted traces " + result.traces()
                        + " — a log-emitting builtin must not be classified total");
            }
        }
        assertTrue(failures.isEmpty(),
                "builtins claimed total by BuiltinSemantics failed to execute:\n"
                        + String.join("\n", failures));
    }

    /**
     * Sample constants per {@link BuiltinSemantics.ArgType}. TYPICAL uses
     * ordinary values; EDGE uses empty/negative/boundary values that a wrongly
     * classified builtin (clamping vs. erroring) would trip over.
     */
    enum SamplePool {
        TYPICAL {
            @Override
            Constant sample(BuiltinSemantics.ArgType t) {
                return switch (t) {
                    case INTEGER -> Constant.integer(7);
                    case CONSTR_TAG -> Constant.integer(3);
                    case BYTESTRING -> Constant.byteString(new byte[]{1, 2, 3});
                    case STRING -> Constant.string("julc");
                    case BOOL -> Constant.bool(true);
                    case UNIT -> Constant.unit();
                    case DATA -> Constant.data(PlutusData.integer(42));
                    case LIST -> new Constant.ListConst(DefaultUni.INTEGER,
                            List.of(Constant.integer(1), Constant.integer(2)));
                    case LIST_DATA -> new Constant.ListConst(DefaultUni.DATA,
                            List.of(Constant.data(PlutusData.integer(1))));
                    case LIST_PAIR_DATA -> new Constant.ListConst(
                            DefaultUni.pairOf(DefaultUni.DATA, DefaultUni.DATA),
                            List.of(new Constant.PairConst(
                                    Constant.data(PlutusData.integer(1)),
                                    Constant.data(PlutusData.bytes(new byte[]{2})))));
                    case PAIR -> new Constant.PairConst(
                            Constant.data(PlutusData.integer(1)),
                            Constant.data(PlutusData.bytes(new byte[]{1})));
                    case ANY -> Constant.unit();
                };
            }
        },
        EDGE {
            @Override
            Constant sample(BuiltinSemantics.ArgType t) {
                return switch (t) {
                    case INTEGER -> Constant.integer(java.math.BigInteger.valueOf(-1234567890123L));
                    case CONSTR_TAG -> Constant.integer(0);
                    case BYTESTRING -> Constant.byteString(new byte[0]);
                    case STRING -> Constant.string("");
                    case BOOL -> Constant.bool(false);
                    case UNIT -> Constant.unit();
                    case DATA -> Constant.data(PlutusData.list());
                    case LIST -> new Constant.ListConst(DefaultUni.INTEGER, List.of());
                    case LIST_DATA -> new Constant.ListConst(DefaultUni.DATA, List.of());
                    case LIST_PAIR_DATA -> new Constant.ListConst(
                            DefaultUni.pairOf(DefaultUni.DATA, DefaultUni.DATA), List.of());
                    case PAIR -> new Constant.PairConst(Constant.integer(0), Constant.integer(0));
                    case ANY -> Constant.string("");
                };
            }
        };

        abstract Constant sample(BuiltinSemantics.ArgType t);
    }
}
