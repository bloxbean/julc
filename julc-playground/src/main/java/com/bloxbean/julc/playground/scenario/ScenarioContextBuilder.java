package com.bloxbean.julc.playground.scenario;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.julc.playground.model.FieldDto;
import com.bloxbean.julc.playground.model.RedeemerInput;
import com.bloxbean.julc.playground.model.ScenarioOverrides;
import com.bloxbean.julc.playground.model.VariantDto;
import com.bloxbean.cardano.julc.testkit.ScriptContextTestBuilder;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds ScriptContext PlutusData from scenario overrides and metadata.
 */
public final class ScenarioContextBuilder {

    private ScenarioContextBuilder() {}

    /**
     * Build a ScriptContext using purpose string and pre-built datum/redeemer.
     */
    public static PlutusData buildContext(
            String purpose,
            ScenarioOverrides scenario,
            PlutusData datum,
            PlutusData redeemer) {

        return switch (purpose.toUpperCase()) {
            case "SPENDING" -> buildSpendingContext(scenario, datum, redeemer);
            case "MINTING" -> buildMintingContext(scenario, redeemer);
            default -> throw new IllegalArgumentException("Unsupported purpose: " + purpose);
        };
    }

    /**
     * Build datum PlutusData from FieldDto list and user-provided values.
     */
    public static PlutusData buildDatumFromFields(List<FieldDto> fields, Map<String, String> values) {
        if (fields == null || fields.isEmpty() || values == null || values.isEmpty()) {
            return null;
        }

        var data = new ArrayList<PlutusData>();
        for (var field : fields) {
            String value = values.get(field.name());
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Missing datum field: " + field.name());
            }
            data.add(convertValue(value, field.type()));
        }
        return PlutusData.constr(0, data.toArray(PlutusData[]::new));
    }

    /**
     * Build redeemer PlutusData from variant/field DTOs and user input.
     */
    public static PlutusData buildRedeemerFromMetadata(
            List<VariantDto> variants,
            List<FieldDto> fields,
            RedeemerInput input) {

        if (input == null) {
            return PlutusData.constr(0);
        }

        if (variants != null && !variants.isEmpty()) {
            int tag = input.variant();
            if (tag < 0 || tag >= variants.size()) {
                return PlutusData.constr(tag);
            }
            var variant = variants.get(tag);
            if (variant.fields().isEmpty() || input.fields() == null || input.fields().isEmpty()) {
                return PlutusData.constr(tag);
            }
            var data = new ArrayList<PlutusData>();
            for (var field : variant.fields()) {
                String value = input.fields().get(field.name());
                if (value == null) {
                    throw new IllegalArgumentException("Missing redeemer field: " + field.name());
                }
                data.add(convertValue(value, field.type()));
            }
            return PlutusData.constr(tag, data.toArray(PlutusData[]::new));
        }

        if (fields != null && !fields.isEmpty()) {
            if (input.fields() == null || input.fields().isEmpty()) {
                return PlutusData.constr(0);
            }
            var data = new ArrayList<PlutusData>();
            for (var field : fields) {
                String value = input.fields().get(field.name());
                if (value == null) {
                    throw new IllegalArgumentException("Missing redeemer field: " + field.name());
                }
                data.add(convertValue(value, field.type()));
            }
            return PlutusData.constr(0, data.toArray(PlutusData[]::new));
        }

        return PlutusData.constr(input.variant());
    }

    /**
     * Convert a string value to PlutusData based on a type name string.
     * Handles both display names (ByteString, Integer) and
     * Java source type names (byte[], BigInteger) from Java validators.
     */
    public static PlutusData convertValue(String value, String typeName) {
        return switch (typeName) {
            case "Integer", "BigInteger", "POSIXTime", "Lovelace" -> PlutusData.integer(Long.parseLong(value));
            case "boolean", "Boolean" -> PlutusData.constr(Boolean.parseBoolean(value) ? 1 : 0);
            case "byte[]", "ByteString", "PubKeyHash", "ValidatorHash", "ScriptHash",
                 "PolicyId", "TokenName", "DatumHash", "TxId" -> PlutusData.bytes(hexToBytes(value));
            default -> PlutusData.integer(0);
        };
    }

    private static PlutusData buildSpendingContext(
            ScenarioOverrides scenario,
            PlutusData datum,
            PlutusData redeemer) {

        var ref = new TxOutRef(new TxId(new byte[32]), BigInteger.ZERO);
        var builder = ScriptContextTestBuilder.spending(ref, datum != null ? datum : PlutusData.constr(0));

        if (redeemer != null) {
            builder.redeemer(redeemer);
        }

        applyScenarioOverrides(builder, scenario);
        return builder.buildPlutusData();
    }

    private static PlutusData buildMintingContext(
            ScenarioOverrides scenario,
            PlutusData redeemer) {

        var builder = ScriptContextTestBuilder.minting(new PolicyId(new byte[28]));

        if (redeemer != null) {
            builder.redeemer(redeemer);
        }

        applyScenarioOverrides(builder, scenario);
        return builder.buildPlutusData();
    }

    private static void applyScenarioOverrides(ScriptContextTestBuilder builder,
                                                ScenarioOverrides scenario) {
        if (scenario == null) return;

        if (scenario.signers() != null) {
            for (String signer : scenario.signers()) {
                builder.signer(hexToBytes(signer));
            }
        }

        if (scenario.validRangeAfter() != null) {
            builder.validRange(Interval.after(BigInteger.valueOf(scenario.validRangeAfter())));
        } else if (scenario.validRangeBefore() != null) {
            builder.validRange(Interval.before(BigInteger.valueOf(scenario.validRangeBefore())));
        }
    }

    static byte[] hexToBytes(String hex) {
        if (hex == null || hex.isEmpty()) return new byte[0];
        if (hex.length() % 2 != 0) hex = "0" + hex;
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }
}
