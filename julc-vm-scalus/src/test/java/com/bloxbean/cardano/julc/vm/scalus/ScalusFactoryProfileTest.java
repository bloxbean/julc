package com.bloxbean.cardano.julc.vm.scalus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import scalus.cardano.ledger.CardanoInfo;
import scalus.cardano.ledger.Language;
import scalus.cardano.ledger.MajorProtocolVersion;
import scalus.uplc.eval.MachineParams;
import scalus.uplc.eval.PlutusVM;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pins the target mapping and bundled-parameter provenance exposed by Scalus 1.1.0. */
class ScalusFactoryProfileTest {

    @ParameterizedTest(name = "{0}/PV{1} selects variant {2}")
    @MethodSource("factoryProfiles")
    void explicitFactoriesSelectLanguageProtocolAndVariant(
            Language language, int protocolMajor, String expectedVariant) {
        var protocol = new MajorProtocolVersion(protocolMajor);
        var params = MachineParams.defaultParamsFor(language, protocol);

        var vm = createVm(language, params, protocol);

        assertEquals(language, vm.language());
        assertEquals(protocolMajor, vm.protocolVersion().version());
        assertEquals(expectedVariant, vm.semanticVariant().toString());
    }

    @ParameterizedTest(name = "{0}/PV{1} default params come from bundled mainnet model")
    @MethodSource("languagesAndProtocols")
    void defaultParamsForUsesBundledMainnetCostModels(Language language, int protocolMajor) {
        var protocol = new MajorProtocolVersion(protocolMajor);
        var mainnetCostModels = CardanoInfo.mainnet().protocolParams().costModels();

        var direct = MachineParams.defaultParamsFor(language, protocol);
        var reconstructed = MachineParams.fromCostModels(mainnetCostModels, language, protocol);

        assertEquals(reconstructed, direct);
    }

    @Test
    void bundledMainnetAndNoArgV3FactoryArePv11VariantE() {
        var mainnet = CardanoInfo.mainnet();
        var vm = PlutusVM.makePlutusV3VM();

        assertEquals(11, mainnet.majorProtocolVersion().version());
        assertEquals(Language.PlutusV3, vm.language());
        assertEquals(11, vm.protocolVersion().version());
        assertEquals("E", vm.semanticVariant().toString());
        assertEquals(MachineParams.defaultParamsFor(
                Language.PlutusV3, mainnet.majorProtocolVersion()), vm.machineParams());
    }

    private static Stream<Arguments> factoryProfiles() {
        return Stream.of(
                Arguments.of(Language.PlutusV1, 10, "B"),
                Arguments.of(Language.PlutusV1, 11, "D"),
                Arguments.of(Language.PlutusV2, 10, "B"),
                Arguments.of(Language.PlutusV2, 11, "D"),
                Arguments.of(Language.PlutusV3, 10, "C"),
                Arguments.of(Language.PlutusV3, 11, "E"));
    }

    private static Stream<Arguments> languagesAndProtocols() {
        return Stream.of(Language.PlutusV1, Language.PlutusV2, Language.PlutusV3)
                .flatMap(language -> Stream.of(10, 11)
                        .map(protocol -> Arguments.of(language, protocol)));
    }

    private PlutusVM createVm(Language language, MachineParams params,
                              MajorProtocolVersion protocol) {
        if (language == Language.PlutusV1) {
            return PlutusVM.makePlutusV1VM(params, protocol);
        }
        if (language == Language.PlutusV2) {
            return PlutusVM.makePlutusV2VM(params, protocol);
        }
        if (language == Language.PlutusV3) {
            return PlutusVM.makePlutusV3VM(params, protocol);
        }
        throw new IllegalArgumentException("Unsupported test language: " + language);
    }
}
