package org.julclang.cli.repl;

import org.julclang.compiler.LibrarySource;
import org.julclang.compiler.LibrarySourceResolver;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

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
        var result = engine.evaluate("MathLib.pow(BigInteger.TWO, BigInteger.TEN)");
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
    void methodsCommand_reportsAmbiguousLibrarySimpleName() {
        var pool = new LinkedHashMap<String, LibrarySource>();
        var left = LibrarySourceResolver.librarySource("""
                package com.left;
                public final class Utils {
                    public static boolean leftOnly() { return true; }
                }
                """);
        var right = LibrarySourceResolver.librarySource("""
                package com.right;
                public final class Utils {
                    public static boolean rightOnly() { return true; }
                }
                """);
        pool.put(left.fqcn(), left);
        pool.put(right.fqcn(), right);
        var localEngine = new ReplEngine(pool);

        var result = localEngine.evaluate(":methods Utils");

        assertInstanceOf(ReplResult.Error.class, result);
        var message = ((ReplResult.Error) result).message();
        assertTrue(message.contains("Ambiguous library class 'Utils'"));
        assertTrue(message.contains("com.left.Utils"));
        assertTrue(message.contains("com.right.Utils"));
    }

    @Test
    void methodsCommand_acceptsFqcnForDuplicateLibrarySimpleName() {
        var pool = new LinkedHashMap<String, LibrarySource>();
        var left = LibrarySourceResolver.librarySource("""
                package com.left;
                public final class Utils {
                    public static boolean leftOnly() { return true; }
                }
                """);
        var right = LibrarySourceResolver.librarySource("""
                package com.right;
                public final class Utils {
                    public static boolean rightOnly() { return true; }
                }
                """);
        pool.put(left.fqcn(), left);
        pool.put(right.fqcn(), right);
        var localEngine = new ReplEngine(pool);

        var result = localEngine.evaluate(":methods com.left.Utils");

        assertInstanceOf(ReplResult.MetaOutput.class, result);
        var text = ((ReplResult.MetaOutput) result).text();
        assertTrue(text.contains("leftOnly"));
        assertFalse(text.contains("rightOnly"));
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
        // javac checks the import (ADR-068 D9): an unknown package fails the next evaluation, until :reset.
        assertInstanceOf(ReplResult.Error.class, engine.evaluate("1 + 2"));
        engine.evaluate(":reset");
        assertInstanceOf(ReplResult.Success.class, engine.evaluate("1 + 2"));
    }

    @Test
    void importCommand_usesAnImportedType() {
        engine.evaluate(":import org.julclang.core.types.JulcArray");
        try {
            var result = engine.evaluate("JulcArray.of(BigInteger.ONE, BigInteger.TWO).length()");
            assertInstanceOf(ReplResult.Success.class, result, result.toString());
            assertEquals("2", ((ReplResult.Success) result).formattedValue());
        } finally {
            engine.evaluate(":reset");
        }
    }

    @Test
    void integerArithmeticIsUnboundedOnChain() {
        // ADR-068 D9 / ADR-062 T6: the REPL evaluates on-chain, where 2147483647 + 1 does not wrap.
        var result = engine.evaluate("2147483647 + 1");
        assertInstanceOf(ReplResult.Success.class, result);
        assertEquals("2147483648", ((ReplResult.Success) result).formattedValue());
    }

    @Test
    void typeCommand_reportsJavacsType() {
        var result = engine.evaluate(":type 1 + 2");
        assertInstanceOf(ReplResult.MetaOutput.class, result);
        assertEquals("Type: BigInteger", ((ReplResult.MetaOutput) result).text());
        var bool = engine.evaluate(":type 1 < 2");
        assertEquals("Type: boolean", ((ReplResult.MetaOutput) bool).text());
    }

    @Test
    void pirCommand_showsThePir() {
        var result = engine.evaluate(":pir 1 + 2");
        assertInstanceOf(ReplResult.MetaOutput.class, result);
        assertFalse(((ReplResult.MetaOutput) result).text().isBlank());
    }

    @Test
    void javacRejection_isTheError() {
        var result = engine.evaluate("1 + true");
        assertInstanceOf(ReplResult.Error.class, result);
        assertTrue(((ReplResult.Error) result).message().contains("JULC0085"), result.toString());
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
