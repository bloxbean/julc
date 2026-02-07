package com.bloxbean.cardano.plutus.compiler.pir;

import com.bloxbean.cardano.plutus.compiler.resolve.SymbolTable;
import com.bloxbean.cardano.plutus.compiler.resolve.TypeResolver;
import com.bloxbean.cardano.plutus.core.Constant;
import com.bloxbean.cardano.plutus.core.DefaultFun;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.expr.Expression;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

class PirGeneratorTest {

    TypeResolver typeResolver;
    SymbolTable symbolTable;
    PirGenerator generator;

    @BeforeEach
    void setUp() {
        typeResolver = new TypeResolver();
        symbolTable = new SymbolTable();
        generator = new PirGenerator(typeResolver, symbolTable);
        // Define common variables
        symbolTable.define("a", new PirType.IntegerType());
        symbolTable.define("b", new PirType.IntegerType());
        symbolTable.define("x", new PirType.BoolType());
        symbolTable.define("y", new PirType.BoolType());
    }

    private PirTerm compile(String expr) {
        var parsed = StaticJavaParser.parseExpression(expr);
        return generator.generateExpression(parsed);
    }

    @Nested
    class Literals {
        @Test void intLiteral() {
            var t = compile("42");
            assertInstanceOf(PirTerm.Const.class, t);
            assertEquals(BigInteger.valueOf(42), ((Constant.IntegerConst) ((PirTerm.Const) t).value()).value());
        }

        @Test void boolTrue() {
            var t = compile("true");
            assertInstanceOf(PirTerm.Const.class, t);
            assertTrue(((Constant.BoolConst) ((PirTerm.Const) t).value()).value());
        }

        @Test void boolFalse() {
            var t = compile("false");
            assertInstanceOf(PirTerm.Const.class, t);
            assertFalse(((Constant.BoolConst) ((PirTerm.Const) t).value()).value());
        }

        @Test void stringLiteral() {
            var t = compile("\"hello\"");
            assertInstanceOf(PirTerm.Const.class, t);
            assertEquals("hello", ((Constant.StringConst) ((PirTerm.Const) t).value()).value());
        }
    }

    @Nested
    class Variables {
        @Test void variableRef() {
            var t = compile("a");
            assertInstanceOf(PirTerm.Var.class, t);
            assertEquals("a", ((PirTerm.Var) t).name());
        }

        @Test void undefinedVariable() {
            assertThrows(Exception.class, () -> compile("undefined"));
        }
    }

    @Nested
    class Arithmetic {
        @Test void addition() {
            var t = compile("a + b");
            assertInstanceOf(PirTerm.App.class, t);
            var outer = (PirTerm.App) t;
            assertInstanceOf(PirTerm.App.class, outer.function());
            var inner = (PirTerm.App) outer.function();
            assertInstanceOf(PirTerm.Builtin.class, inner.function());
            assertEquals(DefaultFun.AddInteger, ((PirTerm.Builtin) inner.function()).fun());
        }

        @Test void subtraction() {
            var t = compile("a - b");
            assertBuiltinApp(t, DefaultFun.SubtractInteger);
        }

        @Test void multiplication() {
            var t = compile("a * b");
            assertBuiltinApp(t, DefaultFun.MultiplyInteger);
        }

        @Test void division() {
            var t = compile("a / b");
            assertBuiltinApp(t, DefaultFun.DivideInteger);
        }

        @Test void remainder() {
            var t = compile("a % b");
            assertBuiltinApp(t, DefaultFun.RemainderInteger);
        }

        @Test void negation() {
            var t = compile("-a");
            // Should be SubtractInteger(0, a)
            assertBuiltinApp(t, DefaultFun.SubtractInteger);
        }
    }

    @Nested
    class Comparisons {
        @Test void equals() { assertBuiltinApp(compile("a == b"), DefaultFun.EqualsInteger); }
        @Test void lessThan() { assertBuiltinApp(compile("a < b"), DefaultFun.LessThanInteger); }
        @Test void lessEquals() { assertBuiltinApp(compile("a <= b"), DefaultFun.LessThanEqualsInteger); }

        @Test void greaterThan() {
            // a > b -> LessThanInteger(b, a)
            assertBuiltinApp(compile("a > b"), DefaultFun.LessThanInteger);
        }

        @Test void greaterEquals() {
            assertBuiltinApp(compile("a >= b"), DefaultFun.LessThanEqualsInteger);
        }

        @Test void notEquals() {
            var t = compile("a != b");
            assertInstanceOf(PirTerm.IfThenElse.class, t);
        }
    }

    @Nested
    class BooleanLogic {
        @Test void and() {
            var t = compile("x && y");
            assertInstanceOf(PirTerm.IfThenElse.class, t);
            var ite = (PirTerm.IfThenElse) t;
            // IfThenElse(x, y, false)
            assertInstanceOf(PirTerm.Var.class, ite.cond());
            assertInstanceOf(PirTerm.Var.class, ite.thenBranch());
            assertInstanceOf(PirTerm.Const.class, ite.elseBranch());
        }

        @Test void or() {
            var t = compile("x || y");
            assertInstanceOf(PirTerm.IfThenElse.class, t);
            var ite = (PirTerm.IfThenElse) t;
            // IfThenElse(x, true, y)
            assertInstanceOf(PirTerm.Const.class, ite.thenBranch());
        }

        @Test void not() {
            var t = compile("!x");
            assertInstanceOf(PirTerm.IfThenElse.class, t);
        }
    }

    @Nested
    class ControlFlow {
        @Test void ternary() {
            var t = compile("x ? a : b");
            assertInstanceOf(PirTerm.IfThenElse.class, t);
        }

        @Test void parenthesized() {
            var t = compile("(a + b)");
            assertBuiltinApp(t, DefaultFun.AddInteger);
        }
    }

    // Helper to verify a builtin application
    private void assertBuiltinApp(PirTerm term, DefaultFun expectedFun) {
        assertInstanceOf(PirTerm.App.class, term);
        var app = (PirTerm.App) term;
        assertInstanceOf(PirTerm.App.class, app.function());
        var innerApp = (PirTerm.App) app.function();
        assertInstanceOf(PirTerm.Builtin.class, innerApp.function());
        assertEquals(expectedFun, ((PirTerm.Builtin) innerApp.function()).fun());
    }
}
