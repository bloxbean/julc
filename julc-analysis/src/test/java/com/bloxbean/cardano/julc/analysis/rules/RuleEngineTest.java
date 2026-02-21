package com.bloxbean.cardano.julc.analysis.rules;

import com.bloxbean.cardano.julc.analysis.Category;
import com.bloxbean.cardano.julc.analysis.Severity;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.decompiler.DecompileResult;
import com.bloxbean.cardano.julc.decompiler.hir.HirTerm;
import com.bloxbean.cardano.julc.decompiler.hir.HirType;
import com.bloxbean.cardano.julc.decompiler.input.ScriptAnalyzer;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RuleEngineTest {

    // ---- Helper to create mock ScriptStats ----

    private ScriptAnalyzer.ScriptStats mockStats(Set<DefaultFun> builtins) {
        return new ScriptAnalyzer.ScriptStats(
                "1.1.0",
                ScriptAnalyzer.PlutusVersion.V3,
                100, 20, 2,
                Map.of("Lam", 5, "Apply", 30),
                builtins,
                5, 30, 10, 3, 2, 1, true
        );
    }

    private ScriptAnalyzer.ScriptStats mockStats() {
        return mockStats(Set.of());
    }

    private DecompileResult mockResult(HirTerm hir, Set<DefaultFun> builtins) {
        return new DecompileResult(null, "", mockStats(builtins), hir, null);
    }

    private DecompileResult mockResult(HirTerm hir) {
        return new DecompileResult(null, "", mockStats(), hir, null);
    }

    // ==== HardcodedCredentialRule ====

    @Test
    void hardcodedCredential_detects28ByteHash() {
        var hir = new HirTerm.Let("v",
                new HirTerm.ByteStringLiteral(new byte[28]),
                new HirTerm.Var("v", HirType.BYTE_STRING));

        var findings = new HardcodedCredentialRule().analyze(mockResult(hir));
        assertEquals(1, findings.size());
        assertEquals(Category.HARDCODED_CREDENTIAL, findings.getFirst().category());
        assertEquals(Severity.MEDIUM, findings.getFirst().severity());
    }

    @Test
    void hardcodedCredential_detects32ByteHash() {
        var hir = new HirTerm.ByteStringLiteral(new byte[32]);
        var findings = new HardcodedCredentialRule().analyze(mockResult(hir));
        assertEquals(1, findings.size());
    }

    @Test
    void hardcodedCredential_ignoresShortBytes() {
        var hir = new HirTerm.ByteStringLiteral(new byte[4]);
        var findings = new HardcodedCredentialRule().analyze(mockResult(hir));
        assertTrue(findings.isEmpty());
    }

    @Test
    void hardcodedCredential_ignoresOtherLengths() {
        var hir = new HirTerm.ByteStringLiteral(new byte[16]);
        var findings = new HardcodedCredentialRule().analyze(mockResult(hir));
        assertTrue(findings.isEmpty());
    }

    @Test
    void hardcodedCredential_detectsMultiple() {
        var hir = new HirTerm.Let("a",
                new HirTerm.ByteStringLiteral(new byte[28]),
                new HirTerm.Let("b",
                        new HirTerm.ByteStringLiteral(new byte[32]),
                        new HirTerm.Var("b", HirType.BYTE_STRING)));

        var findings = new HardcodedCredentialRule().analyze(mockResult(hir));
        assertEquals(2, findings.size());
    }

    @Test
    void hardcodedCredential_nullHir_returnsEmpty() {
        var result = new DecompileResult(null, "", mockStats(), null, null);
        var findings = new HardcodedCredentialRule().analyze(result);
        assertTrue(findings.isEmpty());
    }

    // ==== AuthorizationCheckRule ====

    @Test
    void authorizationCheck_flagsMissingWhenValueManipulated() {
        // Has AddInteger but no signatory check
        var hir = new HirTerm.BuiltinCall(DefaultFun.AddInteger, List.of(
                new HirTerm.IntLiteral(BigInteger.ONE),
                new HirTerm.IntLiteral(BigInteger.TWO)));

        var findings = new AuthorizationCheckRule().analyze(
                mockResult(hir, Set.of(DefaultFun.AddInteger)));
        assertEquals(1, findings.size());
        assertEquals(Category.MISSING_AUTHORIZATION, findings.getFirst().category());
        assertEquals(Severity.HIGH, findings.getFirst().severity());
    }

    @Test
    void authorizationCheck_passesWithSignatoryCheckAndEquality() {
        // Has signatory access + EqualsByteString
        var hir = new HirTerm.Let("sigs",
                new HirTerm.FieldAccess(
                        new HirTerm.Var("txInfo", HirType.DATA),
                        "signatories", 7, "TxInfo"),
                new HirTerm.BuiltinCall(DefaultFun.EqualsByteString, List.of(
                        new HirTerm.Var("expected", HirType.BYTE_STRING),
                        new HirTerm.Var("sigs", HirType.BYTE_STRING))));

        var findings = new AuthorizationCheckRule().analyze(
                mockResult(hir, Set.of(DefaultFun.AddInteger)));
        assertTrue(findings.isEmpty());
    }

    @Test
    void authorizationCheck_flagsSignatoryWithoutEquality() {
        // Has signatory access but no comparison
        var hir = new HirTerm.FieldAccess(
                new HirTerm.Var("txInfo", HirType.DATA),
                "signatories", 7, "TxInfo");

        var findings = new AuthorizationCheckRule().analyze(mockResult(hir));
        assertEquals(1, findings.size());
        assertEquals(Severity.MEDIUM, findings.getFirst().severity());
    }

    // ==== ValuePreservationRule ====

    @Test
    void valuePreservation_flagsMissingComparison() {
        // Has arithmetic + value access but no comparison
        var hir = new HirTerm.Let("v",
                new HirTerm.FieldAccess(
                        new HirTerm.Var("out", HirType.DATA),
                        "value", 1, "TxOut"),
                new HirTerm.BuiltinCall(DefaultFun.AddInteger, List.of(
                        new HirTerm.IntLiteral(BigInteger.ONE),
                        new HirTerm.IntLiteral(BigInteger.TWO))));

        var findings = new ValuePreservationRule().analyze(
                mockResult(hir, Set.of(DefaultFun.AddInteger)));
        assertEquals(1, findings.size());
        assertEquals(Category.VALUE_LEAK, findings.getFirst().category());
    }

    @Test
    void valuePreservation_passesWithComparison() {
        var hir = new HirTerm.Let("v",
                new HirTerm.FieldAccess(
                        new HirTerm.Var("out", HirType.DATA),
                        "value", 1, "TxOut"),
                new HirTerm.BuiltinCall(DefaultFun.EqualsInteger, List.of(
                        new HirTerm.IntLiteral(BigInteger.ONE),
                        new HirTerm.IntLiteral(BigInteger.TWO))));

        var findings = new ValuePreservationRule().analyze(
                mockResult(hir, Set.of(DefaultFun.AddInteger, DefaultFun.EqualsInteger)));
        assertTrue(findings.isEmpty());
    }

    // ==== TimeValidationRule ====

    @Test
    void timeValidation_flagsDeadlineWithoutRange() {
        var hir = new HirTerm.Trace(
                new HirTerm.StringLiteral("deadline passed"),
                new HirTerm.Error());

        var findings = new TimeValidationRule().analyze(mockResult(hir));
        assertEquals(1, findings.size());
        assertEquals(Category.TIME_VALIDATION, findings.getFirst().category());
    }

    @Test
    void timeValidation_passesWithValidRange() {
        var hir = new HirTerm.Let("range",
                new HirTerm.FieldAccess(
                        new HirTerm.Var("txInfo", HirType.DATA),
                        "validRange", 8, "TxInfo"),
                new HirTerm.Trace(
                        new HirTerm.StringLiteral("deadline check"),
                        new HirTerm.Var("range", HirType.DATA)));

        var findings = new TimeValidationRule().analyze(mockResult(hir));
        assertTrue(findings.isEmpty());
    }

    @Test
    void timeValidation_noTimeLiterals_noFinding() {
        var hir = new HirTerm.IntLiteral(BigInteger.ONE);
        var findings = new TimeValidationRule().analyze(mockResult(hir));
        assertTrue(findings.isEmpty());
    }

    // ==== UnboundedRecursionRule ====

    @Test
    void unboundedRecursion_flagsUnguardedLetRec() {
        // LetRec with no If/Switch guard in value
        var hir = new HirTerm.LetRec("loop",
                new HirTerm.Lambda(List.of("x"),
                        new HirTerm.FunCall("loop", List.of(
                                new HirTerm.Var("x", HirType.INTEGER)))),
                new HirTerm.FunCall("loop", List.of(
                        new HirTerm.IntLiteral(BigInteger.ZERO))));

        var findings = new UnboundedRecursionRule().analyze(mockResult(hir));
        assertEquals(1, findings.size());
        assertEquals(Category.UNBOUNDED_EXECUTION, findings.getFirst().category());
    }

    @Test
    void unboundedRecursion_passesWithGuard() {
        // LetRec with If guard
        var hir = new HirTerm.LetRec("loop",
                new HirTerm.Lambda(List.of("n"),
                        new HirTerm.If(
                                new HirTerm.BuiltinCall(DefaultFun.EqualsInteger, List.of(
                                        new HirTerm.Var("n", HirType.INTEGER),
                                        new HirTerm.IntLiteral(BigInteger.ZERO))),
                                new HirTerm.IntLiteral(BigInteger.ZERO),
                                new HirTerm.FunCall("loop", List.of(
                                        new HirTerm.Var("n", HirType.INTEGER))))),
                new HirTerm.FunCall("loop", List.of(
                        new HirTerm.IntLiteral(BigInteger.TEN))));

        var findings = new UnboundedRecursionRule().analyze(mockResult(hir));
        assertTrue(findings.isEmpty());
    }

    @Test
    void unboundedRecursion_passesWithNullListCheck() {
        var hir = new HirTerm.LetRec("fold",
                new HirTerm.Lambda(List.of("xs"),
                        new HirTerm.BuiltinCall(DefaultFun.NullList, List.of(
                                new HirTerm.Var("xs", new HirType.ListType(HirType.DATA))))),
                new HirTerm.FunCall("fold", List.of(
                        new HirTerm.Var("list", new HirType.ListType(HirType.DATA)))));

        var findings = new UnboundedRecursionRule().analyze(mockResult(hir));
        assertTrue(findings.isEmpty());
    }

    // ==== DoubleSatisfactionRule ====

    @Test
    void doubleSatisfaction_flagsMissingOwnInput() {
        // Accesses inputs but no purpose/txOutRef
        var hir = new HirTerm.FieldAccess(
                new HirTerm.Var("txInfo", HirType.DATA),
                "inputs", 0, "TxInfo");

        var findings = new DoubleSatisfactionRule().analyze(mockResult(hir));
        assertEquals(1, findings.size());
        assertEquals(Category.DOUBLE_SATISFACTION, findings.getFirst().category());
    }

    @Test
    void doubleSatisfaction_passesWithPurpose() {
        var hir = new HirTerm.Let("inp",
                new HirTerm.FieldAccess(
                        new HirTerm.Var("txInfo", HirType.DATA),
                        "inputs", 0, "TxInfo"),
                new HirTerm.FieldAccess(
                        new HirTerm.Var("ctx", HirType.DATA),
                        "purpose", 1, "ScriptContext"));

        var findings = new DoubleSatisfactionRule().analyze(mockResult(hir));
        assertTrue(findings.isEmpty());
    }

    @Test
    void doubleSatisfaction_noInputsAccess_noFinding() {
        var hir = new HirTerm.IntLiteral(BigInteger.ONE);
        var findings = new DoubleSatisfactionRule().analyze(mockResult(hir));
        assertTrue(findings.isEmpty());
    }

    // ==== DatumIntegrityRule ====

    @Test
    void datumIntegrity_flagsMissingDatumCheck() {
        // Accesses outputs + iterates but no datum check
        var hir = new HirTerm.Let("outs",
                new HirTerm.FieldAccess(
                        new HirTerm.Var("txInfo", HirType.DATA),
                        "outputs", 2, "TxInfo"),
                new HirTerm.ForEach("out",
                        new HirTerm.Var("outs", new HirType.ListType(HirType.DATA)),
                        "acc", new HirTerm.IntLiteral(BigInteger.ZERO),
                        new HirTerm.BuiltinCall(DefaultFun.AddInteger, List.of(
                                new HirTerm.Var("acc", HirType.INTEGER),
                                new HirTerm.IntLiteral(BigInteger.ONE)))));

        var findings = new DatumIntegrityRule().analyze(mockResult(hir));
        assertEquals(1, findings.size());
        assertEquals(Category.DATUM_INTEGRITY, findings.getFirst().category());
    }

    @Test
    void datumIntegrity_passesWithDatumAccess() {
        var hir = new HirTerm.Let("outs",
                new HirTerm.FieldAccess(
                        new HirTerm.Var("txInfo", HirType.DATA),
                        "outputs", 2, "TxInfo"),
                new HirTerm.ForEach("out",
                        new HirTerm.Var("outs", new HirType.ListType(HirType.DATA)),
                        "acc", new HirTerm.IntLiteral(BigInteger.ZERO),
                        new HirTerm.FieldAccess(
                                new HirTerm.Var("out", HirType.DATA),
                                "datum", 2, "TxOut")));

        var findings = new DatumIntegrityRule().analyze(mockResult(hir));
        assertTrue(findings.isEmpty());
    }

    // ==== RuleEngine aggregation ====

    @Test
    void ruleEngine_aggregatesMultipleFindings() {
        // Create a tree that triggers multiple rules
        var hir = new HirTerm.Let("hash",
                new HirTerm.ByteStringLiteral(new byte[28]),  // hardcoded cred
                new HirTerm.FieldAccess(
                        new HirTerm.Var("txInfo", HirType.DATA),
                        "inputs", 0, "TxInfo"));  // double satisfaction (no purpose)

        var engine = new RuleEngine();
        var findings = engine.analyze(mockResult(hir));

        // Should have at least hardcoded credential + double satisfaction
        assertTrue(findings.size() >= 2,
                "Expected at least 2 findings, got: " + findings.size());

        var categories = findings.stream().map(f -> f.category()).toList();
        assertTrue(categories.contains(Category.HARDCODED_CREDENTIAL));
        assertTrue(categories.contains(Category.DOUBLE_SATISFACTION));
    }

    @Test
    void ruleEngine_returnsEmptyForSimpleTree() {
        var hir = new HirTerm.BoolLiteral(true);
        var engine = new RuleEngine();
        var findings = engine.analyze(mockResult(hir));
        assertTrue(findings.isEmpty());
    }

    @Test
    void ruleEngine_hasSevenRules() {
        var engine = new RuleEngine();
        assertEquals(7, engine.rules().size());
    }
}
