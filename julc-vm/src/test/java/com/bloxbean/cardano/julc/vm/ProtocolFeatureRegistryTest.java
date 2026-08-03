package com.bloxbean.cardano.julc.vm;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.Term;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolFeatureRegistryTest {

    @Test
    void semanticsVariantsMatchPinnedHaskellTable() {
        assertVariant(PlutusLanguage.PLUTUS_V1, 8, BuiltinSemanticsVariant.A);
        assertVariant(PlutusLanguage.PLUTUS_V2, 8, BuiltinSemanticsVariant.A);
        assertVariant(PlutusLanguage.PLUTUS_V1, 9, BuiltinSemanticsVariant.B);
        assertVariant(PlutusLanguage.PLUTUS_V2, 10, BuiltinSemanticsVariant.B);
        assertVariant(PlutusLanguage.PLUTUS_V3, 9, BuiltinSemanticsVariant.C);
        assertVariant(PlutusLanguage.PLUTUS_V3, 10, BuiltinSemanticsVariant.C);
        assertVariant(PlutusLanguage.PLUTUS_V1, 11, BuiltinSemanticsVariant.D);
        assertVariant(PlutusLanguage.PLUTUS_V2, 11, BuiltinSemanticsVariant.D);
        assertVariant(PlutusLanguage.PLUTUS_V3, 11, BuiltinSemanticsVariant.E);
    }

    @Test
    void availabilityMatchesPinnedBuiltinBatches() {
        assertBuiltinCount(PlutusLanguage.PLUTUS_V1, 10, 51);
        assertBuiltinCount(PlutusLanguage.PLUTUS_V1, 11, 101);
        assertBuiltinCount(PlutusLanguage.PLUTUS_V2, 9, 54);
        assertBuiltinCount(PlutusLanguage.PLUTUS_V2, 10, 56);
        assertBuiltinCount(PlutusLanguage.PLUTUS_V2, 11, 101);
        assertBuiltinCount(PlutusLanguage.PLUTUS_V3, 9, 75);
        assertBuiltinCount(PlutusLanguage.PLUTUS_V3, 10, 87);
        assertBuiltinCount(PlutusLanguage.PLUTUS_V3, 11, 101);
    }

    @Test
    void v2Pv10EnablesOnlyBatch4bFromTheV3EraBatches() {
        var profile = profile(PlutusLanguage.PLUTUS_V2, 10);
        assertTrue(profile.isBuiltinAvailable(DefaultFun.IntegerToByteString));
        assertTrue(profile.isBuiltinAvailable(DefaultFun.ByteStringToInteger));
        assertFalse(profile.isBuiltinAvailable(DefaultFun.Bls12_381_G1_add));
        assertFalse(profile.isBuiltinAvailable(DefaultFun.AndByteString));
    }

    @Test
    void batch6IsPv11OnlyAndMultiIndexArrayIsFutureOnly() {
        assertFalse(profile(PlutusLanguage.PLUTUS_V3, 10)
                .isBuiltinAvailable(DefaultFun.ExpModInteger));
        assertTrue(profile(PlutusLanguage.PLUTUS_V3, 11)
                .isBuiltinAvailable(DefaultFun.ExpModInteger));

        for (var language : PlutusLanguage.values()) {
            assertFalse(profile(language, 11).isBuiltinAvailable(DefaultFun.MultiIndexArray));
        }
    }

    @Test
    void pv11Batch6ExactlyMatchesPinnedHaskellBaseline() {
        // PlutusLedgerApi.Common.Versions.batch6 at f92b7d7d8.
        var expected = Set.of(
                DefaultFun.ExpModInteger,
                DefaultFun.DropList,
                DefaultFun.LengthOfArray,
                DefaultFun.ListToArray,
                DefaultFun.IndexArray,
                DefaultFun.Bls12_381_G1_multiScalarMul,
                DefaultFun.Bls12_381_G2_multiScalarMul,
                DefaultFun.InsertCoin,
                DefaultFun.LookupCoin,
                DefaultFun.UnionValue,
                DefaultFun.ValueContains,
                DefaultFun.ValueData,
                DefaultFun.UnValueData,
                DefaultFun.ScaleValue);

        var newlyAvailable = EnumSet.copyOf(
                profile(PlutusLanguage.PLUTUS_V3, 11).availableBuiltins());
        newlyAvailable.removeAll(
                profile(PlutusLanguage.PLUTUS_V3, 10).availableBuiltins());

        assertEquals(expected, newlyAvailable);
        assertEquals(EnumSet.range(DefaultFun.ExpModInteger, DefaultFun.ScaleValue), newlyAvailable);
        assertFalse(newlyAvailable.contains(DefaultFun.MultiIndexArray));
    }

    @Test
    void uplcVersionsMatchLanguageAndProtocol() {
        assertEquals(Set.of(UplcVersion.V1_0_0),
                profile(PlutusLanguage.PLUTUS_V1, 10).availableUplcVersions());
        assertEquals(Set.of(UplcVersion.V1_0_0, UplcVersion.V1_1_0),
                profile(PlutusLanguage.PLUTUS_V1, 11).availableUplcVersions());
        assertEquals(Set.of(UplcVersion.V1_0_0, UplcVersion.V1_1_0),
                profile(PlutusLanguage.PLUTUS_V3, 9).availableUplcVersions());
    }

    @Test
    void programVersionValidationUsesTheResolvedProfile() {
        var term = Term.const_(Constant.unit());
        var v1Pv10 = profile(PlutusLanguage.PLUTUS_V1, 10);
        v1Pv10.validateProgramVersion(new Program(1, 0, 0, term));
        assertThrows(UnsupportedLedgerTargetException.class,
                () -> v1Pv10.validateProgramVersion(new Program(1, 1, 0, term)));
        profile(PlutusLanguage.PLUTUS_V1, 11)
                .validateProgramVersion(new Program(1, 1, 0, term));
    }

    @Test
    void pv11SelectsDecodeLimitsAndCaseOnBuiltin() {
        var pv10 = profile(PlutusLanguage.PLUTUS_V3, 10);
        assertEquals(DecodeLimits.UNBOUNDED, pv10.decodeLimits());
        assertFalse(pv10.caseOnBuiltinConstants());

        var pv11 = profile(PlutusLanguage.PLUTUS_V3, 11);
        assertEquals(new DecodeLimits(32, 1024), pv11.decodeLimits());
        assertTrue(pv11.caseOnBuiltinConstants());
    }

    @Test
    void exactKnownSchemaIsPartOfEveryProfile() {
        assertEquals(CostModelSchema.PLUTUS_V1_LEGACY,
                profile(PlutusLanguage.PLUTUS_V1, 10).costModelSchema());
        assertEquals(CostModelSchema.PLUTUS_V1_PV11,
                profile(PlutusLanguage.PLUTUS_V1, 11).costModelSchema());
        assertEquals(CostModelSchema.PLUTUS_V2_PV10,
                profile(PlutusLanguage.PLUTUS_V2, 10).costModelSchema());
        assertEquals(CostModelSchema.PLUTUS_V2_PV11,
                profile(PlutusLanguage.PLUTUS_V2, 11).costModelSchema());
        assertEquals(CostModelSchema.PLUTUS_V3_PV9,
                profile(PlutusLanguage.PLUTUS_V3, 9).costModelSchema());
        assertEquals(CostModelSchema.PLUTUS_V3_PV10,
                profile(PlutusLanguage.PLUTUS_V3, 10).costModelSchema());
        assertEquals(CostModelSchema.PLUTUS_V3_PV11,
                profile(PlutusLanguage.PLUTUS_V3, 11).costModelSchema());
    }

    @Test
    void rejectsUnavailableLanguagesAndFutureProtocols() {
        assertThrows(UnsupportedLedgerTargetException.class,
                () -> profile(PlutusLanguage.PLUTUS_V3, 8));
        assertThrows(UnsupportedLedgerTargetException.class,
                () -> profile(PlutusLanguage.PLUTUS_V1, 12));
    }

    @Test
    void protocolAndTargetAreValidatedAndImmutable() {
        assertThrows(IllegalArgumentException.class, () -> new ProtocolVersion(-1, 0));
        assertThrows(NullPointerException.class,
                () -> new LedgerEvaluationTarget(null, ProtocolVersion.PV10));
        assertEquals("PLUTUS_V3/PV11.0",
                LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3).toString());
    }

    private static ProtocolFeatureProfile profile(PlutusLanguage language, int protocol) {
        return ProtocolFeatureRegistry.resolve(new LedgerEvaluationTarget(
                language, new ProtocolVersion(protocol, 0)));
    }

    private static void assertVariant(
            PlutusLanguage language, int protocol, BuiltinSemanticsVariant expected) {
        assertEquals(expected, profile(language, protocol).semanticsVariant());
    }

    private static void assertBuiltinCount(PlutusLanguage language, int protocol, int expected) {
        assertEquals(expected, profile(language, protocol).availableBuiltins().size());
    }
}
