package com.bloxbean.cardano.plutus.examples;

import com.bloxbean.cardano.plutus.compiler.PlutusCompiler;
import com.bloxbean.cardano.plutus.core.PlutusData;
import com.bloxbean.cardano.plutus.core.Program;
import com.bloxbean.cardano.plutus.stdlib.StdlibRegistry;
import com.bloxbean.cardano.plutus.testkit.BudgetAssertions;
import com.bloxbean.cardano.plutus.testkit.ValidatorTest;
import com.bloxbean.cardano.plutus.vm.PlutusVm;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Realistic minting policy using stdlib calls and proper ScriptContext.
 * <p>
 * Demonstrates:
 * - @MintingPolicy annotation
 * - ContextsLib.getTxInfo and ContextsLib.signedBy
 * - Redeemer as authority PubKeyHash
 * - Proper V3 ScriptContext with signatories
 */
class RealisticMintingTest {

    /**
     * Authorized minting policy: the redeemer is a PubKeyHash,
     * and the policy checks that the tx is signed by that key.
     */
    static final String AUTHORIZED_MINT = """
            @MintingPolicy
            class AuthorizedMinting {
                @Entrypoint
                static boolean validate(PlutusData redeemer, PlutusData ctx) {
                    PlutusData txInfo = ContextsLib.getTxInfo(ctx);
                    return ContextsLib.signedBy(txInfo, redeemer);
                }
            }
            """;

    /**
     * Always-true minting policy (for testing basic flow).
     */
    static final String ALWAYS_TRUE_MINT = """
            @MintingPolicy
            class AlwaysTrueMint {
                @Entrypoint
                static boolean validate(PlutusData redeemer, PlutusData ctx) {
                    return true;
                }
            }
            """;

    static PlutusVm vm;
    static StdlibRegistry stdlib;

    @BeforeAll
    static void setUp() {
        vm = PlutusVm.create();
        stdlib = StdlibRegistry.defaultRegistry();
    }

    private Program compileWithStdlib(String source) {
        return ValidatorTest.compile(source, stdlib::lookup);
    }

    /**
     * Build a V3 ScriptContext for minting with signatories.
     */
    private PlutusData buildMintingCtx(PlutusData redeemer, PlutusData... signatories) {
        var sigsList = PlutusData.list(signatories);
        var zero = PlutusData.integer(0);
        // TxInfo with signatories at index 8
        var txInfo = PlutusData.constr(0,
                zero, zero, zero, zero, zero, zero, zero, zero,
                sigsList,
                zero, zero, zero, zero, zero, zero, zero);
        // Minting ScriptInfo: Constr(0, [policyId])
        var scriptInfo = PlutusData.constr(0, PlutusData.bytes(new byte[28]));
        return PlutusData.constr(0, txInfo, redeemer, scriptInfo);
    }

    @Test
    void compilesAuthorizedMinting() {
        var program = compileWithStdlib(AUTHORIZED_MINT);
        assertNotNull(program);
        assertEquals(1, program.major());
    }

    @Test
    void compilesAlwaysTrueMint() {
        var program = compileWithStdlib(ALWAYS_TRUE_MINT);
        assertNotNull(program);
    }

    @Test
    void authorizedSignerCanMint() {
        var program = compileWithStdlib(AUTHORIZED_MINT);
        var authorityPkh = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
                11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28};

        // Redeemer is the authority PubKeyHash
        var ctx = buildMintingCtx(PlutusData.bytes(authorityPkh),
                PlutusData.bytes(authorityPkh));  // signer matches redeemer
        var result = vm.evaluateWithArgs(program, List.of(ctx));
        assertTrue(result.isSuccess(), "Authorized signer should be able to mint: " + result);
    }

    @Test
    void unauthorizedSignerRejected() {
        var program = compileWithStdlib(AUTHORIZED_MINT);
        var authorityPkh = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
                11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28};
        var attackerPkh = new byte[]{99, 98, 97, 96, 95, 94, 93, 92, 91, 90,
                89, 88, 87, 86, 85, 84, 83, 82, 81, 80, 79, 78, 77, 76, 75, 74, 73, 72};

        var ctx = buildMintingCtx(PlutusData.bytes(authorityPkh),
                PlutusData.bytes(attackerPkh));  // signer doesn't match
        var result = vm.evaluateWithArgs(program, List.of(ctx));
        assertFalse(result.isSuccess(), "Unauthorized signer should be rejected: " + result);
    }

    @Test
    void noSignerRejected() {
        var program = compileWithStdlib(AUTHORIZED_MINT);
        var authorityPkh = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
                11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28};

        // No signatories
        var ctx = buildMintingCtx(PlutusData.bytes(authorityPkh));
        var result = vm.evaluateWithArgs(program, List.of(ctx));
        assertFalse(result.isSuccess(), "No signatories should cause rejection: " + result);
    }

    @Test
    void alwaysTrueMintAccepts() {
        var program = compileWithStdlib(ALWAYS_TRUE_MINT);
        var ctx = buildMintingCtx(PlutusData.integer(0));
        var result = vm.evaluateWithArgs(program, List.of(ctx));
        assertTrue(result.isSuccess(), "Always-true mint should succeed: " + result);
    }

    @Test
    void mintBudgetIsReasonable() {
        var program = compileWithStdlib(AUTHORIZED_MINT);
        var pkh = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
                11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28};
        var ctx = buildMintingCtx(PlutusData.bytes(pkh), PlutusData.bytes(pkh));
        var result = vm.evaluateWithArgs(program, List.of(ctx));
        BudgetAssertions.assertBudgetUnder(result, 10_000_000_000L, 10_000_000_000L);
    }
}
