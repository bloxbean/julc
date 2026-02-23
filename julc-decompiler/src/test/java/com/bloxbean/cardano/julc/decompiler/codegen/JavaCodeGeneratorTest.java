package com.bloxbean.cardano.julc.decompiler.codegen;

import com.bloxbean.cardano.julc.decompiler.hir.HirTerm;
import com.bloxbean.cardano.julc.decompiler.input.ScriptAnalyzer;
import com.bloxbean.cardano.julc.decompiler.input.ScriptAnalyzer.PlutusVersion;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class JavaCodeGeneratorTest {

    private static final ScriptAnalyzer.ScriptStats DUMMY_STATS = new ScriptAnalyzer.ScriptStats(
            "1.1.0", PlutusVersion.V3, 10, 5, 1,
            Map.of(), Set.of(), 1, 1, 1, 0, 0, 1, false
    );

    @Test
    void errorInStatementPosition_emitsBuiltinsError() {
        // Error in statement position (inside if-else body)
        var hir = new HirTerm.Lambda(List.of("ctx"),
                new HirTerm.If(
                        new HirTerm.BoolLiteral(true),
                        new HirTerm.UnitLiteral(),
                        new HirTerm.Error()
                ));
        var code = JavaCodeGenerator.generate(hir, DUMMY_STATS);
        assertTrue(code.contains("Builtins.error(); // reject transaction"),
                "Statement-position error should emit 'Builtins.error(); // reject transaction'");
        assertFalse(code.contains("throw new RuntimeException"),
                "Should NOT emit 'throw new RuntimeException'");
    }

    @Test
    void errorInExpressionPosition_emitsBuiltinsError() {
        // Error in expression position (as a return value)
        var hir = new HirTerm.Lambda(List.of("ctx"), new HirTerm.Error());
        var code = JavaCodeGenerator.generate(hir, DUMMY_STATS);
        // In expression position, error becomes "return Builtins.error();"
        assertTrue(code.contains("Builtins.error()"),
                "Expression-position error should emit 'Builtins.error()'");
        assertFalse(code.contains("throw new RuntimeException"),
                "Should NOT emit 'throw new RuntimeException'");
    }
}
