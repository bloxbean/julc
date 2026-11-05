package org.julclang.testkit;

import org.julclang.compiler.CompilerException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-068 D8, M1: the testkit's host adapter. {@code SourceDiscovery} keeps its pool (the thread context loader's
 * bundled sources plus same-project sources found through imports and the package); the javac gate sees the
 * validator as root and that pool's libraries on the source path.
 */
class JavacSourceDiscoveryTest {

    @TempDir
    Path root;
    void write(String library) throws Exception {
        Files.createDirectories(root.resolve("com/example"));
        Files.writeString(root.resolve("com/example/Fees.java"), library);
        Files.writeString(root.resolve("com/example/FeeGate.java"), """
                package com.example;

                import java.math.BigInteger;
                import org.julclang.core.PlutusData;
                import org.julclang.ledger.ScriptContext;
                import org.julclang.stdlib.annotation.Entrypoint;
                import org.julclang.stdlib.annotation.SpendingValidator;
                import org.julclang.stdlib.lib.MathLib;

                @SpendingValidator
                public class FeeGate {
                    @Entrypoint
                    public static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return MathLib.max(ctx.txInfo().fee(), Fees.minimum()).compareTo(BigInteger.TEN) > 0;
                    }
                }
                """);
    }

    @Test
    void samePackageLibraryAndBundledStdlibAreInThePool() throws Exception {
        write("""
                package com.example;

                import java.math.BigInteger;
                import org.julclang.stdlib.annotation.OnchainLibrary;

                @OnchainLibrary
                public class Fees {
                    public static BigInteger minimum() { return BigInteger.TWO; }
                }
                """);
        assertFalse(SourceDiscovery.compile("com.example.FeeGate", root).hasErrors());
    }

    /** A pool library the program reads meets the subset's rules at the default mode, with nothing set. */
    @Test
    void aSubsetRuleInAPoolLibraryRejects() throws Exception {
        write("""
                package com.example;

                import java.math.BigInteger;
                import org.julclang.stdlib.annotation.OnchainLibrary;

                @OnchainLibrary
                public class Fees {
                    final long instance = 1 / 0;
                    public static BigInteger minimum() { return BigInteger.TWO; }
                }
                """);
        var e = assertThrows(CompilerException.class, () -> SourceDiscovery.compile("com.example.FeeGate", root));
        assertEquals("JULC0095", e.diagnostics().getFirst().code(), e.diagnostics().toString());
    }

    private static final String CAST = """
            import java.math.BigInteger;
            import org.julclang.ledger.ScriptContext;
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.SpendingValidator;

            @SpendingValidator
            public class Gate {
                @Entrypoint
                public static boolean validate(BigInteger redeemer, ScriptContext ctx) {
                    return (boolean) (Object) redeemer;
                }
            }
            """;

    @Test
    void theHostsMeetTheCastAndNameRules() {
        var cast = assertThrows(CompilerException.class, () -> ValidatorTest.compile(
                CAST.replace("(boolean) (Object) redeemer", "org.julclang.core.PlutusData.cast(redeemer, Boolean.class)")));
        assertEquals("JULC0098", cast.diagnostics().getFirst().code(), cast.diagnostics().toString());
        var member = assertThrows(CompilerException.class, () -> ValidatorTest.compile(CAST.replace("    @Entrypoint",
                "    record R(long a) { static boolean fromPlutusData(long x) { return false; } }\n    @Entrypoint")
                .replace("(boolean) (Object) redeemer", "true")));
        assertEquals("JULC0080", member.diagnostics().getFirst().code(), member.diagnostics().toString());
    }

    @Test
    void validatorTestMeetsTheSubsetsRules() {
        var e = assertThrows(CompilerException.class, () -> ValidatorTest.compile(CAST));
        assertEquals("JULC0098", e.diagnostics().getFirst().code(), e.diagnostics().toString());
    }

    @Test
    void methodEvaluatorMeetsTheSubsetsRules() {
        var e = assertThrows(CompilerException.class,
                () -> MethodEvaluator.evaluateBoolean("""
                        class P {
                            final long instance = 1 / 0;
                            static boolean m() { return true; }
                        }
                        """, "m"));
        assertEquals("JULC0095", e.diagnostics().getFirst().code(), e.diagnostics().toString());
    }

    private static final List<String> CONSTRUCTORS = List.of(
            "record R(BigInteger a) { R { a = a.abs(); } }",
            "record R(BigInteger a) { R { a = BigInteger.ONE; } }",
            "record R(BigInteger a) { R() { this(BigInteger.ONE); } }",
            "static boolean OPEN = true; record R(BigInteger a) { R { OPEN = false; } }",
            "record R(BigInteger a) { R { a = a.max(BigInteger.ONE); } }");

    @Test
    void aConstructorsStatementRejectsAtEveryHostEntry() throws Exception {
        for (String member : CONSTRUCTORS) {
            write("""
                    package com.example;

                    import java.math.BigInteger;
                    import org.julclang.stdlib.annotation.OnchainLibrary;

                    @OnchainLibrary
                    public class Fees {
                        %s
                        public static BigInteger minimum() { return BigInteger.TWO; }
                    }
                    """.formatted(member));
            var pool = assertThrows(CompilerException.class, () -> SourceDiscovery.compile("com.example.FeeGate", root));
            assertEquals("JULC0099", pool.diagnostics().getFirst().code(), pool.diagnostics().toString());
            var validator = assertThrows(CompilerException.class,
                    () -> ValidatorTest.compile(CAST.replace("(boolean) (Object) redeemer", "true").replace("    @Entrypoint", "    " + member + "\n    @Entrypoint")));
            assertEquals("JULC0099", validator.diagnostics().getFirst().code(), validator.diagnostics().toString());
            var method = assertThrows(CompilerException.class, () -> MethodEvaluator.evaluateBoolean("""
                    import java.math.BigInteger;
                    class P {
                        %s
                        static boolean m() { return true; }
                    }
                    """.formatted(member), "m"));
            assertEquals("JULC0099", method.diagnostics().getFirst().code(), method.diagnostics().toString());
        }
    }

    @Test
    void javacErrorInAPoolLibraryRejects() throws Exception {
        write("""
                package com.example;

                import java.math.BigInteger;
                import org.julclang.stdlib.annotation.OnchainLibrary;

                @OnchainLibrary
                public class Fees {
                    public static BigInteger minimum() { return BigInteger.ONE + BigInteger.ONE; }
                }
                """);
        var e = assertThrows(CompilerException.class, () -> SourceDiscovery.compile("com.example.FeeGate", root));
        assertEquals("JULC0085", e.diagnostics().getFirst().code(), e.diagnostics().toString());
        assertEquals("Fees.java", e.diagnostics().getFirst().fileName());
    }
}
