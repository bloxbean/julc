package org.julclang.playground.scenario;

import org.julclang.playground.model.ScenarioDto;
import org.julclang.playground.model.ScenariosResponse;
import org.julclang.tools.service.ServiceResult;

import java.util.List;
import java.util.Map;

/**
 * Pre-built scenario templates per purpose type.
 */
public final class ScenarioRegistry {

    private static final String DEFAULT_PKH = "01010101010101010101010101010101010101010101010101010101";

    private static final Map<String, List<ScenarioDto>> SCENARIOS = Map.of(
            "SPENDING", List.of(
                    new ScenarioDto(
                            "Signer Check",
                            "Transaction signed by a known public key hash",
                            List.of(DEFAULT_PKH),
                            null, null,
                            Map.of(), Map.of(), null
                    ),
                    new ScenarioDto(
                            "Time-Locked Spend",
                            "Signer check with valid-after time constraint",
                            List.of(DEFAULT_PKH),
                            5000L, null,
                            Map.of(), Map.of(), null
                    ),
                    new ScenarioDto(
                            "Unauthorized Spend (should fail)",
                            "No signers — expect validation failure",
                            List.of(),
                            null, null,
                            Map.of(), Map.of(), null
                    )
            ),
            "MINTING", List.of(
                    new ScenarioDto(
                            "Basic Mint",
                            "Minting with a single authorized signer",
                            List.of(DEFAULT_PKH),
                            null, null,
                            Map.of(), Map.of(), 0
                    ),
                    new ScenarioDto(
                            "Multi-Sig Mint",
                            "Minting requiring multiple signers",
                            List.of(DEFAULT_PKH,
                                    "02020202020202020202020202020202020202020202020202020202"),
                            null, null,
                            Map.of(), Map.of(), 0
                    )
            ),
            "WITHDRAW", List.of(
                    new ScenarioDto(
                            "Basic Withdrawal",
                            "Reward withdrawal with signer",
                            List.of(DEFAULT_PKH),
                            null, null,
                            Map.of(), Map.of(), null
                    )
            )
    );

    public static List<ScenarioDto> getScenarios(String purpose) {
        return SCENARIOS.getOrDefault(purpose.toUpperCase(), List.of());
    }

    public static Map<String, List<ScenarioDto>> getAllScenarios() {
        return SCENARIOS;
    }

    public static ServiceResult<ScenariosResponse> scenarios(String purpose) {
        var scenarios = getScenarios(purpose);
        if (scenarios.isEmpty()) {
            return ServiceResult.ok(new ScenariosResponse(purpose.toUpperCase(), List.of(),
                    "No scenario templates available for purpose: " + purpose));
        }
        return ServiceResult.ok(new ScenariosResponse(purpose.toUpperCase(), scenarios, null));
    }
}
