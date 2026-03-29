package com.bloxbean.julc.cli.repl;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReplCompleterTest {

    private static ReplCompleter completer;

    @BeforeAll
    static void setup() {
        completer = new ReplCompleter();
    }

    @Test
    void classNamesIncludeListsLib() {
        assertTrue(completer.getClassNames().contains("ListsLib"));
    }

    @Test
    void classNamesIncludeBuiltins() {
        assertTrue(completer.getClassNames().contains("Builtins"));
    }

    @Test
    void classNamesIncludeMapLib() {
        assertTrue(completer.getClassNames().contains("MapLib"));
    }

    @Test
    void methodsForBuiltins_containsSha2() {
        var methods = completer.getMethodsForClass("Builtins");
        assertTrue(methods.contains("sha2_256"), "Expected sha2_256 in Builtins methods");
    }

    @Test
    void methodsForMapLib_containsLookup() {
        var methods = completer.getMethodsForClass("MapLib");
        assertTrue(methods.contains("lookup"), "Expected lookup in MapLib methods, got: " + methods);
    }

    @Test
    void methodsForMathLib_containsPow() {
        var methods = completer.getMethodsForClass("MathLib");
        assertTrue(methods.contains("pow"), "Expected pow in MathLib methods, got: " + methods);
    }

    @Test
    void methodsForByteStringLib_notEmpty() {
        var methods = completer.getMethodsForClass("ByteStringLib");
        assertFalse(methods.isEmpty(), "ByteStringLib should have methods");
    }

    @Test
    void methodsForUnknownClass_isEmpty() {
        assertTrue(completer.getMethodsForClass("UnknownClass").isEmpty());
    }

    @Test
    void extractPublicStaticMethods_parsesSource() {
        var source = """
                public class Foo {
                    public static BigInteger bar(int x) { return null; }
                    public static boolean baz() { return true; }
                    private static void hidden() {}
                    public void instance() {}
                }
                """;
        var methods = ReplCompleter.extractPublicStaticMethods(source);
        assertTrue(methods.contains("bar"));
        assertTrue(methods.contains("baz"));
        assertFalse(methods.contains("hidden"));
        assertFalse(methods.contains("instance"));
    }

    @Test
    void metaCommandsIncludeHelp() {
        assertTrue(completer.getMetaCommands().contains(":help"));
    }

    @Test
    void metaCommandsIncludeQuit() {
        assertTrue(completer.getMetaCommands().contains(":quit"));
    }

    @Test
    void metaCommandsIncludeMethods() {
        assertTrue(completer.getMetaCommands().contains(":methods"));
    }

    @Test
    void metaCommandsIncludeDoc() {
        assertTrue(completer.getMetaCommands().contains(":doc"));
    }
}
