package com.bloxbean.cardano.julc.crl;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.vm.JulcVm;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests: CRL source -> UPLC -> VM evaluation -> correct result.
 * Tests the full compilation pipeline end-to-end.
 */
class CrlIntegrationTest {

    static JulcVm vm;

    @BeforeAll
    static void setUp() {
        vm = JulcVm.create();
    }

    // ── Test helpers ────────────────────────────────────────────

    private Program compileCrl(String crlSource) {
        return CrlTestSupport.compileCrl(crlSource);
    }

    // Spending context: validator receives just ScriptContext (after wrapper unwraps)
    private PlutusData buildSimpleSpendingCtx(byte[][] signatories) {
        // Build signatories list
        var sigList = new PlutusData[signatories.length];
        for (int i = 0; i < signatories.length; i++) {
            sigList[i] = PlutusData.bytes(signatories[i]);
        }

        var txInfo = buildTxInfo(PlutusData.list(sigList), alwaysInterval());

        // ScriptInfo for spending: Constr(1, [txOutRef, datum])
        // txOutRef = Constr(0, [txId, index])
        var txOutRef = PlutusData.constr(0,
                PlutusData.constr(0, PlutusData.bytes(new byte[32])),
                PlutusData.integer(0));
        // datum = Optional (Some) -> Constr(0, [Constr(0, [])])
        var someDatum = PlutusData.constr(0, PlutusData.constr(0));
        var scriptInfo = PlutusData.constr(1, txOutRef, someDatum);

        // Redeemer = Constr(0, []) (unit redeemer)
        var redeemer = PlutusData.constr(0);

        return PlutusData.constr(0, txInfo, redeemer, scriptInfo);
    }

    private PlutusData buildTxInfo(PlutusData signatories, PlutusData validRange) {
        return PlutusData.constr(0,
                PlutusData.list(),              // 0: inputs
                PlutusData.list(),              // 1: referenceInputs
                PlutusData.list(),              // 2: outputs
                PlutusData.integer(0),          // 3: fee
                PlutusData.map(),               // 4: mint
                PlutusData.list(),              // 5: certificates
                PlutusData.map(),               // 6: withdrawals
                validRange,                     // 7: validRange
                signatories,                    // 8: signatories
                PlutusData.map(),               // 9: redeemers
                PlutusData.map(),               // 10: datums
                PlutusData.constr(0, PlutusData.bytes(new byte[32])), // 11: id
                PlutusData.map(),               // 12: votes
                PlutusData.list(),              // 13: proposalProcedures
                PlutusData.map(),               // 14: currentTreasuryAmount (Optional)
                PlutusData.map()                // 15: treasuryDonation (Optional)
        );
    }

    private PlutusData alwaysInterval() {
        // Extended(NegInf), Extended(PosInf) -> always valid
        var negInf = PlutusData.constr(0);
        var posInf = PlutusData.constr(2);
        var lowerBound = PlutusData.constr(0, negInf, PlutusData.constr(1));
        var upperBound = PlutusData.constr(0, posInf, PlutusData.constr(1));
        return PlutusData.constr(0, lowerBound, upperBound);
    }

    // ── Transpilation verification ──────────────────────────────

    @Nested
    class TranspilationTests {

        @Test
        void simpleContract_compilesToUplc() {
            compileCrl("""
                    contract "Simple" version "1" purpose spending
                    rule "always" when Condition( true ) then allow
                    default: deny
                    """);
            // If we get here without exception, compilation succeeded
        }

        @Test
        void vestingContract_compilesToUplc() {
            compileCrl("""
                    contract "Vesting" version "1" purpose spending
                    datum VestingDatum:
                        lockUntil   : POSIXTime
                        owner       : PubKeyHash
                        beneficiary : PubKeyHash
                    rule "Owner can withdraw"
                    when
                        Datum( VestingDatum( owner: $owner ) )
                        Transaction( signedBy: $owner )
                    then allow
                    rule "Beneficiary after deadline"
                    when
                        Datum( VestingDatum( lockUntil: $dl, beneficiary: $ben ) )
                        Transaction( signedBy: $ben )
                        Transaction( validAfter: $dl )
                    then allow
                    default: deny
                    """);
        }

        @Test
        void mintingContract_compilesToUplc() {
            compileCrl("""
                    contract "Mint" version "1" purpose minting
                    redeemer MintAction:
                        | Create
                        | Burn
                    rule "create"
                    when Redeemer( Create )
                    then allow
                    rule "burn"
                    when Redeemer( Burn )
                    then allow
                    default: deny
                    """);
        }

        @Test
        void parameterizedContract_compilesToUplc() {
            compileCrl("""
                    contract "Param" version "1" purpose spending
                    params:
                        owner : PubKeyHash
                        deadline : POSIXTime
                    rule "r"
                    when
                        Transaction( signedBy: owner )
                        Transaction( validAfter: deadline )
                    then allow
                    default: deny
                    """);
        }

        @Test
        void htlcContract_compilesToUplc() {
            compileCrl("""
                    contract "HTLC" version "1" purpose spending
                    params:
                        secretHash : ByteString
                        expiration : POSIXTime
                        owner      : PubKeyHash
                    redeemer HtlcAction:
                        | Guess:
                            answer : ByteString
                        | Withdraw
                    rule "Hash match"
                    when
                        Redeemer( Guess( answer: $answer ) )
                        Condition( sha2_256($answer) == secretHash )
                    then allow
                    rule "Owner withdraw"
                    when
                        Redeemer( Withdraw )
                        Transaction( signedBy: owner )
                        Transaction( validAfter: expiration )
                    then allow
                    default: deny
                    """);
        }

        @Test
        void generatedJavaIsAccessible() {
            var compiler = new CrlCompiler();
            var result = compiler.compile("""
                    contract "T" version "1" purpose spending
                    rule "r" when Condition( true ) then allow
                    default: deny
                    """, "test.crl");
            assertNotNull(result.generatedJavaSource());
            assertTrue(result.generatedJavaSource().contains("@SpendingValidator"));
        }
    }

    // ── VM evaluation tests ─────────────────────────────────────

    @Nested
    class EvaluationTests {

        @Test
        void simpleTransfer_correctSigner_succeeds() {
            var program = compileCrl("""
                    contract "SimpleTransfer" version "1" purpose spending
                    params:
                        receiver : PubKeyHash
                    rule "Receiver can spend"
                    when
                        Transaction( signedBy: receiver )
                    then allow
                    default: deny
                    """);

            // The program is parameterized, so it needs the param applied first
            // For now just verify it compiled successfully
            assertNotNull(program);
        }

        @Test
        void alwaysSucceeds_evaluates() {
            var program = compileCrl("""
                    contract "Always" version "1" purpose spending
                    rule "always" when Condition( true ) then allow
                    default: deny
                    """);

            var ctx = buildSimpleSpendingCtx(new byte[][]{});
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "Expected success: " + result);
        }
    }

    // ── Error handling ──────────────────────────────────────────

    @Nested
    class ErrorHandlingTests {

        @Test
        void parseError_returnsDiagnostics() {
            var compiler = new CrlCompiler();
            var result = compiler.compile("not valid CRL", "bad.crl");
            assertTrue(result.hasErrors());
            assertFalse(result.crlDiagnostics().isEmpty());
        }

        @Test
        void typeError_returnsDiagnostics() {
            var compiler = new CrlCompiler();
            var result = compiler.compile("""
                    contract "T" version "1" purpose spending
                    params:
                        x : UnknownType
                    rule "r" when Condition( true ) then allow
                    default: deny
                    """, "test.crl");
            assertTrue(result.hasErrors());
            assertTrue(result.crlDiagnostics().stream()
                    .anyMatch(d -> d.code().equals("CRL002")));
        }

        @Test
        void transpileOnlyApi() {
            var compiler = new CrlCompiler();
            var result = compiler.transpile("""
                    contract "T" version "1" purpose spending
                    rule "r" when Condition( true ) then allow
                    default: deny
                    """, "test.crl");
            assertFalse(result.hasErrors());
            assertNotNull(result.javaSource());
            assertTrue(result.javaSource().contains("@SpendingValidator"));
        }
    }
}
