package com.bloxbean.cardano.julc.jrl.transpile;

import com.bloxbean.cardano.julc.jrl.parser.JrlParser;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JavaTranspilerTest {

    private String transpile(String jrlSource) {
        var parseResult = JrlParser.parse(jrlSource, "test.jrl");
        assertFalse(parseResult.hasErrors(),
                () -> "Parse errors: " + parseResult.diagnostics());
        return JavaTranspiler.transpile(parseResult.contract());
    }

    // ── Structure tests ─────────────────────────────────────────

    @Nested
    class StructureTests {

        @Test
        void generatesSpendingAnnotation() {
            var java = transpile("""
                    contract "T" version "1" purpose spending
                    rule "r" when Condition( true ) then allow
                    default: deny
                    """);
            assertTrue(java.contains("@SpendingValidator"));
            assertTrue(java.contains("public class T"));
        }

        @Test
        void generatesMintingAnnotation() {
            var java = transpile("""
                    contract "T" version "1" purpose minting
                    rule "r" when Condition( true ) then allow
                    default: deny
                    """);
            assertTrue(java.contains("@MintingValidator"));
        }

        @Test
        void generatesImports() {
            var java = transpile("""
                    contract "T" version "1" purpose spending
                    rule "r" when Condition( true ) then allow
                    default: deny
                    """);
            assertTrue(java.contains("import com.bloxbean.cardano.julc.stdlib.annotation.*;"));
            assertTrue(java.contains("import com.bloxbean.cardano.julc.stdlib.lib.*;"));
            assertTrue(java.contains("import java.math.BigInteger;"));
        }

        @Test
        void generatesEntrypoint() {
            var java = transpile("""
                    contract "T" version "1" purpose spending
                    rule "r" when Condition( true ) then allow
                    default: deny
                    """);
            assertTrue(java.contains("@Entrypoint"));
            assertTrue(java.contains("public static boolean validate("));
        }

        @Test
        void spendingHasDatumParam() {
            var java = transpile("""
                    contract "T" version "1" purpose spending
                    rule "r" when Condition( true ) then allow
                    default: deny
                    """);
            assertTrue(java.contains("PlutusData datum, PlutusData redeemer, ScriptContext ctx"));
        }

        @Test
        void mintingHasNoDataParam() {
            var java = transpile("""
                    contract "T" version "1" purpose minting
                    rule "r" when Condition( true ) then allow
                    default: deny
                    """);
            assertTrue(java.contains("PlutusData redeemer, ScriptContext ctx"));
            assertFalse(java.contains("PlutusData datum,"));
        }
    }

    // ── Params ──────────────────────────────────────────────────

    @Nested
    class ParamTests {

        @Test
        void generatesParamFields() {
            var java = transpile("""
                    contract "T" version "1" purpose spending
                    params:
                        beneficiary : PubKeyHash
                        cliff       : POSIXTime
                    rule "r" when Condition( true ) then allow
                    default: deny
                    """);
            assertTrue(java.contains("@Param static byte[] beneficiary;"));
            assertTrue(java.contains("@Param static BigInteger cliff;"));
        }
    }

    // ── Type declarations ───────────────────────────────────────

    @Nested
    class TypeDeclTests {

        @Test
        void generatesDatumRecord() {
            var java = transpile("""
                    contract "T" version "1" purpose spending
                    datum VestingDatum:
                        owner    : PubKeyHash
                        deadline : POSIXTime
                    rule "r" when Condition( true ) then allow
                    default: deny
                    """);
            assertTrue(java.contains("record VestingDatum(byte[] owner, BigInteger deadline) {}"));
        }

        @Test
        void generatesVariantRedeemer() {
            var java = transpile("""
                    contract "T" version "1" purpose spending
                    redeemer Action:
                        | Claim
                        | Extend:
                            newDeadline : POSIXTime
                    rule "r" when Condition( true ) then allow
                    default: deny
                    """);
            assertTrue(java.contains("sealed interface Action permits Claim, Extend {}"));
            assertTrue(java.contains("record Claim() implements Action {}"));
            assertTrue(java.contains("record Extend(BigInteger newDeadline) implements Action {}"));
        }

        @Test
        void generatesRecordRedeemer() {
            var java = transpile("""
                    contract "T" version "1" purpose spending
                    redeemer EscrowAction:
                        action : Integer
                    rule "r" when Condition( true ) then allow
                    default: deny
                    """);
            assertTrue(java.contains("record EscrowAction(BigInteger action) {}"));
        }
    }

    // ── Fact pattern translation ────────────────────────────────

    @Nested
    class FactPatternTests {

        @Test
        void signedByTranslation() {
            var java = transpile("""
                    contract "T" version "1" purpose spending
                    params:
                        owner : PubKeyHash
                    rule "r"
                    when Transaction( signedBy: owner )
                    then allow
                    default: deny
                    """);
            assertTrue(java.contains("ListsLib.contains(txInfo.signatories(), owner)"));
        }

        @Test
        void validAfterTranslation() {
            var java = transpile("""
                    contract "T" version "1" purpose spending
                    params:
                        deadline : POSIXTime
                    rule "r"
                    when Transaction( validAfter: deadline )
                    then allow
                    default: deny
                    """);
            assertTrue(java.contains("IntervalLib.finiteLowerBound(txInfo.validRange()).compareTo(deadline) >= 0"));
        }

        @Test
        void conditionTranslation() {
            var java = transpile("""
                    contract "T" version "1" purpose spending
                    rule "r"
                    when Condition( true )
                    then allow
                    default: deny
                    """);
            assertTrue(java.contains("if (true)"));
        }

        @Test
        void redeemerInstanceOf() {
            var java = transpile("""
                    contract "T" version "1" purpose spending
                    redeemer Action:
                        | Claim
                    rule "r"
                    when Redeemer( Claim )
                    then allow
                    default: deny
                    """);
            assertTrue(java.contains("Builtins.constrTag(redeemer) == 0"));
        }

        @Test
        void datumFieldBinding() {
            var java = transpile("""
                    contract "T" version "1" purpose spending
                    datum D:
                        owner : PubKeyHash
                    rule "r"
                    when
                        Datum( D( owner: $o ) )
                        Transaction( signedBy: $o )
                    then allow
                    default: deny
                    """);
            assertTrue(java.contains("var _d0 = (D) datum;"));
            assertTrue(java.contains("var o = _d0.owner();"));
            assertTrue(java.contains("ListsLib.contains(txInfo.signatories(), o)"));
        }

        @Test
        void minAdaOutputTranslation() {
            var java = transpile("""
                    contract "T" version "1" purpose spending
                    rule "r"
                    when
                        Output( to: $addr, value: minADA( 1000000 ) )
                    then allow
                    default: deny
                    """);
            assertTrue(java.contains("OutputLib.lovelacePaidTo(txInfo.outputs(), addr)"));
        }

        @Test
        void builtinFunctionTranslation() {
            var java = transpile("""
                    contract "T" version "1" purpose spending
                    params:
                        h : ByteString
                    datum D:
                        val : ByteString
                    rule "r"
                    when
                        Datum( D( val: $v ) )
                        Condition( sha2_256($v) == h )
                    then allow
                    default: deny
                    """);
            assertTrue(java.contains("Builtins.sha2_256(v)"));
        }
    }

    // ── Default action ──────────────────────────────────────────

    @Nested
    class DefaultTests {

        @Test
        void defaultDeny() {
            var java = transpile("""
                    contract "T" version "1" purpose spending
                    rule "r" when Condition( true ) then allow
                    default: deny
                    """);
            assertTrue(java.contains("return false;"));
        }

        @Test
        void defaultAllow() {
            var java = transpile("""
                    contract "T" version "1" purpose spending
                    rule "r" when Condition( false ) then deny
                    default: allow
                    """);
            assertTrue(java.contains("return true;"));
        }
    }

    // ── Multi-validator ─────────────────────────────────────────

    @Nested
    class MultiValidatorTests {

        @Test
        void generatesMultiValidatorAnnotation() {
            var java = transpile("""
                    contract "Multi" version "1"
                    purpose minting:
                        rule "mint" when Condition( true ) then allow
                        default: deny
                    purpose spending:
                        rule "spend" when Condition( true ) then allow
                        default: deny
                    """);
            assertTrue(java.contains("@MultiValidator"));
            assertTrue(java.contains("@Entrypoint(purpose = Purpose.MINT)"));
            assertTrue(java.contains("@Entrypoint(purpose = Purpose.SPEND)"));
            assertTrue(java.contains("handleMinting"));
            assertTrue(java.contains("handleSpending"));
        }
    }

    // ── Full ADR example ────────────────────────────────────────

    @Test
    void vestingTranspilation() {
        var java = transpile("""
                contract "Vesting" version "1.0" purpose spending
                datum VestingDatum:
                    lockUntil   : POSIXTime
                    owner       : PubKeyHash
                    beneficiary : PubKeyHash
                rule "Owner can always withdraw"
                when
                    Datum( VestingDatum( owner: $owner ) )
                    Transaction( signedBy: $owner )
                then allow
                rule "Beneficiary can withdraw after deadline"
                when
                    Datum( VestingDatum( lockUntil: $deadline, beneficiary: $ben ) )
                    Transaction( signedBy: $ben )
                    Transaction( validAfter: $deadline )
                then allow
                default: deny
                """);
        // Verify key elements
        assertTrue(java.contains("@SpendingValidator"));
        assertTrue(java.contains("record VestingDatum(BigInteger lockUntil, byte[] owner, byte[] beneficiary) {}"));
        assertTrue(java.contains("(VestingDatum) datum"));
        assertTrue(java.contains("ListsLib.contains(txInfo.signatories(),"));
        assertTrue(java.contains("IntervalLib.finiteLowerBound(txInfo.validRange())"));
        assertTrue(java.contains("return false;"));
    }
}
