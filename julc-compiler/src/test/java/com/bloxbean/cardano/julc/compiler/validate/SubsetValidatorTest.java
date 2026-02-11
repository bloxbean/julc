package com.bloxbean.cardano.julc.compiler.validate;

import com.bloxbean.cardano.julc.compiler.error.CompilerDiagnostic;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SubsetValidatorTest {

    @BeforeAll
    static void configureParser() {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
    }

    private List<CompilerDiagnostic> validate(String code) {
        var cu = StaticJavaParser.parse(code);
        return new SubsetValidator().validate(cu);
    }

    private void assertRejects(String code, String expectedMessage) {
        var diags = validate(code);
        assertFalse(diags.isEmpty(), "Expected errors but got none");
        assertTrue(diags.stream().anyMatch(d -> d.message().contains(expectedMessage)),
                "Expected error containing '" + expectedMessage + "' but got: " + diags);
    }

    private void assertAccepts(String code) {
        var diags = validate(code);
        assertTrue(diags.isEmpty(), "Expected no errors but got: " + diags);
    }

    @Test void rejectsTryCatch() { assertRejects("class X { void f() { try { } catch (Exception e) { } } }", "try/catch"); }
    @Test void rejectsThrow() { assertRejects("class X { void f() { throw new RuntimeException(); } }", "throw"); }
    @Test void rejectsNull() { assertRejects("class X { void f() { Object x = null; } }", "null"); }
    @Test void rejectsSynchronized() { assertRejects("class X { void f() { synchronized(this) { } } }", "synchronized"); }
    @Test void rejectsForLoop() { assertRejects("class X { void f() { for (int i = 0; i < 10; i++) { } } }", "for loops"); }
    @Test void acceptsForEach() { assertAccepts("class X { void f(java.util.List<Integer> xs) { for (var x : xs) { } } }"); }
    @Test void acceptsBreakInForEach() { assertAccepts("class X { void f(java.util.List<Integer> xs) { for (var x : xs) { break; } } }"); }
    @Test void rejectsBreakOutsideForEach() { assertRejects("class X { void f() { break; } }", "break is only supported inside for-each"); }
    @Test void acceptsWhile() { assertAccepts("class X { void f() { while (true) { } } }"); }
    @Test void rejectsDoWhile() { assertRejects("class X { void f() { do { } while (true); } }", "do-while"); }
    @Test void acceptsLambda() { assertAccepts("class X { void f() { Runnable r = () -> {}; } }"); }
    @Test void rejectsThis() { assertRejects("class X { void f() { Object x = this; } }", "this"); }
    @Test void rejectsSuper() { assertRejects("class X extends Y { void f() { super.f(); } }", "super"); }
    @Test void rejectsArrayCreation() { assertRejects("class X { void f() { int[] a = new int[5]; } }", "arrays"); }
    @Test void rejectsArrayAccess() { assertRejects("class X { void f(int[] a) { int x = a[0]; } }", "array access"); }
    @Test void rejectsFloat() { assertRejects("class X { void f(float x) { } }", "floating point"); }
    @Test void rejectsDouble() { assertRejects("class X { void f(double x) { } }", "floating point"); }
    @Test void rejectsInheritance() { assertRejects("class X extends Y { }", "inheritance"); }

    @Test
    void acceptsValidCode() {
        assertAccepts("""
            import java.math.BigInteger;
            class MyValidator {
                static boolean validate(BigInteger a, BigInteger b) {
                    var sum = a;
                    if (sum == b) { return true; }
                    return false;
                }
            }
            """);
    }

    @Test
    void acceptsRecordDeclaration() {
        assertAccepts("""
            record MyDatum(int value, String name) {}
            """);
    }

    @Test
    void acceptsIfElse() {
        assertAccepts("class X { boolean f(boolean a) { if (a) { return true; } else { return false; } } }");
    }

    @Test
    void acceptsArithmetic() {
        assertAccepts("class X { int f(int a, int b) { return a + b * (a - b); } }");
    }

    @Test
    void multipleErrors() {
        var diags = validate("class X { void f() { try {} catch(Exception e) {} Object x = null; } }");
        assertTrue(diags.size() >= 2, "Expected at least 2 errors, got: " + diags.size());
    }

    @Test
    void diagnosticHasLocation() {
        var diags = validate("class X {\n  void f() {\n    Object x = null;\n  }\n}");
        assertFalse(diags.isEmpty());
        var d = diags.getFirst();
        assertTrue(d.line() > 0);
        assertTrue(d.isError());
    }

    @Test
    void diagnosticHasSuggestion() {
        var diags = validate("class X { void f() { try {} catch(Exception e) {} } }");
        assertFalse(diags.isEmpty());
        var d = diags.getFirst();
        assertTrue(d.hasSuggestion(), "Expected suggestion but got none");
        assertTrue(d.suggestion().contains("if/else"), "Expected suggestion about if/else");
    }

    @Test
    void nullSuggestionUsesOptional() {
        var diags = validate("class X { void f() { Object x = null; } }");
        assertFalse(diags.isEmpty());
        assertTrue(diags.getFirst().hasSuggestion());
        assertTrue(diags.getFirst().suggestion().contains("Optional"));
    }

    @Test
    void acceptsSealedInterface() {
        assertAccepts("""
            sealed interface Action permits Mint, Burn {}
            record Mint(int amt) implements Action {}
            record Burn(int amt) implements Action {}
            """);
    }
}
