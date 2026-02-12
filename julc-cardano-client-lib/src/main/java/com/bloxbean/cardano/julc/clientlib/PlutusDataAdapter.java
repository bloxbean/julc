package com.bloxbean.cardano.julc.clientlib;

import com.bloxbean.cardano.julc.core.PlutusData;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * Bidirectional conversion between plutus-java's {@link PlutusData}
 * and cardano-client-lib's {@link com.bloxbean.cardano.client.plutus.spec.PlutusData}.
 */
public final class PlutusDataAdapter {

    private PlutusDataAdapter() {}

    /**
     * Convert our PlutusData to cardano-client-lib PlutusData.
     */
    public static com.bloxbean.cardano.client.plutus.spec.PlutusData toClientLib(PlutusData data) {
        return switch (data) {
            case PlutusData.IntData(var value) ->
                    new com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData(value);

            case PlutusData.BytesData(var value) ->
                    new com.bloxbean.cardano.client.plutus.spec.BytesPlutusData(value);

            case PlutusData.ConstrData(var tag, var fields) -> {
                var clientFields = new com.bloxbean.cardano.client.plutus.spec.ListPlutusData();
                for (var field : fields) {
                    clientFields.add(toClientLib(field));
                }
                yield com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData.builder()
                        .alternative(tag)
                        .data(clientFields)
                        .build();
            }

            case PlutusData.ListData(var items) -> {
                var clientList = new com.bloxbean.cardano.client.plutus.spec.ListPlutusData();
                for (var item : items) {
                    clientList.add(toClientLib(item));
                }
                yield clientList;
            }

            case PlutusData.MapData(var entries) -> {
                var clientMap = new com.bloxbean.cardano.client.plutus.spec.MapPlutusData();
                for (var entry : entries) {
                    clientMap.put(toClientLib(entry.key()), toClientLib(entry.value()));
                }
                yield clientMap;
            }
        };
    }

    /**
     * Convert cardano-client-lib PlutusData to our PlutusData.
     */
    public static PlutusData fromClientLib(com.bloxbean.cardano.client.plutus.spec.PlutusData data) {
        if (data instanceof com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData bigInt) {
            return PlutusData.integer(bigInt.getValue());
        } else if (data instanceof com.bloxbean.cardano.client.plutus.spec.BytesPlutusData bytes) {
            return PlutusData.bytes(bytes.getValue());
        } else if (data instanceof com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData constr) {
            var fields = new ArrayList<PlutusData>();
            if (constr.getData() != null) {
                for (var item : constr.getData().getPlutusDataList()) {
                    fields.add(fromClientLib(item));
                }
            }
            return PlutusData.constr((int) constr.getAlternative(),
                    fields.toArray(PlutusData[]::new));
        } else if (data instanceof com.bloxbean.cardano.client.plutus.spec.ListPlutusData list) {
            var items = new ArrayList<PlutusData>();
            for (var item : list.getPlutusDataList()) {
                items.add(fromClientLib(item));
            }
            return PlutusData.list(items.toArray(PlutusData[]::new));
        } else if (data instanceof com.bloxbean.cardano.client.plutus.spec.MapPlutusData map) {
            var entries = new ArrayList<PlutusData.Pair>();
            for (var entry : map.getMap().entrySet()) {
                entries.add(new PlutusData.Pair(
                        fromClientLib(entry.getKey()),
                        fromClientLib(entry.getValue())));
            }
            return PlutusData.map(entries.toArray(PlutusData.Pair[]::new));
        }
        throw new IllegalArgumentException("Unknown cardano-client-lib PlutusData type: " + data.getClass());
    }
}
