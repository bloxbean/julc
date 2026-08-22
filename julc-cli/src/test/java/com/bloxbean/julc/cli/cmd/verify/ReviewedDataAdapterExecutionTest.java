package com.bloxbean.julc.cli.cmd.verify;

import com.bloxbean.cardano.julc.compiler.CompileResult;
import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import com.bloxbean.cardano.julc.vm.JulcVm;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewedDataAdapterExecutionTest {
    private static final byte[] AUTHORITY = new byte[28];

    static {
        java.util.Arrays.fill(AUTHORITY, (byte) 65);
    }

    @Test
    void committedFixtureReadsExactLedgerEncodingOnTheVm() throws Exception {
        String source = Files.readString(Path.of("..",
                "verification/e4l/fixtures/reviewed-adapters/src/"
                        + "AuthorizedReviewedAdapters.java"));
        CompileResult compiled = new JulcCompiler(StdlibRegistry.defaultRegistry())
                .compile(source);

        assertTrue(evaluate(compiled, boundedRange(10, true, 20, true),
                someInteger(100), none()));
        assertFalse(evaluate(compiled, boundedRange(9, true, 20, true),
                someInteger(100), none()));
        assertFalse(evaluate(compiled, boundedRange(10, false, 20, true),
                someInteger(100), none()));
        assertFalse(evaluate(compiled, boundedRange(10, true, 21, true),
                someInteger(100), none()));
        assertFalse(evaluate(compiled, boundedRange(10, true, 20, true),
                someInteger(99), none()));
        assertFalse(evaluate(compiled, boundedRange(10, true, 20, true),
                someInteger(100), someInteger(1)));

        assertFalse(evaluate(compiled,
                PlutusData.constr(0, finiteBound(10, true)),
                someInteger(100), none()), "A short interval must reject");
        assertFalse(evaluate(compiled, boundedRange(10, true, 20, true),
                PlutusData.constr(0, PlutusData.bytes(new byte[]{1})), none()),
                "A non-integer treasury payload must reject");
        assertFalse(evaluate(compiled, boundedRange(10, true, 20, true),
                PlutusData.constr(1, PlutusData.integer(100)), none()),
                "A trailing field on None must reject");
        assertFalse(evaluate(compiled, boundedRange(10, true, 20, true),
                someInteger(100), PlutusData.integer(0)),
                "A wrong-kind donation must reject");
    }

    private static boolean evaluate(CompileResult compiled, PlutusData range,
                                    PlutusData currentTreasury,
                                    PlutusData treasuryDonation) {
        return JulcVm.create().evaluateWithArgs(compiled.program(),
                List.of(spendingContext(range, currentTreasury, treasuryDonation)))
                .isSuccess();
    }

    private static PlutusData spendingContext(PlutusData range,
                                              PlutusData currentTreasury,
                                              PlutusData treasuryDonation) {
        PlutusData ownRef = PlutusData.constr(0,
                PlutusData.bytes(new byte[32]), PlutusData.integer(0));
        PlutusData txInfo = PlutusData.constr(0,
                PlutusData.list(), PlutusData.list(), PlutusData.list(),
                PlutusData.integer(0), PlutusData.map(), PlutusData.list(),
                PlutusData.map(), range,
                PlutusData.list(PlutusData.bytes(AUTHORITY)),
                PlutusData.map(), PlutusData.map(), PlutusData.bytes(new byte[32]),
                PlutusData.map(), PlutusData.list(), currentTreasury,
                treasuryDonation);
        return PlutusData.constr(0, txInfo, PlutusData.constr(0),
                PlutusData.constr(1, ownRef,
                        PlutusData.constr(0,
                                PlutusData.constr(0, PlutusData.integer(10)))));
    }

    private static PlutusData boundedRange(long from, boolean fromInclusive,
                                           long to, boolean toInclusive) {
        return PlutusData.constr(0, finiteBound(from, fromInclusive),
                finiteBound(to, toInclusive));
    }

    private static PlutusData finiteBound(long value, boolean inclusive) {
        return PlutusData.constr(0,
                PlutusData.constr(1, PlutusData.integer(value)),
                PlutusData.constr(inclusive ? 1 : 0));
    }

    private static PlutusData someInteger(long value) {
        return PlutusData.constr(0, PlutusData.integer(value));
    }

    private static PlutusData none() {
        return PlutusData.constr(1);
    }
}
