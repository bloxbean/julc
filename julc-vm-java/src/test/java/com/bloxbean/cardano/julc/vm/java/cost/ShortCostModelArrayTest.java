package com.bloxbean.cardano.julc.vm.java.cost;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.vm.ExBudget;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;
import com.bloxbean.cardano.julc.vm.java.CekValue;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compatibility tests for Plutus {@code CMTooFewParamsWarn}: known schemas
 * preserve their supplied prefix and pad missing names with {@code maxBound}.
 */
class ShortCostModelArrayTest {

    private record Schema(PlutusLanguage language, int protocol, int count) {
    }

    private static final List<Schema> SCHEMAS = List.of(
            new Schema(PlutusLanguage.PLUTUS_V1, 5, 166),
            new Schema(PlutusLanguage.PLUTUS_V1, 9, 166),
            new Schema(PlutusLanguage.PLUTUS_V1, 11, 332),
            new Schema(PlutusLanguage.PLUTUS_V2, 7, 175),
            new Schema(PlutusLanguage.PLUTUS_V2, 9, 175),
            new Schema(PlutusLanguage.PLUTUS_V2, 10, 185),
            new Schema(PlutusLanguage.PLUTUS_V2, 11, 332),
            new Schema(PlutusLanguage.PLUTUS_V3, 9, 251),
            new Schema(PlutusLanguage.PLUTUS_V3, 10, 297),
            new Schema(PlutusLanguage.PLUTUS_V3, 11, 350));

    @Test
    void missingOneSeveralAndAllParametersMatchExplicitHaskellPadding() {
        for (var schema : SCHEMAS) {
            for (int missing : List.of(1, 7, schema.count())) {
                int actual = schema.count() - missing;
                long[] supplied = indexedValues(actual);
                long[] explicitlyPadded = Arrays.copyOf(supplied, schema.count());
                Arrays.fill(explicitlyPadded, actual, schema.count(), Long.MAX_VALUE);

                var incomplete = parse(supplied, schema);
                var nodeEquivalent = parse(explicitlyPadded, schema);

                var warning = assertInstanceOf(
                        CostModelParser.TooFewParametersWarning.class,
                        assertSingleWarning(incomplete, schema));
                assertEquals(schema.language(), warning.language(), schema.toString());
                assertEquals(schema.protocol(), warning.protocolMajorVersion(), schema.toString());
                assertEquals(schema.count(), warning.expected(), schema.toString());
                assertEquals(actual, warning.actual(), schema.toString());
                assertTrue(warning.message().contains("Long.MAX_VALUE"), schema.toString());

                assertTrue(nodeEquivalent.warnings().isEmpty(), schema.toString());
                assertEquals(nodeEquivalent.machineCosts(), incomplete.machineCosts(), schema.toString());
                assertArrayEquals(explicitlyPadded, serialize(incomplete, schema), schema.toString());
                assertArrayEquals(serialize(nodeEquivalent, schema),
                        serialize(incomplete, schema), schema.toString());
            }
        }
    }

    @Test
    void exactSchemasDoNotWarnAndWarningsAreImmutable() {
        for (var schema : SCHEMAS) {
            var exact = parse(indexedValues(schema.count()), schema);
            assertTrue(exact.warnings().isEmpty(), schema.toString());

            var shortModel = parse(new long[schema.count() - 1], schema);
            assertThrows(UnsupportedOperationException.class, () ->
                    shortModel.warnings().add(new CostModelParser.TooFewParametersWarning(
                            schema.language(), schema.protocol(), schema.count(), 0)));
        }
    }

    @Test
    void paddedMachineAndBuiltinPricesExhaustARealisticBudget() {
        var args = List.<CekValue>of(
                new CekValue.VCon(Constant.integer(1)),
                new CekValue.VCon(Constant.integer(2)));
        var budget = new ExBudget(10_000_000, 10_000_000);

        for (var schema : SCHEMAS) {
            var parsed = parse(new long[0], schema);
            var addCost = parsed.builtinCostModel().get(DefaultFun.AddInteger);
            assertNotNull(addCost, schema.toString());
            assertEquals(Long.MAX_VALUE, addCost.cpu().apply(1, 1), schema.toString());
            assertEquals(Long.MAX_VALUE, addCost.mem().apply(1, 1), schema.toString());

            var machineTracker = new CostTracker(
                    parsed.machineCosts(), parsed.builtinCostModel(), budget);
            assertThrows(BudgetExhaustedException.class, () ->
                    machineTracker.chargeMachineStep(MachineCosts.StepKind.STARTUP),
                    "machine " + schema);

            var builtinTracker = new CostTracker(
                    parsed.machineCosts(), parsed.builtinCostModel(), budget);
            assertThrows(BudgetExhaustedException.class, () ->
                    builtinTracker.chargeBuiltin(DefaultFun.AddInteger, args),
                    "builtin " + schema);
        }
    }

    @Test
    void extraParametersAndUnknownProfilesStillFail() {
        for (var schema : SCHEMAS) {
            assertThrows(IllegalArgumentException.class, () ->
                    parse(new long[schema.count() + 1], schema), schema.toString());
        }

        assertThrows(IllegalArgumentException.class, () -> CostModelParser.parse(
                new long[0], PlutusLanguage.PLUTUS_V1, 12, 0));
        assertThrows(IllegalArgumentException.class, () -> CostModelParser.parse(
                new long[0], PlutusLanguage.PLUTUS_V2, 12, 0));
        assertThrows(IllegalArgumentException.class, () -> CostModelParser.parse(
                new long[0], PlutusLanguage.PLUTUS_V3, 12, 0));
    }

    private static CostModelParser.CostModelParseWarning assertSingleWarning(
            CostModelParser.ParsedCostModel parsed, Schema schema) {
        assertEquals(1, parsed.warnings().size(), schema.toString());
        return parsed.warnings().getFirst();
    }

    private static CostModelParser.ParsedCostModel parse(long[] values, Schema schema) {
        return CostModelParser.parse(
                values, schema.language(), schema.protocol(), 0);
    }

    private static long[] serialize(
            CostModelParser.ParsedCostModel parsed, Schema schema) {
        return CostModelParser.toFlatArray(
                parsed.machineCosts(), parsed.builtinCostModel(),
                schema.language(), schema.protocol());
    }

    private static long[] indexedValues(int count) {
        long[] values = new long[count];
        for (int i = 0; i < values.length; i++) {
            values[i] = 10_000L + i;
        }
        return values;
    }
}
