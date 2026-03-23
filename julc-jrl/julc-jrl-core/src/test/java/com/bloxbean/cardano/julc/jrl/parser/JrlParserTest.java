package com.bloxbean.cardano.julc.jrl.parser;

import com.bloxbean.cardano.julc.jrl.ast.*;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JrlParserTest {

    private ContractNode parse(String source) {
        var result = JrlParser.parse(source, "test.jrl");
        assertFalse(result.hasErrors(),
                () -> "Parse errors: " + result.diagnostics());
        assertNotNull(result.contract());
        return result.contract();
    }

    // ── Header ──────────────────────────────────────────────────

    @Nested
    class HeaderTests {

        @Test
        void minimalContract() {
            var contract = parse("""
                    contract "Simple"
                    version  "1.0"
                    purpose  spending
                    rule "always" when Condition( true ) then allow
                    default: deny
                    """);
            assertEquals("Simple", contract.name());
            assertEquals("1.0", contract.version());
            assertEquals(PurposeType.SPENDING, contract.purpose());
        }

        @Test
        void allPurposeTypes() {
            for (var purpose : new String[]{"spending", "minting", "withdraw",
                    "certifying", "voting", "proposing"}) {
                var contract = parse("""
                        contract "T" version "1" purpose %s
                        rule "r" when Condition( true ) then allow
                        default: deny
                        """.formatted(purpose));
                assertNotNull(contract.purpose());
            }
        }

        @Test
        void noPurpose_multiValidator() {
            var contract = parse("""
                    contract "Multi" version "1"
                    purpose minting:
                        rule "r" when Condition( true ) then allow
                        default: deny
                    purpose spending:
                        rule "r2" when Condition( true ) then allow
                        default: deny
                    """);
            assertNull(contract.purpose());
            assertTrue(contract.isMultiValidator());
            assertEquals(2, contract.purposeSections().size());
        }
    }

    // ── Params ──────────────────────────────────────────────────

    @Nested
    class ParamTests {

        @Test
        void simpleParams() {
            var contract = parse("""
                    contract "T" version "1" purpose spending
                    params:
                        beneficiary : PubKeyHash
                        cliff       : POSIXTime
                    rule "r" when Condition( true ) then allow
                    default: deny
                    """);
            assertEquals(2, contract.params().size());
            assertEquals("beneficiary", contract.params().get(0).name());
            assertInstanceOf(TypeRef.SimpleType.class, contract.params().get(0).type());
            assertEquals("PubKeyHash",
                    ((TypeRef.SimpleType) contract.params().get(0).type()).name());
        }

        @Test
        void listAndOptionalTypes() {
            var contract = parse("""
                    contract "T" version "1" purpose spending
                    params:
                        signers : List PubKeyHash
                        maybeX  : Optional Integer
                    rule "r" when Condition( true ) then allow
                    default: deny
                    """);
            assertInstanceOf(TypeRef.ListType.class, contract.params().get(0).type());
            assertInstanceOf(TypeRef.OptionalType.class, contract.params().get(1).type());
        }
    }

    // ── Datum ───────────────────────────────────────────────────

    @Nested
    class DatumTests {

        @Test
        void datumWithFields() {
            var contract = parse("""
                    contract "T" version "1" purpose spending
                    datum VestingDatum:
                        owner    : PubKeyHash
                        deadline : POSIXTime
                    rule "r" when Condition( true ) then allow
                    default: deny
                    """);
            assertNotNull(contract.datum());
            assertEquals("VestingDatum", contract.datum().name());
            assertEquals(2, contract.datum().fields().size());
            assertEquals("owner", contract.datum().fields().get(0).name());
        }
    }

    // ── Redeemer ────────────────────────────────────────────────

    @Nested
    class RedeemerTests {

        @Test
        void variantRedeemer() {
            var contract = parse("""
                    contract "T" version "1" purpose spending
                    redeemer MyAction:
                        | Claim
                        | Extend:
                            new_deadline : POSIXTime
                    rule "r" when Condition( true ) then allow
                    default: deny
                    """);
            assertNotNull(contract.redeemer());
            assertTrue(contract.redeemer().isVariantStyle());
            assertEquals(2, contract.redeemer().variants().size());
            assertEquals("Claim", contract.redeemer().variants().get(0).name());
            assertEquals(0, contract.redeemer().variants().get(0).fields().size());
            assertEquals("Extend", contract.redeemer().variants().get(1).name());
            assertEquals(1, contract.redeemer().variants().get(1).fields().size());
        }

        @Test
        void recordRedeemer() {
            var contract = parse("""
                    contract "T" version "1" purpose spending
                    redeemer EscrowAction:
                        action : Integer
                    rule "r" when Condition( true ) then allow
                    default: deny
                    """);
            assertNotNull(contract.redeemer());
            assertFalse(contract.redeemer().isVariantStyle());
            assertEquals(1, contract.redeemer().fields().size());
        }
    }

    // ── Records ─────────────────────────────────────────────────

    @Nested
    class RecordTests {

        @Test
        void helperRecord() {
            var contract = parse("""
                    contract "T" version "1" purpose spending
                    record NestedInfo:
                        no  : Integer
                        msg : Text
                    rule "r" when Condition( true ) then allow
                    default: deny
                    """);
            assertEquals(1, contract.records().size());
            assertEquals("NestedInfo", contract.records().get(0).name());
            assertEquals(2, contract.records().get(0).fields().size());
        }
    }

    // ── Rules and fact patterns ─────────────────────────────────

    @Nested
    class RuleTests {

        @Test
        void ruleWithRedeemerPattern() {
            var contract = parse("""
                    contract "T" version "1" purpose spending
                    redeemer Action:
                        | Claim
                    rule "claim it" when Redeemer( Claim ) then allow
                    default: deny
                    """);
            assertEquals(1, contract.rules().size());
            assertEquals("claim it", contract.rules().get(0).name());
            assertEquals(DefaultAction.ALLOW, contract.rules().get(0).action());
            assertInstanceOf(FactPattern.RedeemerPattern.class,
                    contract.rules().get(0).patterns().get(0));
        }

        @Test
        void ruleWithTransactionSignedBy() {
            var contract = parse("""
                    contract "T" version "1" purpose spending
                    params:
                        owner : PubKeyHash
                    rule "signed" when Transaction( signedBy: owner ) then allow
                    default: deny
                    """);
            var pattern = (FactPattern.TransactionPattern)
                    contract.rules().get(0).patterns().get(0);
            assertEquals(FactPattern.TxField.SIGNED_BY, pattern.field());
            assertInstanceOf(Expression.IdentRefExpr.class, pattern.value());
        }

        @Test
        void ruleWithTransactionValidAfter() {
            var contract = parse("""
                    contract "T" version "1" purpose spending
                    rule "timed"
                    when
                        Transaction( validAfter: $deadline )
                    then allow
                    default: deny
                    """);
            var pattern = (FactPattern.TransactionPattern)
                    contract.rules().get(0).patterns().get(0);
            assertEquals(FactPattern.TxField.VALID_AFTER, pattern.field());
            assertInstanceOf(Expression.VarRefExpr.class, pattern.value());
        }

        @Test
        void ruleWithCondition() {
            var contract = parse("""
                    contract "T" version "1" purpose spending
                    rule "check"
                    when
                        Condition( $a > $b )
                    then allow
                    default: deny
                    """);
            var pattern = (FactPattern.ConditionPattern)
                    contract.rules().get(0).patterns().get(0);
            assertInstanceOf(Expression.BinaryExpr.class, pattern.condition());
        }

        @Test
        void ruleWithDatumPattern() {
            var contract = parse("""
                    contract "T" version "1" purpose spending
                    datum MyDatum:
                        owner : PubKeyHash
                    rule "bound"
                    when
                        Datum( MyDatum( owner: $o ) )
                    then allow
                    default: deny
                    """);
            var pattern = (FactPattern.DatumPattern)
                    contract.rules().get(0).patterns().get(0);
            assertEquals("MyDatum", pattern.match().typeName());
            assertEquals(1, pattern.match().fields().size());
            assertInstanceOf(MatchValue.Binding.class,
                    pattern.match().fields().get(0).value());
            assertEquals("o",
                    ((MatchValue.Binding) pattern.match().fields().get(0).value()).varName());
        }

        @Test
        void ruleWithOutputMinADA() {
            var contract = parse("""
                    contract "T" version "1" purpose spending
                    rule "pay"
                    when
                        Output( to: $addr, value: minADA( 1000000 ) )
                    then allow
                    default: deny
                    """);
            var pattern = (FactPattern.OutputPattern)
                    contract.rules().get(0).patterns().get(0);
            assertNotNull(pattern.to());
            assertInstanceOf(ValueConstraint.MinADA.class, pattern.value());
        }

        @Test
        void ruleWithOutputContains() {
            var contract = parse("""
                    contract "T" version "1" purpose spending
                    rule "token"
                    when
                        Output( to: $addr, value: contains( $pol, $tok, $amt ) )
                    then allow
                    default: deny
                    """);
            var pattern = (FactPattern.OutputPattern)
                    contract.rules().get(0).patterns().get(0);
            assertInstanceOf(ValueConstraint.Contains.class, pattern.value());
        }

        @Test
        void ruleWithDenyAction() {
            var contract = parse("""
                    contract "T" version "1" purpose spending
                    rule "block" when Condition( false ) then deny
                    default: allow
                    """);
            assertEquals(DefaultAction.DENY, contract.rules().get(0).action());
            assertEquals(DefaultAction.ALLOW, contract.defaultAction());
        }

        @Test
        void ruleWithTrace() {
            var contract = parse("""
                    contract "T" version "1" purpose spending
                    rule "traced"
                    when Condition( true )
                    then allow
                    trace "Rule matched"
                    default: deny
                    """);
            assertEquals("Rule matched", contract.rules().get(0).traceMessage());
        }

        @Test
        void multipleRules() {
            var contract = parse("""
                    contract "T" version "1" purpose spending
                    rule "r1" when Condition( true ) then allow
                    rule "r2" when Condition( false ) then deny
                    default: deny
                    """);
            assertEquals(2, contract.rules().size());
        }

        @Test
        void multipleFactPatterns() {
            var contract = parse("""
                    contract "T" version "1" purpose spending
                    params:
                        owner : PubKeyHash
                    redeemer Action:
                        | Claim
                    rule "complex"
                    when
                        Redeemer( Claim )
                        Transaction( signedBy: owner )
                        Condition( true )
                    then allow
                    default: deny
                    """);
            assertEquals(3, contract.rules().get(0).patterns().size());
        }
    }

    // ── Expressions ─────────────────────────────────────────────

    @Nested
    class ExpressionTests {

        @Test
        void binaryExpression() {
            var contract = parse("""
                    contract "T" version "1" purpose spending
                    rule "r" when Condition( $a + $b == 10 ) then allow
                    default: deny
                    """);
            var cond = ((FactPattern.ConditionPattern) contract.rules().get(0).patterns().get(0))
                    .condition();
            // Precedence: * > +/- > comparisons > == > && > ||
            // So ($a + $b) == 10 has root == with left = ($a + $b)
            assertInstanceOf(Expression.BinaryExpr.class, cond);
            var root = (Expression.BinaryExpr) cond;
            assertEquals(Expression.BinaryOp.EQ, root.op());
            assertInstanceOf(Expression.BinaryExpr.class, root.left());
        }

        @Test
        void functionCall() {
            var contract = parse("""
                    contract "T" version "1" purpose spending
                    rule "r" when Condition( sha2_256($x) == $y ) then allow
                    default: deny
                    """);
            var cond = ((FactPattern.ConditionPattern) contract.rules().get(0).patterns().get(0))
                    .condition();
            var eq = (Expression.BinaryExpr) cond;
            assertInstanceOf(Expression.FunctionCallExpr.class, eq.left());
            var call = (Expression.FunctionCallExpr) eq.left();
            assertEquals("sha2_256", call.name());
            assertEquals(1, call.args().size());
        }

        @Test
        void ownAddressExpr() {
            var contract = parse("""
                    contract "T" version "1" purpose spending
                    rule "r" when Output( to: ownAddress ) then allow
                    default: deny
                    """);
            var pattern = (FactPattern.OutputPattern) contract.rules().get(0).patterns().get(0);
            assertInstanceOf(Expression.OwnAddressExpr.class, pattern.to());
        }

        @Test
        void hexLiteral() {
            var contract = parse("""
                    contract "T" version "1" purpose spending
                    rule "r" when Condition( $x == 0xdeadbeef ) then allow
                    default: deny
                    """);
            var eq = (Expression.BinaryExpr) ((FactPattern.ConditionPattern)
                    contract.rules().get(0).patterns().get(0)).condition();
            assertInstanceOf(Expression.HexLiteralExpr.class, eq.right());
        }

        @Test
        void fieldAccess() {
            var contract = parse("""
                    contract "T" version "1" purpose spending
                    rule "r" when Condition( $d.owner == $x ) then allow
                    default: deny
                    """);
            var eq = (Expression.BinaryExpr) ((FactPattern.ConditionPattern)
                    contract.rules().get(0).patterns().get(0)).condition();
            assertInstanceOf(Expression.FieldAccessExpr.class, eq.left());
        }
    }

    // ── Multi-validator ─────────────────────────────────────────

    @Nested
    class MultiValidatorTests {

        @Test
        void purposeSections() {
            var contract = parse("""
                    contract "Auction" version "1"
                    params:
                        seller : PubKeyHash
                    purpose minting:
                        redeemer MintAction:
                            | CreateAuction
                        rule "create" when Redeemer( CreateAuction ) then allow
                        default: deny
                    purpose spending:
                        rule "spend" when Condition( true ) then allow
                        default: deny
                    """);
            assertTrue(contract.isMultiValidator());
            assertEquals(2, contract.purposeSections().size());
            assertEquals(PurposeType.MINTING, contract.purposeSections().get(0).purpose());
            assertEquals(PurposeType.SPENDING, contract.purposeSections().get(1).purpose());
            assertNotNull(contract.purposeSections().get(0).redeemer());
        }
    }

    // ── Comments ────────────────────────────────────────────────

    @Nested
    class CommentTests {

        @Test
        void lineComments() {
            var contract = parse("""
                    -- This is a comment
                    contract "T" version "1" purpose spending
                    -- Another comment
                    rule "r" when Condition( true ) then allow
                    default: deny
                    """);
            assertNotNull(contract);
        }

        @Test
        void blockComments() {
            var contract = parse("""
                    /* Block comment */
                    contract "T" version "1" purpose spending
                    rule "r" when Condition( true ) then allow
                    default: deny
                    """);
            assertNotNull(contract);
        }
    }

    // ── Error cases ─────────────────────────────────────────────

    @Nested
    class ErrorTests {

        @Test
        void syntaxError_missingVersion() {
            var result = JrlParser.parse("""
                    contract "T"
                    rule "r" when Condition( true ) then allow
                    default: deny
                    """, "test.jrl");
            assertTrue(result.hasErrors());
        }

        @Test
        void syntaxError_reportsLocation() {
            var result = JrlParser.parse("""
                    contract "T" version "1" purpose spending
                    rule "r" when ??? then allow
                    default: deny
                    """, "test.jrl");
            assertTrue(result.hasErrors());
            var diag = result.diagnostics().get(0);
            assertEquals("JRL000", diag.code());
            assertTrue(diag.sourceRange().startLine() > 0);
        }
    }

    // ── Full ADR examples ───────────────────────────────────────

    @Nested
    class AdrExampleTests {

        @Test
        void vestingValidator() {
            var contract = parse("""
                    contract "Vesting"
                    version  "1.0"
                    purpose  spending

                    datum VestingDatum:
                        lockUntil   : POSIXTime
                        owner       : PubKeyHash
                        beneficiary : PubKeyHash

                    rule "Owner can always withdraw"
                    when
                        Datum( VestingDatum( owner: $owner ) )
                        Transaction( signedBy: $owner )
                    then
                        allow

                    rule "Beneficiary can withdraw after deadline"
                    when
                        Datum( VestingDatum( lockUntil: $deadline, beneficiary: $ben ) )
                        Transaction( signedBy: $ben )
                        Transaction( validAfter: $deadline )
                    then
                        allow

                    default: deny
                    """);
            assertEquals("Vesting", contract.name());
            assertEquals(PurposeType.SPENDING, contract.purpose());
            assertNotNull(contract.datum());
            assertEquals(3, contract.datum().fields().size());
            assertEquals(2, contract.rules().size());
            assertEquals(DefaultAction.DENY, contract.defaultAction());
        }

        @Test
        void multiSigMinting() {
            var contract = parse("""
                    contract "MultiSigMinting"
                    version "1.0"
                    purpose  minting

                    redeemer MintAction:
                        | MintByAuthority:
                            authority : PubKeyHash
                        | BurnByOwner
                        | MintByMultiSig:
                            signer1 : PubKeyHash
                            signer2 : PubKeyHash

                    rule "Authority can mint"
                    when
                        Redeemer( MintByAuthority( authority: $auth ) )
                        Transaction( signedBy: $auth )
                    then allow

                    rule "Owner can burn"
                    when
                        Redeemer( BurnByOwner )
                    then allow

                    rule "Multi-sig mint"
                    when
                        Redeemer( MintByMultiSig( signer1: $s1, signer2: $s2 ) )
                        Transaction( signedBy: $s1 )
                        Transaction( signedBy: $s2 )
                    then allow

                    default: deny
                    """);
            assertEquals("MultiSigMinting", contract.name());
            assertEquals(PurposeType.MINTING, contract.purpose());
            assertTrue(contract.redeemer().isVariantStyle());
            assertEquals(3, contract.redeemer().variants().size());
            assertEquals(3, contract.rules().size());
        }

        @Test
        void htlcValidator() {
            var contract = parse("""
                    contract "HTLC"
                    version "1.0"
                    purpose  spending

                    params:
                        secretHash : ByteString
                        expiration : POSIXTime
                        owner      : PubKeyHash

                    redeemer HtlcAction:
                        | Guess:
                            answer : ByteString
                        | Withdraw

                    rule "Correct hash preimage unlocks"
                    when
                        Redeemer( Guess( answer: $answer ) )
                        Condition( sha2_256($answer) == secretHash )
                    then allow

                    rule "Owner can withdraw after expiration"
                    when
                        Redeemer( Withdraw )
                        Transaction( signedBy: owner )
                        Transaction( validAfter: expiration )
                    then allow

                    default: deny
                    """);
            assertEquals("HTLC", contract.name());
            assertEquals(3, contract.params().size());
            assertEquals(2, contract.rules().size());
        }
    }

    // ── Phase 2: New Fact Patterns ──────────────────────────────

    @Nested
    class Phase2FactPatterns {

        @Test
        void inputPattern_from() {
            var contract = parse("""
                    contract "T" version "1" purpose spending
                    params:
                        addr : PubKeyHash
                    rule "r"
                    when
                        Input( from: addr )
                    then allow
                    default: deny
                    """);
            assertEquals(1, contract.rules().size());
            var pattern = contract.rules().get(0).patterns().get(0);
            assertInstanceOf(FactPattern.InputPattern.class, pattern);
            var ip = (FactPattern.InputPattern) pattern;
            assertNotNull(ip.from());
            assertNull(ip.valueBinding());
            assertNull(ip.token());
        }

        @Test
        void inputPattern_tokenContains() {
            var contract = parse("""
                    contract "T" version "1" purpose spending
                    params:
                        authPolicy : PolicyId
                    rule "r"
                    when
                        Input( token: contains(authPolicy, "AUTH", 1) )
                    then allow
                    default: deny
                    """);
            var pattern = contract.rules().get(0).patterns().get(0);
            assertInstanceOf(FactPattern.InputPattern.class, pattern);
            var ip = (FactPattern.InputPattern) pattern;
            assertNull(ip.from());
            assertNotNull(ip.token());
            assertInstanceOf(ValueConstraint.Contains.class, ip.token());
        }

        @Test
        void inputPattern_fromWithValueBinding() {
            var contract = parse("""
                    contract "T" version "1" purpose spending
                    params:
                        addr : PubKeyHash
                    rule "r"
                    when
                        Input( from: ownAddress, value: $inputVal )
                    then allow
                    default: deny
                    """);
            var ip = (FactPattern.InputPattern) contract.rules().get(0).patterns().get(0);
            assertNotNull(ip.from());
            assertEquals("inputVal", ip.valueBinding());
        }

        @Test
        void mintPattern_policyTokenAmount() {
            var contract = parse("""
                    contract "T" version "1" purpose minting
                    rule "r"
                    when
                        Mint( policy: ownPolicyId, token: "MyToken", amount: $amt )
                    then allow
                    default: deny
                    """);
            var pattern = contract.rules().get(0).patterns().get(0);
            assertInstanceOf(FactPattern.MintPattern.class, pattern);
            var mp = (FactPattern.MintPattern) pattern;
            assertNotNull(mp.policy());
            assertNotNull(mp.token());
            assertEquals("amt", mp.amountBinding());
            assertFalse(mp.burned());
        }

        @Test
        void mintPattern_burned() {
            var contract = parse("""
                    contract "T" version "1" purpose minting
                    rule "r"
                    when
                        Mint( policy: ownPolicyId, token: "LPToken", burned )
                    then allow
                    default: deny
                    """);
            var mp = (FactPattern.MintPattern) contract.rules().get(0).patterns().get(0);
            assertTrue(mp.burned());
            assertNull(mp.amountBinding());
        }

        @Test
        void continuingOutputPattern_value() {
            var contract = parse("""
                    contract "T" version "1" purpose spending
                    rule "r"
                    when
                        ContinuingOutput( value: minADA( 2000000 ) )
                    then allow
                    default: deny
                    """);
            var pattern = contract.rules().get(0).patterns().get(0);
            assertInstanceOf(FactPattern.ContinuingOutputPattern.class, pattern);
            var cop = (FactPattern.ContinuingOutputPattern) pattern;
            assertNotNull(cop.value());
            assertInstanceOf(ValueConstraint.MinADA.class, cop.value());
            assertNull(cop.datum());
        }

        @Test
        void continuingOutputPattern_datumInline() {
            var contract = parse("""
                    contract "T" version "1" purpose spending
                    datum StateDatum:
                        counter : Integer
                    rule "r"
                    when
                        ContinuingOutput( Datum: inline StateDatum( counter: 42 ) )
                    then allow
                    default: deny
                    """);
            var cop = (FactPattern.ContinuingOutputPattern) contract.rules().get(0).patterns().get(0);
            assertNull(cop.value());
            assertNotNull(cop.datum());
            assertEquals("StateDatum", cop.datum().typeName());
            assertEquals(1, cop.datum().fields().size());
            assertEquals("counter", cop.datum().fields().get(0).fieldName());
        }

        @Test
        void transactionPattern_fee() {
            var contract = parse("""
                    contract "T" version "1" purpose spending
                    rule "r"
                    when
                        Transaction( fee: $f )
                        Condition( $f <= 2000000 )
                    then allow
                    default: deny
                    """);
            var tp = (FactPattern.TransactionPattern) contract.rules().get(0).patterns().get(0);
            assertEquals(FactPattern.TxField.FEE, tp.field());
        }

        @Test
        void softKeywords_usableAsFieldNames() {
            // Ensure 'amount', 'token', 'policy', 'fee', 'from', 'burned' can be field names
            var contract = parse("""
                    contract "T" version "1" purpose spending
                    redeemer Action:
                        amount : Integer
                        token  : TokenName
                        policy : PolicyId
                        fee    : Lovelace
                        from   : PubKeyHash
                        burned : Boolean
                    rule "r"
                    when
                        Redeemer( Action( amount: $a ) )
                    then allow
                    default: deny
                    """);
            assertEquals(6, contract.redeemer().fields().size());
            assertEquals("amount", contract.redeemer().fields().get(0).name());
            assertEquals("token", contract.redeemer().fields().get(1).name());
            assertEquals("policy", contract.redeemer().fields().get(2).name());
            assertEquals("fee", contract.redeemer().fields().get(3).name());
            assertEquals("from", contract.redeemer().fields().get(4).name());
            assertEquals("burned", contract.redeemer().fields().get(5).name());
        }

        @Test
        void outputPattern_datumInline() {
            var contract = parse("""
                    contract "T" version "1" purpose spending
                    params:
                        receiver : PubKeyHash
                    record PaymentReceipt:
                        payer : PubKeyHash
                    rule "r"
                    when
                        Output( to: receiver, value: minADA( 2000000 ), Datum: inline PaymentReceipt( payer: 0xdead ) )
                    then allow
                    default: deny
                    """);
            var op = (FactPattern.OutputPattern) contract.rules().get(0).patterns().get(0);
            assertNotNull(op.datum());
            assertEquals("PaymentReceipt", op.datum().typeName());
        }
    }
}
