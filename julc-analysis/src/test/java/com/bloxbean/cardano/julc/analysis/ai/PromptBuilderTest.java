package com.bloxbean.cardano.julc.analysis.ai;

import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.decompiler.input.ScriptAnalyzer;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PromptBuilderTest {

    private ScriptAnalyzer.ScriptStats mockStats() {
        return new ScriptAnalyzer.ScriptStats(
                "1.1.0",
                ScriptAnalyzer.PlutusVersion.V3,
                500, 30, 2,
                Map.of("Lam", 10, "Apply", 50),
                Set.of(DefaultFun.AddInteger, DefaultFun.EqualsData),
                10, 50, 20, 5, 3, 2, true
        );
    }

    @Test
    void systemPrompt_containsCardanoContext() {
        var prompt = PromptBuilder.systemPrompt();
        assertNotNull(prompt);
        assertFalse(prompt.isEmpty());
        assertTrue(prompt.contains("ScriptContext"));
        assertTrue(prompt.contains("double satisfaction") || prompt.contains("Double Satisfaction"));
        assertTrue(prompt.contains("UTxO") || prompt.contains("UTXO"));
        assertTrue(prompt.contains("Plutus"));
    }

    @Test
    void systemPrompt_containsVulnerabilityCategories() {
        var prompt = PromptBuilder.systemPrompt();
        assertTrue(prompt.contains("MISSING_AUTHORIZATION"));
        assertTrue(prompt.contains("VALUE_LEAK"));
        assertTrue(prompt.contains("HARDCODED_CREDENTIAL"));
        assertTrue(prompt.contains("DATUM_INTEGRITY"));
    }

    @Test
    void systemPrompt_containsSeverityLevels() {
        var prompt = PromptBuilder.systemPrompt();
        assertTrue(prompt.contains("CRITICAL"));
        assertTrue(prompt.contains("HIGH"));
        assertTrue(prompt.contains("MEDIUM"));
    }

    @Test
    void userPrompt_includesSourceAndStats() {
        var prompt = PromptBuilder.userPrompt("class Foo { void bar() {} }", mockStats());
        assertNotNull(prompt);
        assertTrue(prompt.contains("class Foo { void bar() {} }"));
        assertTrue(prompt.contains("V3"));
        assertTrue(prompt.contains("500"));
    }

    @Test
    void userPrompt_substitutesAllPlaceholders() {
        var prompt = PromptBuilder.userPrompt("source code here", mockStats());
        assertFalse(prompt.contains("{{"), "No unsubstituted placeholders should remain");
    }

    @Test
    void outputFormat_specifiesJsonStructure() {
        var format = PromptBuilder.outputFormat();
        assertTrue(format.contains("severity"));
        assertTrue(format.contains("category"));
        assertTrue(format.contains("title"));
        assertTrue(format.contains("JSON"));
    }
}
