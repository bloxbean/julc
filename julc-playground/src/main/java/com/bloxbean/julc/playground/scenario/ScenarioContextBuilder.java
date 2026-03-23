package com.bloxbean.julc.playground.scenario;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.jrl.ast.*;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.julc.playground.model.RedeemerInput;
import com.bloxbean.julc.playground.model.ScenarioOverrides;
import com.bloxbean.cardano.julc.testkit.ScriptContextTestBuilder;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds ScriptContext PlutusData from scenario overrides and AST metadata.
 */
public final class ScenarioContextBuilder {

    private ScenarioContextBuilder() {}

    /**
     * Build a complete ScriptContext as PlutusData for VM evaluation.
     */
    public static PlutusData buildContext(
            ContractNode ast,
            ScenarioOverrides scenario,
            Map<String, String> datumValues,
            RedeemerInput redeemerInput) {

        PurposeType purpose = ast.purpose();
        if (purpose == null) {
            throw new IllegalArgumentException("Multi-validator contracts are not yet supported in playground");
        }

        PlutusData datum = buildDatum(ast.datum(), datumValues);
        PlutusData redeemer = buildRedeemer(ast.redeemer(), redeemerInput);

        return switch (purpose) {
            case SPENDING -> buildSpendingContext(scenario, datum, redeemer);
            case MINTING -> buildMintingContext(scenario, redeemer);
            default -> throw new IllegalArgumentException("Unsupported purpose: " + purpose);
        };
    }

    /**
     * Build param values as PlutusData list from user-provided strings + AST type info.
     */
    public static List<PlutusData> buildParamValues(List<ParamNode> paramNodes, Map<String, String> values) {
        var result = new ArrayList<PlutusData>();
        for (var param : paramNodes) {
            String value = values.get(param.name());
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Missing param value: " + param.name());
            }
            result.add(convertValue(value, param.type()));
        }
        return result;
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

    /**
     * Build datum PlutusData from field name→value map and AST type info.
     */
    static PlutusData buildDatum(DatumDeclNode datumDecl, Map<String, String> values) {
        if (datumDecl == null || values == null || values.isEmpty()) {
            return null;
        }

        var fields = new ArrayList<PlutusData>();
        for (var field : datumDecl.fields()) {
            String value = values.get(field.name());
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Missing datum field: " + field.name());
            }
            fields.add(convertValue(value, field.type()));
        }
        return PlutusData.constr(0, fields.toArray(PlutusData[]::new));
    }

    /**
     * Build redeemer PlutusData from variant tag + field values.
     */
    static PlutusData buildRedeemer(RedeemerDeclNode redeemerDecl, RedeemerInput input) {
        if (input == null) {
            return PlutusData.constr(0);
        }

        if (redeemerDecl == null) {
            return PlutusData.constr(input.variant());
        }

        if (redeemerDecl.isVariantStyle()) {
            var variants = redeemerDecl.variants();
            int tag = input.variant();
            if (tag < 0 || tag >= variants.size()) {
                return PlutusData.constr(tag);
            }

            var variant = variants.get(tag);
            if (variant.fields().isEmpty() || input.fields() == null || input.fields().isEmpty()) {
                return PlutusData.constr(tag);
            }

            var fields = new ArrayList<PlutusData>();
            for (var field : variant.fields()) {
                String value = input.fields().get(field.name());
                if (value == null) {
                    throw new IllegalArgumentException("Missing redeemer field: " + field.name());
                }
                fields.add(convertValue(value, field.type()));
            }
            return PlutusData.constr(tag, fields.toArray(PlutusData[]::new));
        } else {
            // Record-style redeemer
            if (input.fields() == null || input.fields().isEmpty()) {
                return PlutusData.constr(0);
            }
            var fields = new ArrayList<PlutusData>();
            for (var field : redeemerDecl.fields()) {
                String value = input.fields().get(field.name());
                if (value == null) {
                    throw new IllegalArgumentException("Missing redeemer field: " + field.name());
                }
                fields.add(convertValue(value, field.type()));
            }
            return PlutusData.constr(0, fields.toArray(PlutusData[]::new));
        }
    }

    /**
     * Convert a string value to PlutusData based on JRL type.
     */
    static PlutusData convertValue(String value, TypeRef type) {
        String typeName = switch (type) {
            case TypeRef.SimpleType s -> s.name();
            default -> "Data";
        };
        return switch (typeName) {
            case "Integer", "POSIXTime", "Lovelace" -> PlutusData.integer(Long.parseLong(value));
            case "Boolean" -> PlutusData.constr(Boolean.parseBoolean(value) ? 1 : 0);
            case "ByteString", "PubKeyHash", "ValidatorHash", "ScriptHash",
                 "PolicyId", "TokenName", "DatumHash", "TxId" -> PlutusData.bytes(hexToBytes(value));
            default -> PlutusData.integer(0);
        };
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
