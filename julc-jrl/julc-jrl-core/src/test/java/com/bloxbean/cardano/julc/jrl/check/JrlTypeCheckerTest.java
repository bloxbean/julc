package com.bloxbean.cardano.julc.jrl.check;

import com.bloxbean.cardano.julc.jrl.parser.JrlParser;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JrlTypeCheckerTest {

    private List<JrlDiagnostic> check(String source) {
        var parseResult = JrlParser.parse(source, "test.jrl");
        assertFalse(parseResult.hasErrors(), () -> "Parse errors: " + parseResult.diagnostics());
        return JrlTypeChecker.check(parseResult.contract());
    }

    private void assertNoErrors(String source) {
        var diags = check(source);
        var errors = diags.stream().filter(JrlDiagnostic::isError).toList();
        assertTrue(errors.isEmpty(), () -> "Unexpected errors: " + errors);
    }

    private void assertHasError(String source, String expectedCode) {
        var diags = check(source);
        var errors = diags.stream().filter(JrlDiagnostic::isError).toList();
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
                    """, "JRL009");
        }

        @Test
        void mixedSingleAndMultiPurpose() {
            assertHasError("""
                    contract "T" version "1" purpose spending
                    purpose minting:
                        rule "r" when Condition( true ) then allow
                        default: deny
                    """, "JRL010");
        }

        @Test
        void noPurposeNoSections() {
            // Has rules but no purpose
            assertHasError("""
                    contract "T" version "1"
                    rule "r" when Condition( true ) then allow
                    default: deny
                    """, "JRL001");
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
                    """, "JRL002");
        }

        @Test
        void unknownDatumFieldType() {
            assertHasError("""
                    contract "T" version "1" purpose spending
                    datum D:
                        f : Bogus
                    rule "r" when Condition( true ) then allow
                    default: deny
                    """, "JRL002");
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
                    """, "JRL005");
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
                    """, "JRL005");
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
                    """, "JRL005");
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
                    """, "JRL003");
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
                    """, "JRL003");
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
                    """, "JRL007");
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
                    """, "JRL008");
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

    // ── Output pattern errors ────────────────────────────────────

    @Nested
    class OutputPatternErrors {

        @Test
        void outputPattern_missingTo_error() {
            assertHasError("""
                    contract "T" version "1" purpose spending
                    params:
                        receiver : PubKeyHash
                    rule "r"
                    when
                        Output( value: minADA( 1000000 ) )
                    then allow
                    default: deny
                    """, "JRL031");
        }

        @Test
        void outputPattern_missingValue_error() {
            assertHasError("""
                    contract "T" version "1" purpose spending
                    params:
                        receiver : PubKeyHash
                    rule "r"
                    when
                        Output( to: receiver )
                    then allow
                    default: deny
                    """, "JRL032");
        }

        @Test
        void outputPattern_datumConstraint_valid() {
            // Output datum constraints are now supported (Phase 2)
            assertNoErrors("""
                    contract "T" version "1" purpose spending
                    params:
                        receiver : PubKeyHash
                    record PaymentDatum:
                        ref : ByteString
                    rule "r"
                    when
                        Output( to: receiver, value: minADA( 2000000 ), Datum: inline PaymentDatum( ref: 0xdead ) )
                    then allow
                    default: deny
                    """);
        }

        @Test
        void outputPattern_toAndValue_valid() {
            assertNoErrors("""
                    contract "T" version "1" purpose spending
                    params:
                        receiver : PubKeyHash
                    rule "r"
                    when
                        Output( to: receiver, value: minADA( 2000000 ) )
                    then allow
                    default: deny
                    """);
        }

        @Test
        void outputPattern_toAndValueContains_valid() {
            assertNoErrors("""
                    contract "T" version "1" purpose spending
                    params:
                        receiver : PubKeyHash
                        policy   : PolicyId
                        token    : TokenName
                    rule "r"
                    when
                        Output( to: receiver, value: contains( policy, token, 1 ) )
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
                    """, "JRL011");
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

    // ── Phase 2: New Fact Validation ─────────────────────────────

    @Nested
    class Phase2InputMintContinuing {

        @Test
        void inputPattern_fromOnly_nonOwnAddress_nowErrors() {
            // Input(from: addr) without token/value was previously valid but is now
            // rejected (JRL047) because it generates no useful check
            assertHasError("""
                    contract "T" version "1" purpose spending
                    params:
                        addr : PubKeyHash
                    rule "r"
                    when
                        Input( from: addr )
                    then allow
                    default: deny
                    """, "JRL047");
        }

        @Test
        void inputPattern_tokenContains_valid() {
            assertNoErrors("""
                    contract "T" version "1" purpose spending
                    params:
                        authPolicy : PolicyId
                    rule "r"
                    when
                        Input( token: contains(authPolicy, "AUTH", 1) )
                    then allow
                    default: deny
                    """);
        }

        @Test
        void inputPattern_valueBinding_createsVariable() {
            assertNoErrors("""
                    contract "T" version "1" purpose spending
                    rule "r"
                    when
                        Input( from: ownAddress, value: $val )
                        Condition( $val > 0 )
                    then allow
                    default: deny
                    """);
        }

        @Test
        void mintPattern_valid() {
            assertNoErrors("""
                    contract "T" version "1" purpose minting
                    rule "r"
                    when
                        Mint( policy: ownPolicyId, token: "MyToken", amount: $amt )
                        Condition( $amt > 0 )
                    then allow
                    default: deny
                    """);
        }

        @Test
        void mintPattern_missingPolicy_error() {
            assertHasError("""
                    contract "T" version "1" purpose minting
                    rule "r"
                    when
                        Mint( token: "MyToken" )
                    then allow
                    default: deny
                    """, "JRL041");
        }

        @Test
        void mintPattern_burned_valid() {
            assertNoErrors("""
                    contract "T" version "1" purpose minting
                    rule "r"
                    when
                        Mint( policy: ownPolicyId, token: "LPToken", burned )
                    then allow
                    default: deny
                    """);
        }

        @Test
        void continuingOutput_valid() {
            assertNoErrors("""
                    contract "T" version "1" purpose spending
                    rule "r"
                    when
                        ContinuingOutput( value: minADA( 2000000 ) )
                    then allow
                    default: deny
                    """);
        }

        @Test
        void continuingOutput_valueAndDatum_valid() {
            assertNoErrors("""
                    contract "T" version "1" purpose spending
                    datum StateDatum:
                        counter : Integer
                    rule "r"
                    when
                        ContinuingOutput( value: minADA( 2000000 ), Datum: inline StateDatum( counter: 42 ) )
                    then allow
                    default: deny
                    """);
        }

        @Test
        void continuingOutput_nonSpending_error() {
            assertHasError("""
                    contract "T" version "1" purpose minting
                    rule "r"
                    when
                        ContinuingOutput( value: minADA( 2000000 ) )
                    then allow
                    default: deny
                    """, "JRL042");
        }

        @Test
        void transactionFee_valid() {
            assertNoErrors("""
                    contract "T" version "1" purpose spending
                    rule "r"
                    when
                        Transaction( fee: $f )
                        Condition( $f <= 2000000 )
                    then allow
                    default: deny
                    """);
        }

        @Test
        void outputDatumConstraint_valid() {
            assertNoErrors("""
                    contract "T" version "1" purpose spending
                    params:
                        receiver : PubKeyHash
                    record Receipt:
                        payer : PubKeyHash
                    rule "r"
                    when
                        Output( to: receiver, Datum: inline Receipt( payer: 0xdead ) )
                    then allow
                    default: deny
                    """);
        }

        @Test
        void outputDatumConstraint_unknownType_error() {
            assertHasError("""
                    contract "T" version "1" purpose spending
                    params:
                        receiver : PubKeyHash
                    rule "r"
                    when
                        Output( to: receiver, Datum: inline UnknownType( x: 1 ) )
                    then allow
                    default: deny
                    """, "JRL002");
        }

        @Test
        void continuingOutput_datumInline_valid() {
            assertNoErrors("""
                    contract "T" version "1" purpose spending
                    datum StateDatum:
                        counter : Integer
                    rule "r"
                    when
                        ContinuingOutput( Datum: inline StateDatum( counter: 42 ) )
                    then allow
                    default: deny
                    """);
        }

        @Test
        void softKeywords_asFieldNames_valid() {
            assertNoErrors("""
                    contract "T" version "1" purpose spending
                    redeemer Action:
                        amount : Integer
                        token  : TokenName
                        policy : PolicyId
                    rule "r"
                    when
                        Redeemer( Action( amount: $a ) )
                        Condition( $a > 0 )
                    then allow
                    default: deny
                    """);
        }
    }

    // ── Phase 2A Security Fix Tests ─────────────────────────────

    @Nested
    class Phase2ASecurityFixes {

        // C2: Input value binding only with ownAddress
        @Test
        void inputPattern_valueBinding_nonOwnAddress_error() {
            assertHasError("""
                    contract "T" version "1" purpose spending
                    params:
                        addr : PubKeyHash
                    rule "r"
                    when
                        Input( from: addr, value: $val )
                        Condition( $val > 0 )
                    then allow
                    default: deny
                    """, "JRL046");
        }

        // C2: Input(from: expr) without token/value is not useful
        @Test
        void inputPattern_fromOnly_nonOwnAddress_error() {
            assertHasError("""
                    contract "T" version "1" purpose spending
                    params:
                        addr : PubKeyHash
                    rule "r"
                    when
                        Input( from: addr )
                    then allow
                    default: deny
                    """, "JRL047");
        }

        // C2: Input(from: ownAddress, value: $val) is valid
        @Test
        void inputPattern_ownAddress_valueBinding_valid() {
            assertNoErrors("""
                    contract "T" version "1" purpose spending
                    rule "r"
                    when
                        Input( from: ownAddress, value: $val )
                        Condition( $val > 0 )
                    then allow
                    default: deny
                    """);
        }

        // C2: Input(token: contains(...)) without from is valid
        @Test
        void inputPattern_tokenOnly_valid() {
            assertNoErrors("""
                    contract "T" version "1" purpose spending
                    params:
                        authPolicy : PolicyId
                    rule "r"
                    when
                        Input( token: contains(authPolicy, "AUTH", 1) )
                    then allow
                    default: deny
                    """);
        }

        // C4: Literal match value error
        @Test
        void datumPattern_literalMatch_error() {
            assertHasError("""
                    contract "T" version "1" purpose spending
                    datum D:
                        owner : PubKeyHash
                    rule "r"
                    when
                        Datum( D( owner: 0xdead ) )
                    then allow
                    default: deny
                    """, "JRL050");
        }

        // H2: Mint requires at least token/amount/burned
        @Test
        void mintPattern_policyOnly_error() {
            assertHasError("""
                    contract "T" version "1" purpose minting
                    rule "r"
                    when
                        Mint( policy: ownPolicyId )
                    then allow
                    default: deny
                    """, "JRL044");
        }

        // H3: burned and amount mutually exclusive
        @Test
        void mintPattern_burnedAndAmount_error() {
            assertHasError("""
                    contract "T" version "1" purpose minting
                    rule "r"
                    when
                        Mint( policy: ownPolicyId, token: "T", burned, amount: $a )
                    then allow
                    default: deny
                    """, "JRL045");
        }

        // H4: Output with to + datum (no value) is valid
        @Test
        void outputPattern_toDatumOnly_valid() {
            assertNoErrors("""
                    contract "T" version "1" purpose spending
                    params:
                        receiver : PubKeyHash
                        refHash  : ByteString
                    record Receipt:
                        ref : ByteString
                    rule "r"
                    when
                        Output( to: receiver, Datum: inline Receipt( ref: refHash ) )
                    then allow
                    default: deny
                    """);
        }
    }
}
