package org.julclang.compiler.validate;

import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.error.CompilerDiagnostic;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SubsetValidatorTest {

    private List<CompilerDiagnostic> validate(String code) {
        return new JulcCompiler().subsetDiagnostics(code, List.of());
    }

    private void assertRejects(String code, String expectedMessage) {
        var diags = validate(code);
        assertFalse(diags.isEmpty(), "Expected errors but got none");
        assertTrue(diags.stream().anyMatch(d -> d.message().contains(expectedMessage)),
                "Expected error containing '" + expectedMessage + "' but got: " + diags);
    }

    private void assertAccepts(String code) {
        var diags = validate(code);
        var errors = diags.stream().filter(CompilerDiagnostic::isError).toList();
        assertTrue(errors.isEmpty(), "Expected no errors but got: " + errors);
    }

    private void assertWarns(String code, String expectedMessage) {
        var diags = validate(code);
        assertTrue(diags.stream().anyMatch(d -> d.level() == CompilerDiagnostic.Level.WARNING
                        && d.message().contains(expectedMessage)),
                "Expected warning containing '" + expectedMessage + "' but got: " + diags);
    }

    private void assertNoWarnings(String code) {
        var diags = validate(code);
        var warnings = diags.stream()
                .filter(d -> d.level() == CompilerDiagnostic.Level.WARNING).toList();
        assertTrue(warnings.isEmpty(), "Expected no warnings but got: " + warnings);
    }

    @Test void rejectsTryCatch() { assertRejects("class X { void f() { try { } catch (Exception e) { } } }", "try/catch"); }
    @Test void rejectsThrow() { assertRejects("class X { void f() { throw new RuntimeException(); } }", "throw"); }
    @Test void rejectsNull() { assertRejects("class X { void f() { Object x = null; } }", "null"); }
    @Test void rejectsSynchronized() { assertRejects("class X { void f() { synchronized(this) { } } }", "synchronized"); }
    @Test void rejectsForLoop() { assertRejects("class X { void f() { for (int i = 0; i < 10; i++) { } } }", "for loops"); }
    @Test void acceptsForEach() { assertAccepts("class X { void f(java.util.List<Integer> xs) { for (var x : xs) { } } }"); }
    @Test void acceptsBreakInForEach() { assertAccepts("class X { void f(java.util.List<Integer> xs) { for (var x : xs) { break; } } }"); }
    @Test void rejectsBreakOutsideForEach() { assertRejects("class X { void f() { break; } }", "break outside switch or loop"); }
    @Test void acceptsWhile() { assertAccepts("class X { void f() { while (true) { } } }"); }
    @Test void rejectsDoWhile() { assertRejects("class X { void f() { do { } while (true); } }", "do-while"); }
    @Test void rejectsThis() { assertRejects("class X { void f() { Object x = this; } }", "this"); }
    @Test void rejectsSuper() { assertRejects("class X extends Y { void f() { super.f(); } }", "super"); }
    @Test void rejectsArrayCreation() { assertRejects("class X { void f() { int[] a = new int[5]; } }", "arrays"); }
    @Test void acceptsByteArrayLiteral() { assertAccepts("class X { void f() { byte[] b = new byte[]{0x48, 0x45}; } }"); }
    @Test void acceptsEmptyByteArrayLiteral() { assertAccepts("class X { void f() { byte[] b = new byte[]{}; } }"); }
    @Test void rejectsIntArrayLiteral() { assertRejects("class X { void f() { int[] a = new int[]{1, 2}; } }", "arrays"); }
    @Test void rejectsByteArrayWithVarElement() { assertRejects("class X { void f(int v) { byte[] b = new byte[]{(byte)v}; } }", "arrays"); }
    @Test void rejectsArrayAccess() { assertRejects("class X { void f(int[] a) { int x = a[0]; } }", "array access"); }
    @Test void rejectsFloat() { assertRejects("class X { void f(float x) { } }", "floating point"); }
    @Test void rejectsDouble() { assertRejects("class X { void f(double x) { } }", "floating point"); }
    @Test void rejectsInheritance() { assertRejects("class Y { } class X extends Y { }", "inheritance"); }

    @Test
    void acceptsValidCode() {
        assertAccepts("""
            import java.math.BigInteger;
            class MyValidator {
                static boolean validate(BigInteger a, BigInteger b) {
                    var sum = a;
                    if (sum.equals(b)) { return true; }
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

    // --- Item 2: Method references and lambda .apply() ---

    @Test
    void rejectsLambdaApply() {
        assertRejects("""
            import java.util.function.Function;
            class X {
                long f() {
                    Function<Long, Long> fn = x -> x + 1;
                    return fn.apply(42L);
                }
            }
            """, "Functional interface .apply()");
    }

    @Test
    void acceptsNormalMethodCall() {
        assertAccepts("""
            class X {
                static long bar(long x) { return x; }
                long f() { return bar(42L); }
            }
            """);
    }

    // --- Item 3: Unreachable code warnings ---

    @Test
    void warnsCodeAfterBuiltinsError() {
        assertWarns("""
            import org.julclang.stdlib.Builtins;
            class X {
                int f() {
                    Builtins.error();
                    return 0;
                }
            }
            """, "Unreachable code after Builtins.error()");
    }

    @Test
    void noWarningForNormalFlow() {
        assertNoWarnings("""
            class X {
                int f(int a) {
                    int x = a + 1;
                    int y = x + 2;
                    return y;
                }
            }
            """);
    }

}
