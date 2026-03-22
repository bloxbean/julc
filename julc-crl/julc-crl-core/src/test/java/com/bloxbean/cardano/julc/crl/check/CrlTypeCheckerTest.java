package com.bloxbean.cardano.julc.crl.check;

import com.bloxbean.cardano.julc.crl.parser.CrlParser;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CrlTypeCheckerTest {

    private List<CrlDiagnostic> check(String source) {
        var parseResult = CrlParser.parse(source, "test.crl");
        assertFalse(parseResult.hasErrors(), () -> "Parse errors: " + parseResult.diagnostics());
        return CrlTypeChecker.check(parseResult.contract());
    }

    private void assertNoErrors(String source) {
        var diags = check(source);
        var errors = diags.stream().filter(CrlDiagnostic::isError).toList();
        assertTrue(errors.isEmpty(), () -> "Unexpected errors: " + errors);
    }

    private void assertHasError(String source, String expectedCode) {
        var diags = check(source);
        var errors = diags.stream().filter(CrlDiagnostic::isError).toList();
        assertTrue(errors.stream().anyMatch(d -> d.code().equals(expectedCode)),
                () -> "Expected error " + expectedCode + " but got: " + errors);
    }

    // ── Valid contracts ─────────────────────────────────────────

    @Nested
    class ValidContracts {

        @Test
        void simpleTransfer() {
            assertNoErrors("""
                    contract "SimpleTransfer" version "1" purpose spending
                    params:
                        receiver : PubKeyHash
                    rule "Receiver can spend"
                    when
                        Transaction( signedBy: receiver )
                    then allow
                    default: deny
                    """);
        }

        @Test
        void vestingValidator() {
            assertNoErrors("""
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
        void htlcValidator() {
            assertNoErrors("""
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
        void mintingValidator() {
            assertNoErrors("""
                    contract "Mint" version "1" purpose minting
                    rule "always mint"
                    when Condition( true )
                    then allow
                    default: deny
                    """);
        }

        @Test
        void multiValidator() {
            assertNoErrors("""
                    contract "Multi" version "1"
                    purpose minting:
                        rule "mint" when Condition( true ) then allow
                        default: deny
                    purpose spending:
                        rule "spend" when Condition( true ) then allow
                        default: deny
                    """);
        }

        @Test
        void recordRedeemer() {
            assertNoErrors("""
                    contract "T" version "1" purpose spending
                    redeemer EscrowAction:
                        action : Integer
                    rule "r"
                    when
                        Redeemer( EscrowAction( action: $a ) )
                        Condition( $a == 0 )
                    then allow
                    default: deny
                    """);
        }
    }

    // ── Structure errors ────────────────────────────────────────

    @Nested
    class StructureErrors {

        @Test
        void missingDefault() {
            assertHasError("""
                    contract "T" version "1" purpose spending
                    rule "r" when Condition( true ) then allow
                    """, "CRL009");
        }

        @Test
        void mixedSingleAndMultiPurpose() {
            assertHasError("""
                    contract "T" version "1" purpose spending
                    purpose minting:
                        rule "r" when Condition( true ) then allow
                        default: deny
                    """, "CRL010");
        }

        @Test
        void noPurposeNoSections() {
            // Has rules but no purpose
            assertHasError("""
                    contract "T" version "1"
                    rule "r" when Condition( true ) then allow
                    default: deny
                    """, "CRL001");
        }
    }

    // ── Type errors ─────────────────────────────────────────────

    @Nested
    class TypeErrors {

        @Test
        void unknownType() {
            assertHasError("""
                    contract "T" version "1" purpose spending
                    params:
                        x : UnknownType
                    rule "r" when Condition( true ) then allow
                    default: deny
                    """, "CRL002");
        }

        @Test
        void unknownDatumFieldType() {
            assertHasError("""
                    contract "T" version "1" purpose spending
                    datum D:
                        f : Bogus
                    rule "r" when Condition( true ) then allow
                    default: deny
                    """, "CRL002");
        }

        @Test
        void knownTypesPass() {
            assertNoErrors("""
                    contract "T" version "1" purpose spending
                    params:
                        a : Integer
                        b : Lovelace
                        c : POSIXTime
                        d : ByteString
                        e : PubKeyHash
                        f : PolicyId
                        g : TokenName
                        h : Address
                        i : Text
                        j : Boolean
                    rule "r" when Condition( true ) then allow
                    default: deny
                    """);
        }
    }

    // ── Name errors ─────────────────────────────────────────────

    @Nested
    class NameErrors {

        @Test
        void duplicateRuleName() {
            assertHasError("""
                    contract "T" version "1" purpose spending
                    rule "same" when Condition( true ) then allow
                    rule "same" when Condition( false ) then deny
                    default: deny
                    """, "CRL005");
        }

        @Test
        void duplicateFieldName() {
            assertHasError("""
                    contract "T" version "1" purpose spending
                    datum D:
                        x : Integer
                        x : ByteString
                    rule "r" when Condition( true ) then allow
                    default: deny
                    """, "CRL005");
        }

        @Test
        void duplicateVariantName() {
            assertHasError("""
                    contract "T" version "1" purpose spending
                    redeemer R:
                        | A
                        | A
                    rule "r" when Condition( true ) then allow
                    default: deny
                    """, "CRL005");
        }
    }

    // ── Variable errors ─────────────────────────────────────────

    @Nested
    class VariableErrors {

        @Test
        void undefinedVariable() {
            assertHasError("""
                    contract "T" version "1" purpose spending
                    rule "r"
                    when
                        Condition( $undefined == 42 )
                    then allow
                    default: deny
                    """, "CRL003");
        }

        @Test
        void unknownIdentifier() {
            assertHasError("""
                    contract "T" version "1" purpose spending
                    rule "r"
                    when
                        Transaction( signedBy: notAParam )
                    then allow
                    default: deny
                    """, "CRL003");
        }

        @Test
        void ownAddressInMinting() {
            assertHasError("""
                    contract "T" version "1" purpose minting
                    rule "r"
                    when
                        Output( to: ownAddress )
                    then allow
                    default: deny
                    """, "CRL007");
        }

        @Test
        void ownPolicyIdInSpending() {
            assertHasError("""
                    contract "T" version "1" purpose spending
                    rule "r"
                    when
                        Condition( ownPolicyId == 0xdead )
                    then allow
                    default: deny
                    """, "CRL008");
        }

        @Test
        void varBoundInRedeemerUsableInCondition() {
            assertNoErrors("""
                    contract "T" version "1" purpose spending
                    redeemer R:
                        | Action:
                            amount : Integer
                    rule "r"
                    when
                        Redeemer( Action( amount: $amt ) )
                        Condition( $amt > 0 )
                    then allow
                    default: deny
                    """);
        }
    }

    // ── Function errors ─────────────────────────────────────────

    @Nested
    class FunctionErrors {

        @Test
        void unknownFunction() {
            assertHasError("""
                    contract "T" version "1" purpose spending
                    rule "r"
                    when
                        Condition( unknownFn($x) == 42 )
                    then allow
                    default: deny
                    """, "CRL011");
        }

        @Test
        void knownFunctionPasses() {
            assertNoErrors("""
                    contract "T" version "1" purpose spending
                    params:
                        hash : ByteString
                    datum D:
                        val : ByteString
                    rule "r"
                    when
                        Datum( D( val: $v ) )
                        Condition( sha2_256($v) == hash )
                    then allow
                    default: deny
                    """);
        }
    }
}
