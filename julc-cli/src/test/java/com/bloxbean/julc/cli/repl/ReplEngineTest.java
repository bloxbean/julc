package com.bloxbean.julc.cli.repl;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReplEngineTest {

    private static ReplEngine engine;

    @BeforeAll
    static void setup() {
        engine = new ReplEngine();
    }

    @Test
    void evaluateSimpleAddition() {
        var result = engine.evaluate("1 + 2");
        assertInstanceOf(ReplResult.Success.class, result);
        assertEquals("3", ((ReplResult.Success) result).formattedValue());
    }

    @Test
    void evaluateBooleanTrue() {
        var result = engine.evaluate("true");
        assertInstanceOf(ReplResult.Success.class, result);
        assertEquals("true", ((ReplResult.Success) result).formattedValue());
    }

    @Test
    void evaluateBooleanFalse() {
        var result = engine.evaluate("false");
        assertInstanceOf(ReplResult.Success.class, result);
        assertEquals("false", ((ReplResult.Success) result).formattedValue());
    }

    @Test
    void evaluateMultiplication() {
        var result = engine.evaluate("3 * 7");
        assertInstanceOf(ReplResult.Success.class, result);
        assertEquals("21", ((ReplResult.Success) result).formattedValue());
    }

    @Test
    void evaluateComparison() {
        var result = engine.evaluate("1 == 1");
        assertInstanceOf(ReplResult.Success.class, result);
        assertEquals("true", ((ReplResult.Success) result).formattedValue());
    }

    @Test
    void evaluateComparisonFalse() {
        var result = engine.evaluate("1 == 2");
        assertInstanceOf(ReplResult.Success.class, result);
        assertEquals("false", ((ReplResult.Success) result).formattedValue());
    }

    @Test
    void evaluateMathPow() {
        var result = engine.evaluate("MathLib.pow(2, 10)");
        assertInstanceOf(ReplResult.Success.class, result);
        assertEquals("1024", ((ReplResult.Success) result).formattedValue());
    }

    @Test
    void evaluateSha2_256() {
        var result = engine.evaluate("Builtins.sha2_256(new byte[]{1, 2, 3})");
        assertInstanceOf(ReplResult.Success.class, result);
        String value = ((ReplResult.Success) result).formattedValue();
        assertTrue(value.startsWith("#"), "Expected hex bytes, got: " + value);
        assertEquals(65, value.length(), "sha2_256 should produce 32 bytes (64 hex chars + #)");
    }

    @Test
    void evaluateBudgetPopulated() {
        var result = engine.evaluate("1 + 2");
        assertInstanceOf(ReplResult.Success.class, result);
        var success = (ReplResult.Success) result;
        assertTrue(success.budget().cpuSteps() > 0, "CPU should be > 0");
        assertTrue(success.budget().memoryUnits() > 0, "Mem should be > 0");
    }

    @Test
    void evaluateEmptyInput_returnsNull() {
        assertNull(engine.evaluate(""));
        assertNull(engine.evaluate("   "));
    }

    @Test
    void compilationError_returnsError() {
        var result = engine.evaluate("undeclaredVar.foo()");
        assertInstanceOf(ReplResult.Error.class, result);
    }

    @Test
    void helpCommand_returnsMetaOutput() {
        var result = engine.evaluate(":help");
        assertInstanceOf(ReplResult.MetaOutput.class, result);
        assertTrue(((ReplResult.MetaOutput) result).text().contains(":quit"));
    }

    @Test
    void libsCommand_listsStdlib() {
        var result = engine.evaluate(":libs");
        assertInstanceOf(ReplResult.MetaOutput.class, result);
        var text = ((ReplResult.MetaOutput) result).text();
        assertTrue(text.contains("ListsLib"));
        assertTrue(text.contains("MapLib"));
        assertTrue(text.contains("Builtins"));
    }

    @Test
    void methodsCommand_listsMethodsForClass() {
        var result = engine.evaluate(":methods Builtins");
        assertInstanceOf(ReplResult.MetaOutput.class, result);
        var text = ((ReplResult.MetaOutput) result).text();
        assertTrue(text.contains("sha2_256"));
    }

    @Test
    void methodsCommand_noClassName_returnsError() {
        var result = engine.evaluate(":methods");
        assertInstanceOf(ReplResult.Error.class, result);
    }

    @Test
    void budgetCommand_toggle() {
        engine.setShowBudget(true);
        var result = engine.evaluate(":budget off");
        assertInstanceOf(ReplResult.MetaOutput.class, result);
        assertFalse(engine.isShowBudget());

        result = engine.evaluate(":budget on");
        assertTrue(engine.isShowBudget());
    }

    @Test
    void importCommand_addsImport() {
        var result = engine.evaluate(":import com.example.Foo");
        assertInstanceOf(ReplResult.MetaOutput.class, result);
        assertTrue(((ReplResult.MetaOutput) result).text().contains("com.example.Foo"));
    }

    @Test
    void resetCommand_clearsState() {
        engine.evaluate(":import com.example.Bar");
        var result = engine.evaluate(":reset");
        assertInstanceOf(ReplResult.MetaOutput.class, result);
    }

    @Test
    void unknownCommand_returnsError() {
        var result = engine.evaluate(":foo");
        assertInstanceOf(ReplResult.Error.class, result);
        assertTrue(((ReplResult.Error) result).message().contains("Unknown command"));
    }

    @Test
    void docCommand_method_showsSignature() {
        var result = engine.evaluate(":doc MathLib.pow");
        assertInstanceOf(ReplResult.MetaOutput.class, result);
        var text = ((ReplResult.MetaOutput) result).text();
        assertTrue(text.contains("MathLib.pow("), "Expected signature, got: " + text);
        assertTrue(text.contains("BigInteger"), "Expected return type, got: " + text);
    }

    @Test
    void docCommand_method_showsJavadoc() {
        var result = engine.evaluate(":doc MathLib.abs");
        assertInstanceOf(ReplResult.MetaOutput.class, result);
        var text = ((ReplResult.MetaOutput) result).text();
        assertTrue(text.contains("absolute value"), "Expected javadoc, got: " + text);
    }

    @Test
    void docCommand_class_showsMethods() {
        var result = engine.evaluate(":doc MathLib");
        assertInstanceOf(ReplResult.MetaOutput.class, result);
        var text = ((ReplResult.MetaOutput) result).text();
        assertTrue(text.contains("MathLib"), "Expected class name, got: " + text);
        assertTrue(text.contains("Methods:"), "Expected method list, got: " + text);
    }

    @Test
    void docCommand_noArg_showsUsage() {
        var result = engine.evaluate(":doc");
        assertInstanceOf(ReplResult.MetaOutput.class, result);
        var text = ((ReplResult.MetaOutput) result).text();
        assertTrue(text.contains("Usage:"), "Expected usage hint, got: " + text);
    }

    @Test
    void docCommand_unknownMethod_returnsError() {
        var result = engine.evaluate(":doc UnknownClass.foo");
        assertInstanceOf(ReplResult.Error.class, result);
    }

    @Test
    void docCommand_unknownClass_returnsError() {
        var result = engine.evaluate(":doc UnknownClass");
        assertInstanceOf(ReplResult.Error.class, result);
    }

    @Test
    void helpCommand_mentionsDoc() {
        var result = engine.evaluate(":help");
        assertInstanceOf(ReplResult.MetaOutput.class, result);
        assertTrue(((ReplResult.MetaOutput) result).text().contains(":doc"));
    }

    @Test
    void formatValue_integer() {
        assertEquals("42", ReplEngine.formatValue(java.math.BigInteger.valueOf(42)));
    }

    @Test
    void formatValue_byteArray() {
        assertEquals("#0102ff", ReplEngine.formatValue(new byte[]{1, 2, (byte) 0xff}));
    }

    @Test
    void formatValue_boolean() {
        assertEquals("true", ReplEngine.formatValue(true));
        assertEquals("false", ReplEngine.formatValue(false));
    }

    @Test
    void formatValue_null() {
        assertEquals("()", ReplEngine.formatValue(null));
    }

    @Test
    void formatValue_string() {
        assertEquals("\"hello\"", ReplEngine.formatValue("hello"));
    }
}
