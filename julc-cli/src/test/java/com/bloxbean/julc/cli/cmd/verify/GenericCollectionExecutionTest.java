package com.bloxbean.julc.cli.cmd.verify;

import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import com.bloxbean.cardano.julc.vm.JulcVm;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact-VM controls for the contract-data shapes projected by schema 4. */
class GenericCollectionExecutionTest {
    @Test
    void committedFixtureAcceptsCanonicalNestedCollectionsAndRejectsMalformedData()
            throws Exception {
        String source = Files.readString(Path.of("..",
                "verification/e4f/fixtures/contracts/src/AuthorizedCollectionGate.java"));
        var compiled = new JulcCompiler(StdlibRegistry.defaultRegistry()).compile(source);
        byte[] owner = "JULC_VERIFY_OWNER_000000001".getBytes(StandardCharsets.UTF_8);
        byte[] key = new byte[]{1, 2, 3};
        PlutusData redeemer = PlutusData.constr(0, PlutusData.bytes(key));

        PlutusData duplicateMap = PlutusData.map(
                new PlutusData.Pair(PlutusData.bytes(key), PlutusData.integer(10)),
                new PlutusData.Pair(PlutusData.bytes(key), PlutusData.integer(20)));
        PlutusData config = PlutusData.constr(0,
                PlutusData.bytes(owner),
                PlutusData.constr(0, PlutusData.integer(5)),
                PlutusData.list(PlutusData.integer(5), PlutusData.integer(8)),
                duplicateMap);
        PlutusData datum = PlutusData.constr(0, config);

        assertTrue(evaluate(compiled.program(), spendingContext(datum, redeemer, owner)),
                "Canonical nested option/list/map data, including duplicate keys, must decode");
        assertFalse(evaluate(compiled.program(), spendingContext(datum, redeemer, new byte[28])),
                "The fixture's signer condition must remain observable after strict decoding");

        PlutusData wrongConfigTag = PlutusData.constr(0, PlutusData.constr(1,
                PlutusData.bytes(owner), PlutusData.constr(1), PlutusData.list(),
                PlutusData.map()));
        assertFalse(evaluate(compiled.program(),
                spendingContext(wrongConfigTag, redeemer, owner)),
                "A nested record with the wrong constructor tag must reject");

        PlutusData malformedOption = PlutusData.constr(0, PlutusData.constr(0,
                PlutusData.bytes(owner), PlutusData.constr(2), PlutusData.list(),
                PlutusData.map()));
        assertFalse(evaluate(compiled.program(),
                spendingContext(malformedOption, redeemer, owner)),
                "An optional with an unknown constructor must reject");

        PlutusData malformedList = PlutusData.constr(0, PlutusData.constr(0,
                PlutusData.bytes(owner), PlutusData.constr(1), PlutusData.map(),
                PlutusData.map()));
        assertFalse(evaluate(compiled.program(),
                spendingContext(malformedList, redeemer, owner)),
                "A Data.Map cannot decode as the declared list");

        PlutusData malformedMap = PlutusData.constr(0, PlutusData.constr(0,
                PlutusData.bytes(owner), PlutusData.constr(1), PlutusData.list(),
                PlutusData.list()));
        assertFalse(evaluate(compiled.program(),
                spendingContext(malformedMap, redeemer, owner)),
                "A Data.List cannot decode as the declared association map");

        assertFalse(evaluate(compiled.program(),
                spendingContext(datum, PlutusData.constr(2), owner)),
                "An unknown sealed-variant constructor must reject");
    }

    private static boolean evaluate(
            com.bloxbean.cardano.julc.core.Program program, PlutusData context) {
        return JulcVm.create().evaluateWithArgs(program, List.of(context)).isSuccess();
    }

    private static PlutusData spendingContext(
            PlutusData datum, PlutusData redeemer, byte[] signer) {
        PlutusData txInfo = PlutusData.constr(0,
                PlutusData.list(), PlutusData.list(), PlutusData.list(),
                PlutusData.integer(0), PlutusData.map(), PlutusData.list(),
                PlutusData.map(), alwaysInterval(),
                PlutusData.list(PlutusData.bytes(signer)),
                PlutusData.map(), PlutusData.map(), PlutusData.bytes(new byte[32]),
                PlutusData.map(), PlutusData.list(), PlutusData.constr(1),
                PlutusData.constr(1));
        PlutusData outRef = PlutusData.constr(0,
                PlutusData.bytes(new byte[32]), PlutusData.integer(0));
        PlutusData scriptInfo = PlutusData.constr(1, outRef,
                PlutusData.constr(0, datum));
        return PlutusData.constr(0, txInfo, redeemer, scriptInfo);
    }

    private static PlutusData alwaysInterval() {
        return PlutusData.constr(0,
                PlutusData.constr(0, PlutusData.constr(0), PlutusData.constr(1)),
                PlutusData.constr(0, PlutusData.constr(2), PlutusData.constr(1)));
    }
}
