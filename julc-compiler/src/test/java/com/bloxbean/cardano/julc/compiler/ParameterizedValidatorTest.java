package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.vm.JulcVm;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for parameterized contracts using @Param annotation.
 */
class ParameterizedValidatorTest {

    static JulcVm vm;

    @BeforeAll
    static void setUp() {
        vm = JulcVm.create();
    }

    /**
     * Build a minimal V3 ScriptContext.
     * ScriptContext = Constr(0, [txInfo, redeemer, scriptInfo])
     */
    static PlutusData buildScriptContext(PlutusData txInfo, PlutusData redeemer, PlutusData scriptInfo) {
        return PlutusData.constr(0, txInfo, redeemer, scriptInfo);
    }

    /**
     * Build a minimal TxInfo with signatories.
     */
    static PlutusData buildTxInfo(PlutusData[] signatories) {
        return PlutusData.constr(0,
                PlutusData.list(),                                     // 0: inputs
                PlutusData.list(),                                     // 1: referenceInputs
                PlutusData.list(),                                     // 2: outputs
                PlutusData.integer(2000000),                           // 3: fee
                PlutusData.map(),                                      // 4: mint
                PlutusData.list(),                                     // 5: certificates
                PlutusData.map(),                                      // 6: withdrawals
                alwaysInterval(),                                      // 7: validRange
                PlutusData.list(signatories),                          // 8: signatories
                PlutusData.map(),                                      // 9: redeemers
                PlutusData.map(),                                      // 10: datums
                PlutusData.bytes(new byte[32]),                        // 11: txId
                PlutusData.map(),                                      // 12: votes
                PlutusData.list(),                                     // 13: proposalProcedures
                PlutusData.constr(1),                                  // 14: currentTreasuryAmount (None)
                PlutusData.constr(1)                                   // 15: treasuryDonation (None)
        );
    }

    static PlutusData alwaysInterval() {
        var negInf = PlutusData.constr(0);
        var posInf = PlutusData.constr(2);
        var trueVal = PlutusData.constr(1);
        var lowerBound = PlutusData.constr(0, negInf, trueVal);
        var upperBound = PlutusData.constr(0, posInf, trueVal);
        return PlutusData.constr(0, lowerBound, upperBound);
    }

    @Test
    void singleByteArrayParam() {
        var source = """
                @Validator
                class TokenValidator {
                    @Param byte[] owner;

                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        byte[] signerPkh = owner;
                        return true;
                    }
                }
                """;
        var result = new JulcCompiler().compile(source);
        assertNotNull(result.program());
        assertTrue(result.isParameterized());
        assertEquals(1, result.params().size());
        assertEquals("owner", result.params().get(0).name());

        // Apply param and evaluate
        var ownerBytes = new byte[]{1, 2, 3};
        var concrete = result.program().applyParams(PlutusData.bytes(ownerBytes));
        var ctx = buildScriptContext(
                buildTxInfo(new PlutusData[0]),
                PlutusData.integer(0),
                PlutusData.constr(0, PlutusData.bytes(new byte[28])));
        var evalResult = vm.evaluateWithArgs(concrete, List.of(ctx));
        assertTrue(evalResult.isSuccess(), "Should succeed: " + evalResult);
    }

    @Test
    void singleIntegerParam() {
        var source = """
                import java.math.BigInteger;

                @Validator
                class ThresholdValidator {
                    @Param BigInteger threshold;

                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return threshold > 0;
                    }
                }
                """;
        var result = new JulcCompiler().compile(source);
        assertTrue(result.isParameterized());

        // threshold = 100 > 0 => success
        var concrete = result.program().applyParams(PlutusData.integer(100));
        var ctx = buildScriptContext(
                buildTxInfo(new PlutusData[0]),
                PlutusData.integer(0),
                PlutusData.constr(0, PlutusData.bytes(new byte[28])));
        var evalResult = vm.evaluateWithArgs(concrete, List.of(ctx));
        assertTrue(evalResult.isSuccess(), "threshold=100 > 0 should succeed: " + evalResult);
    }

    @Test
    void multipleParams() {
        var source = """
                import java.math.BigInteger;

                @Validator
                class MultiParamValidator {
                    @Param byte[] tokenPolicyId;
                    @Param BigInteger minAmount;

                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return minAmount > 0;
                    }
                }
                """;
        var result = new JulcCompiler().compile(source);
        assertTrue(result.isParameterized());
        assertEquals(2, result.params().size());
        assertEquals("tokenPolicyId", result.params().get(0).name());
        assertEquals("minAmount", result.params().get(1).name());

        // Apply both params
        var concrete = result.program().applyParams(
                PlutusData.bytes(new byte[]{0x0A}),
                PlutusData.integer(50));
        var ctx = buildScriptContext(
                buildTxInfo(new PlutusData[0]),
                PlutusData.integer(0),
                PlutusData.constr(0, PlutusData.bytes(new byte[28])));
        var evalResult = vm.evaluateWithArgs(concrete, List.of(ctx));
        assertTrue(evalResult.isSuccess(), "minAmount=50 > 0 should succeed: " + evalResult);
    }

    @Test
    void paramWithDatum() {
        // 3-param spending validator + @Param
        var source = """
                import java.math.BigInteger;

                @Validator
                class DatumParamValidator {
                    @Param BigInteger requiredAmount;

                    @Entrypoint
                    static boolean validate(PlutusData datum, PlutusData redeemer, ScriptContext ctx) {
                        return requiredAmount > 0;
                    }
                }
                """;
        var result = new JulcCompiler().compile(source);
        assertTrue(result.isParameterized());
        assertEquals(1, result.params().size());

        var concrete = result.program().applyParams(PlutusData.integer(1000));
        // Build context with datum in ScriptInfo(Spending)
        // Spending = Constr(1, [txOutRef, optionalDatum])
        // TxOutRef = Constr(0, [txId, index])
        var txOutRef = PlutusData.constr(0,
                PlutusData.bytes(new byte[32]),
                PlutusData.integer(0));
        var optDatum = PlutusData.constr(0, PlutusData.integer(42)); // Some(42)
        var scriptInfo = PlutusData.constr(1, txOutRef, optDatum);
        var ctx = buildScriptContext(
                buildTxInfo(new PlutusData[0]),
                PlutusData.integer(0),
                scriptInfo);
        var evalResult = vm.evaluateWithArgs(concrete, List.of(ctx));
        assertTrue(evalResult.isSuccess(), "requiredAmount=1000 > 0 with datum should succeed: " + evalResult);
    }

    @Test
    void paramWithMintingPolicy() {
        var source = """
                import java.math.BigInteger;

                @MintingPolicy
                class ParamMint {
                    @Param byte[] requiredSigner;

                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;
        var result = new JulcCompiler().compile(source);
        assertTrue(result.isParameterized());
        assertEquals(1, result.params().size());
        assertEquals("requiredSigner", result.params().get(0).name());

        var concrete = result.program().applyParams(PlutusData.bytes(new byte[]{1, 2, 3}));
        var ctx = buildScriptContext(
                buildTxInfo(new PlutusData[0]),
                PlutusData.integer(0),
                PlutusData.constr(0, PlutusData.bytes(new byte[28])));
        var evalResult = vm.evaluateWithArgs(concrete, List.of(ctx));
        assertTrue(evalResult.isSuccess(), "Parameterized minting policy should succeed: " + evalResult);
    }

    @Test
    void paramUsedInArithmetic() {
        var source = """
                import java.math.BigInteger;

                @Validator
                class ArithValidator {
                    @Param BigInteger base;

                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        var doubled = base + base;
                        return doubled > 0;
                    }
                }
                """;
        var result = new JulcCompiler().compile(source);
        assertTrue(result.isParameterized());

        var concrete = result.program().applyParams(PlutusData.integer(25));
        var ctx = buildScriptContext(
                buildTxInfo(new PlutusData[0]),
                PlutusData.integer(0),
                PlutusData.constr(0, PlutusData.bytes(new byte[28])));
        var evalResult = vm.evaluateWithArgs(concrete, List.of(ctx));
        assertTrue(evalResult.isSuccess(), "base=25, doubled=50 > 0 should succeed: " + evalResult);
    }

    @Test
    void customTypeParam() {
        var source = """
                @Validator
                class CustomParamValidator {
                    record TokenConfig(byte[] policyId, byte[] assetName) {}

                    @Param TokenConfig config;

                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        byte[] policy = config.policyId();
                        return true;
                    }
                }
                """;
        var result = new JulcCompiler().compile(source);
        assertTrue(result.isParameterized());
        assertEquals(1, result.params().size());
        assertEquals("config", result.params().get(0).name());
        assertEquals("TokenConfig", result.params().get(0).type());

        // TokenConfig encoded as Constr(0, [policyId, assetName])
        var configData = PlutusData.constr(0,
                PlutusData.bytes(new byte[]{0x01, 0x02}),
                PlutusData.bytes(new byte[]{0x03, 0x04}));
        var concrete = result.program().applyParams(configData);
        var ctx = buildScriptContext(
                buildTxInfo(new PlutusData[0]),
                PlutusData.integer(0),
                PlutusData.constr(0, PlutusData.bytes(new byte[28])));
        var evalResult = vm.evaluateWithArgs(concrete, List.of(ctx));
        assertTrue(evalResult.isSuccess(), "Custom type param should succeed: " + evalResult);
    }

    @Test
    void wrongParamCount() {
        var source = """
                import java.math.BigInteger;

                @Validator
                class TwoParamValidator {
                    @Param byte[] owner;
                    @Param BigInteger amount;

                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return amount > 0;
                    }
                }
                """;
        var result = new JulcCompiler().compile(source);

        // Apply only one param — the result is a lambda, not a validator
        var partial = result.program().applyParams(PlutusData.bytes(new byte[]{1}));
        // Evaluating with scriptContext should fail because the program
        // still expects the second param before the scriptContext
        var ctx = buildScriptContext(
                buildTxInfo(new PlutusData[0]),
                PlutusData.integer(0),
                PlutusData.constr(0, PlutusData.bytes(new byte[28])));
        var evalResult = vm.evaluateWithArgs(partial, List.of(ctx));
        // With only 1 of 2 params applied, the ctx arg becomes the second param,
        // and we're left with a lambda (not a terminal value), which won't produce Unit
        assertFalse(evalResult.isSuccess(), "Wrong param count should fail at eval");
    }

    @Test
    void nonParameterizedUnchanged() {
        var source = """
                import java.math.BigInteger;

                @Validator
                class SimpleValidator {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;
        var result = new JulcCompiler().compile(source);
        assertNotNull(result.program());
        assertFalse(result.isParameterized());
        assertTrue(result.params().isEmpty());

        // Should work as before
        var ctx = buildScriptContext(
                buildTxInfo(new PlutusData[0]),
                PlutusData.integer(0),
                PlutusData.constr(0, PlutusData.bytes(new byte[28])));
        var evalResult = vm.evaluateWithArgs(result.program(), List.of(ctx));
        assertTrue(evalResult.isSuccess(), "Non-parameterized should still work: " + evalResult);
    }

    @Test
    void compileResultHasParamInfo() {
        var source = """
                import java.math.BigInteger;

                @Validator
                class InfoValidator {
                    @Param byte[] owner;
                    @Param BigInteger minStake;

                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;
        var result = new JulcCompiler().compile(source);
        assertTrue(result.isParameterized());
        assertEquals(2, result.params().size());

        assertEquals("owner", result.params().get(0).name());
        assertEquals("byte[]", result.params().get(0).type());

        assertEquals("minStake", result.params().get(1).name());
        assertEquals("BigInteger", result.params().get(1).type());
    }

    @Test
    void compileResultNonParameterized() {
        var source = """
                @Validator
                class PlainValidator {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;
        var result = new JulcCompiler().compile(source);
        assertFalse(result.isParameterized());
        assertTrue(result.params().isEmpty());
    }

    @Test
    void stringParam() {
        var source = """
                @Validator
                class StringParamValidator {
                    @Param String msg;

                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return msg.equals("hello");
                    }
                }
                """;
        var result = new JulcCompiler().compile(source);
        assertTrue(result.isParameterized());
        assertEquals(1, result.params().size());
        assertEquals("msg", result.params().get(0).name());

        // String is encoded as BData(EncodeUtf8(s)) — pass UTF-8 bytes
        var concrete = result.program().applyParams(
                PlutusData.bytes("hello".getBytes(StandardCharsets.UTF_8)));
        var ctx = buildScriptContext(
                buildTxInfo(new PlutusData[0]),
                PlutusData.integer(0),
                PlutusData.constr(0, PlutusData.bytes(new byte[28])));
        var evalResult = vm.evaluateWithArgs(concrete, List.of(ctx));
        assertTrue(evalResult.isSuccess(), "String param 'hello'.equals('hello') should succeed: " + evalResult);
    }

    @Test
    void multipleParamsWithString() {
        var source = """
                import java.math.BigInteger;

                @Validator
                class MultiStringValidator {
                    @Param BigInteger no;
                    @Param String msg;

                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return no > 0;
                    }
                }
                """;
        var result = new JulcCompiler().compile(source);
        assertTrue(result.isParameterized());
        assertEquals(2, result.params().size());

        var concrete = result.program().applyParams(
                PlutusData.integer(42),
                PlutusData.bytes("test".getBytes(StandardCharsets.UTF_8)));
        var ctx = buildScriptContext(
                buildTxInfo(new PlutusData[0]),
                PlutusData.integer(0),
                PlutusData.constr(0, PlutusData.bytes(new byte[28])));
        var evalResult = vm.evaluateWithArgs(concrete, List.of(ctx));
        assertTrue(evalResult.isSuccess(), "BigInteger + String params should succeed: " + evalResult);
    }
}
