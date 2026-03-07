package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.compiler.pir.PirTerm;
import com.bloxbean.cardano.julc.compiler.pir.PirType;
import com.bloxbean.cardano.julc.compiler.util.MethodDependencyResolver;
import com.bloxbean.cardano.julc.compiler.util.MethodDependencyResolver.NamedBinding;
import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.JulcVm;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for method ordering: forward references, mutual recursion, and dependency resolution.
 */
class MethodOrderingTest {

    static JulcVm vm;

    @BeforeAll
    static void setUp() {
        vm = JulcVm.create();
    }

    private BigInteger evalInteger(Term term) {
        var result = vm.evaluate(com.bloxbean.cardano.julc.core.Program.plutusV3(term));
        assertTrue(result.isSuccess(), "Expected success: " + result);
        var val = ((Term.Const) ((EvalResult.Success) result).resultTerm()).value();
        return ((Constant.IntegerConst) val).value();
    }

    private boolean evalBool(Term term) {
        var result = vm.evaluate(com.bloxbean.cardano.julc.core.Program.plutusV3(term));
        assertTrue(result.isSuccess(), "Expected success: " + result);
        var val = ((Term.Const) ((EvalResult.Success) result).resultTerm()).value();
        return ((Constant.BoolConst) val).value();
    }

    // --- Forward reference tests (compile + evaluate) ---

    @Test
    void entrypointCallsHelperDefinedAfter() {
        var source = """
            import java.math.BigInteger;

            @Validator
            class MyValidator {
                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    return double_(redeemer) == 10;
                }

                static BigInteger double_(BigInteger x) {
                    return x + x;
                }
            }
            """;
        var result = new JulcCompiler().compile(source);
        assertNotNull(result.program());
        assertFalse(result.hasErrors());
    }

    @Test
    void helperChainDefinedAfterEntrypoint() {
        // Entrypoint calls helperA, helperA calls helperB — both defined after entrypoint
        var source = """
            import java.math.BigInteger;

            @Validator
            class MyValidator {
                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    return quadruple(redeemer) == 20;
                }

                static BigInteger quadruple(BigInteger x) {
                    return double_(x) + double_(x);
                }

                static BigInteger double_(BigInteger x) {
                    return x + x;
                }
            }
            """;
        var result = new JulcCompiler().compile(source);
        assertNotNull(result.program());
        assertFalse(result.hasErrors());
    }

    @Test
    void reverseOrderChainABC() {
        // A→B→C, defined in order C, B, A (reverse dependency)
        var source = """
            import java.math.BigInteger;

            @Validator
            class MyValidator {
                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    return a(redeemer) == 8;
                }

                static BigInteger a(BigInteger x) {
                    return b(x) + 1;
                }

                static BigInteger b(BigInteger x) {
                    return c(x) + 1;
                }

                static BigInteger c(BigInteger x) {
                    return x + 1;
                }
            }
            """;
        var result = new JulcCompiler().compile(source);
        assertNotNull(result.program());
        assertFalse(result.hasErrors());
    }

    @Test
    void diamondDependency() {
        // Entrypoint → A, B; A → C; B → C (diamond pattern)
        var source = """
            import java.math.BigInteger;

            @Validator
            class MyValidator {
                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    return addViaA(redeemer) + addViaB(redeemer) == 10;
                }

                static BigInteger addViaA(BigInteger x) {
                    return shared(x) + 1;
                }

                static BigInteger addViaB(BigInteger x) {
                    return shared(x) + 2;
                }

                static BigInteger shared(BigInteger x) {
                    return x + 10;
                }
            }
            """;
        var result = new JulcCompiler().compile(source);
        assertNotNull(result.program());
        assertFalse(result.hasErrors());
    }

    @Test
    void forwardReferenceViaCompileMethod() {
        // Uses compileMethod path: target method calls helper defined after it
        var source = """
            import java.math.BigInteger;

            class MyClass {
                static BigInteger compute(BigInteger x) {
                    return helper(x) + 1;
                }

                static BigInteger helper(BigInteger x) {
                    return x * 2;
                }
            }
            """;
        var compiler = new JulcCompiler();
        var result = compiler.compileMethod(source, "compute");
        assertNotNull(result.program());
        assertFalse(result.hasErrors());

        // Evaluate: compute(5) = helper(5) + 1 = 10 + 1 = 11
        // compileMethod wraps params with Data decode, so pass Data-encoded arg
        var term = new Term.Apply(result.program().term(),
                new Term.Const(new Constant.DataConst(new PlutusData.IntData(5))));
        assertEquals(BigInteger.valueOf(11), evalInteger(term));
    }

    // --- Mutual recursion tests ---

    @Test
    void twoWayMutualRecursion() {
        // isEven/isOdd: classic mutual recursion
        var source = """
            import java.math.BigInteger;

            @Validator
            class MyValidator {
                static boolean isEven(BigInteger n) {
                    if (n == 0) {
                        return true;
                    } else {
                        return isOdd(n - 1);
                    }
                }

                static boolean isOdd(BigInteger n) {
                    if (n == 0) {
                        return false;
                    } else {
                        return isEven(n - 1);
                    }
                }

                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    return isEven(redeemer);
                }
            }
            """;
        var result = new JulcCompiler().compile(source);
        assertNotNull(result.program());
        assertFalse(result.hasErrors());
    }

    @Test
    void threeWayMutualRecursionFails() {
        // Three-way mutual recursion should produce a clear error
        var source = """
            import java.math.BigInteger;

            @Validator
            class MyValidator {
                static BigInteger fa(BigInteger n) {
                    if (n == 0) { return 1; } else { return fb(n - 1); }
                }
                static BigInteger fb(BigInteger n) {
                    if (n == 0) { return 2; } else { return fc(n - 1); }
                }
                static BigInteger fc(BigInteger n) {
                    if (n == 0) { return 3; } else { return fa(n - 1); }
                }

                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    return fa(redeemer) > 0;
                }
            }
            """;
        var ex = assertThrows(CompilerException.class, () -> new JulcCompiler().compile(source));
        assertTrue(ex.getMessage().contains("Mutual recursion with more than 2 methods"),
                "Expected mutual recursion error, got: " + ex.getMessage());
    }

    // --- Regression tests ---

    @Test
    void correctOrderStillWorks() {
        // Helpers defined BEFORE callers (existing correct order)
        var source = """
            import java.math.BigInteger;

            @Validator
            class MyValidator {
                static BigInteger double_(BigInteger x) {
                    return x + x;
                }

                static BigInteger quadruple(BigInteger x) {
                    return double_(double_(x));
                }

                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    return quadruple(redeemer) == 40;
                }
            }
            """;
        var result = new JulcCompiler().compile(source);
        assertNotNull(result.program());
        assertFalse(result.hasErrors());
    }

    @Test
    void selfRecursiveHelperAfterCaller() {
        // Self-recursive helper defined after its caller
        var source = """
            import java.math.BigInteger;

            @Validator
            class MyValidator {
                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    return factorial(redeemer) == 120;
                }

                static BigInteger factorial(BigInteger n) {
                    if (n <= 1) {
                        return 1;
                    } else {
                        return n * factorial(n - 1);
                    }
                }
            }
            """;
        var result = new JulcCompiler().compile(source);
        assertNotNull(result.program());
        assertFalse(result.hasErrors());
    }

    // --- MethodDependencyResolver unit tests ---

    @Nested
    class DependencyResolverTests {

        // Simple containsVarRef for testing
        private boolean containsVar(PirTerm term, String name) {
            return switch (term) {
                case PirTerm.Var v -> v.name().equals(name);
                case PirTerm.Let l -> containsVar(l.value(), name) || containsVar(l.body(), name);
                case PirTerm.LetRec lr -> lr.bindings().stream().anyMatch(b -> containsVar(b.value(), name))
                        || containsVar(lr.body(), name);
                case PirTerm.Lam lam -> containsVar(lam.body(), name);
                case PirTerm.App app -> containsVar(app.function(), name) || containsVar(app.argument(), name);
                case PirTerm.IfThenElse ite -> containsVar(ite.cond(), name)
                        || containsVar(ite.thenBranch(), name) || containsVar(ite.elseBranch(), name);
                case PirTerm.DataConstr dc -> dc.fields().stream().anyMatch(f -> containsVar(f, name));
                case PirTerm.DataMatch dm -> containsVar(dm.scrutinee(), name)
                        || dm.branches().stream().anyMatch(b -> containsVar(b.body(), name));
                case PirTerm.Trace t -> containsVar(t.message(), name) || containsVar(t.body(), name);
                case PirTerm.Const _, PirTerm.Builtin _, PirTerm.Error _ -> false;
            };
        }

        private PirTerm varRef(String name) {
            return new PirTerm.Var(name, new PirType.DataType());
        }

        private PirTerm constant42() {
            return new PirTerm.Const(new Constant.IntegerConst(BigInteger.valueOf(42)));
        }

        @Test
        void singleMethod() {
            var bindings = List.of(new NamedBinding("a", constant42()));
            var groups = MethodDependencyResolver.resolveDependencyOrder(bindings, this::containsVar);
            assertEquals(1, groups.size());
            assertTrue(groups.get(0).isSingle());
            assertEquals("a", groups.get(0).bindings().get(0).name());
        }

        @Test
        void twoIndependentMethods() {
            var bindings = List.of(
                    new NamedBinding("a", constant42()),
                    new NamedBinding("b", constant42()));
            var groups = MethodDependencyResolver.resolveDependencyOrder(bindings, this::containsVar);
            assertEquals(2, groups.size());
            // Both are independent single-binding groups
            assertTrue(groups.get(0).isSingle());
            assertTrue(groups.get(1).isSingle());
        }

        @Test
        void chainDependency() {
            // a calls b, b calls c — dependency chain
            var bindings = List.of(
                    new NamedBinding("a", varRef("b")),
                    new NamedBinding("b", varRef("c")),
                    new NamedBinding("c", constant42()));
            var groups = MethodDependencyResolver.resolveDependencyOrder(bindings, this::containsVar);
            assertEquals(3, groups.size());
            // Dependents first (innermost): a, then b, then c (outermost)
            assertEquals("a", groups.get(0).bindings().get(0).name());
            assertEquals("b", groups.get(1).bindings().get(0).name());
            assertEquals("c", groups.get(2).bindings().get(0).name());
        }

        @Test
        void diamondDependency() {
            // a → b, a → c, b → d, c → d
            var bindings = List.of(
                    new NamedBinding("a", new PirTerm.App(varRef("b"), varRef("c"))),
                    new NamedBinding("b", varRef("d")),
                    new NamedBinding("c", varRef("d")),
                    new NamedBinding("d", constant42()));
            var groups = MethodDependencyResolver.resolveDependencyOrder(bindings, this::containsVar);
            // a is innermost, d is outermost; b and c are in between
            assertEquals("a", groups.get(0).bindings().get(0).name());
            assertEquals("d", groups.get(groups.size() - 1).bindings().get(0).name());
        }

        @Test
        void selfRecursive() {
            // a references itself
            var bindings = List.of(new NamedBinding("a", varRef("a")));
            var groups = MethodDependencyResolver.resolveDependencyOrder(bindings, this::containsVar);
            assertEquals(1, groups.size());
            assertTrue(groups.get(0).isSingle());
        }

        @Test
        void twoMutuallyRecursive() {
            // a calls b, b calls a
            var bindings = List.of(
                    new NamedBinding("a", varRef("b")),
                    new NamedBinding("b", varRef("a")));
            var groups = MethodDependencyResolver.resolveDependencyOrder(bindings, this::containsVar);
            assertEquals(1, groups.size());
            assertEquals(2, groups.get(0).bindings().size());
        }

        @Test
        void threeMutuallyRecursive() {
            // a→b, b→c, c→a — 3-way cycle
            var bindings = List.of(
                    new NamedBinding("a", varRef("b")),
                    new NamedBinding("b", varRef("c")),
                    new NamedBinding("c", varRef("a")));
            var groups = MethodDependencyResolver.resolveDependencyOrder(bindings, this::containsVar);
            assertEquals(1, groups.size());
            assertEquals(3, groups.get(0).bindings().size());
        }
    }
}
