package com.bloxbean.cardano.julc.vm;

/** Known flat cost-model schemas at the cardano-node 11.0.1 compatibility baseline. */
public enum CostModelSchema {
    PLUTUS_V1_LEGACY(166),
    PLUTUS_V1_PV11(332),
    PLUTUS_V2_LEGACY(175),
    PLUTUS_V2_PV10(185),
    PLUTUS_V2_PV11(332),
    PLUTUS_V3_PV9(251),
    PLUTUS_V3_PV10(297),
    PLUTUS_V3_PV11(350);

    private final int parameterCount;

    CostModelSchema(int parameterCount) {
        this.parameterCount = parameterCount;
    }

    public int parameterCount() {
        return parameterCount;
    }
}
